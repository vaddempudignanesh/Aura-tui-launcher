package vaddempudi.gnanesh.syntaxcli.browser;

import android.app.Activity;
import android.net.Uri;
import android.os.Message;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import android.webkit.WebResourceResponse;

/**
 * Central security layer applied to every WebView created by AuraBrowser.
 *
 * Responsibilities:
 *   • Kills script-initiated redirects that try to steer off the current host.
 *   • Suppresses window.open() popups unless they come from a real user gesture.
 *   • Blocks non-web scheme hijacks (intent://, market://, whatsapp://, ...).
 *   • Blocks ad hosts via {@link AdBlocker}.
 *   • Keeps legitimate in-page navigation working normally.
 */
public final class SecureWebViewLayer {

    private static final String TAG = "AuraSecure";

    /** Scheme whitelist — everything else is dropped on the floor. */
    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "file", "content", "about", "data", "blob"
    ));

    /** Hosts that must never be loaded in the main frame. */
    private static final Set<String> HARD_BLOCK_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "ads.google.com",
            "pagead2.googlesyndication.com",
            "outbrain.com",
            "taboola.com",
            "popads.net",
            "propellerads.com",
            "onclickads.net",
            "revcontent.com"
    ));

    /**
     * Search engines whose outbound links must be trusted.
     * If the tab is currently on one of these hosts, a gesture-less
     * navigation away from it is treated as a user-initiated result click,
     * not as a hijack.
     */
    private static final Set<String> SEARCH_HOSTS = new HashSet<>(Arrays.asList(
            "google.com", "google.co.uk", "google.co.in", "google.de",
            "google.fr", "google.co.jp", "google.com.br", "google.ca",
            "google.com.au", "google.es", "google.it", "google.ru",
            "google.com.mx", "google.co.id", "google.com.tr",
            "bing.com", "duckduckgo.com", "yahoo.com", "yandex.com",
            "yandex.ru", "baidu.com", "search.yahoo.com", "search.brave.com"
    ));

    /**
     * Sites known to rotate across many TLDs. When the primary host
     * matches one of these base names, ANY tld of the same base name is
     * considered "the same site" — the tld swaps are the site's own
     * load-balancing, not a hijack.
     *
     * Add more base names here as you discover them.
     */
    private static final Set<String> MULTI_TLD_SITES = new HashSet<>(Arrays.asList(
            "moviezwap",
            "moviesflix",
            "mkvcinemas",
            "vegamovies",
            "hdhub4u",
            "9xmovies",
            "tamilmv",
            "isaimini",
            "kuttymovies",
            "movierulz"
    ));

    private SecureWebViewLayer() {}

    /** Call once per WebView. */
    public static void install(WebView wv,
                               Activity activity,
                               HostTracker hostTracker,
                               Runnable onPopupBlocked) {
        install(wv, activity, hostTracker, onPopupBlocked, false);
    }

    /**
     * @param popupsAllowedForThisTab  reserved for future per-tab policy.
     */
    public static void install(WebView wv,
                               Activity activity,
                               HostTracker hostTracker,
                               Runnable onPopupBlocked,
                               boolean popupsAllowedForThisTab) {

        // ── Main-frame navigation filter ─────────────────────────
        // ── Main-frame navigation filter ─────────────────────────
        WebViewClient client = new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(view, request.getUrl(),
                        request.hasGesture(), request.isForMainFrame(), hostTracker);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(view, Uri.parse(url),
                        true, true, hostTracker);
            }

            // ── NEW: lightweight tracking / telemetry neutering ──────
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    WebResourceResponse neutered =
                            TrackingFilter.maybeNeuter(request.getUrl().toString());
                    if (neutered != null) return neutered;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                WebResourceResponse neutered = TrackingFilter.maybeNeuter(url);
                if (neutered != null) return neutered;
                return super.shouldInterceptRequest(view, url);
            }
        };
        wv.setWebViewClient(client);


        // ── Popup / new-window suppression ───────────────────────
        wv.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onCreateWindow(WebView view,
                                          boolean isDialog,
                                          boolean isUserGesture,
                                          Message resultMsg) {
                // 1) Script-initiated popup (no gesture) → destroy silently.
                if (!isUserGesture) {
                    if (resultMsg != null && resultMsg.obj != null) {
                        try {
                            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
                            WebView throwaway = new WebView(view.getContext());
                            t.setWebView(throwaway);
                            resultMsg.sendToTarget();
                            throwaway.destroy();
                        } catch (Exception ignored) {}
                    }
                    if (onPopupBlocked != null) onPopupBlocked.run();
                    return true;
                }

                // 2) Gesture-driven popup → hijack the target URL and load
                //    it in the current tab so back-button history stays clean.
                WebView.HitTestResult hit = view.getHitTestResult();
                String data = hit != null ? hit.getExtra() : null;

                if (data != null && isLoadableUrl(data)) {
                    view.loadUrl(data);
                    if (onPopupBlocked != null) onPopupBlocked.run();
                    return true;
                }

                // 3) Fallback: capture the target through a throwaway WebView
                //    and redirect it into the current tab.
                try {
                    final WebView stub = new WebView(view.getContext());
                    stub.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                            String u = req.getUrl().toString();
                            if (isLoadableUrl(u)) view.loadUrl(u);
                            v.destroy();
                            return true;
                        }
                        @SuppressWarnings("deprecation")
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, String u) {
                            if (isLoadableUrl(u)) view.loadUrl(u);
                            v.destroy();
                            return true;
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(stub);
                    resultMsg.sendToTarget();
                } catch (Exception e) {
                    Log.w(TAG, "popup capture failed", e);
                }

                if (onPopupBlocked != null) onPopupBlocked.run();
                return true;
            }
        });
    }

    /** Shared navigation decision logic — called from both shouldOverride variants. */
    private static boolean handleNavigation(WebView view,
                                            Uri uri,
                                            boolean hasGesture,
                                            boolean isMainFrame,
                                            HostTracker hostTracker) {
        if (uri == null) return true;

        String scheme = uri.getScheme();
        if (scheme == null) return true;
        scheme = scheme.toLowerCase(Locale.US);

        String url = uri.toString();

        // ── 1. Scheme whitelist ──────────────────────────────────
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return true;
        }

        // ── 2. Ad host block ────────────────────────────────────
        if (AdBlocker.isAd(url)) {
            return true;
        }

        String host = uri.getHost();
        if (host != null) host = host.toLowerCase(Locale.US);

        // ── 3. Hard-block list ──────────────────────────────────
        if (host != null && isHardBlocked(host)) {
            return true;
        }

        // ── 4. Anti-hijack ──────────────────────────────────────
        // Rule: a gesture-less MAIN-frame navigation away from the
        // current site family is discarded — UNLESS the current site is
        // a search engine (user clearly tapped a result), or the two
        // hosts share the same multi-tld base name (the site itself is
        // rotating its domain).
        if (isMainFrame && !hasGesture && host != null) {

            // 4a. Coming from a search engine → trust the hop.
            if (hostTracker.isSearchEngine()) {
                hostTracker.rememberIfMainNavigation(host, true);
                return false;
            }

            // 4b. Same multi-tld base name → same site, allow.
            String incomingBase = firstLabelOf(host);
            String currentBase  = hostTracker.getFirstLabel();
            if (incomingBase != null && incomingBase.equals(currentBase)
                    && MULTI_TLD_SITES.contains(incomingBase)) {
                hostTracker.rememberIfMainNavigation(host, true);
                return false;
            }

            // 4c. Real off-site hop → block.
            if (!hostTracker.isSameOrRelatedHost(host)) {
                // Log first time only to avoid hammering logcat.
                hostTracker.noteBlocked(host);
                return true;
            }
        }

        // ── 5. Otherwise: let WebView load it normally so history
        //        stack stays correct.
        if (host != null) hostTracker.rememberIfMainNavigation(host, isMainFrame);
        return false;
    }

    private static boolean isHardBlocked(String host) {
        String h = host;
        while (h.indexOf('.') >= 0) {
            if (HARD_BLOCK_HOSTS.contains(h)) return true;
            h = h.substring(h.indexOf('.') + 1);
        }
        return HARD_BLOCK_HOSTS.contains(h);
    }

    private static boolean isLoadableUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri u = Uri.parse(url);
            String s = u.getScheme();
            return s != null && ALLOWED_SCHEMES.contains(s.toLowerCase(Locale.US));
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the first label of a host: "www.foo.bar" → "www". */
    private static String firstLabelOf(String host) {
        if (host == null) return null;
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }

    // ═══════════════════════════════════════════════════════════
    //  Per-tab host tracker
    // ═══════════════════════════════════════════════════════════
    /**
     * Remembers the "primary" host a tab is on, plus any sub-hosts.
     * Used to decide whether a gesture-less redirect stays in the same
     * site family (allow) or leaves it (block).
     */
    public static final class HostTracker {
        private String primaryHost;
        private String primaryBaseDomain;
        private String lastBlockedHost;
        private long lastBlockedAt;

        public synchronized void rememberIfMainNavigation(String host, boolean isMainFrame) {
            if (!isMainFrame || host == null) return;

            if (primaryHost == null) {
                primaryHost = host;
                primaryBaseDomain = baseDomainOf(host);
                return;
            }

            // Sibling or parent-domain match → keep the anchor where it is.
            if (host.equals(primaryHost)
                    || host.endsWith("." + primaryHost)
                    || primaryHost.endsWith("." + host)) {
                return;
            }

            // Same base domain (e.g. a.b.example.com ↔ example.com) → keep.
            if (baseDomainOf(host).equals(primaryBaseDomain)) return;

            // Otherwise treat as a new anchor.
            primaryHost = host;
            primaryBaseDomain = baseDomainOf(host);
        }

        public synchronized boolean isSameOrRelatedHost(String host) {
            if (host == null) return false;
            if (primaryHost == null) return true; // first hop — allow
            if (host.equals(primaryHost)) return true;
            if (host.endsWith("." + primaryHost)) return true;
            if (primaryHost.endsWith("." + host)) return true;

            String base = baseDomainOf(host);
            return base != null && base.equals(primaryBaseDomain);
        }

        public synchronized boolean isSearchEngine() {
            if (primaryHost == null) return false;
            String base = baseDomainOf(primaryHost);
            return base != null && SEARCH_HOSTS.contains(base);
        }

        public synchronized String getFirstLabel() {
            return firstLabelOf(primaryHost);
        }

        /**
         * Debounces the "blocked" log to avoid spamming Logcat and
         * burning CPU when a site rotates every 150 ms.
         */
        public synchronized void noteBlocked(String host) {
            long now = System.currentTimeMillis();
            if (!host.equals(lastBlockedHost) || now - lastBlockedAt > 3000L) {
                Log.d(TAG, "Blocked hijack redirect → " + host);
                lastBlockedHost = host;
                lastBlockedAt = now;
            }
        }

        public synchronized void reset() {
            primaryHost = null;
            primaryBaseDomain = null;
            lastBlockedHost = null;
            lastBlockedAt = 0L;
        }

        private static String baseDomainOf(String host) {
            if (host == null) return null;
            String[] parts = host.split("\\.");
            if (parts.length <= 2) return host;
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }

        private static String firstLabelOf(String host) {
            if (host == null) return null;
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : host;
        }
    }
}