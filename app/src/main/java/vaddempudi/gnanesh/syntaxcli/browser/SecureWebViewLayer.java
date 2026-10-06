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

public final class SecureWebViewLayer {

    private static final String TAG = "AuraSecure";

    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "file", "content", "about", "data", "blob"
    ));

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

    private static final Set<String> SEARCH_HOSTS = new HashSet<>(Arrays.asList(
            "google.com", "google.co.uk", "google.co.in", "google.de",
            "google.fr", "google.co.jp", "google.com.br", "google.ca",
            "google.com.au", "google.es", "google.it", "google.ru",
            "google.com.mx", "google.co.id", "google.com.tr",
            "bing.com", "duckduckgo.com", "yahoo.com", "yandex.com",
            "yandex.ru", "baidu.com", "search.yahoo.com", "search.brave.com"
    ));

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

    public static void install(WebView wv,
                               Activity activity,
                               HostTracker hostTracker,
                               Runnable onPopupBlocked) {
        install(wv, activity, hostTracker, onPopupBlocked, false);
    }

    public static void install(WebView wv,
                               Activity activity,
                               HostTracker hostTracker,
                               Runnable onPopupBlocked,
                               boolean popupsAllowedForThisTab) {

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

        wv.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onCreateWindow(WebView view,
                                          boolean isDialog,
                                          boolean isUserGesture,
                                          Message resultMsg) {
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

                WebView.HitTestResult hit = view.getHitTestResult();
                String data = hit != null ? hit.getExtra() : null;

                if (data != null && isLoadableUrl(data)) {
                    view.loadUrl(data);
                    if (onPopupBlocked != null) onPopupBlocked.run();
                    return true;
                }

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

        if (!ALLOWED_SCHEMES.contains(scheme)) {
            return true;
        }

        if (AdBlocker.isAd(url)) {
            return true;
        }

        String host = uri.getHost();
        if (host != null) host = host.toLowerCase(Locale.US);

        if (host != null && isHardBlocked(host)) {
            return true;
        }

        if (isMainFrame && host != null) {

            // 4a. Coming from a search engine OR landing on one → trust the hop.
            if (hostTracker.isSearchEngine() || isKnownSearchHost(host)) {
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

            // 4c. Real off-site hop with no gesture → block.
            if (!hasGesture && !hostTracker.isSameOrRelatedHost(host)) {
                hostTracker.noteBlocked(host);
                return true;
            }
        }

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

    private static boolean isKnownSearchHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        if (h.contains("google.")) return true;
        if (h.contains("bing.")) return true;
        if (h.contains("duckduckgo.")) return true;
        if (h.contains("yahoo.")) return true;
        if (h.contains("yandex.")) return true;
        if (h.contains("baidu.")) return true;
        if (h.contains("search.brave.")) return true;
        return false;
    }

    private static String firstLabelOf(String host) {
        if (host == null) return null;
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }

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

            if (host.equals(primaryHost)
                    || host.endsWith("." + primaryHost)
                    || primaryHost.endsWith("." + host)) {
                return;
            }

            if (baseDomainOf(host).equals(primaryBaseDomain)) return;

            primaryHost = host;
            primaryBaseDomain = baseDomainOf(host);
        }

        public synchronized boolean isSameOrRelatedHost(String host) {
            if (host == null) return false;
            if (primaryHost == null) return true;
            if (host.equals(primaryHost)) return true;
            if (host.endsWith("." + primaryHost)) return true;
            if (primaryHost.endsWith("." + host)) return true;

            String base = baseDomainOf(host);
            return base != null && base.equals(primaryBaseDomain);
        }

        public synchronized boolean isSearchEngine() {
            if (primaryHost == null) return false;
            String base = baseDomainOf(primaryHost);
            if (base == null) return false;
            if (SEARCH_HOSTS.contains(base)) return true;
            String first = firstLabelOf(primaryHost);
            if (first != null && first.startsWith("google")) return true;
            if (primaryHost.contains("google.")) return true;
            if (primaryHost.contains("bing.")) return true;
            if (primaryHost.contains("duckduckgo.")) return true;
            if (primaryHost.contains("yahoo.")) return true;
            if (primaryHost.contains("yandex.")) return true;
            return false;
        }

        public synchronized String getFirstLabel() {
            return firstLabelOf(primaryHost);
        }

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
            host = host.toLowerCase(Locale.US);
            String[] parts = host.split("\\.");
            if (parts.length <= 2) return host;

            String tld = parts[parts.length - 1];
            String sld = parts[parts.length - 2];
            if (tld.length() == 2
                    && (sld.equals("co") || sld.equals("com") || sld.equals("org")
                    || sld.equals("net") || sld.equals("gov") || sld.equals("ac")
                    || sld.equals("edu"))
                    && parts.length >= 3) {
                return parts[parts.length - 3] + "." + sld + "." + tld;
            }
            return sld + "." + tld;
        }

        private static String firstLabelOf(String host) {
            if (host == null) return null;
            int dot = host.indexOf('.');
            return dot > 0 ? host.substring(0, dot) : host;
        }
    }
}