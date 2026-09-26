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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Theme;
import ohi.andre.consolelauncher.managers.xml.options.Ui;

/**
 * AuraBrowser — a lightweight WebView-based browser embedded in T-UI.
 * - Uses system Chromium (no bundled engine → tiny APK)
 * - Plain-text cookie storage in app-private dir (WebView default)
 * - Dark TUI-matched UI
 */
public class AuraBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "aura_url";
    public static final String DEFAULT_HOME = "https://www.google.com";

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private Button btnBack, btnForward, btnReload, btnHome, btnGo;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Match T-UI visual style
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        try {
            getWindow().setStatusBarColor(XMLPrefsManager.getColor(Theme.statusbar_color));
            getWindow().setNavigationBarColor(XMLPrefsManager.getColor(Theme.navigationbar_color));
        } catch (Exception ignored) {}

        boolean fullscreen = false;
        try {
            fullscreen = XMLPrefsManager.getBoolean(Ui.fullscreen);
        } catch (Exception ignored) {}

        if (fullscreen) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setContentView(R.layout.activity_aura_browser);

        webView     = findViewById(R.id.aura_webview);
        etUrl       = findViewById(R.id.aura_et_url);
        progressBar = findViewById(R.id.aura_progress);
        btnBack     = findViewById(R.id.aura_btn_back);
        btnForward  = findViewById(R.id.aura_btn_forward);
        btnReload   = findViewById(R.id.aura_btn_reload);
        btnHome     = findViewById(R.id.aura_btn_home);
        btnGo       = findViewById(R.id.aura_btn_go);

        configureWebView();

        // Determine start URL
        String startUrl = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_URL)
                : null;
        if (startUrl == null || startUrl.trim().isEmpty()) {
            startUrl = DEFAULT_HOME;
        }
        webView.loadUrl(normalizeUrl(startUrl));
        etUrl.setText(startUrl);

        // ── URL bar ────────────────────────────────────────────────
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

        // ── Navigation buttons ─────────────────────────────────────
        btnBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnForward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnReload.setOnClickListener(v -> webView.reload());
        btnHome.setOnClickListener(v -> webView.loadUrl(DEFAULT_HOME));
    }

    // ─────────────────────────────────────────────────────────────
    //  WebView setup
    // ─────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();

        // Essentials
        s.setJavaScriptEnabled(true);          // Google login needs this
        s.setDomStorageEnabled(true);          // localStorage / sessionStorage
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        // Lightweight behavior
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        // Mixed content (allow https-only by default)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        // Cookies (needed for Google sessions — see earlier discussion)
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        // Keep links inside our WebView
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
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                etUrl.setText(url);
            }
        });

        // Chrome client: title + progress + JS dialogs
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) {
                    // You could show title in a TextView here if desired
                }
            }
        });

        // Downloads → system DownloadManager
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
    //  URL handling
    // ─────────────────────────────────────────────────────────────
    private void loadFromBar() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) return;

        webView.loadUrl(normalizeUrl(url));

        // Hide keyboard
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);

        etUrl.clearFocus();
    }

    private String normalizeUrl(String input) {
        String u = input.trim();

        // If it looks like a URL, keep it; otherwise send to Google search
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
    //  Lifecycle + hardware back
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

    /** Convenience launcher */
    public static void open(Context ctx, String url) {
        Intent i = new Intent(ctx, AuraBrowserActivity.class);
        if (url != null) i.putExtra(EXTRA_URL, url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}