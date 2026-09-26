package ohi.andre.consolelauncher;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Theme;
import ohi.andre.consolelauncher.managers.xml.options.Ui;

/**
 * AuraBrowser v3 — real multi-tab, incognito tabs, smooth chrome hide/show.
 *
 *  - Multi-tab: each tab is its own WebView in a FrameLayout container.
 *  - Incognito tabs: tagged tabs; disallow 3rd-party cookies + cache.
 *  - Chrome auto-hide: debounced, direction-aware, no flicker.
 *  - Footer: Back | Forward | Tabs[count] | Incognito | Downloads
 */
public class AuraBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL       = "aura_url";
    public static final String EXTRA_INCOGNITO = "aura_incognito";
    public static final String DEFAULT_HOME    = "https://www.google.com";

    // ── Views ────────────────────────────────────────────────────
    private LinearLayout topBar;
    private LinearLayout footerBar;
    private FrameLayout webContainer;
    private EditText etUrl;
    private ProgressBar progressBar;
    private Button btnGo;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private FrameLayout btnTabsContainer;
    private ImageButton btnIncognito;
    private ImageButton btnDownloads;
    private TextView tabCountView;

    // ── Chrome visibility ────────────────────────────────────────
    private boolean chromeVisible = true;
    private int lastScrollY = 0;
    private long lastChromeToggleTime = 0L;
    private static final long CHROME_DEBOUNCE_MS = 350;
    private static final int CHROME_HIDE_THRESHOLD_PX = 120; // must scroll past this to hide
    private int accumulatedScrollDown = 0;
    private int accumulatedScrollUp = 0;
    private static final int SCROLL_STEP = 40;   // px per direction before toggling

    // ── Tabs ─────────────────────────────────────────────────────
    private static class Tab {
        WebView webView;
        boolean incognito;
        String url;
        Tab(WebView wv, boolean incog, String url) {
            this.webView = wv;
            this.incognito = incog;
            this.url = url;
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int currentTabIndex = -1;
    private int freshTabCount = 0; // used to name "Tab 1", "Tab 2"

    // ── Lifecycle ────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        try {
            getWindow().setStatusBarColor(XMLPrefsManager.getColor(Theme.statusbar_color));
            getWindow().setNavigationBarColor(XMLPrefsManager.getColor(Theme.navigationbar_color));
        } catch (Exception ignored) {}

        boolean fullscreen = false;
        try { fullscreen = XMLPrefsManager.getBoolean(Ui.fullscreen); } catch (Exception ignored) {}
        if (fullscreen) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setContentView(R.layout.activity_aura_browser);

        topBar          = findViewById(R.id.aura_top_bar);
        footerBar       = findViewById(R.id.aura_footer_bar);
        webContainer    = findViewById(R.id.aura_web_container);
        etUrl           = findViewById(R.id.aura_et_url);
        progressBar     = findViewById(R.id.aura_progress);
        btnGo           = findViewById(R.id.aura_btn_go);
        btnBack         = findViewById(R.id.aura_btn_back);
        btnForward      = findViewById(R.id.aura_btn_forward);
        btnTabsContainer= findViewById(R.id.aura_btn_tabs_container);
        btnIncognito    = findViewById(R.id.aura_btn_incognito);
        btnDownloads    = findViewById(R.id.aura_btn_downloads);
        tabCountView    = findViewById(R.id.aura_tab_count);

        // ── Address bar select-all on tap ────────────────────────
        etUrl.setOnClickListener(v -> {
            etUrl.selectAll();
            etUrl.requestFocus();
        });
        etUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) etUrl.selectAll();
        });

        btnGo.setOnClickListener(v -> loadFromBar());
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromBar();
                return true;
            }
            return false;
        });

        // ── Footer actions ───────────────────────────────────────
        btnBack.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoBack()) t.webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoForward()) t.webView.goForward();
        });
        btnTabsContainer.setOnClickListener(v -> showTabsDialog());
        btnIncognito.setOnClickListener(v -> newTab(null, true));
        btnDownloads.setOnClickListener(v -> openDownloads());

        // ── Initial tab ──────────────────────────────────────────
        Intent intent = getIntent();
        boolean startIncognito = intent != null && intent.getBooleanExtra(EXTRA_INCOGNITO, false);
        String startUrl = intent != null ? intent.getStringExtra(EXTRA_URL) : null;

        newTab(startUrl, startIncognito);
    }

    // ─────────────────────────────────────────────────────────────
    //  Tab management
    // ─────────────────────────────────────────────────────────────
    private Tab currentTab() {
        if (currentTabIndex < 0 || currentTabIndex >= tabs.size()) return null;
        return tabs.get(currentTabIndex);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void newTab(String url, boolean incognito) {
        WebView wv = new WebView(this);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        wv.setBackgroundColor(0xFF000000);

        configureWebViewFor(wv, incognito);

        String startUrl = (url == null || url.trim().isEmpty()) ? DEFAULT_HOME : url;
        String normalized = normalizeUrl(startUrl);

        Tab tab = new Tab(wv, incognito, normalized);
        tabs.add(tab);

        // Attach to container but keep hidden until selected
        webContainer.addView(wv);
        wv.setVisibility(View.GONE);

        // Load URL
        wv.loadUrl(normalized);

        // Switch to it
        switchToTab(tabs.size() - 1);

        freshTabCount++;
        updateTabCountBadge();
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        // Hide current
        Tab old = currentTab();
        if (old != null) {
            old.webView.setVisibility(View.GONE);
        }

        // Show new
        currentTabIndex = index;
        Tab t = tabs.get(index);
        t.webView.setVisibility(View.VISIBLE);
        t.webView.requestFocus();

        // Update URL bar
        etUrl.setText(t.url != null ? t.url : DEFAULT_HOME);

        // Reset chrome state (show on tab change)
        showChrome();

        updateTabCountBadge();
        invalidateOptionsMenu();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        Tab t = tabs.get(index);
        try {
            webContainer.removeView(t.webView);
            t.webView.loadUrl("about:blank");
            t.webView.stopLoading();
            t.webView.setWebChromeClient(null);
            t.webView.setWebViewClient(null);
            t.webView.destroy();
        } catch (Exception ignored) {}
        tabs.remove(index);

        if (tabs.isEmpty()) {
            finish();
            return;
        }

        if (currentTabIndex >= tabs.size()) currentTabIndex = tabs.size() - 1;
        switchToTab(currentTabIndex);
    }

    private void updateTabCountBadge() {
        tabCountView.setText(String.valueOf(tabs.size()));
    }

    private void showTabsDialog() {
        if (tabs.isEmpty()) return;

        final String[] labels = new String[tabs.size() + 1];
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            String prefix = t.incognito ? "🕶️ " : "🌐 ";
            String title = t.webView.getTitle();
            if (title == null || title.isEmpty()) title = t.url != null ? t.url : "New Tab";
            if (title.length() > 45) title = title.substring(0, 45) + "…";
            labels[i] = prefix + title;
        }
        labels[tabs.size()] = "➕  New Tab";
        final int newTabRow = tabs.size();

        new AlertDialog.Builder(this)
                .setTitle("Tabs (" + tabs.size() + ")")
                .setItems(labels, (d, which) -> {
                    if (which == newTabRow) {
                        newTab(null, false);
                    } else {
                        switchToTab(which);
                    }
                })
                .setNeutralButton("New Incognito", (d, w) -> newTab(null, true))
                .setNegativeButton("Close", null)
                .setPositiveButton("Close Current", (d, w) -> {
                    if (currentTabIndex >= 0) closeTab(currentTabIndex);
                })
                .show();
    }

    // ─────────────────────────────────────────────────────────────
    //  Downloads
    // ─────────────────────────────────────────────────────────────
    private void openDownloads() {
        try {
            Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No downloads app found", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  WebView configuration (per-tab, so incognito can differ)
    // ─────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebViewFor(WebView wv, boolean incognito) {
        WebSettings s = wv.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        s.setCacheMode(incognito ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(!incognito);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(wv, !incognito);
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == currentTabWebView()) {
                    progressBar.setVisibility(View.VISIBLE);
                    etUrl.setText(url);
                }
                updateTabUrl(view, url);
                // Reset scroll-tracking state per page
                lastScrollY = 0;
                accumulatedScrollDown = 0;
                accumulatedScrollUp = 0;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == currentTabWebView()) {
                    progressBar.setVisibility(View.GONE);
                    etUrl.setText(url);
                }
                updateTabUrl(view, url);
            }
        });

        // Smooth, debounced chrome hide/show
        wv.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (v != currentTabWebView()) return;

            int delta = scrollY - lastScrollY;
            lastScrollY = scrollY;

            // Accumulate deltas by direction, reset the opposite
            if (delta > 0) {
                accumulatedScrollDown += delta;
                accumulatedScrollUp = 0;
            } else if (delta < 0) {
                accumulatedScrollUp += -delta;
                accumulatedScrollDown = 0;
            }

            // Hide only when scrolled DOWN enough + we're past top threshold
            if (accumulatedScrollDown >= SCROLL_STEP
                    && scrollY > CHROME_HIDE_THRESHOLD_PX
                    && chromeVisible) {
                if (canToggleChromeNow()) {
                    hideChrome();
                    accumulatedScrollDown = 0;
                }
            }
            // Show only when scrolled UP enough
            else if (accumulatedScrollUp >= SCROLL_STEP && !chromeVisible) {
                if (canToggleChromeNow()) {
                    showChrome();
                    accumulatedScrollUp = 0;
                }
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view == currentTabWebView()) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
            }
        });

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                req.addRequestHeader("User-Agent", userAgent);
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(AuraBrowserActivity.this,
                            "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(AuraBrowserActivity.this,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private WebView currentTabWebView() {
        Tab t = currentTab();
        return t != null ? t.webView : null;
    }

    private void updateTabUrl(WebView wv, String url) {
        for (Tab t : tabs) {
            if (t.webView == wv) {
                t.url = url;
                return;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Chrome hide/show — debounced, no flicker
    // ─────────────────────────────────────────────────────────────
    private boolean canToggleChromeNow() {
        long now = System.currentTimeMillis();
        if (now - lastChromeToggleTime < CHROME_DEBOUNCE_MS) return false;
        lastChromeToggleTime = now;
        return true;
    }

    private void hideChrome() {
        if (!chromeVisible) return;
        chromeVisible = false;

        TranslateAnimation up = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, -1f);
        up.setDuration(200);
        up.setFillAfter(true);

        TranslateAnimation down = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f);
        down.setDuration(200);
        down.setFillAfter(true);

        topBar.startAnimation(up);
        footerBar.startAnimation(down);

        // Use post() so the animation plays before removal
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!chromeVisible) {
                topBar.setVisibility(View.GONE);
                footerBar.setVisibility(View.GONE);
            }
        }, 200);
    }

    private void showChrome() {
        if (chromeVisible) return;
        chromeVisible = true;

        topBar.setVisibility(View.VISIBLE);
        footerBar.setVisibility(View.VISIBLE);

        TranslateAnimation downIn = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, -1f,
                Animation.RELATIVE_TO_SELF, 0f);
        downIn.setDuration(200);

        TranslateAnimation upIn = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f,
                Animation.RELATIVE_TO_SELF, 0f);
        upIn.setDuration(200);

        topBar.startAnimation(downIn);
        footerBar.startAnimation(upIn);
    }

    // ─────────────────────────────────────────────────────────────
    //  URL handling
    // ─────────────────────────────────────────────────────────────
    private void loadFromBar() {
        Tab t = currentTab();
        if (t == null) return;

        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) return;

        String finalUrl = normalizeUrl(url);
        t.webView.loadUrl(finalUrl);
        t.url = finalUrl;

        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
        etUrl.clearFocus();
    }

    private String normalizeUrl(String input) {
        String u = input.trim();
        if (u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file://") || u.startsWith("content://")) {
            return u;
        }
        if (u.contains(".") && !u.contains(" ")) {
            return "https://" + u;
        }
        return "https://www.google.com/search?q=" + Uri.encode(u);
    }

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        Tab t = currentTab();
        if (t != null && t.webView.canGoBack()) {
            t.webView.goBack();
        } else if (tabs.size() > 1) {
            closeTab(currentTabIndex);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Tab t : tabs) t.webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Tab t : tabs) t.webView.onResume();
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) {
            try {
                t.webView.loadUrl("about:blank");
                t.webView.stopLoading();
                t.webView.setWebChromeClient(null);
                t.webView.setWebViewClient(null);
                t.webView.destroy();
            } catch (Exception ignored) {}
        }
        tabs.clear();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────
    //  Static entry points
    // ─────────────────────────────────────────────────────────────
    public static void open(Context ctx, String url) {
        Intent i = new Intent(ctx, AuraBrowserActivity.class);
        if (url != null) i.putExtra(EXTRA_URL, url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    public static void openIncognito(Context ctx, String url) {
        Intent i = new Intent(ctx, AuraBrowserActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_INCOGNITO, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}