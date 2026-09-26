package ohi.andre.consolelauncher;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Theme;
import ohi.andre.consolelauncher.managers.xml.options.Ui;

/**
 * AuraBrowser v5 — fixed dark mode, skinny tab badge, working swipe, aria2c downloads.
 */
public class AuraBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL       = "aura_url";
    public static final String EXTRA_INCOGNITO = "aura_incognito";
    public static final String DEFAULT_HOME    = "https://www.google.com";

    // aria2c external download destination
    private static final String DOWNLOAD_DIR   = "/storage/emulated/0/Download";

    // ── Views ────────────────────────────────────────────────────
    private LinearLayout topBar;
    private LinearLayout footerBar;
    private FrameLayout webContainer;
    private EditText etUrl;
    private ProgressBar progressBar;
    private ImageButton btnDarkMode;
    private ImageButton btnBookmark;
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
    private static final int CHROME_HIDE_THRESHOLD_PX = 120;
    private int accumulatedScrollDown = 0;
    private int accumulatedScrollUp = 0;
    private static final int SCROLL_STEP = 40;

    // ── Dark mode ────────────────────────────────────────────────
    private boolean forceDark = false;

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

    // Swipe on tab badge to switch tabs
    private GestureDetector tabSwipeDetector;

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

        topBar           = findViewById(R.id.aura_top_bar);
        footerBar        = findViewById(R.id.aura_footer_bar);
        webContainer     = findViewById(R.id.aura_web_container);
        etUrl            = findViewById(R.id.aura_et_url);
        progressBar      = findViewById(R.id.aura_progress);
        btnDarkMode      = findViewById(R.id.aura_btn_darkmode);
        btnBookmark      = findViewById(R.id.aura_btn_bookmark);
        btnBack          = findViewById(R.id.aura_btn_back);
        btnForward       = findViewById(R.id.aura_btn_forward);
        btnTabsContainer = findViewById(R.id.aura_btn_tabs_container);
        btnIncognito     = findViewById(R.id.aura_btn_incognito);
        btnDownloads     = findViewById(R.id.aura_btn_downloads);
        tabCountView     = findViewById(R.id.aura_tab_count);

        // ── Address bar ──────────────────────────────────────────
        etUrl.setOnClickListener(v -> { etUrl.selectAll(); etUrl.requestFocus(); });
        etUrl.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) etUrl.selectAll(); });
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromBar();
                return true;
            }
            return false;
        });

        // ── Dark mode ────────────────────────────────────────────
        btnDarkMode.setOnClickListener(v -> toggleForceDark());
        updateDarkIconTint();

        // ── Bookmark ─────────────────────────────────────────────
        btnBookmark.setOnClickListener(v -> addBookmark());

        // ── Footer ───────────────────────────────────────────────
        btnBack.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoBack()) t.webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoForward()) t.webView.goForward();
        });

        // Swipe detector on tabs container: left → next tab, right → prev tab
        tabSwipeDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();

                        // Horizontal only
                        if (Math.abs(dx) < Math.abs(dy)) return false;
                        if (Math.abs(dx) < 40) return false;
                        if (Math.abs(vx) < 100) return false;

                        if (dx > 0) {
                            // swipe right → previous tab
                            if (currentTabIndex > 0) switchToTab(currentTabIndex - 1);
                        } else {
                            // swipe left → next tab
                            if (currentTabIndex < tabs.size() - 1)
                                switchToTab(currentTabIndex + 1);
                        }
                        return true;
                    }
                });

        // Attach to the tabs button — LONG PRESS opens the sheet, TAP opens the sheet,
        // SWIPE switches tabs
        btnTabsContainer.setOnTouchListener((v, event) -> {
            boolean handled = tabSwipeDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !handled) {
                v.performClick();
            }
            return true;
        });
        btnTabsContainer.setOnClickListener(v -> showTabSheet());

        btnIncognito.setOnClickListener(v -> newTab(null, true));
        btnDownloads.setOnClickListener(v -> openDownloads());

        // Initial tab
        Intent intent = getIntent();
        boolean startIncognito = intent != null && intent.getBooleanExtra(EXTRA_INCOGNITO, false);
        String startUrl = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        newTab(startUrl, startIncognito);
    }

    // ─────────────────────────────────────────────────────────────
    //  Dark mode — applied to existing + future tabs
    // ─────────────────────────────────────────────────────────────
    private void toggleForceDark() {
        forceDark = !forceDark;
        updateDarkIconTint();
        applyForceDarkToAllTabs();
    }

    private void updateDarkIconTint() {
        int tint = forceDark ? 0xFFFFAA00 : 0xFF33FF33;
        btnDarkMode.setColorFilter(tint);
        btnDarkMode.setImageResource(forceDark
                ? android.R.drawable.ic_menu_day       // bright icon while dark is ON
                : android.R.drawable.ic_menu_day);     // same icon, tint signals state
    }

    private void applyForceDarkToAllTabs() {
        for (Tab t : tabs) applyForceDark(t.webView);
    }

    @SuppressLint("RequiresFeature")
    private void applyForceDark(WebView wv) {
        if (wv == null) return;
        try {
            WebSettings settings = wv.getSettings();

            // Strategy first — must be set before FORCE_DARK
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(settings,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings,
                        forceDark ? WebSettingsCompat.FORCE_DARK_ON
                                : WebSettingsCompat.FORCE_DARK_OFF);
            }

            if (forceDark) {
                // Inject CSS: pure black background on <html> and <body>
                injectPureBlackCSS(wv);
            } else {
                // Remove the injected CSS by reloading
                wv.reload();
            }
        } catch (Exception ignored) {}
    }

    private void injectPureBlackCSS(WebView wv) {
        String css =
                "(function(){" +
                        "  var s = document.getElementById('aura-dark-css');" +
                        "  if (!s) {" +
                        "    s = document.createElement('style');" +
                        "    s.id = 'aura-dark-css';" +
                        "    document.documentElement.appendChild(s);" +
                        "  }" +
                        "  s.textContent = 'html,body{background:#000 !important;color:#ccc !important;}' +" +
                        "                  'html{color-scheme:dark;}' +" +
                        "                  'a{color:#7aa2f7 !important;}';" +
                        "})();";
        wv.evaluateJavascript(css, null);
    }

    // ─────────────────────────────────────────────────────────────
    //  Bookmark
    // ─────────────────────────────────────────────────────────────
    private void addBookmark() {
        Tab t = currentTab();
        if (t == null) return;
        String url = t.webView.getUrl();
        String title = t.webView.getTitle();
        if (url == null) return;

        getSharedPreferences("aura_bookmarks", MODE_PRIVATE)
                .edit()
                .putString(url, title != null ? title : url)
                .apply();

        Toast.makeText(this, "⭐ Bookmarked", Toast.LENGTH_SHORT).show();
        btnBookmark.setColorFilter(0xFFFFAA00);
    }

    // ─────────────────────────────────────────────────────────────
    //  Tab sheet
    // ─────────────────────────────────────────────────────────────
    private void showTabSheet() {
        if (tabs.isEmpty()) return;

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0A);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Tabs");
        title.setTextColor(0xFF33FF33);
        title.setTextSize(14);
        title.setPadding(dp(6), dp(4), dp(6), dp(6));
        root.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dp(2), 0, dp(2));
            row.setLayoutParams(rowLp);
            row.setBackgroundColor(i == currentTabIndex ? 0xFF1A3A1A : 0xFF111111);

            ImageView icon = new ImageView(this);
            icon.setImageResource(t.incognito
                    ? android.R.drawable.ic_menu_view
                    : android.R.drawable.ic_menu_compass);
            icon.setColorFilter(t.incognito ? 0xFFAA66FF : 0xFF33FF33);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.setMarginEnd(dp(8));
            icon.setLayoutParams(iconLp);
            row.addView(icon);

            TextView tv = new TextView(this);
            String tt = t.webView.getTitle();
            if (tt == null || tt.isEmpty()) tt = t.url != null ? t.url : "New Tab";
            if (tt.length() > 45) tt = tt.substring(0, 45) + "…";
            tv.setText(tt);
            tv.setTextColor(0xFFEEEEEE);
            tv.setTextSize(13);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            ImageButton close = new ImageButton(this);
            close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            close.setColorFilter(0xFFFF6666);
            close.setBackground(null);
            close.setPadding(dp(2), dp(2), dp(2), dp(2));
            close.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
            close.setOnClickListener(v -> {
                dialog.dismiss();
                closeTab(idx);
            });
            row.addView(close);

            row.setOnClickListener(v -> {
                switchToTab(idx);
                dialog.dismiss();
            });

            list.addView(row);
        }

        root.addView(scroll);

        TextView plus = new TextView(this);
        plus.setText("+   New Tab");
        plus.setTextColor(0xFF33FF33);
        plus.setTextSize(14);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plusLp.setMargins(0, dp(6), 0, 0);
        plus.setLayoutParams(plusLp);
        plus.setBackgroundColor(0xFF111111);
        plus.setOnClickListener(v -> {
            newTab(null, false);
            dialog.dismiss();
        });
        root.addView(plus);

        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.95),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            lp.y = footerBar.getHeight() + dp(4);
            w.setAttributes(lp);
        }

        dialog.show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
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

        webContainer.addView(wv);
        wv.setVisibility(View.GONE);

        // Apply dark mode BEFORE load, so first paint is dark
        try {
            WebSettings s = wv.getSettings();
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(s,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(s,
                        forceDark ? WebSettingsCompat.FORCE_DARK_ON
                                : WebSettingsCompat.FORCE_DARK_OFF);
            }
        } catch (Exception ignored) {}

        wv.loadUrl(normalized);
        switchToTab(tabs.size() - 1);
        updateTabCountBadge();
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        Tab old = currentTab();
        if (old != null) old.webView.setVisibility(View.GONE);

        currentTabIndex = index;
        Tab t = tabs.get(index);
        t.webView.setVisibility(View.VISIBLE);
        t.webView.requestFocus();
        etUrl.setText(t.url != null ? t.url : DEFAULT_HOME);
        showChrome();
        updateTabCountBadge();
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

        if (tabs.isEmpty()) { finish(); return; }

        if (currentTabIndex >= tabs.size()) currentTabIndex = tabs.size() - 1;
        switchToTab(currentTabIndex);
        updateTabCountBadge();
    }

    private void updateTabCountBadge() {
        tabCountView.setText(String.valueOf(tabs.size()));
    }

    // ─────────────────────────────────────────────────────────────
    //  Downloads — aria2c first, DownloadManager fallback
    // ─────────────────────────────────────────────────────────────
    private void openDownloads() {
        // Long-press or direct tap on downloads icon: open the Download folder
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("file://" + DOWNLOAD_DIR), "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // Fallback: open with any file manager
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                startActivity(Intent.createChooser(i, "Browse downloads"));
            } catch (Exception e2) {
                Toast.makeText(this, "Downloads: " + DOWNLOAD_DIR,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Called from the WebView download listener.
     * Tries aria2c first (with resume + parallel).
     * Falls back to Android DownloadManager if aria2c isn't available.
     */
    private void startAria2Download(String url, String userAgent,
                                    String contentDisposition, String mimeType) {
        final String finalUrl = url;
        final String referer = currentTabWebView() != null
                ? currentTabWebView().getUrl() : "";

        new Thread(() -> {
            String aria2Path = ensureAria2Binary();
            if (aria2Path != null) {
                try {
                    // aria2c -x 16 -j 16 -c -d <dir> -U <ua> -R <referer> <url>
                    // -x 16 = 16 connections per server
                    // -j 16 = 16 parallel downloads (only matters with multiple URLs)
                    // -c    = continue/resume (picks up where paused)
                    // -d    = destination directory
                    // -U    = user agent
                    // -R    = referer
                    // --file-allocation=none = don't preallocate (faster start on some FS)
                    List<String> cmd = new ArrayList<>();
                    cmd.add(aria2Path);
                    cmd.add("-x"); cmd.add("16");
                    cmd.add("-j"); cmd.add("16");
                    cmd.add("-c");
                    cmd.add("--file-allocation=none");
                    cmd.add("-d"); cmd.add(DOWNLOAD_DIR);
                    if (userAgent != null && !userAgent.isEmpty()) {
                        cmd.add("-U"); cmd.add(userAgent);
                    }
                    if (referer != null && !referer.isEmpty()) {
                        cmd.add("-R"); cmd.add(referer);
                    }
                    cmd.add(finalUrl);

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();

                    // Drain output so it doesn't block
                    InputStream in = proc.getInputStream();
                    byte[] buf = new byte[4096];
                    while (in.read(buf) != -1) { /* discard */ }

                    int exit = proc.waitFor();

                    runOnUiThread(() -> {
                        if (exit == 0) {
                            Toast.makeText(this,
                                    "aria2c: done → " + DOWNLOAD_DIR,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this,
                                    "aria2c exited with code " + exit,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> fallbackDownloadManager(
                            finalUrl, userAgent, contentDisposition, mimeType));
                }
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            "aria2c not bundled — using DownloadManager fallback",
                            Toast.LENGTH_SHORT).show();
                    fallbackDownloadManager(finalUrl, userAgent, contentDisposition, mimeType);
                });
            }
        }).start();
    }

    /** Extract aria2c from assets to filesDir on first use, chmod +x. */
    private String ensureAria2Binary() {
        File out = new File(getFilesDir(), "aria2c");
        if (out.exists() && out.canExecute()) return out.getAbsolutePath();

        try (InputStream is = getAssets().open("aria2c");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            return null; // no bundled binary
        }

        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"chmod", "755", out.getAbsolutePath()});
            p.waitFor();
        } catch (Exception ignored) {}

        return out.canExecute() ? out.getAbsolutePath() : null;
    }

    private void fallbackDownloadManager(String url, String ua,
                                         String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mimeType);
            if (ua != null) req.addRequestHeader("User-Agent", ua);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(req);
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  WebView configuration
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

                // Re-inject dark CSS after page load
                if (forceDark) injectPureBlackCSS(view);
            }
        });

        wv.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (v != currentTabWebView()) return;

            int delta = scrollY - lastScrollY;
            lastScrollY = scrollY;

            if (delta > 0) {
                accumulatedScrollDown += delta;
                accumulatedScrollUp = 0;
            } else if (delta < 0) {
                accumulatedScrollUp += -delta;
                accumulatedScrollDown = 0;
            }

            if (accumulatedScrollDown >= SCROLL_STEP
                    && scrollY > CHROME_HIDE_THRESHOLD_PX
                    && chromeVisible) {
                if (canToggleChromeNow()) { hideChrome(); accumulatedScrollDown = 0; }
            } else if (accumulatedScrollUp >= SCROLL_STEP && !chromeVisible) {
                if (canToggleChromeNow()) { showChrome(); accumulatedScrollUp = 0; }
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
        });

        // Downloads → aria2c
        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            Toast.makeText(this, "Downloading via aria2c...", Toast.LENGTH_SHORT).show();
            startAria2Download(url, userAgent, contentDisposition, mimeType);
        });
    }

    private WebView currentTabWebView() {
        Tab t = currentTab();
        return t != null ? t.webView : null;
    }

    private void updateTabUrl(WebView wv, String url) {
        for (Tab t : tabs) {
            if (t.webView == wv) { t.url = url; return; }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Chrome hide/show
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

        TranslateAnimation up = new TranslateAnimation(0,0,0,-1);
        up.setDuration(200); up.setFillAfter(true);
        TranslateAnimation down = new TranslateAnimation(0,0,0,1);
        down.setDuration(200); down.setFillAfter(true);

        topBar.startAnimation(up);
        footerBar.startAnimation(down);

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

        TranslateAnimation downIn = new TranslateAnimation(0,0,-1,0);
        downIn.setDuration(200);
        TranslateAnimation upIn = new TranslateAnimation(0,0,1,0);
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
        if (u.contains(".") && !u.contains(" ")) return "https://" + u;
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