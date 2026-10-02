package ohi.andre.consolelauncher;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
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


public class AuraBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL       = "aura_url";
    public static final String EXTRA_INCOGNITO = "aura_incognito";
    public static final String DEFAULT_HOME    = "https://www.google.com";

    private LinearLayout topBar;
    private LinearLayout footerBar;
    private FrameLayout webContainer;
    private EditText etUrl;
    private ProgressBar progressBar;
    private ImageButton btnDarkMode;
    private ImageButton btnRefresh;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private FrameLayout btnTabsContainer;
    private ImageButton btnIncognito;
    private ImageButton btnDownloads;
    private TextView tabCountView;

    private boolean forceDark = false;
    private boolean isPageLoading = false;
    private int blockedPopupsThisSession = 0;
    private ActionMode currentSelectionActionMode;

    private static class Tab {
        WebView webView;
        boolean incognito;
        String url;
        String profileName;
        SecureWebViewLayer.HostTracker hostTracker;

        Tab(WebView wv, boolean incog, String url, String profileName) {
            this.webView = wv;
            this.incognito = incog;
            this.url = url;
            this.profileName = profileName;
            this.hostTracker = new SecureWebViewLayer.HostTracker();
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int currentTabIndex = -1;

    private GestureDetector tabSwipeDetector;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AuraCrashGuard.install();
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

        new Thread(() -> AdBlocker.init(getApplicationContext()), "adblock-init").start();

        topBar           = findViewById(R.id.aura_top_bar);
        footerBar        = findViewById(R.id.aura_footer_bar);
        webContainer     = findViewById(R.id.aura_web_container);
        etUrl            = findViewById(R.id.aura_et_url);
        progressBar      = findViewById(R.id.aura_progress);
        btnDarkMode      = findViewById(R.id.aura_btn_darkmode);
        btnRefresh       = findViewById(R.id.aura_btn_refresh);
        btnBack          = findViewById(R.id.aura_btn_back);
        btnForward       = findViewById(R.id.aura_btn_forward);
        btnTabsContainer = findViewById(R.id.aura_btn_tabs_container);
        btnIncognito     = findViewById(R.id.aura_btn_incognito);
        btnDownloads     = findViewById(R.id.aura_btn_downloads);
        tabCountView     = findViewById(R.id.aura_tab_count);

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

        btnDarkMode.setOnClickListener(v -> toggleForceDark());
        updateDarkIconTint();

        btnRefresh.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t == null) return;
            if (isPageLoading) {
                t.webView.stopLoading();
                isPageLoading = false;
                progressBar.setVisibility(View.GONE);
                btnRefresh.setImageResource(android.R.drawable.ic_popup_sync);
            } else {
                t.webView.reload();
            }
        });

        btnBack.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoBack()) t.webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            Tab t = currentTab();
            if (t != null && t.webView.canGoForward()) t.webView.goForward();
        });

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

        cleanupOrphanedIncognitoProfiles();

        Intent intent = getIntent();
        boolean startIncognito = intent != null && intent.getBooleanExtra(EXTRA_INCOGNITO, false);
        String startUrl = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        newTab(startUrl, startIncognito);
    }

    private void onPopupBlocked() {
        blockedPopupsThisSession++;
        if (blockedPopupsThisSession <= 3) {
            runOnUiThread(() -> Toast.makeText(this,
                    "Popup blocked", Toast.LENGTH_SHORT).show());
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Dark mode — one toggle
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
        for (Tab t : tabs) {
            applyForceDark(t.webView);
            try {
                t.webView.invalidate();
                t.webView.requestLayout();
            } catch (Exception ignored) {}
        }
    }

    @SuppressLint("RequiresFeature")
    private void applyForceDark(WebView wv) {
        if (wv == null) return;

        try {
            WebSettings settings = wv.getSettings();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    settings.setAlgorithmicDarkeningAllowed(forceDark);
                } catch (Throwable ignored) {}
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings,
                        forceDark ? WebSettingsCompat.FORCE_DARK_ON
                                : WebSettingsCompat.FORCE_DARK_OFF);
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(settings,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
            }
        } catch (Exception ignored) {}

        injectDeepDarkCss(wv, forceDark);
    }

    private void injectDeepDarkCss(WebView wv, boolean enable) {
        String js = enable ? DEEP_DARK_CSS_JS : DEEP_DARK_REMOVE_JS;
        Log.d("AURA-DARK", "=== injectDeepDarkCss called enable=" + enable
                + " tabIndex=" + currentTabIndex
                + " url=" + (wv != null ? wv.getUrl() : "null") + " ===");
        try {
            wv.evaluateJavascript(js, value -> {
                Log.d("AURA-DARK", "evaluateJavascript callback value=" + value);
            });
        } catch (Exception e) {
            Log.e("AURA-DARK", "evaluateJavascript threw", e);
        }
    }


    private static final String DEEP_DARK_CSS_JS =
            "(function(){" +
                    "try{" +
                    "console.log('===== [AURA] DARK CSS DIAGNOSTIC START =====');" +
                    "var ID='aura-dark-v5';" +
                    "var html=document.documentElement;" +
                    "if(!html){console.log('[AURA] no documentElement');return;}" +
                    "console.log('[AURA] url=' + location.href);" +
                    "console.log('[AURA] title=' + document.title);" +

                    /* ═══════════════ INSTALL CSS ═══════════════ */
                    // The old, destructive CSS has been removed.
                    "html.setAttribute('data-aura-dark','1');" +
                    "var old=document.getElementById(ID);" +
                    "if(old&&old.parentNode)old.parentNode.removeChild(old);" +
                    "var st=document.createElement('style');" +
                    "st.id=ID;" +

                    // Copy nonce if available
                    "try{" +
                    "var nonceSource=document.querySelector('style[nonce],script[nonce]');" +
                    "if(nonceSource&&nonceSource.nonce){" +
                    "st.setAttribute('nonce',nonceSource.nonce);" +
                    "console.log('[AURA] copied nonce=' + nonceSource.nonce);" +
                    "}" +
                    "}catch(e){console.log('[AURA] nonce err '+e);}" +

                    // NEW CSS:
                    // 1. Invert the entire HTML element.
                    // 2. Apply a hue rotation to make colors look more natural after inversion.
                    // 3. Re-invert images, videos, and iframes so they look normal.
                    "st.textContent=" +
                    "'html[data-aura-dark]{filter:invert(100%) hue-rotate(180deg) !important;background:#000 !important;}' +" +
                    "'html[data-aura-dark] img,' +" +
                    "'html[data-aura-dark] video,' +" +
                    "'html[data-aura-dark] iframe,' +" +
                    "'html[data-aura-dark] canvas,' +" +
                    "'html[data-aura-dark] svg{' +" +
                    "'filter:invert(100%) hue-rotate(180deg) !important;' +" +
                    "'}' ;" +

                    "console.log('[AURA] style textContent length=' + st.textContent.length);" +
                    "try{" +
                    "(document.head||html).appendChild(st);" +
                    "console.log('[AURA] style attached to ' + (document.head?'head':'html'));" +
                    "}catch(e){" +
                    "console.log('[AURA] head append failed '+e);" +
                    "try{html.appendChild(st);}catch(e2){console.log('[AURA] html append failed '+e2);}" +
                    "}" +

                    /* ═══════════════ FORCE REFLOW ═══════════════ */
                    "try{ void document.documentElement.offsetHeight; }catch(e){}" +

                    "console.log('===== [AURA] DARK CSS DIAGNOSTIC END =====');" +
                    "}catch(e){" +
                    "console.log('[AURA] FATAL '+e);" +
                    "}" +
                    "})();";



    private static final String DEEP_DARK_REMOVE_JS =
            "(function(){" +
                    "try{" +
                    "console.log('[AURA] remove CSS start');" +
                    "var html=document.documentElement;" +
                    "if(html)html.removeAttribute('data-aura-dark');" +
                    "var st=document.getElementById('aura-dark-v5');" +
                    "if(st){console.log('[AURA] removing style, parent='+(st.parentNode?st.parentNode.tagName:'null'));}" +
                    "if(st&&st.parentNode)st.parentNode.removeChild(st);" +
                    "var old=document.getElementById('aura-dark-v3');" +
                    "if(old&&old.parentNode)old.parentNode.removeChild(old);" +
                    "try{" +
                    "if(window.__auraDarkMO){" +
                    "window.__auraDarkMO.disconnect();" +
                    "window.__auraDarkMO=null;" +
                    "console.log('[AURA] observer disconnected');" +
                    "}" +
                    "}catch(e){}" +
                    "try{" +
                    "var frames=document.querySelectorAll('iframe');" +
                    "for(var i=0;i<frames.length;i++){" +
                    "try{" +
                    "var d=frames[i].contentDocument;" +
                    "if(d){" +
                    "var f=d.getElementById('aura-dark-v5');" +
                    "if(f&&f.parentNode)f.parentNode.removeChild(f);" +
                    "var g=d.getElementById('aura-dark-v3');" +
                    "if(g&&g.parentNode)g.parentNode.removeChild(g);" +
                    "if(d.documentElement)" +
                    "d.documentElement.removeAttribute('data-aura-dark');" +
                    "void d.documentElement.offsetHeight;" +
                    "}" +
                    "}catch(e){}" +
                    "}" +
                    "}catch(e){}" +
                    "try{" +
                    "void document.documentElement.offsetHeight;" +
                    "if(document.body)void document.body.offsetHeight;" +
                    "}catch(e){}" +
                    "console.log('[AURA] remove CSS done');" +
                    "}catch(e){" +
                    "console.log('[AURA] REMOVE FATAL '+e);" +
                    "}" +
                    "})();";
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

    private Tab currentTab() {
        if (currentTabIndex < 0 || currentTabIndex >= tabs.size()) return null;
        return tabs.get(currentTabIndex);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void newTab(String url, boolean incognito) {
        WebView wv;
        try {
            wv = new WebView(this);
        } catch (Throwable t) {
            Log.e("AuraBrowser", "WebView construction failed", t);
            Toast.makeText(this, "Could not open browser view", Toast.LENGTH_LONG).show();
            return;
        }

        String profileName = null;
        if (incognito) {
            profileName = attachFreshIncognitoProfile(wv);
            if (profileName == null) {
                Toast.makeText(this,
                        "Incognito mode is unavailable on this WebView",
                        Toast.LENGTH_LONG).show();
                wv.destroy();
                return;
            }
        }

        wv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        wv.setBackgroundColor(0xFF000000);

        configureWebViewFor(wv, incognito);

        String startUrl = (url == null || url.trim().isEmpty()) ? DEFAULT_HOME : url;
        String normalized = normalizeUrl(startUrl);

        Tab tab = new Tab(wv, incognito, normalized, profileName);
        tabs.add(tab);

        webContainer.addView(wv);
        wv.setVisibility(View.GONE);

        applyForceDark(wv);
        wv.loadUrl(normalized);
        switchToTab(tabs.size() - 1);
        updateTabCountBadge();
    }

    private static final String INCOGNITO_PROFILE_PREFIX = "aura_incognito_";

    private String attachFreshIncognitoProfile(WebView wv) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            Log.e("AuraBrowser",
                    "WebView MULTI_PROFILE is unavailable; incognito isolation cannot be guaranteed");
            return null;
        }

        String profileName = INCOGNITO_PROFILE_PREFIX + System.nanoTime();

        try {
            WebViewCompat.setProfile(wv, profileName);
            return profileName;
        } catch (Exception e) {
            Log.e("AuraBrowser", "Failed to create incognito profile", e);
            return null;
        }
    }

    private void cleanupOrphanedIncognitoProfiles() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return;

        try {
            ProfileStore store = ProfileStore.getInstance();
            List<String> names = new ArrayList<>(store.getAllProfileNames());

            for (String name : names) {
                if (name != null && name.startsWith(INCOGNITO_PROFILE_PREFIX)) {
                    try { store.deleteProfile(name); }
                    catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w("AuraBrowser", "Incognito profile cleanup failed", e);
        }
    }

    private void deleteIncognitoProfile(String profileName) {
        if (profileName == null || profileName.isEmpty()) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return;

        try {
            ProfileStore.getInstance().deleteProfile(profileName);
        } catch (Exception e) {
            Log.w("AuraBrowser",
                    "Could not delete incognito profile " + profileName, e);
        }
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

        updateTabCountBadge();

        isPageLoading = false;
        progressBar.setVisibility(View.GONE);
        btnRefresh.setImageResource(android.R.drawable.ic_popup_sync);
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

        if (t.incognito) deleteIncognitoProfile(t.profileName);

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
    //  DOWNLOADS
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
        clearBtn.setOnClickListener(v -> new Thread(() ->
                Aria2Manager.get(this).clearStopped()).start());
        header.addView(clearBtn);
        root.addView(header);

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

        Thread pollThread = new Thread(() -> {
            while (alive[0]) {
                try {
                    final JSONArray arr = Aria2Manager.get(this).listAll();
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
            if (!"active".equals(status)) spd = 0;
            tvInfo.setText(statusIcon + "  " + pct + "%  •  " + formatSpeed(spd));
            tvInfo.setTextColor(0xFF999999);
            tvInfo.setTextSize(11);
            row.addView(tvInfo);

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

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(6), 0, 0);

            final String g = gid;
            final String s = status;

            if ("active".equals(s)) {
                actions.addView(makeActionButton("⏸ Pause", () ->
                        new Thread(() -> Aria2Manager.get(this).pause(g)).start()));
            } else if ("paused".equals(s)) {
                actions.addView(makeActionButton("▶ Resume", () ->
                        new Thread(() -> Aria2Manager.get(this).unpause(g)).start()));
            } else if ("complete".equals(s)) {
                actions.addView(makeActionButton("📂 Open", () ->
                        openFile(new File(Aria2Manager.DOWNLOAD_DIR, name))));
            }

            if (!"removed".equals(s)) {
                actions.addView(makeActionButton("✕ Remove", () ->
                        new Thread(() -> Aria2Manager.get(this).remove(g)).start()));
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
        if (bytesPerSec <= 0) return "—";
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

        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
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
        if (incognito) {
            try {
                Profile profile = WebViewCompat.getProfile(wv);
                cm = profile.getCookieManager();
            } catch (Exception e) {
                Log.e("AuraBrowser",
                        "Incognito profile cookie manager unavailable", e);
            }
        }

        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(wv, !incognito);
        }

        SecureWebViewLayer.HostTracker tracker = trackerFor(wv);
        SecureWebViewLayer.install(wv, this, tracker, this::onPopupBlocked);

        final WebViewClient baseSecurity = wv.getWebViewClient();
        wv.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return baseSecurity.shouldOverrideUrlLoading(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return baseSecurity.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return baseSecurity.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, String url) {
                return baseSecurity.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == currentTabWebView()) {
                    progressBar.setVisibility(View.VISIBLE);
                    etUrl.setText(url);
                    isPageLoading = true;
                    btnRefresh.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                }
                updateTabUrl(view, url);
                baseSecurity.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                applyForceDark(view);
                baseSecurity.onPageCommitVisible(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == currentTabWebView()) {
                    progressBar.setVisibility(View.GONE);
                    etUrl.setText(url);
                    isPageLoading = false;
                    btnRefresh.setImageResource(android.R.drawable.ic_popup_sync);
                }
                updateTabUrl(view, url);
                applyForceDark(view);
                baseSecurity.onPageFinished(view, url);
            }
        });

        final WebChromeClient baseChrome = wv.getWebChromeClient();
        wv.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                return baseChrome != null
                        && baseChrome.onCreateWindow(view, isDialog, isUserGesture, resultMsg);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view == currentTabWebView()) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (callback != null) callback.onCustomViewHidden();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.d("AURA-DARK-JS", cm.messageLevel() + " " + cm.message()
                        + " @" + cm.lineNumber() + " src=" + cm.sourceId());
                return true;
            }
        });

        installLongPressMenu(wv);

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (contentLength > 0 && contentLength < 4096
                    && mimeType != null && mimeType.startsWith("image/")) {
                return;
            }
            Toast.makeText(this, "Downloading via aria2c...", Toast.LENGTH_SHORT).show();
            startAria2Download(url, userAgent, contentDisposition, mimeType);
        });
    }

    private SecureWebViewLayer.HostTracker trackerFor(WebView wv) {
        for (Tab t : tabs) if (t.webView == wv) return t.hostTracker;
        return new SecureWebViewLayer.HostTracker();
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

    private void loadFromBar() {
        Tab t = currentTab();
        if (t == null) return;

        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) return;

        String finalUrl = normalizeUrl(url);
        t.hostTracker.reset();
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

    private void installLongPressMenu(WebView wv) {
        wv.setLongClickable(true);
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.setHapticFeedbackEnabled(true);

        final Handler longPressHandler = new Handler(Looper.getMainLooper());
        final Runnable[] pendingLongPress = {null};
        final float[] downXY = new float[2];
        final boolean[] longPressFired = {false};

        final int longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout();

        wv.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    downXY[0] = event.getX();
                    downXY[1] = event.getY();
                    longPressFired[0] = false;

                    if (pendingLongPress[0] != null) {
                        longPressHandler.removeCallbacks(pendingLongPress[0]);
                    }
                    pendingLongPress[0] = () -> {
                        pendingLongPress[0] = null;
                        longPressFired[0] = true;
                        handleLongPressAt(wv, downXY[0], downXY[1]);
                    };
                    longPressHandler.postDelayed(pendingLongPress[0], longPressTimeout);
                    return false;

                case MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(event.getX() - downXY[0]);
                    float dy = Math.abs(event.getY() - downXY[1]);
                    int slop = android.view.ViewConfiguration.get(v.getContext())
                            .getScaledTouchSlop();
                    if ((dx > slop || dy > slop) && pendingLongPress[0] != null) {
                        longPressHandler.removeCallbacks(pendingLongPress[0]);
                        pendingLongPress[0] = null;
                    }
                    return false;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pendingLongPress[0] != null) {
                        longPressHandler.removeCallbacks(pendingLongPress[0]);
                        pendingLongPress[0] = null;
                    }
                    if (longPressFired[0]) {
                        longPressFired[0] = false;
                        return true;
                    }
                    return false;
            }
            return false;
        });
    }

    private void handleLongPressAt(WebView wv, float x, float y) {
        try {
            WebView.HitTestResult hit = wv.getHitTestResult();
            int type = hit != null ? hit.getType() : WebView.HitTestResult.UNKNOWN_TYPE;
            String extra = hit != null ? hit.getExtra() : null;

            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showLinkMenu(wv, extra);
                    return;
                }
            }

            if (type == WebView.HitTestResult.IMAGE_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showLinkMenu(wv, extra);
                    return;
                }
            }

            String js =
                    "(function(){" +
                            "  try{" +
                            "    var x=" + (int) x + ", y=" + (int) y + ";" +
                            "    var r=null;" +
                            "    if(document.caretRangeFromPoint){" +
                            "      r=document.caretRangeFromPoint(x,y);" +
                            "    } else if(document.caretPositionFromPoint){" +
                            "      var cp=document.caretPositionFromPoint(x,y);" +
                            "      if(cp){" +
                            "        r=document.createRange();" +
                            "        r.setStart(cp.offsetNode,cp.offset);" +
                            "        r.setEnd(cp.offsetNode,cp.offset);" +
                            "      }" +
                            "    }" +
                            "    if(!r) return '';" +
                            "    var node=r.startContainer;" +
                            "    if(!node) return '';" +
                            "    if(node.nodeType!==3){" +
                            "      if(node.firstChild && node.firstChild.nodeType===3) node=node.firstChild;" +
                            "      else return '';" +
                            "    }" +
                            "    var text=node.textContent||'';" +
                            "    var start=r.startOffset;" +
                            "    if(start>text.length) start=text.length;" +
                            "    var left=start,right=start;" +
                            "    var isWord=function(c){return /[\\w'\\-\\u00C0-\\u024F]/i.test(c);};" +
                            "    while(left>0 && isWord(text.charAt(left-1))) left--;" +
                            "    while(right<text.length && isWord(text.charAt(right))) right++;" +
                            "    var w=text.substring(left,right);" +
                            "    if(!w || w.length===0){" +
                            "      left=start; right=start+1;" +
                            "      w=text.substring(left,right);" +
                            "    }" +
                            "    try{" +
                            "      var sel=window.getSelection();" +
                            "      var range=document.createRange();" +
                            "      range.setStart(node,left);" +
                            "      range.setEnd(node,right);" +
                            "      sel.removeAllRanges();" +
                            "      sel.addRange(range);" +
                            "    }catch(e2){}" +
                            "    return w || '';" +
                            "  }catch(e){return '';}" +
                            "})();";

            wv.evaluateJavascript(js, value -> {
                String text = decodeJsString(value);
                if (text == null || text.trim().isEmpty()) {
                    return;
                }
                showTextMenu(wv, text);
            });

        } catch (Exception e) {
            Log.w("AuraBrowser", "handleLongPressAt failed", e);
        }
    }

    private String decodeJsString(String raw) {
        if (raw == null || raw.length() < 2) return "";
        if (raw.equals("null")) return "";
        String s = raw;
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\t", "\t")
                .replace("\\r", "");
        return s;
    }

    private void showLinkMenu(WebView wv, String url) {
        if (url == null || url.isEmpty()) return;

        final String linkUrl = url;
        wv.evaluateJavascript(
                "(function(){try{"
                        + "var a=document.activeElement;"
                        + "if(!a||a.tagName!=='A'){"
                        + "  var all=document.querySelectorAll('a[href]');"
                        + "  for(var i=0;i<all.length;i++){"
                        + "    if(all[i].href===" + jsStringLiteral(linkUrl) + "){a=all[i];break;}}"
                        + "}"
                        + "return a&&a.textContent?a.textContent.trim():'';"
                        + "}catch(e){return '';}})();",
                value -> {
                    String linkText = decodeJsString(value);
                    if (linkText == null || linkText.isEmpty()) linkText = linkUrl;

                    final String finalText = linkText;

                    new AlertDialog.Builder(AuraBrowserActivity.this)
                            .setTitle(trimForMenu(linkUrl))
                            .setItems(new CharSequence[]{
                                    "Open link",
                                    "Open in new tab",
                                    "Open in incognito",
                                    "Copy link URL",
                                    "Copy link text",
                                    "Share link"
                            }, (d, which) -> {
                                switch (which) {
                                    case 0: wv.loadUrl(linkUrl); break;
                                    case 1: newTab(linkUrl, false); break;
                                    case 2: newTab(linkUrl, true); break;
                                    case 3: copyToClipboard("URL", linkUrl); break;
                                    case 4: copyToClipboard("Link text", finalText); break;
                                    case 5: shareText(linkUrl); break;
                                }
                            })
                            .show();
                });
    }

    private void showTextMenu(WebView wv, String text) {
        if (text == null || text.isEmpty()) return;
        final String body = text;

        new AlertDialog.Builder(AuraBrowserActivity.this)
                .setTitle(trimForMenu(body))
                .setItems(new CharSequence[]{
                        "Copy text",
                        "Select all",
                        "Share text"
                }, (d, which) -> {
                    switch (which) {
                        case 0:
                            copyToClipboard("Text", body);
                            break;
                        case 1:
                            wv.evaluateJavascript(
                                    "(function(){var r=document.createRange();"
                                            + "r.selectNodeContents(document.body);"
                                            + "var s=window.getSelection();"
                                            + "s.removeAllRanges();s.addRange(r);})();",
                                    null);
                            break;
                        case 2:
                            shareText(body);
                            break;
                    }
                })
                .show();
    }

    private void copyToClipboard(String label, String value) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareText(String value) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, value);
            startActivity(Intent.createChooser(send, "Share"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show();
        }
    }

    private String trimForMenu(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > 80) t = t.substring(0, 80) + "…";
        return t;
    }

    private String jsStringLiteral(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

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
        for (Tab t : tabs) {
            t.webView.onResume();
            applyForceDark(t.webView);
        }
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

            if (t.incognito) deleteIncognitoProfile(t.profileName);
        }
        tabs.clear();

        if (isFinishing()) {
            try { Aria2Manager.get(this).stop(); } catch (Exception ignored) {}
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