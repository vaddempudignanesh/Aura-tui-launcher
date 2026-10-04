package ohi.andre.consolelauncher.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.provider.MediaStore;
import android.util.Log;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Theme;
import ohi.andre.consolelauncher.managers.xml.options.Ui;


public class AuraBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL       = "aura_url";
    public static final String EXTRA_INCOGNITO = "aura_incognito";
    public static final String DEFAULT_HOME    = "https://www.google.com";

    private Dialog downloadPanelDialog = null;

    // ── File chooser request codes ────────────────────────────────────
    private static final int REQ_FILE_CHOOSER          = 1001;
    private static final int REQ_CAMERA_CAPTURE        = 1002;

    private volatile boolean pendingUserLoad = false;

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

    private boolean forceDark = true;
    private static final String PREFS_BROWSER = "aura_browser";
    private static final String PREF_DARK     = "force_dark";
    private static final String KEY_RESTORED  = "aura_restored_tabs";
    private boolean isPageLoading = false;
    private int blockedPopupsThisSession = 0;
    private ActionMode currentSelectionActionMode;

    // ── URL bar typing-lock ───────────────────────────────────────────
    private boolean urlBarUserEditing = false;

    // ── URL bar tap cycle: select-all → cursor → cursor ───────────────
    // First tap on the address bar selects everything (browser-style).
    // Second tap clears the selection and places a blinking cursor so
    // the user can edit precisely.
    private boolean urlBarJustSelectedAll = false;

    // ── File chooser callback state ───────────────────────────────────
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    private static final String PREF_ASKED_DEFAULT_BROWSER =
            "aura_asked_default_browser_v1";

    private static final int REQ_DEFAULT_BROWSER = 4200;
    // ═══════════════════════════════════════════════════════════════════
    // DarkReader — injected via addDocumentStartJavaScript so it runs
    // BEFORE any page script, in every frame, on every page.
    // ═══════════════════════════════════════════════════════════════════
    private static final String DARKREADER_ASSET = "darkreader.min.js";
    private static final String BRIDGE_NAME = "__AuraDarkReaderBridge";
    private static final String APPLIED_MARKER = "data-aura-dr-applied";
    private static final String IFRAME_APPLIED_MARKER = "data-aura-dr-iframe-applied";

    private static volatile String darkReaderSource = "";

    private static final String DARKREADER_BOOTSTRAP =
            "(function(){" +
                    "'use strict';" +
                    "if(window.__AURA_DR_BOOTSTRAP_DONE__)return;" +
                    "try{Object.defineProperty(window,'__AURA_DR_BOOTSTRAP_DONE__',{" +
                    "value:true,configurable:false,writable:false});}catch(e){}" +

                    "var IS_IFRAME=false;" +
                    "try{IS_IFRAME=(window.top!==window.self);}catch(e){IS_IFRAME=true;}" +
                    "var MARKER=IS_IFRAME?'" + IFRAME_APPLIED_MARKER + "':'" + APPLIED_MARKER + "';" +

                    "function applied(){" +
                    "try{return document.documentElement&&document.documentElement.getAttribute(MARKER)==='1';}" +
                    "catch(e){return false;}" +
                    "}" +

                    "function source(){" +
                    "try{var b=window." + BRIDGE_NAME + ";return b?String(b.getSource()||''):'';}" +
                    "catch(e){return '';}" +
                    "}" +

                    "function trustedEval(code){" +
                    "try{" +
                    "if(window.trustedTypes&&trustedTypes.createPolicy){" +
                    "if(!window.__AURA_TRUSTED_POLICY__){" +
                    "window.__AURA_TRUSTED_POLICY__=trustedTypes.createPolicy('aura-dark-reader',{" +
                    "createScript:function(s){return s;}" +
                    "});" +
                    "}" +
                    "return (0,eval)(window.__AURA_TRUSTED_POLICY__.createScript(code));" +
                    "}" +
                    "}catch(e){}" +
                    "return (0,eval)(code);" +
                    "}" +

                    "function enable(){" +
                    "if(applied())return true;" +
                    "if(!document.documentElement)return false;" +
                    "if(!window.DarkReader){" +
                    "var code=source();" +
                    "if(!code)return false;" +
                    "try{trustedEval(code);}catch(e){return false;}" +
                    "}" +
                    "var DR=window.DarkReader;" +
                    "if(!DR||typeof DR.enable!=='function')return false;" +
                    "try{" +
                    "DR.enable({" +
                    "brightness:100," +
                    "contrast:100," +
                    "sepia:0," +
                    "grayscale:0," +
                    "darkSchemeBackgroundColor:'#181a1b'," +
                    "darkSchemeTextColor:'#e8e6e3'," +
                    "lightSchemeBackgroundColor:'#ffffff'," +
                    "lightSchemeTextColor:'#181a1b'" +
                    "});" +
                    "document.documentElement.setAttribute(MARKER,'1');" +
                    "return true;" +
                    "}catch(e){return false;}" +
                    "}" +

                    "function disable(){" +
                    "try{" +
                    "if(window.DarkReader&&typeof window.DarkReader.disable==='function'){" +
                    "window.DarkReader.disable();" +
                    "}" +
                    "var h=document.documentElement;" +
                    "if(h)h.removeAttribute(MARKER);" +
                    "}catch(e){}" +
                    "}" +

                    "window.__auraDarkReaderControl=function(mode){" +
                    "if(mode){enable();}else{disable();}" +
                    "};" +

                    "function ready(fn){" +
                    "if(document.documentElement&&(document.head||document.readyState!=='loading')){" +
                    "fn();return;" +
                    "}" +
                    "var done=false;" +
                    "function once(){if(done)return;done=true;fn();}" +
                    "document.addEventListener('DOMContentLoaded',once,{once:true});" +
                    "document.addEventListener('readystatechange',function(){" +
                    "if(document.readyState!=='loading')once();" +
                    "});" +
                    "setTimeout(once,0);" +
                    "}" +

                    "function retry(){" +
                    "var attempts=0;" +
                    "var timer=setInterval(function(){" +
                    "if(applied()||attempts>=60){" +
                    "clearInterval(timer);return;" +
                    "}" +
                    "attempts++;" +
                    "enable();" +
                    "},500);" +
                    "}" +

                    "ready(function(){" +
                    "if(window.__auraDarkReaderDesired!==false)enable();" +
                    "retry();" +
                    "});" +
                    "})();";


    private final class DarkReaderBridge {

        @JavascriptInterface
        public String getSource() {
            String cached = darkReaderSource;
            if (!cached.isEmpty()) return cached;

            synchronized (DarkReaderBridge.class) {
                cached = darkReaderSource;
                if (!cached.isEmpty()) return cached;

                try (InputStream input = getAssets().open(DARKREADER_ASSET)) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    String source = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    source = source.replaceAll("(?m)^//# sourceMappingURL=.*$", "");
                    source = source.trim();
                    darkReaderSource = source;
                    return source;
                } catch (Throwable t) {
                    Log.e("AURA-DARK", "Failed to load DarkReader asset", t);
                    darkReaderSource = "";
                    return "";
                }
            }
        }
    }


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
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS_BROWSER, MODE_PRIVATE);
        forceDark = prefs.getBoolean(PREF_DARK, true);   // default = true
        updateDarkIconTint();

        etUrl.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (etUrl.hasFocus()) urlBarUserEditing = true;
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromBar();
                return true;
            }
            return false;
        });

        installUrlBarTextActions();

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

// ★ Make dark-mode persistent across process death:
//   the shared-pref value is authoritative; the TabStore value is the
//   belt-and-braces backup for the case where the local pref wasn't
//   written before a crash.
        boolean storedDark = TabStore.loadDarkMode(this, forceDark);
        if (storedDark != forceDark) {
            forceDark = storedDark;
            getSharedPreferences(PREFS_BROWSER, MODE_PRIVATE)
                    .edit().putBoolean(PREF_DARK, forceDark).commit();
        }
        updateDarkIconTint();

        Intent intent = getIntent();
        boolean startIncognito = intent != null && intent.getBooleanExtra(EXTRA_INCOGNITO, false);
        String startUrl = resolveUrlFromIntent(intent);

// ★ Two entry modes:
//   A) Caller passed an explicit URL/incognito → single fresh tab, ignore history.
//   B) Cold launch with no intent data → restore last session's tabs.
        boolean explicitOpen = (startUrl != null && !startUrl.trim().isEmpty()) || startIncognito;

        if (explicitOpen) {
            // We're being asked to open a specific URL — clear the stored session
            // so it doesn't fight with what the user is asking for now.
            TabStore.clear(this);
            newTab(startUrl, startIncognito);
        } else {
            TabStore.Snapshot snap = TabStore.load(this);

            if (snap.urls.isEmpty()) {
                // First launch ever.
                newTab(null, false);
            } else {
                // ★ Restore every tab in order, then jump to the last-active one.
                for (String u : snap.urls) {
                    newTab(u, false);
                }
                int idx = Math.max(0, Math.min(snap.activeIndex, tabs.size() - 1));
                if (idx != currentTabIndex) switchToTab(idx);
            }
        }
        // After the initial tab is set up:
        new Handler(Looper.getMainLooper()).postDelayed(
                this::maybeRequestDefaultBrowserRole, 800);
    }


    private void requestDefaultBrowserNow() {
        // Always request — user explicitly asked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                android.app.role.RoleManager rm =
                        (android.app.role.RoleManager)
                                getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    Intent i = rm.createRequestRoleIntent(
                            android.app.role.RoleManager.ROLE_BROWSER);
                    startActivityForResult(i, REQ_DEFAULT_BROWSER);
                    return;
                }
            } catch (Exception ignored) {}
        }
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Apps → Default apps",
                    Toast.LENGTH_LONG).show();
        }
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String url = resolveUrlFromIntent(intent);
        if (url == null) return;

        boolean incognito = intent.getBooleanExtra(EXTRA_INCOGNITO, false);

        // If the browser is already open, open the new URL as a NEW TAB
        // instead of reloading the current one — that matches every real
        // browser's behaviour and keeps the user's existing tabs intact.
        newTab(url, incognito);
    }

    private android.widget.PopupWindow urlBarPopup = null;

    private void installUrlBarTextActions() {
        etUrl.setLongClickable(true);
        etUrl.setTextIsSelectable(true);

        etUrl.setOnClickListener(v -> {
            urlBarUserEditing = true;
            if (!etUrl.hasFocus()) {
                etUrl.requestFocus();
            }
            if (etUrl.getText() != null && etUrl.getText().length() > 0) {
                etUrl.selectAll();
            }
            showUrlBarPopup();
        });

        etUrl.setOnLongClickListener(v -> {
            urlBarUserEditing = true;
            if (!etUrl.hasFocus()) {
                etUrl.requestFocus();
            }
            showUrlBarPopup();
            return true;
        });

        etUrl.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                urlBarUserEditing = true;
                if (!etUrl.hasFocus()) {
                    etUrl.requestFocus();
                    if (etUrl.getText() != null && etUrl.getText().length() > 0) {
                        etUrl.selectAll();
                    }
                    showUrlBarPopup();
                }
            }
            return false;
        });

        etUrl.setOnFocusChangeListener((v, hasFocus) -> {
            urlBarUserEditing = hasFocus;
            if (!hasFocus) {
                dismissUrlBarPopup();
            }
        });
    }

    private void showUrlBarPopup() {
        dismissUrlBarPopup();
        try {
            int s = Math.max(0, etUrl.getSelectionStart());
            int e = Math.max(0, etUrl.getSelectionEnd());
            boolean hasSelection = e > s;
            boolean hasText = etUrl.getText() != null && etUrl.getText().length() > 0;

            if (!hasSelection && hasText) {
                etUrl.selectAll();
                hasSelection = true;
            }

            boolean hasClip = false;
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                hasClip = cm != null && cm.hasPrimaryClip();
            } catch (Exception ignored) {}

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int padH = dp(4), padV = dp(2);
            row.setPadding(padH, padV, padH, padV);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF1E1E1E);
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), 0xFF333333);
            row.setBackground(bg);

            if (hasSelection) addPopupItem(row, "Cut", 0xFFFFFFFF, 1, false);
            if (hasSelection) addPopupItem(row, "Copy", 0xFFFFFFFF, 2, false);
            addPopupItem(row, "Paste", hasClip ? 0xFFFFFFFF : 0xFF666666, 3, false);
            if (hasText) addPopupItem(row, "Select all", 0xFF33FF33, 4, true);
            addPopupItem(row, "Set as default", 0xFF66BBFF, 5, true);

            android.widget.PopupWindow popup = new android.widget.PopupWindow(
                    row,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    false);
            popup.setOutsideTouchable(true);
            popup.setFocusable(false);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                popup.setElevation(dp(6));
            }

            // Inside showUrlBarPopup()
            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int popupWidth = row.getMeasuredWidth();
            int barWidth = etUrl.getWidth();
            int xOffset = Math.max(0, (barWidth - popupWidth) / 2);

// Change yOffset to place it below the address bar instead of above
            int yOffset = dp(4);

            popup.showAsDropDown(etUrl, xOffset, yOffset);
            urlBarPopup = popup;
        } catch (Exception ex) {
            Log.w("AuraBrowser", "showUrlBarPopup failed", ex);
        }
    }

    private void addPopupItem(LinearLayout row, String label, int color,
                              int actionId, boolean isLast) {
        // Insert a divider BEFORE every item except the first.
        if (row.getChildCount() > 0) {
            View sep = new View(this);
            LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
            sepLp.topMargin = dp(6);
            sepLp.bottomMargin = dp(6);
            sep.setLayoutParams(sepLp);
            sep.setBackgroundColor(0xFF333333);
            row.addView(sep);
        }

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(0x00000000);
        tv.setClickable(true);
        tv.setFocusable(true);

        tv.setOnClickListener(v -> {
            dismissUrlBarPopup();
            handleUrlBarTextAction(actionId);
        });

        row.addView(tv);
    }

    private void dismissUrlBarPopup() {
        try {
            if (urlBarPopup != null && urlBarPopup.isShowing()) {
                urlBarPopup.dismiss();
            }
        } catch (Exception ignored) {}
        urlBarPopup = null;
    }

    private void maybeRequestDefaultBrowserRole() {
        android.content.SharedPreferences p =
                getSharedPreferences(PREFS_BROWSER, MODE_PRIVATE);
        if (p.getBoolean(PREF_ASKED_DEFAULT_BROWSER, false)) return;
        p.edit().putBoolean(PREF_ASKED_DEFAULT_BROWSER, true).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                android.app.role.RoleManager rm =
                        (android.app.role.RoleManager)
                                getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) {
                    if (!rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)) {
                        Intent roleIntent = rm.createRequestRoleIntent(
                                android.app.role.RoleManager.ROLE_BROWSER);
                        startActivityForResult(roleIntent, REQ_DEFAULT_BROWSER);
                        return;
                    }
                }
            } catch (Throwable t) {
                Log.w("AuraBrowser", "RoleManager request failed", t);
            }
        }

        // Fallback (older Android, or RoleManager unavailable): open the
        // "Default apps" settings screen so the user can pick manually.
        try {
            Intent settings = new Intent(
                    android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            startActivity(settings);
        } catch (Exception ignored) {}
    }

    private void showUrlBarTextMenu() {
        int selStart = Math.max(0, etUrl.getSelectionStart());
        int selEnd   = Math.max(0, etUrl.getSelectionEnd());
        boolean hasSelection = selEnd > selStart;
        boolean hasText      = etUrl.getText() != null && etUrl.getText().length() > 0;
        boolean hasClip      = false;
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            hasClip = cm != null && cm.hasPrimaryClip();
        } catch (Exception ignored) {}

        final java.util.List<CharSequence> labels  = new java.util.ArrayList<>();
        final java.util.List<Integer>      actions = new java.util.ArrayList<>();

        if (hasSelection) { labels.add("Cut");          actions.add(1); }
        if (hasSelection) { labels.add("Copy");         actions.add(2); }
        if (hasClip)      { labels.add("Paste");        actions.add(3); }
        if (hasText)      { labels.add("Select all");   actions.add(4); }

        if (labels.isEmpty()) {
            labels.add("Select all");
            actions.add(4);
        }

        new AlertDialog.Builder(this)
                .setTitle("Address bar")
                .setItems(labels.toArray(new CharSequence[0]), (d, which) ->
                        handleUrlBarTextAction(actions.get(which)))
                .show();
    }

    private void handleUrlBarTextAction(int actionId) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

            switch (actionId) {
                case 1: {   // Cut
                    int s = Math.max(0, etUrl.getSelectionStart());
                    int e = Math.max(0, etUrl.getSelectionEnd());
                    if (e > s) {
                        CharSequence sel = etUrl.getText().subSequence(s, e);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", sel));
                        }
                        etUrl.getText().delete(s, e);
                    }
                    break;
                }
                case 2: {   // Copy
                    int s = Math.max(0, etUrl.getSelectionStart());
                    int e = Math.max(0, etUrl.getSelectionEnd());
                    if (e > s) {
                        CharSequence sel = etUrl.getText().subSequence(s, e);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", sel));
                        }
                    }
                    break;
                }
                case 3: {   // Paste
                    if (cm != null && cm.hasPrimaryClip()) {
                        CharSequence clip = cm.getPrimaryClip()
                                .getItemAt(0).coerceToText(this);
                        if (clip != null) {
                            int s = Math.max(0, etUrl.getSelectionStart());
                            int e = Math.max(0, etUrl.getSelectionEnd());
                            etUrl.getText().replace(s, e, clip);

                            // Automatically trigger URL loading after paste
                            loadFromBar();
                        }
                    }
                    break;
                }
                case 4: {   // Select all
                    etUrl.selectAll();
                    break;
                }

                case 5: {   // "Make default browser"
                    requestDefaultBrowserNow();
                    break;
                }
            }
        } catch (Exception e) {
            Log.w("AuraBrowser", "URL bar action failed", e);
        }
    }

    /**
     * Resolves the URL that this activity was launched to display.
     * Handles:
     *   • EXTRA_URL (explicit — from our own launcher commands)
     *   • ACTION_VIEW with getData() (any external app: WhatsApp, Gmail, ...)
     *   • ACTION_WEB_SEARCH with SearchManager.QUERY (global search / "google X")
     *   • ACTION_SEND with a text/plain body that contains a URL
     */
    private String resolveUrlFromIntent(Intent intent) {
        if (intent == null) return null;

        // 1. Explicit extra (highest priority — our own callers)
        String extra = intent.getStringExtra(EXTRA_URL);
        if (extra != null && !extra.trim().isEmpty()) return extra.trim();

        String action = intent.getAction();
        if (action == null) return null;

        // 2. Standard external app launch: whatsapp://... -> ACTION_VIEW
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                String s = data.toString();
                if (s != null && !s.trim().isEmpty()) return s;
            }
        }

        // 3. Global search / "search the web for X"
        if (Intent.ACTION_WEB_SEARCH.equals(action)
                || "com.google.android.gms.actions.SEARCH_ACTION".equals(action)
                || Intent.ACTION_SEARCH.equals(action)) {
            String q = intent.getStringExtra(android.app.SearchManager.QUERY);
            if (q == null) q = intent.getStringExtra("query");
            if (q == null) q = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (q != null && !q.trim().isEmpty()) {
                return "https://www.google.com/search?q="
                        + Uri.encode(q.trim());
            }
        }

        // 4. Share sheet: "Share link to browser"
        if (Intent.ACTION_SEND.equals(action)) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                // Extract the first http(s):// URL from the shared text.
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https?://\\S+")
                        .matcher(shared);
                if (m.find()) return m.group();
                // No URL found → search the whole text.
                return "https://www.google.com/search?q=" + Uri.encode(shared);
            }
        }

        return null;
    }

    private void persistTabs() {
        try {
            List<String> urls = new ArrayList<>();
            int activeNonIncognito = 0;
            int nonIncognitoCount = 0;

            for (int i = 0; i < tabs.size(); i++) {
                Tab t = tabs.get(i);
                if (t.incognito) continue;
                String u = t.url;
                if (u == null || u.isEmpty() || "about:blank".equals(u)) continue;
                if (i == currentTabIndex) activeNonIncognito = nonIncognitoCount;
                urls.add(u);
                nonIncognitoCount++;
            }

            TabStore.save(this, urls, activeNonIncognito);
        } catch (Exception ignored) {}
    }

    private void onPopupBlocked() {
        blockedPopupsThisSession++;
    }

    private void setUrlBarText(String url) {
        if (urlBarUserEditing || etUrl.hasFocus()) return;
        etUrl.setText(url != null ? url : DEFAULT_HOME);
    }


    private void toggleForceDark() {
        forceDark = !forceDark;

        // ★ Sync save to BOTH stores so a crash or process-kill cannot
        //   revert the choice. commit() is synchronous and durable.
        getSharedPreferences(PREFS_BROWSER, MODE_PRIVATE)
                .edit().putBoolean(PREF_DARK, forceDark).commit();
        TabStore.saveDarkMode(this, forceDark);

        updateDarkIconTint();
        applyDarkReaderToAllTabs();
    }
    private void applyDarkReaderToAllTabs() {
        for (Tab t : tabs) {
            if (t.webView == null) continue;
            final String js =
                    "(function(){try{" +
                            "window.__auraDarkReaderDesired=" + (forceDark ? "true" : "false") + ";" +
                            "if(typeof window.__auraDarkReaderControl==='function'){" +
                            "window.__auraDarkReaderControl(" + (forceDark ? "true" : "false") + ");" +
                            "}" +
                            "}catch(e){}})();";
            try {
                t.webView.evaluateJavascript(js, null);
            } catch (Throwable ignored) {}
        }
    }

    private void updateDarkIconTint() {
        int tint = forceDark ? 0xFFFFAA00 : 0xFF33FF33;
        btnDarkMode.setColorFilter(tint);
        btnDarkMode.setImageResource(forceDark
                ? R.drawable.aura_ic_sun    // showing sun means "tap to go light"
                : R.drawable.aura_ic_moon); // showing moon means "tap to go dark"
    }



    // ═══════════════════════════════════════════════════════════════════
    // FILE / MEDIA / CAMERA PICKER
    // ═══════════════════════════════════════════════════════════════════

    private boolean handleFileChooser(ValueCallback<Uri[]> callback,
                                      WebChromeClient.FileChooserParams params) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        filePathCallback = callback;

        try {
            Intent chooserIntent;

            if (params != null && params.isCaptureEnabled()) {
                chooserIntent = buildCameraIntent();
                if (chooserIntent == null) chooserIntent = buildPickerIntent(params);
            } else {
                chooserIntent = buildPickerIntent(params);
            }

            if (chooserIntent == null) {
                callback.onReceiveValue(null);
                filePathCallback = null;
                return false;
            }

            startActivityForResult(chooserIntent, REQ_FILE_CHOOSER);
            return true;

        } catch (Exception e) {
            Log.e("AuraBrowser", "File chooser failed", e);
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            return false;
        }
    }

    private Intent buildPickerIntent(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        String[] acceptTypes = (params != null) ? params.getAcceptTypes() : null;
        String mimeFilter = "*/*";
        boolean hasAccept = false;

        if (acceptTypes != null && acceptTypes.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String a : acceptTypes) {
                if (a == null || a.trim().isEmpty()) continue;
                String t = a.trim();
                if (t.startsWith(".")) {
                    String ext = t.substring(1).toLowerCase(Locale.US);
                    if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                            || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp")
                            || ext.equals("heic") || ext.equals("heif")) {
                        sb.append("image/*").append(",");
                    } else if (ext.equals("mp4") || ext.equals("webm") || ext.equals("mkv")
                            || ext.equals("mov") || ext.equals("avi")) {
                        sb.append("video/*").append(",");
                    } else if (ext.equals("mp3") || ext.equals("wav") || ext.equals("ogg")
                            || ext.equals("m4a") || ext.equals("flac")) {
                        sb.append("audio/*").append(",");
                    } else if (ext.equals("pdf")) {
                        sb.append("application/pdf").append(",");
                    } else if (ext.equals("txt")) {
                        sb.append("text/plain").append(",");
                    }
                    hasAccept = true;
                } else {
                    sb.append(t).append(",");
                    hasAccept = true;
                }
            }
            if (sb.length() > 0) {
                mimeFilter = sb.substring(0, sb.length() - 1);
            }
        }

        if (hasAccept) intent.setType(mimeFilter);
        else intent.setType("*/*");

        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);

        Intent cameraIntent = null;
        if (mimeFilter.contains("image") || mimeFilter.equals("*/*")) {
            cameraIntent = buildCameraIntent();
        }

        if (cameraIntent != null) {
            Intent chooser = Intent.createChooser(intent, "Select file");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ cameraIntent });
            return chooser;
        }
        return Intent.createChooser(intent, "Select file");
    }

    private Intent buildCameraIntent() {
        try {
            File dir = new File(getCacheDir(), "aura_camera");
            if (!dir.exists()) dir.mkdirs();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            cameraOutputFile = new File(dir, "aura_" + ts + ".jpg");

            cameraOutputUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    cameraOutputFile);

            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            capture.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (capture.resolveActivity(getPackageManager()) == null) return null;
            return capture;
        } catch (Exception e) {
            Log.w("AuraBrowser", "Camera intent unavailable", e);
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }

            Uri[] results = null;

            if (resultCode == Activity.RESULT_OK) {
                if (data == null || (data.getData() == null && data.getClipData() == null)) {
                    if (cameraOutputUri != null) {
                        results = new Uri[]{ cameraOutputUri };
                    }
                } else {
                    if (data.getData() != null) {
                        results = new Uri[]{ data.getData() };
                    } else if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            cameraOutputUri = null;
            cameraOutputFile = null;
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    // ═════════════════════════════════════════════════════════════
    //  TABS
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
            return;
        }

        String profileName = null;
        if (incognito) {
            profileName = attachFreshIncognitoProfile(wv);
            if (profileName == null) {
                Log.w("AuraBrowser", "Incognito mode is unavailable on this WebView");
                wv.destroy();
                return;
            }
        }

        wv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        wv.setBackgroundColor(0xFF000000);

        // ★ Install the DarkReader bridge BEFORE any navigation.
        try {
            wv.addJavascriptInterface(new DarkReaderBridge(), BRIDGE_NAME);
        } catch (Throwable t) {
            Log.e("AURA-DARK", "Bridge install failed", t);
        }

        // ★ Register the bootstrap as a document-start script. WebView will
        //   execute it in EVERY frame (top + iframes) BEFORE the page's own
        //   scripts, BEFORE CSP is applied, BEFORE Trusted Types is enforced.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                        wv,
                        DARKREADER_BOOTSTRAP,
                        Collections.singleton("*")
                );
            } catch (Throwable t) {
                Log.w("AURA-DARK", "addDocumentStartJavaScript failed", t);
            }
        } else {
            Log.w("AURA-DARK", "DOCUMENT_START_SCRIPT unsupported; dark mode may be partial");
        }

        configureWebViewFor(wv, incognito);

        String startUrl = (url == null || url.trim().isEmpty()) ? DEFAULT_HOME : url;
        String normalized = normalizeUrl(startUrl);

        Tab tab = new Tab(wv, incognito, normalized, profileName);
        tabs.add(tab);

        webContainer.addView(wv);
        wv.setVisibility(View.GONE);

        wv.loadUrl(normalized);
        switchToTab(tabs.size() - 1);
        updateTabCountBadge();
        persistTabs();
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
        if (old != null) {
            old.webView.setVisibility(View.GONE);
            // ★ Fix bug #5 — kill any audio/video playing in the tab we're
            //   leaving, plus pause the WebView itself.
            try {
                old.webView.evaluateJavascript(
                        "(function(){try{" +
                                "var m=document.querySelectorAll('video,audio');" +
                                "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}" +
                                "}catch(e){}})();", null);
            } catch (Throwable ignored) {}
            try { old.webView.onPause(); } catch (Throwable ignored) {}
        }

        currentTabIndex = index;
        Tab t = tabs.get(index);
        t.webView.setVisibility(View.VISIBLE);
        t.webView.requestFocus();
        // ★ Resume the WebView we just switched to.
        try { t.webView.onResume(); } catch (Throwable ignored) {}
        setUrlBarText(t.url != null ? t.url : DEFAULT_HOME);

        updateTabCountBadge();

        isPageLoading = false;
        progressBar.setVisibility(View.GONE);
        btnRefresh.setImageResource(android.R.drawable.ic_popup_sync);

        persistTabs();   // ★ ADD THIS LINE
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
        persistTabs();
    }

    private void updateTabCountBadge() {
        tabCountView.setText(String.valueOf(tabs.size()));
    }

    private void startNativeDownload(String url, String userAgent,
                                     String contentDisposition, String mimeType) {
        try {
            Intent svc = new Intent(this, AuraDownloadService.class);
            svc.putExtra(AuraDownloadService.EXTRA_DOWNLOAD_URL, url);
            svc.putExtra(AuraDownloadService.EXTRA_USER_AGENT, userAgent);
            svc.putExtra(AuraDownloadService.EXTRA_CONTENT_DISPOSITION, contentDisposition);
            svc.putExtra(AuraDownloadService.EXTRA_MIME_TYPE, mimeType);
            svc.putExtra(AuraDownloadService.EXTRA_REFERER,
                    currentTabWebView() != null ? currentTabWebView().getUrl() : "");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, svc);
            } else {
                startService(svc);
            }

            // ★ Auto-open the download panel so the user sees live progress
            //   without having to tap the download icon. If it's already
            //   open, do nothing (avoid stacking dialogs).
            runOnUiThread(() -> {
                if (downloadPanelDialog != null && downloadPanelDialog.isShowing()) {
                    return;
                }

            });

        } catch (Exception e) {
            Log.e("AuraBrowser", "Failed to start download service", e);
        }
    }


    private static boolean isSameRegistrableDomain(String a, String b) {
        if (a == null || b == null) return false;
        String ra = registrableDomain(a);
        String rb = registrableDomain(b);
        return ra != null && ra.equals(rb);
    }

    private static String registrableDomain(String host) {
        if (host == null) return null;
        host = host.toLowerCase(Locale.US);
        if (host.startsWith("www.")) host = host.substring(4);
        // Very small public-suffix heuristic: keep the last two labels
        // (works for .com, .org, .net, .in, .co.uk because we also
        // peek at the third-to-last label).
        String[] parts = host.split("\\.");
        if (parts.length < 2) return host;

        // Handle common two-part suffixes
        String last = parts[parts.length - 1];
        String secondLast = parts[parts.length - 2];
        String[] ccTwoPart = { "co", "com", "org", "net", "gov", "ac", "edu" };
        if ((last.length() == 2)
                && (secondLast.equals("co") || secondLast.equals("com")
                || secondLast.equals("org") || secondLast.equals("net")
                || secondLast.equals("gov") || secondLast.equals("ac")
                || secondLast.equals("edu"))
                && parts.length >= 3) {
            return parts[parts.length - 3] + "." + secondLast + "." + last;
        }
        return secondLast + "." + last;
    }
    private void showDownloadPanel() {
        if (downloadPanelDialog != null && downloadPanelDialog.isShowing()) return;

        final Dialog dialog = new Dialog(this);
        downloadPanelDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0A);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ── Header ─────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Downloads");
        title.setTextColor(0xFF33FF33);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView clearBtn = new TextView(this);
        clearBtn.setText("Clear completed");
        clearBtn.setTextColor(0xFFFF6666);
        clearBtn.setTextSize(12);
        clearBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        clearBtn.setBackgroundColor(0xFF1A1A1A);
        clearBtn.setOnClickListener(v -> {
            AuraDownloadHistory.get(this).clearStopped();
        });
        header.addView(clearBtn);
        root.addView(header);

        // Thin divider
        View div = new View(this);
        div.setBackgroundColor(0xFF1F1F1F);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(8), 0, dp(8));
        div.setLayoutParams(divLp);
        root.addView(div);

        // ── Scrollable list ───────────────────────────────────────
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        scroll.setFillViewport(true);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);

        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.96),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            lp.y = footerBar.getHeight() + dp(6);
            w.setAttributes(lp);
        }

        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] alive = {true};

        Thread pollThread = new Thread(() -> {
            while (alive[0]) {
                try {
                    final JSONArray arr = AuraDownloadHistory.get(this).listAll();
                    h.post(() -> {
                        if (!dialog.isShowing()) return;
                        try {
                            renderDownloadList(list, dialog, arr);
                        } catch (Exception e) {
                            Log.e("AuraBrowser", "renderDownloadList failed", e);
                        }
                    });
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    Log.e("AuraBrowser", "poll error", e);
                }
            }
        }, "aura-poll");
        pollThread.start();

        dialog.setOnDismissListener(d -> {
            alive[0] = false;
            if (downloadPanelDialog == d) downloadPanelDialog = null;
        });
        dialog.show();

    }

    private static final String EMPTY_TAG = "__aura_empty__";

    private void renderDownloadList(LinearLayout list, Dialog dialog, JSONArray arr) {
        // ── Empty state ─────────────────────────────────────────────
        if (arr.length() == 0) {
            // Only rebuild if we don't already show the empty state.
            if (list.getChildCount() == 1
                    && EMPTY_TAG.equals(list.getChildAt(0).getTag())) {
                return;
            }
            list.removeAllViews();

            LinearLayout empty = new LinearLayout(this);
            empty.setTag(EMPTY_TAG);                    // ← tagged so we can identify it
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));

            TextView icon = new TextView(this);
            icon.setText("⬇");
            icon.setTextSize(48);
            icon.setTextColor(0xFF333333);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);

            TextView msg = new TextView(this);
            msg.setText("No downloads yet");
            msg.setTextSize(14);
            msg.setTextColor(0xFF666666);
            msg.setGravity(Gravity.CENTER);
            msg.setPadding(0, dp(8), 0, 0);
            empty.addView(msg);

            TextView sub = new TextView(this);
            sub.setText("Downloads you start will appear here");
            sub.setTextSize(12);
            sub.setTextColor(0xFF444444);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(4), 0, 0);
            empty.addView(sub);

            list.addView(empty);
            return;
        }

        // ── Non-empty: drop the empty state if present ─────────────
        if (list.getChildCount() == 1
                && EMPTY_TAG.equals(list.getChildAt(0).getTag())) {
            list.removeAllViews();
        }

        // Gids that SHOULD be present after this render
        java.util.Set<String> incoming = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                incoming.add(arr.getJSONObject(i).optString("gid"));
            } catch (Exception ignored) {}
        }

        // Remove rows whose gid is gone
        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            View v = list.getChildAt(i);
            Object tag = v.getTag();
            if (!(tag instanceof String) || !incoming.contains(tag)) {
                list.removeViewAt(i);
            }
        }

        // Insert / update rows
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject job = arr.getJSONObject(i);
                String gid = job.optString("gid", "");
                if (gid.isEmpty()) continue;

                View existing = null;
                for (int c = 0; c < list.getChildCount(); c++) {
                    View child = list.getChildAt(c);
                    if (gid.equals(child.getTag())) { existing = child; break; }
                }

                if (existing != null) {
                    updateDownloadCard(existing, job);
                } else {
                    View card = createDownloadCard(job);
                    card.setTag(gid);
                    list.addView(card, 0);
                }
            } catch (Exception e) {
                Log.e("AuraBrowser", "renderDownloadList row failed", e);
            }
        }
    }

    private View createDownloadCard(JSONObject job) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        card.setLayoutParams(lp);
        card.setBackgroundColor(0xFF131313);

        // Filename row
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setId(View.generateViewId());
        tvName.setTextColor(0xFFF0F0F0);
        tvName.setTextSize(14);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setMaxLines(2);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nameRow.addView(tvName);

        TextView tvStatusChip = new TextView(this);
        tvStatusChip.setId(View.generateViewId());
        tvStatusChip.setTextSize(10);
        tvStatusChip.setPadding(dp(6), dp(2), dp(6), dp(2));
        nameRow.addView(tvStatusChip);

        card.addView(nameRow);

        // Stats row (progress text: "45% • 2.3 MB/s • 1.8 / 4 GB")
        TextView tvStats = new TextView(this);
        tvStats.setId(View.generateViewId());
        tvStats.setTextSize(12);
        tvStats.setTextColor(0xFF888888);
        tvStats.setPadding(0, dp(6), 0, dp(6));
        card.addView(tvStats);

        // Timers row (Elapsed / ETA)
        TextView tvTimers = new TextView(this);
        tvTimers.setId(View.generateViewId());
        tvTimers.setTextSize(11);
        tvTimers.setTextColor(0xFF666666);
        tvTimers.setPadding(0, 0, 0, dp(6));
        card.addView(tvTimers);
        // Progress bar
        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setId(View.generateViewId());
        bar.setMax(100);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        bar.setProgressDrawable(makeProgressDrawable());
        card.addView(bar);

        // Error line (hidden by default)
        TextView tvErr = new TextView(this);
        tvErr.setId(View.generateViewId());
        tvErr.setTextSize(11);
        tvErr.setTextColor(0xFFFF6666);
        tvErr.setPadding(0, dp(6), 0, 0);
        tvErr.setVisibility(View.GONE);
        card.addView(tvErr);

        // Actions
        LinearLayout actions = new LinearLayout(this);
        actions.setId(View.generateViewId());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);
        card.addView(actions);

        updateDownloadCard(card, job);
        return card;
    }

    private android.graphics.drawable.Drawable makeProgressDrawable() {
        // Build a LayerDrawable entirely from scratch so we never touch
        // Resources.getDrawable(int), which throws on themes that don't
        // define progressBarStyleHorizontal.
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF222222);
        bg.setCornerRadius(dp(3));

        android.graphics.drawable.GradientDrawable fg =
                new android.graphics.drawable.GradientDrawable();
        fg.setColor(0xFF33FF33);
        fg.setCornerRadius(dp(3));

        android.graphics.drawable.ClipDrawable progress =
                new android.graphics.drawable.ClipDrawable(
                        fg, Gravity.START,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL);

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{ bg, progress });

        // The progress layer is index 1, background index 0.
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);

        return ld;
    }

    private void updateDownloadCard(View card, JSONObject job) {
        if (!(card instanceof LinearLayout)) return;
        LinearLayout layout = (LinearLayout) card;

        String gid = job.optString("gid", "");
        String status = job.optString("status", "unknown");
        String name = job.optString("name", "file");
        long completed = job.optLong("completedLength", 0);
        long total = job.optLong("totalLength", 0);
        long elapsedMs = job.optLong("elapsedMs", 0);
        long etaMs = job.optLong("etaMs", 0);

        int pct = total > 0 ? (int) Math.min(100, (completed * 100) / total) : 0;
        long speed = 0;
        try { speed = Long.parseLong(job.optString("downloadSpeed", "0")); }
        catch (Exception ignored) {}
        if (!"active".equals(status)) speed = 0;

        // ★ Child indices must match createDownloadCard exactly
        LinearLayout nameRow      = (LinearLayout) layout.getChildAt(0);
        TextView tvName           = (TextView) nameRow.getChildAt(0);
        TextView tvStatusChip     = (TextView) nameRow.getChildAt(1);
        TextView tvStats          = (TextView) layout.getChildAt(1);
        TextView tvTimers         = (TextView) layout.getChildAt(2);
        ProgressBar bar           = (ProgressBar) layout.getChildAt(3);
        TextView tvErr            = (TextView) layout.getChildAt(4);
        LinearLayout actions      = (LinearLayout) layout.getChildAt(5);

        tvName.setText(name);

        // ── Status chip ──────────────────────────────────────────
        String chipText;
        int chipBg, chipFg;
        switch (status) {
            case "active":
                chipText = "DOWNLOADING"; chipBg = 0xFF1A3A1A; chipFg = 0xFF33FF33; break;
            case "paused":
                chipText = "PAUSED";      chipBg = 0xFF3A2A1A; chipFg = 0xFFFFAA00; break;
            case "complete":
                chipText = "DONE";        chipBg = 0xFF1A2A3A; chipFg = 0xFF66BBFF; break;
            case "error":
                chipText = "STOPPED";     chipBg = 0xFF3A1A1A; chipFg = 0xFFFF6666; break;
            default:
                chipText = "WAITING";     chipBg = 0xFF222222; chipFg = 0xFF888888; break;
        }
        tvStatusChip.setText(chipText);
        tvStatusChip.setTextColor(chipFg);
        tvStatusChip.setBackgroundColor(chipBg);

        // ── Stats ────────────────────────────────────────────────
        String stats;
        if ("active".equals(status)) {
            stats = pct + "%  •  " + humanSpeed(speed)
                    + "  •  " + humanBytes(completed) + " / " + humanBytes(total);
        } else if ("complete".equals(status)) {
            stats = "Saved  •  " + humanBytes(total);
        } else if ("paused".equals(status) || "error".equals(status)) {
            stats = pct + "%  •  " + humanBytes(completed) + " / " + humanBytes(total);
        } else {
            stats = pct + "%";
        }
        tvStats.setText(stats);

        // ── Timers ───────────────────────────────────────────────
        String timers;
        switch (status) {
            case "active":
                timers = "Elapsed " + formatElapsed(elapsedMs)
                        + "   •   " + formatEtaFull(etaMs) + " left";
                break;
            case "paused":
                timers = "Elapsed " + formatElapsed(elapsedMs) + "   •   Paused";
                break;
            case "complete":
                timers = "Completed in " + formatElapsed(elapsedMs);
                break;
            case "error":
                timers = "Elapsed " + formatElapsed(elapsedMs) + "   •   Stopped";
                break;
            default:
                timers = "";
                break;
        }
        tvTimers.setText(timers);
        tvTimers.setVisibility(timers.isEmpty() ? View.GONE : View.VISIBLE);

        // ── Progress bar ─────────────────────────────────────────
        bar.setProgress(pct);
        bar.setVisibility("complete".equals(status) ? View.GONE : View.VISIBLE);

        // ── Error line ───────────────────────────────────────────
        if ("error".equals(status)) {
            String errMsg = job.optString("errorMessage", "");
            tvErr.setText("⚠ " + (errMsg.isEmpty() ? "Download stopped" : errMsg));
            tvErr.setVisibility(View.VISIBLE);
        } else {
            tvErr.setVisibility(View.GONE);
        }

        // ── Action buttons ───────────────────────────────────────
        // ★ Always rebuild. This guarantees Pause / Resume / Cancel /
        //   Open appear on every card, every render.
        actions.removeAllViews();

        final String g = gid;

        if ("active".equals(status)) {
            actions.addView(makeActionButton("⏸  Pause", 0xFFFFAA00, () ->
                    AuraDownloadHistory.get(this).pause(g)));
            actions.addView(makeActionButton("✕  Cancel", 0xFFFF6666, () ->
                    AuraDownloadHistory.get(this).remove(g)));
        } else if ("paused".equals(status) || "error".equals(status)) {
            actions.addView(makeActionButton("▶  Resume", 0xFF33FF33, () ->
                    AuraDownloadHistory.get(this).unpause(g)));
            actions.addView(makeActionButton("✕  Cancel", 0xFFFF6666, () ->
                    AuraDownloadHistory.get(this).remove(g)));
        } else if ("complete".equals(status)) {
            actions.addView(makeActionButton("📂  Open", 0xFF66BBFF, () -> {
                String path = job.optString("savePath", "");
                if (!path.isEmpty()) openFile(new File(path));
            }));
            actions.addView(makeActionButton("✕  Remove", 0xFFFF6666, () ->
                    AuraDownloadHistory.get(this).remove(g)));
        } else {
            // Unknown / waiting — show Cancel as a safe default.
            actions.addView(makeActionButton("✕  Cancel", 0xFFFF6666, () ->
                    AuraDownloadHistory.get(this).remove(g)));
        }
    }

    private String formatElapsed(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    private String formatEtaFull(long ms) {
        if (ms <= 0) return "calculating";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + " min " + (s % 60) + "s";
        long h = m / 60;
        return h + "h " + (m % 60) + "m";
    }
    private TextView makeActionButton(String label, int color, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        tv.setBackgroundColor(0xFF1E1E1E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            onClick.run();
            // Immediate visual feedback
            tv.setAlpha(0.5f);
            tv.postDelayed(() -> tv.setAlpha(1f), 150);
        });
        return tv;
    }

    private String humanSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.1f MB/s",
                bytesPerSec / (1024.0 * 1024.0));
    }

    private String humanBytes(long b) {
        if (b <= 0) return "0 B";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024L * 1024L)
            return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB",
                b / (1024.0 * 1024.0 * 1024.0));
    }

    private View createDownloadRow(JSONObject job, Dialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(rp);
        row.setBackgroundColor(0xFF111111);

        TextView tvName = new TextView(this);
        tvName.setId(View.generateViewId());
        tvName.setTextColor(0xFFEEEEEE);
        tvName.setTextSize(12);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tvName);

        TextView tvInfo = new TextView(this);
        tvInfo.setId(View.generateViewId());
        tvInfo.setTextColor(0xFF999999);
        tvInfo.setTextSize(11);
        row.addView(tvInfo);

        TextView tvErr = new TextView(this);
        tvErr.setId(View.generateViewId());
        tvErr.setTextColor(0xFFFF6666);
        tvErr.setTextSize(10);
        tvErr.setPadding(0, dp(3), 0, 0);
        tvErr.setVisibility(View.GONE);
        row.addView(tvErr);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);
        actions.setId(View.generateViewId());
        row.addView(actions);

        updateDownloadRow(row, job);
        return row;
    }

    private void updateDownloadRow(View row, JSONObject job) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout layout = (LinearLayout) row;
        if (layout.getChildCount() < 4) return;

        String gid = job.optString("gid", "");
        String status = job.optString("status", "unknown");
        String name = job.optString("name", "unknown");
        long completed = job.optLong("completedLength", 0);
        long total = job.optLong("totalLength", 0);
        int pct = total > 0 ? (int) ((completed * 100) / total) : 0;
        long spd = 0;
        try { spd = Long.parseLong(job.optString("downloadSpeed", "0")); } catch (Exception ignored) {}
        if (!"active".equals(status)) spd = 0;

        TextView tvName = (TextView) layout.getChildAt(0);
        TextView tvInfo = (TextView) layout.getChildAt(1);
        TextView tvErr  = (TextView) layout.getChildAt(2);
        LinearLayout actions = (LinearLayout) layout.getChildAt(3);

        tvName.setText(name);

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
        // ── Real-time: always show current speed ─────────────────────
        tvInfo.setText(statusIcon + "  " + pct + "%  •  " + formatSpeed(spd));

        if ("error".equals(status)) {
            String errMsg = job.optString("errorMessage", "");
            tvErr.setText("⚠ " + (errMsg.isEmpty() ? "Download failed" : errMsg));
            tvErr.setVisibility(View.VISIBLE);
        } else {
            tvErr.setVisibility(View.GONE);
        }

        // Rebuild actions only if the button set needs to change.
        String wantedKey = status;
        Object currentKey = actions.getTag();
        if (!wantedKey.equals(currentKey)) {
            actions.setTag(wantedKey);
            actions.removeAllViews();

            final String g = gid;
            if ("active".equals(status)) {
                actions.addView(makeActionButton("⏸ Pause", () ->
                        AuraDownloadHistory.get(this).pause(g)));
            } else if ("paused".equals(status) || "error".equals(status)) {
                actions.addView(makeActionButton("▶ Resume", () ->
                        AuraDownloadHistory.get(this).unpause(g)));
            } else if ("complete".equals(status)) {
                actions.addView(makeActionButton("📂 Open", () -> {
                    String path = job.optString("savePath", "");
                    if (!path.isEmpty()) openFile(new File(path));
                }));
            }

            if (!"complete".equals(status)) {
                actions.addView(makeActionButton("✕ Remove", () ->
                        AuraDownloadHistory.get(this).remove(g)));
            }
        }
    }

    private void addDownloadRow(LinearLayout list, Dialog dialog, JSONObject job) {
        try {
            String gid = job.optString("gid", "");
            String status = job.optString("status", "unknown");
            String name = job.optString("name", "unknown");

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
                        new Thread(() -> AuraDownloadHistory.get(this).pause(g)).start()));
            } else if ("paused".equals(s)) {
                actions.addView(makeActionButton("▶ Resume", () ->
                        new Thread(() -> AuraDownloadHistory.get(this).unpause(g)).start()));
            } else if ("complete".equals(s)) {
                actions.addView(makeActionButton("📂 Open", () -> {
                    String path = job.optString("savePath", "");
                    if (!path.isEmpty()) openFile(new File(path));
                }));
            }

            if (!"removed".equals(s)) {
                actions.addView(makeActionButton("✕ Remove", () ->
                        new Thread(() -> AuraDownloadHistory.get(this).remove(g)).start()));
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
            Uri uri = FileProvider.getUriForFile(
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
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
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
                // ★ Address-bar navigation — always allow, don't run hijack checks.
                if (pendingUserLoad) {
                    return false;
                }

                Uri u = request.getUrl();
                String current = view.getUrl();

                if (u != null && current != null) {
                    try {
                        String fromHost = Uri.parse(current).getHost();
                        String toHost   = u.getHost();
                        if (isSameRegistrableDomain(fromHost, toHost)) {
                            return false;
                        }
                    } catch (Exception ignored) {}
                }

                return baseSecurity.shouldOverrideUrlLoading(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // ★ Same bypass for the deprecated overload.
                if (pendingUserLoad) {
                    return false;
                }

                String current = view.getUrl();
                if (url != null && current != null) {
                    try {
                        String fromHost = Uri.parse(current).getHost();
                        String toHost   = Uri.parse(url).getHost();
                        if (isSameRegistrableDomain(fromHost, toHost)) {
                            return false;
                        }
                    } catch (Exception ignored) {}
                }
                return baseSecurity.shouldOverrideUrlLoading(view, url);
            }


            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return baseSecurity.shouldInterceptRequest(view, request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, String url) {
                return baseSecurity.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == currentTabWebView()) {
                    progressBar.setVisibility(View.VISIBLE);
                    setUrlBarText(url);
                    isPageLoading = true;
                    btnRefresh.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                }
                if (pendingUserLoad) {
                    pendingUserLoad = false;
                }
                updateTabUrl(view, url);
                baseSecurity.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                baseSecurity.onPageCommitVisible(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == currentTabWebView()) {
                    progressBar.setVisibility(View.GONE);
                    setUrlBarText(url);
                    isPageLoading = false;
                    btnRefresh.setImageResource(android.R.drawable.ic_popup_sync);
                }
                updateTabUrl(view, url);

                // ★ Bug #3 fallback: if DOCUMENT_START_SCRIPT is
                //   unsupported, the bootstrap may never have run for
                //   this page. Force one injection now.
                try {
                    final String js =
                            "(function(){try{" +
                                    "window.__auraDarkReaderDesired=" + (forceDark ? "true" : "false") + ";" +
                                    "if(typeof window.__auraDarkReaderControl==='function'){" +
                                    "window.__auraDarkReaderControl(" + (forceDark ? "true" : "false") + ");" +
                                    "}" +
                                    "}catch(e){}})();";
                    view.evaluateJavascript(js, null);
                } catch (Throwable ignored) {}

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
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                return handleFileChooser(filePathCallback, fileChooserParams);
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.d("AURA-DARK-JS", cm.messageLevel() + " " + cm.message()
                        + " @" + cm.lineNumber() + " src=" + cm.sourceId());
                return true;
            }
        });

        installLongPressMenu(wv);

        // ★ Wired to the new native downloader ★
        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (contentLength > 0 && contentLength < 4096
                    && mimeType != null && mimeType.startsWith("image/")) {
                return;
            }
            startNativeDownload(url, userAgent, contentDisposition, mimeType);
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
            if (t.webView == wv) {
                t.url = url;
                persistTabs();
                return;
            }
        }
    }

    private void loadFromBar() {
        Tab t = currentTab();
        if (t == null) return;

        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) return;

        String finalUrl = normalizeUrl(url);

        // ★ Tell the security layer this navigation came from the user
        //   typing in the address bar, not from a page-controlled redirect.
        pendingUserLoad = true;

        t.hostTracker.reset();
        t.webView.loadUrl(finalUrl);
        t.url = finalUrl;
        persistTabs();

        urlBarUserEditing = false;
        urlBarJustSelectedAll = false;

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

            // ── 1. Plain anchor: extra IS the href ─────────────────────
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showLinkMenu(wv, extra, null);
                    return;
                }
            }

            // ── 2. Image inside an anchor: extra is the IMG src, we
            //      must walk the DOM to find the enclosing <a href>.
            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    // Ask the page to resolve the anchor around this image.
                    String js =
                            "(function(){" +
                                    "  try {" +
                                    "    var imgSrc = " + jsStringLiteral(extra) + ";" +
                                    // Find the image element by src
                                    "    var imgs = document.querySelectorAll('img');" +
                                    "    var img = null;" +
                                    "    for (var i = 0; i < imgs.length; i++) {" +
                                    "      if (imgs[i].src === imgSrc || imgs[i].currentSrc === imgSrc) {" +
                                    "        img = imgs[i]; break;" +
                                    "      }" +
                                    "    }" +
                                    "    if (!img) return JSON.stringify({a:'',i:imgSrc,t:''});" +
                                    // Walk up to the nearest <a href>
                                    "    var a = img.closest ? img.closest('a[href]') : null;" +
                                    "    if (!a) {" +
                                    "      var p = img.parentNode;" +
                                    "      while (p && p.tagName !== 'A') p = p.parentNode;" +
                                    "      if (p && p.tagName === 'A') a = p;" +
                                    "    }" +
                                    "    var href = a ? a.href : '';" +
                                    "    var txt = a && a.textContent ? a.textContent.trim() : '';" +
                                    "    return JSON.stringify({a: href, i: imgSrc, t: txt});" +
                                    "  } catch (e) {" +
                                    "    return JSON.stringify({a:'',i:'',t:''});" +
                                    "  }" +
                                    "})();";

                    wv.evaluateJavascript(js, value -> {
                        String anchorUrl = "";
                        String imageUrl = extra;
                        String anchorText = "";

                        try {
                            String decoded = decodeJsString(value);
                            if (decoded != null && !decoded.isEmpty()) {
                                org.json.JSONObject jo = new org.json.JSONObject(decoded);
                                anchorUrl = jo.optString("a", "");
                                String iu = jo.optString("i", "");
                                if (!iu.isEmpty()) imageUrl = iu;
                                anchorText = jo.optString("t", "");
                            }
                        } catch (Exception ignored) {}

                        if (!anchorUrl.isEmpty()) {
                            showLinkMenu(wv, anchorUrl, imageUrl);
                        } else {
                            // No anchor found — treat the image itself as the target
                            showLinkMenu(wv, imageUrl, imageUrl);
                        }
                    });
                    return;
                }
            }

            // ── 3. Plain image: no anchor, offer image actions ─────────
            if (type == WebView.HitTestResult.IMAGE_TYPE) {
                if (extra != null && !extra.isEmpty()) {
                    showImageMenu(wv, extra);
                    return;
                }
            }

            // ── 4. Fallback: text word at the touch point ──────────────
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
                if (text == null || text.trim().isEmpty()) return;
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

    private void showLinkMenu(WebView wv, String linkUrl, String imageUrl) {
        if (linkUrl == null || linkUrl.isEmpty()) return;

        final String anchorUrl = linkUrl;
        final String imgUrl = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : null;

        wv.evaluateJavascript(
                "(function(){try{"
                        + "var a=document.activeElement;"
                        + "if(!a||a.tagName!=='A'){"
                        + "  var all=document.querySelectorAll('a[href]');"
                        + "  for(var i=0;i<all.length;i++){"
                        + "    if(all[i].href===" + jsStringLiteral(anchorUrl) + "){a=all[i];break;}}"
                        + "}"
                        + "return a&&a.textContent?a.textContent.trim():'';"
                        + "}catch(e){return '';}})();",
                value -> {
                    String linkText = decodeJsString(value);
                    if (linkText == null || linkText.isEmpty()) linkText = anchorUrl;

                    final String finalText = linkText;

                    // Build the item list dynamically so "Open image" only
                    // appears when we actually detected an image.
                    java.util.List<CharSequence> items = new java.util.ArrayList<>();
                    final java.util.List<Integer> actions = new java.util.ArrayList<>();

                    // If we have BOTH an anchor URL and an image URL, and
                    // they differ, offer both "open link" and "open image".
                    boolean hasImage = imgUrl != null && !imgUrl.equals(anchorUrl);

                    if (hasImage) {
                        items.add("Open image");          actions.add(0);
                    }
                    items.add("Open link");               actions.add(1);
                    items.add("Open link in new tab");    actions.add(2);
                    items.add("Open link in incognito");  actions.add(3);
                    items.add("Download link");           actions.add(4);
                    if (hasImage) {
                        items.add("Download image");      actions.add(5);
                    }
                    items.add("Copy link URL");           actions.add(6);
                    items.add("Copy link text");          actions.add(7);
                    items.add("Share link");              actions.add(8);

                    final boolean fHasImage = hasImage;

                    new AlertDialog.Builder(AuraBrowserActivity.this)
                            .setTitle(trimForMenu(anchorUrl))
                            .setItems(items.toArray(new CharSequence[0]),
                                    (d, which) -> {
                                        int action = actions.get(which);
                                        switch (action) {
                                            case 0:  // Open image
                                                wv.loadUrl(imgUrl);
                                                break;
                                            case 1:  // Open link
                                                wv.loadUrl(anchorUrl);
                                                break;
                                            case 2:  // New tab
                                                newTab(anchorUrl, false);
                                                break;
                                            case 3:  // Incognito
                                                newTab(anchorUrl, true);
                                                break;
                                            case 4:  // Download link
                                                startNativeDownload(anchorUrl, null, null, null);
                                                break;
                                            case 5:  // Download image
                                                startNativeDownload(imgUrl, null, null, null);
                                                break;
                                            case 6:  // Copy link URL
                                                copyToClipboard("URL", anchorUrl);
                                                break;
                                            case 7:  // Copy link text
                                                copyToClipboard("Link text", finalText);
                                                break;
                                            case 8:  // Share link
                                                shareText(anchorUrl);
                                                break;
                                        }
                                    })
                            .show();
                });
    }

    private void showImageMenu(WebView wv, String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return;

        final String img = imageUrl;

        new AlertDialog.Builder(AuraBrowserActivity.this)
                .setTitle(trimForMenu(img))
                .setItems(new CharSequence[]{
                        "Open image",
                        "Save image",
                        "Copy image URL",
                        "Share image URL"
                }, (d, which) -> {
                    switch (which) {
                        case 0: wv.loadUrl(img); break;
                        case 1: startNativeDownload(img, null, null, null); break;
                        case 2: copyToClipboard("Image URL", img); break;
                        case 3: shareText(img); break;
                    }
                })
                .show();
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
            }
        } catch (Exception e) {
            Log.w("AuraBrowser", "Copy failed", e);
        }
    }

    private void shareText(String value) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, value);
            startActivity(Intent.createChooser(send, "Share"));
        } catch (Exception e) {
            Log.w("AuraBrowser", "Share failed", e);
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
        for (Tab t : tabs) {
            try { t.webView.onPause(); } catch (Throwable ignored) {}
            // ★ Stop any video/audio even when only the app (not the tab)
            //   loses focus.
            try {
                t.webView.evaluateJavascript(
                        "(function(){try{" +
                                "var m=document.querySelectorAll('video,audio');" +
                                "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}" +
                                "}catch(e){}})();", null);
            } catch (Throwable ignored) {}
        }
        persistTabs();   // ★ save session on background
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Tab t : tabs) {
            t.webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        // ★ Persist tab URLs BEFORE tearing down the WebViews. This is
        //   what makes tabs survive a process kill / reboot.
        persistTabs();

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