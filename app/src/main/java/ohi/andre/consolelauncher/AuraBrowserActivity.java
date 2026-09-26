package ohi.andre.consolelauncher;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Theme;
import ohi.andre.consolelauncher.managers.xml.options.Ui;

/**
 * AuraBrowser v7 — full code with CA-bundle fix and improved download panel.
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

    private GestureDetector tabSwipeDetector;

    // ═════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════
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

        // Address bar
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

        // Dark mode
        btnDarkMode.setOnClickListener(v -> toggleForceDark());
        updateDarkIconTint();

        // Bookmark
        btnBookmark.setOnClickListener(v -> addBookmark());

        // Footer nav
        btnBack.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoBack()) t.webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoForward()) t.webView.goForward();
        });

        // Swipe on tabs badge to change tabs
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
                        if (Math.abs(dx) < Math.abs(dy)) return false;
                        if (Math.abs(dx) < 40) return false;
                        if (Math.abs(vx) < 100) return false;

                        if (dx > 0) {
                            if (currentTabIndex > 0) switchToTab(currentTabIndex - 1);
                        } else {
                            if (currentTabIndex < tabs.size() - 1)
                                switchToTab(currentTabIndex + 1);
                        }
                        return true;
                    }
                });

        btnTabsContainer.setOnTouchListener((v, event) -> {
            boolean handled = tabSwipeDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !handled) {
                v.performClick();
            }
            return true;
        });
        btnTabsContainer.setOnClickListener(v -> showTabSheet());

        btnIncognito.setOnClickListener(v -> newTab(null, true));
        btnDownloads.setOnClickListener(v -> showDownloadPanel());

        // Initial tab
        Intent intent = getIntent();
        boolean startIncognito = intent != null && intent.getBooleanExtra(EXTRA_INCOGNITO, false);
        String startUrl = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        newTab(startUrl, startIncognito);
    }

    // ═════════════════════════════════════════════════════════════
    //  Dark mode
    // ═════════════════════════════════════════════════════════════
    private void toggleForceDark() {
        forceDark = !forceDark;
        updateDarkIconTint();
        applyForceDarkToAllTabs();
    }

    private void updateDarkIconTint() {
        int tint = forceDark ? 0xFFFFAA00 : 0xFF33FF33;
        btnDarkMode.setColorFilter(tint);
        btnDarkMode.setImageResource(android.R.drawable.ic_menu_day);
    }

    private void applyForceDarkToAllTabs() {
        for (Tab t : tabs) applyForceDark(t.webView);
    }

    @SuppressLint("RequiresFeature")
    private void applyForceDark(WebView wv) {
        if (wv == null) return;
        try {
            WebSettings settings = wv.getSettings();
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
                injectPureBlackCSS(wv);
            } else {
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

    // ═════════════════════════════════════════════════════════════
    //  Bookmark
    // ═════════════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════════════
    //  Tab sheet
    // ═════════════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════════════
    //  Tab management
    // ═════════════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════════════
    //  DOWNLOADS — aria2c daemon + in-app panel
    // ═════════════════════════════════════════════════════════════
    private void startAria2Download(String url, String userAgent,
                                    String contentDisposition, String mimeType) {
        String referer = currentTabWebView() != null
                ? currentTabWebView().getUrl() : "";

        Aria2Manager mgr = Aria2Manager.get(this);

        new Thread(() -> {
            boolean ok = mgr.ensureStarted();
            if (!ok) {
                final String err = mgr.getLastError();
                runOnUiThread(() -> Toast.makeText(this,
                        "aria2c failed: " + (err == null ? "unknown" : err),
                        Toast.LENGTH_LONG).show());
                return;
            }
            String gid = mgr.addDownload(url, userAgent, referer);
            runOnUiThread(() -> {
                if (gid != null) {
                    Toast.makeText(this, "Download added",
                            Toast.LENGTH_SHORT).show();
                    showDownloadPanel();
                } else {
                    Toast.makeText(this, "Failed to add download",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void showDownloadPanel() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0A);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Downloads");
        title.setTextColor(0xFF33FF33);
        title.setTextSize(14);
        title.setPadding(dp(6), dp(4), dp(6), dp(6));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView clearBtn = new TextView(this);
        clearBtn.setText("Clear all");
        clearBtn.setTextColor(0xFFFF6666);
        clearBtn.setTextSize(12);
        clearBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        clearBtn.setOnClickListener(v -> {
            new Thread(() -> {
                Aria2Manager.get(this).clearStopped();
            }).start();
        });
        header.addView(clearBtn);
        root.addView(header);

        // Scrollable list
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);

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

        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] alive = {true};

        // Poll on a BACKGROUND thread, then post to main for UI
        Thread pollThread = new Thread(() -> {
            while (alive[0]) {
                try {
                    // ⬇️ RPC runs on THIS background thread, not main
                    final JSONArray arr = Aria2Manager.get(this).listAll();

                    // Post UI update to main thread
                    h.post(() -> {
                        if (!dialog.isShowing()) return;
                        renderDownloadList(list, dialog, arr);
                    });

                    Thread.sleep(700);
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    Log.e("AuraBrowser", "poll error", e);
                }
            }
        }, "aura-poll");
        pollThread.start();

        dialog.setOnDismissListener(d -> alive[0] = false);
        dialog.show();
    }

    /**
     * Renders the downloaded list. MUST be called on the main thread.
     * The JSONArray has already been fetched on a background thread.
     */
    private void renderDownloadList(LinearLayout list, Dialog dialog, JSONArray arr) {
        list.removeAllViews();

        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No downloads");
            empty.setTextColor(0xFF777777);
            empty.setTextSize(13);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            list.addView(empty);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject job = arr.getJSONObject(i);
                addDownloadRow(list, dialog, job);
            } catch (Exception ignored) {}
        }
    }

    private void addDownloadRow(LinearLayout list, Dialog dialog, JSONObject job) {
        try {
            String gid = job.optString("gid", "");
            String status = job.optString("status", "unknown");

            String name;
            JSONArray files = job.optJSONArray("files");
            if (files != null && files.length() > 0) {
                JSONObject f = files.getJSONObject(0);
                String path = f.optString("path", "");
                if (!path.isEmpty()) {
                    int slash = path.lastIndexOf('/');
                    name = slash >= 0 ? path.substring(slash + 1) : path;
                } else {
                    name = "unknown";
                }
            } else {
                name = "unknown";
            }

            long completed = job.optLong("completedLength", 0);
            long total = job.optLong("totalLength", 0);
            int pct = total > 0 ? (int) ((completed * 100) / total) : 0;
            String speed = job.optString("downloadSpeed", "0");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, dp(3), 0, dp(3));
            row.setLayoutParams(rp);
            row.setBackgroundColor(0xFF111111);

            TextView tvName = new TextView(this);
            tvName.setText(name);
            tvName.setTextColor(0xFFEEEEEE);
            tvName.setTextSize(12);
            tvName.setMaxLines(1);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(tvName);

            TextView tvInfo = new TextView(this);
            String statusIcon;
            switch (status) {
                case "active":  statusIcon = "⬇"; break;
                case "paused":  statusIcon = "⏸"; break;
                case "waiting": statusIcon = "⏳"; break;
                case "complete": statusIcon = "✅"; break;
                case "error":   statusIcon = "❌"; break;
                case "removed": statusIcon = "🗑"; break;
                default:        statusIcon = "•"; break;
            }
            long spd = 0;
            try { spd = Long.parseLong(speed); } catch (Exception ignored) {}
            tvInfo.setText(statusIcon + "  " + pct + "%  •  " + formatSpeed(spd));
            tvInfo.setTextColor(0xFF999999);
            tvInfo.setTextSize(11);
            row.addView(tvInfo);

            // Error message row (only for errors)
            if ("error".equals(status)) {
                String errMsg = job.optString("errorMessage", "");
                if (errMsg.isEmpty()) {
                    JSONObject errObj = job.optJSONObject("error");
                    if (errObj != null) errMsg = errObj.optString("message", "");
                }
                TextView tvErr = new TextView(this);
                tvErr.setText("⚠ " + (errMsg.isEmpty() ? "Download failed" : errMsg));
                tvErr.setTextColor(0xFFFF6666);
                tvErr.setTextSize(10);
                tvErr.setPadding(0, dp(3), 0, 0);
                row.addView(tvErr);
            }

            // Action buttons
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(6), 0, 0);

            final String g = gid;
            final String s = status;

            if ("active".equals(s)) {
                actions.addView(makeActionButton("⏸ Pause", () -> {
                    new Thread(() -> {
                        Aria2Manager.get(this).pause(g);
                    }).start();
                }));
            } else if ("paused".equals(s)) {
                actions.addView(makeActionButton("▶ Resume", () -> {
                    new Thread(() -> {
                        Aria2Manager.get(this).unpause(g);
                    }).start();
                }));
            } else if ("complete".equals(s)) {
                actions.addView(makeActionButton("📂 Open", () ->
                        openFile(new File(Aria2Manager.DOWNLOAD_DIR, name))));
            }

            if (!"removed".equals(s)) {
                actions.addView(makeActionButton("✕ Remove", () -> {
                    new Thread(() -> {
                        Aria2Manager.get(this).remove(g);
                    }).start();
                }));
            }

            row.addView(actions);
            list.addView(row);

        } catch (Exception ignored) {}
    }

    private TextView makeActionButton(String label, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF33FF33);
        tv.setTextSize(12);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackgroundColor(0xFF1A1A1A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.1f MB/s",
                bytesPerSec / (1024.0 * 1024.0));
    }

    private void openFile(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            i.setDataAndType(uri, "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  WebView configuration
    // ═════════════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════════════
    //  Chrome hide/show
    // ═════════════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════════════
    //  URL handling
    // ═════════════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════
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

        if (isFinishing()) {
            try {
                Aria2Manager.get(this).stop();
            } catch (Exception ignored) {}
        }

        super.onDestroy();
    }

    // ═════════════════════════════════════════════════════════════
    //  Static entry points
    // ═════════════════════════════════════════════════════════════
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