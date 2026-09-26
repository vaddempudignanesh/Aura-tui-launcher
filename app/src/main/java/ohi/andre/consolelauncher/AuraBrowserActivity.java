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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
 * AuraBrowser — a lightweight WebView-based browser embedded in T-UI.
 *
 * v2 changes:
 *  - Toolbar & footer auto-hide on scroll down, auto-show on scroll up
 *  - Tap address bar → selects all text
 *  - Back/forward moved to bottom footer
 *  - Top-right 3-dot menu: Incognito, Downloads, Add Bookmark, New Tab
 *  - Footer "tabs" button for tab switching (single-tab for now, scaffold)
 *  - Incognito mode uses a separate WebView (fresh CookieManager state)
 */
public class AuraBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL        = "aura_url";
    public static final String EXTRA_INCOGNITO  = "aura_incognito";
    public static final String DEFAULT_HOME     = "https://www.google.com";

    // ── Views ────────────────────────────────────────────────────
    private LinearLayout topBar;
    private LinearLayout footerBar;
    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private Button btnGo;
    private ImageButton btnMenu;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnReload;
    private ImageButton btnHome;
    private ImageButton btnTabs;

    // ── State ────────────────────────────────────────────────────
    private boolean chromeVisible = true;
    private int lastScrollY = 0;
    private boolean isIncognito = false;

    // Simple in-memory tab scaffold (single tab for now, ready for multi-tab)
    private final List<String> tabUrls = new ArrayList<>();
    private int currentTabIndex = 0;

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

        // ── Bind views ───────────────────────────────────────────
        topBar       = findViewById(R.id.aura_top_bar);
        footerBar    = findViewById(R.id.aura_footer_bar);
        webView      = findViewById(R.id.aura_webview);
        etUrl        = findViewById(R.id.aura_et_url);
        progressBar  = findViewById(R.id.aura_progress);
        btnGo        = findViewById(R.id.aura_btn_go);
        btnMenu      = findViewById(R.id.aura_btn_menu);
        btnBack      = findViewById(R.id.aura_btn_back);
        btnForward   = findViewById(R.id.aura_btn_forward);
        btnReload    = findViewById(R.id.aura_btn_reload);
        btnHome      = findViewById(R.id.aura_btn_home);
        btnTabs      = findViewById(R.id.aura_btn_tabs);

        // Read intent
        Intent intent = getIntent();
        isIncognito = intent != null && intent.getBooleanExtra(EXTRA_INCOGNITO, false);
        String startUrl = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        if (startUrl == null || startUrl.trim().isEmpty()) startUrl = DEFAULT_HOME;

        configureWebView();

        webView.loadUrl(normalizeUrl(startUrl));
        etUrl.setText(startUrl);
        tabUrls.add(normalizeUrl(startUrl));

        // ── Address bar: tap selects all ─────────────────────────
        etUrl.setOnClickListener(v -> {
            etUrl.selectAll();
            etUrl.requestFocus();
        });
        etUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) etUrl.selectAll();
        });

        // ── GO ───────────────────────────────────────────────────
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

        // ── Footer navigation ────────────────────────────────────
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        btnReload.setOnClickListener(v -> webView.reload());
        btnHome.setOnClickListener(v -> webView.loadUrl(DEFAULT_HOME));
        btnTabs.setOnClickListener(v -> showTabsDialog());

        // ── 3-dot menu ───────────────────────────────────────────
        btnMenu.setOnClickListener(this::showOverflowMenu);
    }

    // ─────────────────────────────────────────────────────────────
    //  3-dot overflow menu
    // ─────────────────────────────────────────────────────────────
    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, isIncognito ? "🕶️  Exit Incognito" : "🕶️  New Incognito Tab");
        popup.getMenu().add(0, 2, 1, "⬇️  Downloads");
        popup.getMenu().add(0, 3, 2, "⭐  Add Bookmark");
        popup.getMenu().add(0, 4, 3, "➕  New Tab");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: toggleIncognito();          return true;
                case 2: openDownloads();            return true;
                case 3: addBookmark();              return true;
                case 4: openNewTab();               return true;
            }
            return false;
        });
        popup.show();
    }

    private void toggleIncognito() {
        // Launch a fresh AuraBrowserActivity instance marked incognito
        Intent i = new Intent(this, AuraBrowserActivity.class);
        i.putExtra(EXTRA_INCOGNITO, !isIncognito);
        i.putExtra(EXTRA_URL, webView.getUrl());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openDownloads() {
        try {
            Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No downloads app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void addBookmark() {
        String url = webView.getUrl();
        String title = webView.getTitle();
        if (url == null) return;

        // Save to a simple shared-prefs list (lightweight, no DB)
        getSharedPreferences("aura_bookmarks", MODE_PRIVATE)
                .edit()
                .putString(url, title != null ? title : url)
                .apply();

        Toast.makeText(this, "Bookmarked: " + (title != null ? title : url),
                Toast.LENGTH_SHORT).show();
    }

    private void openNewTab() {
        Intent i = new Intent(this, AuraBrowserActivity.class);
        i.putExtra(EXTRA_URL, DEFAULT_HOME);
        i.putExtra(EXTRA_INCOGNITO, isIncognito);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void showTabsDialog() {
        // Simple list dialog. (Single-tab scaffold for now.)
        String[] tabs = tabUrls.isEmpty()
                ? new String[] { webView.getUrl() == null ? DEFAULT_HOME : webView.getUrl() }
                : tabUrls.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("Tabs")
                .setItems(tabs, (d, which) -> {
                    if (which < tabUrls.size()) {
                        webView.loadUrl(tabUrls.get(which));
                    }
                })
                .setPositiveButton("New Tab", (d, w) -> openNewTab())
                .setNegativeButton("Close", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────
    //  WebView setup + scroll-to-hide chrome
    // ─────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        s.setCacheMode(isIncognito
                ? WebSettings.LOAD_NO_CACHE
                : WebSettings.LOAD_DEFAULT);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(!isIncognito);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        CookieManager cm = CookieManager.getInstance();
        if (isIncognito) {
            // Do NOT accept third-party cookies in incognito.
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, false);
            }
        } else {
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
        }

        webView.setWebViewClient(new WebViewClient() {
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
                progressBar.setVisibility(View.VISIBLE);
                etUrl.setText(url);
                lastScrollY = 0;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                etUrl.setText(url);
            }

            // Auto-hide/show top bar and footer on scroll
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
            }
        });

        // Scroll listener: hide chrome on scroll down, show on scroll up
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            int delta = scrollY - lastScrollY;
            if (Math.abs(delta) < 8) return; // debounce small jitters

            if (delta > 0 && scrollY > 80) {
                // Scrolling down → hide chrome
                hideChrome();
            } else if (delta < 0) {
                // Scrolling up → show chrome
                showChrome();
            }
            lastScrollY = scrollY;
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
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
                    Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    //  Chrome show/hide animation
    // ─────────────────────────────────────────────────────────────
    private void hideChrome() {
        if (!chromeVisible) return;
        chromeVisible = false;

        TranslateAnimation hideTop = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, -1f);
        hideTop.setDuration(220);
        hideTop.setFillAfter(true);

        TranslateAnimation hideBottom = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f);
        hideBottom.setDuration(220);
        hideBottom.setFillAfter(true);

        topBar.startAnimation(hideTop);
        footerBar.startAnimation(hideBottom);

        topBar.setVisibility(View.GONE);
        footerBar.setVisibility(View.GONE);
    }

    private void showChrome() {
        if (chromeVisible) return;
        chromeVisible = true;

        topBar.setVisibility(View.VISIBLE);
        footerBar.setVisibility(View.VISIBLE);

        TranslateAnimation showTop = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, -1f,
                Animation.RELATIVE_TO_SELF, 0f);
        showTop.setDuration(220);

        TranslateAnimation showBottom = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 1f,
                Animation.RELATIVE_TO_SELF, 0f);
        showBottom.setDuration(220);

        topBar.startAnimation(showTop);
        footerBar.startAnimation(showBottom);
    }

    // ─────────────────────────────────────────────────────────────
    //  URL handling
    // ─────────────────────────────────────────────────────────────
    private void loadFromBar() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) return;

        String finalUrl = normalizeUrl(url);
        webView.loadUrl(finalUrl);

        if (tabUrls.isEmpty()) {
            tabUrls.add(finalUrl);
            currentTabIndex = 0;
        } else {
            tabUrls.set(currentTabIndex, finalUrl);
        }

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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
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