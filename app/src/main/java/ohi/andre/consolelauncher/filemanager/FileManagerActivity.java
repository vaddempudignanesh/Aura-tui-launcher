package ohi.andre.consolelauncher.filemanager;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import androidx.annotation.NonNull;

import ohi.andre.consolelauncher.mediareader.DocReader;
import ohi.andre.consolelauncher.mediareader.DocxReader;
import ohi.andre.consolelauncher.mediareader.PdfViewerActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.permissionhandler.StorageAdapter;
import ohi.andre.consolelauncher.permissionhandler.StoragePermissionHelper;

public class FileManagerActivity extends AppCompatActivity implements FileManagerAdapter.OnFileClickListener {

    private static final String HOME_DIR = Environment.getExternalStorageDirectory().getAbsolutePath();

    // Sort modes
    private static final int SORT_NAME_ASC   = 0;
    private static final int SORT_NAME_DESC  = 1;
    private static final int SORT_DATE_NEW   = 2;
    private static final int SORT_DATE_OLD   = 3;
    private static final int SORT_SIZE_BIG   = 4;
    private static final int SORT_SIZE_SMALL = 5;


    private static final int REQUEST_INSTALL_PACKAGES = 12345;

    private File pendingFileOpenAfterPermission = null;
    private boolean launchedForIncomingFile = false;

    private int currentSortMode = SORT_NAME_ASC;

    // Unfiltered directory contents (what's on disk)
    private List<File> currentFileList = new ArrayList<>();

    // What the adapter is currently showing (after search filter)
    private List<File> displayedFileList = new ArrayList<>();

    // Active search query ("" means no filter)
    private String currentSearchQuery = "";

    private File pendingApkInstall = null;

    // Views
    private DrawerLayout drawerLayout;
    private RecyclerView recyclerFiles;
    private RecyclerView recyclerStorage;
    private View drawerPanel;
    private View searchBar;
    private TextView tvPath;
    private TextView tvEmpty;
    private TextView tvSelectionInfo;
    private EditText etSearch;
    private ImageView btnMenu;
    private ImageView btnSearch;
    private ImageView btnSearchClear;
    private ImageView btnSort;
    private ImageView btnCopy;
    private ImageView btnMove;
    private ImageView btnDelete;
    private ImageView btnMore;
    private ImageView btnCloseSelection;


    private volatile int searchGeneration = 0;

    // Footer
    private LinearLayout footerBar;
    private LinearLayout footerActions;
    private LinearLayout footerProgress;
    private TextView btnFooterCancel;
    private TextView btnFooterPaste;
    private TextView tvProgressTitle;
    private TextView tvProgressStats;
    private TextView tvProgressPath;
    private ProgressBar progressBar;

    private FileManagerAdapter adapter;
    private StorageAdapter storageAdapter;

    private File currentDir;
    private final List<File> clipboard = new ArrayList<>();
    private boolean isCutOperation = false;
    private boolean isTransferring = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.Map<String, Long> sizeCache = new java.util.HashMap<>();

    // ==================== Lifecycle ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean hasAccess = StoragePermissionHelper.hasFullStorageAccess(this);
        if (!hasAccess) {
            StoragePermissionHelper.requestStorageAccess(this);
        }

        setContentView(R.layout.activity_file_manager);
        launchedForIncomingFile = isIncomingFileIntent(getIntent());

        initViews();
        setupRecyclerViews();
        setupListeners();
        setupStorageDrawer();

        currentDir = new File(HOME_DIR);

        if (!handleIncomingIntent(getIntent())) {
            loadDirectory(currentDir);
        }
    }

    private boolean isIncomingFileIntent(Intent intent) {
        if (intent == null) return false;
        String a = intent.getAction();
        return Intent.ACTION_VIEW.equals(a)
                || Intent.ACTION_SEND.equals(a)
                || Intent.ACTION_SEND_MULTIPLE.equals(a)
                || Intent.ACTION_EDIT.equals(a);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launchedForIncomingFile = isIncomingFileIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        thumbExecutor.shutdownNow();
    }

    /**
     * Called by FileManagerAdapter for image files.
     *  - Cached by absolute path only (mtime ignored) → no scroll cache misses.
     *  - Deduped via thumbInFlight → no duplicate decodes.
     *  - Decoded on 2 threads → no UI stutter.
     *  - View recycling is detected by re-checking the tag before committing.
     */
    public void loadThumbnail(final File file,
                              final ImageView target,
                              final android.graphics.drawable.Drawable fallbackIcon) {
        final String key = file.getAbsolutePath();

        // ── 1. Cache hit ────────────────────────────────────────────────
        Bitmap cached = thumbCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setPadding(0, 0, 0, 0);
            return;
        }

        // ── 2. Fallback while we wait ────────────────────────────────────
        target.setImageDrawable(fallbackIcon);
        target.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(10);
        target.setPadding(pad, pad, pad, pad);
        target.setTag(R.id.iv_icon, key);

        // ── 3. Already queued? just wait ────────────────────────────────
        if (!thumbInFlight.add(key)) return;

        final int sizePx = dp(THUMB_SIZE_DP);

        thumbExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                android.graphics.BitmapFactory.Options bounds =
                        new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(key, bounds);

                int w = bounds.outWidth;
                int h = bounds.outHeight;

                if (w > 0 && h > 0) {
                    int sample = 1;
                    while (w / sample > sizePx * 2 || h / sample > sizePx * 2) {
                        sample *= 2;
                    }

                    android.graphics.BitmapFactory.Options opts =
                            new android.graphics.BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    opts.inDither = false;

                    bmp = android.graphics.BitmapFactory.decodeFile(key, opts);
                }
            } catch (OutOfMemoryError oom) {
                bmp = null;
            } catch (Exception ignored) {
                bmp = null;
            } finally {
                thumbInFlight.remove(key);
            }

            final Bitmap finalBmp = bmp;

            if (finalBmp != null) {
                thumbCache.put(key, finalBmp);
            }

            // Committing the bitmap must be on the UI thread.
            mainHandler.post(() -> {
                if (finalBmp == null) return;

                Object tag = target.getTag(R.id.iv_icon);
                if (!key.equals(tag)) return; // view was recycled to another file

                // Detect recycled bitmaps defensively
                if (finalBmp.isRecycled()) return;

                target.setImageBitmap(finalBmp);
                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                target.setPadding(0, 0, 0, 0);
            });
        });
    }

    private void clearThumbCache() {
        thumbCache.evictAll();
    }

    private List<ResolveInfo> resolveExcludingSelf(Intent intent) {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = pm.queryIntentActivities(intent, 0);
        List<ResolveInfo> filtered = new ArrayList<>();
        String self = getPackageName();
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (!self.equals(pkg)) {
                filtered.add(ri);
            }
        }
        return filtered;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StoragePermissionHelper.REQUEST_CODE_MANAGE_STORAGE
                || requestCode == StoragePermissionHelper.REQUEST_CODE_LEGACY_STORAGE) {
            if (StoragePermissionHelper.handleActivityResult(this, requestCode)) {
                Toast.makeText(this, "Storage access granted", Toast.LENGTH_SHORT).show();
                loadDirectory(currentDir != null ? currentDir : new File(HOME_DIR));
                if (pendingFileOpenAfterPermission != null) {
                    File f = pendingFileOpenAfterPermission;
                    pendingFileOpenAfterPermission = null;
                    openFileWithMime(f, getMimeType(f));
                }
            } else {
                Toast.makeText(this,
                        "Storage access denied — some files won't open",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQUEST_INSTALL_PACKAGES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                if (pendingApkInstall != null) {
                    File apk = pendingApkInstall;
                    pendingApkInstall = null;
                    launchApkInstaller(apk);
                }
            } else {
                Toast.makeText(this,
                        "Please enable 'Install unknown apps' for this launcher",
                        Toast.LENGTH_LONG).show();
                pendingApkInstall = null;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (StoragePermissionHelper.handleRequestPermissionsResult(this, requestCode, grantResults)) {
            Toast.makeText(this, "Storage access granted", Toast.LENGTH_SHORT).show();
            loadDirectory(currentDir != null ? currentDir : new File(HOME_DIR));
            if (pendingFileOpenAfterPermission != null) {
                File f = pendingFileOpenAfterPermission;
                pendingFileOpenAfterPermission = null;
                openFileWithMime(f, getMimeType(f));
            }
        } else {
            Toast.makeText(this,
                    "Storage access denied — some files won't open",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ==================== Incoming File Handling ====================

    private boolean handleIncomingIntent(Intent intent) {
        if (intent == null) return false;

        String action = intent.getAction();
        Uri data = intent.getData();

        if (Intent.ACTION_SEND.equals(action)) {
            Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (streamUri != null) {
                handleIncomingUri(streamUri, intent.getType());
                return true;
            }
        }

        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                handleIncomingUri(uris.get(0), intent.getType());
                return true;
            }
        }

        if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_EDIT.equals(action)
                || Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
            if (data != null) {
                handleIncomingUri(data, intent.getType());
                return true;
            }
        }

        return false;
    }

    private void handleIncomingUri(Uri uri, String mimeType) {
        final String fileName;
        {
            String n = getFileNameFromUri(uri);
            if (n == null || n.isEmpty()) {
                n = "incoming_" + System.currentTimeMillis();
            }
            fileName = n;
        }

        File cacheDir = new File(getCacheDir(), "incoming");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File localFile = new File(cacheDir, fileName);

        long copied = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(localFile)) {
            if (in == null) {
                Toast.makeText(this, "Cannot read incoming file", Toast.LENGTH_LONG).show();
                return;
            }
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                copied += len;
            }
            out.flush();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot copy file: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (copied == 0) {
            Toast.makeText(this,
                    "Received an empty file from the source app.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final File fileToOpen = localFile;
        final String mime = mimeType != null ? mimeType : getMimeType(fileToOpen);

        mainHandler.post(() -> openFileWithMime(fileToOpen, mime));
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return null;

        if ("file".equals(uri.getScheme())) {
            return new File(uri.getPath()).getName();
        }

        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    result = cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) { }

        if (result == null) {
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                result = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }

        return result;
    }

    private void openOfficeNative(File file) {
        final String fileName = file.getName().toLowerCase(Locale.US);
        final boolean isDocx = fileName.endsWith(".docx") || fileName.endsWith(".docm");
        final boolean isDoc  = fileName.endsWith(".doc");

        if (!isDocx && !isDoc) {
            openOfficeDocument(file, getMimeType(file));
            return;
        }

        executor.execute(() -> {
            try {
                String text;
                if (isDocx) {
                    text = DocxReader.readDocx(file);
                } else {
                    text = DocReader.readDoc(file);
                }

                if (text == null || text.trim().isEmpty()) {
                    text = "(This document contains no readable text or is protected.)";
                }

                final String content = text;
                mainHandler.post(() -> showDocumentViewer(file, content));

            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this,
                            "Native reader failed, trying external app…",
                            Toast.LENGTH_SHORT).show();
                    openOfficeDocument(file, getMimeType(file));
                });
            }
        });
    }

    private void showDocumentViewer(File file, String content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        TextView infoBar = new TextView(this);
        infoBar.setText(file.getName() + "  •  " + FileManagerAdapter.formatSize(file.length()));
        infoBar.setTextColor(0xFF00AA00);
        infoBar.setBackgroundColor(0xFF001100);
        infoBar.setPadding(24, 16, 24, 16);
        infoBar.setTextSize(11);
        infoBar.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(infoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView viewer = new TextView(this);
        viewer.setText(content);
        viewer.setTextColor(0xFF00FF00);
        viewer.setBackgroundColor(0xFF000000);
        viewer.setPadding(32, 32, 32, 32);
        viewer.setTextSize(14);
        viewer.setTypeface(android.graphics.Typeface.MONOSPACE);
        viewer.setTextIsSelectable(true);
        viewer.setGravity(Gravity.TOP | Gravity.START);
        viewer.setHorizontallyScrolling(false);

        final android.widget.HorizontalScrollView hScroll =
                new android.widget.HorizontalScrollView(this);
        hScroll.setBackgroundColor(0xFF000000);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setFillViewport(true);
        hScroll.addView(viewer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.addView(hScroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF001100);
        bar.setPadding(8, 8, 8, 8);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        final EditText searchField = new EditText(this);
        searchField.setHint("Type to search…");
        searchField.setTextColor(0xFF00FF00);
        searchField.setHintTextColor(0xFF00AA00);
        searchField.setBackgroundColor(0xFF002200);
        searchField.setPadding(16, 8, 16, 8);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        bar.addView(searchField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnNext = createEditorButton("NEXT", 0xFF00FF00);
        Button btnWrap = createEditorButton("WRAP: ON", 0xFF00FF00);
        Button btnClose = createEditorButton("CLOSE", 0xFFFF5555);
        bar.addView(btnNext);
        bar.addView(btnWrap);
        bar.addView(btnClose);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = blackDialogBuilder().setView(root).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
        }

        final boolean[] wrapOn = { true };

        final Runnable applyWrapMode = () -> {
            if (wrapOn[0]) {
                viewer.setHorizontallyScrolling(false);
                viewer.setSingleLine(false);
                viewer.setMaxLines(Integer.MAX_VALUE);
                viewer.setIncludeFontPadding(true);

                hScroll.setFillViewport(true);
                hScroll.setHorizontalScrollBarEnabled(false);
                hScroll.setHorizontalFadingEdgeEnabled(false);
                hScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                hScroll.scrollTo(0, 0);

                ViewGroup.LayoutParams lp = viewer.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                viewer.setLayoutParams(lp);

                ViewGroup.LayoutParams hlp = hScroll.getLayoutParams();
                hlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                hScroll.setLayoutParams(hlp);

                btnWrap.setText("WRAP: ON");
                btnWrap.setTextColor(0xFF00FF00);
            } else {
                viewer.setHorizontallyScrolling(true);
                viewer.setSingleLine(false);
                viewer.setMaxLines(Integer.MAX_VALUE);

                hScroll.setFillViewport(false);
                hScroll.setHorizontalScrollBarEnabled(true);
                hScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

                ViewGroup.LayoutParams lp = viewer.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                viewer.setLayoutParams(lp);

                ViewGroup.LayoutParams hlp = hScroll.getLayoutParams();
                hlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                hScroll.setLayoutParams(hlp);

                btnWrap.setText("WRAP: OFF");
                btnWrap.setTextColor(0xFFFFFF00);
            }

            viewer.setText(viewer.getText());
            viewer.requestLayout();
            hScroll.requestLayout();
            scroll.requestLayout();
        };

        btnWrap.setOnClickListener(v -> {
            final int anchorLine;
            android.text.Layout oldLayout = viewer.getLayout();
            if (oldLayout != null) {
                int y = scroll.getScrollY();
                anchorLine = oldLayout.getLineForVertical(y);
            } else {
                anchorLine = 0;
            }

            wrapOn[0] = !wrapOn[0];
            applyWrapMode.run();

            scroll.post(() -> {
                android.text.Layout newLayout = viewer.getLayout();
                if (newLayout == null) return;
                int line = Math.min(anchorLine, newLayout.getLineCount() - 1);
                if (line < 0) line = 0;
                int targetY = newLayout.getLineTop(line);
                scroll.scrollTo(0, targetY);
            });
        });

        final String[] lastQuery = { "" };
        final int[] lastIndex = { -1 };

        final Runnable searchNext = () -> {
            String query = searchField.getText().toString();
            if (query.isEmpty()) {
                viewer.setText(content);
                lastIndex[0] = -1;
                lastQuery[0] = "";
                return;
            }

            String haystack = content.toLowerCase(Locale.US);
            String needle = query.toLowerCase(Locale.US);

            if (!needle.equals(lastQuery[0])) {
                lastIndex[0] = -1;
                lastQuery[0] = needle;
            }

            int start = lastIndex[0] + 1;
            int found = haystack.indexOf(needle, start);
            if (found < 0) {
                found = haystack.indexOf(needle);
                if (found < 0) {
                    viewer.setText(content);
                    return;
                }
            }
            lastIndex[0] = found;

            android.text.Spannable span =
                    new android.text.SpannableString(content);
            span.setSpan(new android.text.style.BackgroundColorSpan(0xFFFFFF00),
                    found, found + needle.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            viewer.setText(span);

            final int finalFound = found;
            scroll.post(() -> {
                android.text.Layout layout = viewer.getLayout();
                if (layout == null) return;

                int line = layout.getLineForOffset(finalFound);
                int lineTop = layout.getLineTop(line);
                int lineBottom = layout.getLineBottom(line);

                int viewportHeight = scroll.getHeight();
                int targetY = (lineTop + lineBottom) / 2 - viewportHeight / 2;
                if (targetY < 0) targetY = 0;

                scroll.smoothScrollTo(0, targetY);

                if (!wrapOn[0]) {
                    int x = (int) layout.getPrimaryHorizontal(finalFound);
                    int viewportW = hScroll.getWidth();
                    int targetX = x - viewportW / 2;
                    if (targetX < 0) targetX = 0;
                    hScroll.smoothScrollTo(targetX, 0);
                }
            });
        };

        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                searchNext.run();
            }
        });

        btnNext.setOnClickListener(v -> {
            lastIndex[0] = lastIndex[0] + 1;
            searchNext.run();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        recyclerFiles = findViewById(R.id.recycler_files);
        recyclerStorage = findViewById(R.id.recycler_storage);
        tvPath = findViewById(R.id.tv_path);
        tvEmpty = findViewById(R.id.tv_empty);
        tvSelectionInfo = findViewById(R.id.tv_selection_info);
        btnMenu = findViewById(R.id.btn_menu);
        btnSearch = findViewById(R.id.btn_search);
        btnSort = findViewById(R.id.btn_sort);
        btnCopy = findViewById(R.id.btn_copy);
        btnMove = findViewById(R.id.btn_move);
        btnDelete = findViewById(R.id.btn_delete);
        btnMore = findViewById(R.id.btn_more);
        btnCloseSelection = findViewById(R.id.btn_close_selection);
        drawerPanel = findViewById(R.id.drawer_panel);

        searchBar = findViewById(R.id.search_bar);
        etSearch = findViewById(R.id.et_search);
        btnSearchClear = findViewById(R.id.btn_search_clear);

        footerBar = findViewById(R.id.footer_bar);
        footerActions = findViewById(R.id.footer_actions);
        footerProgress = findViewById(R.id.footer_progress);
        btnFooterCancel = findViewById(R.id.btn_footer_cancel);
        btnFooterPaste = findViewById(R.id.btn_footer_paste);
        tvProgressTitle = findViewById(R.id.tv_progress_title);
        tvProgressStats = findViewById(R.id.tv_progress_stats);
        tvProgressPath = findViewById(R.id.tv_progress_path);
        progressBar = findViewById(R.id.progress_bar);

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        drawerLayout.setScrimColor(0x99000000);
    }

    private void setupRecyclerViews() {
        GridLayoutManager glm = new GridLayoutManager(this, 2);
        recyclerFiles.setLayoutManager(glm);
        recyclerFiles.setNestedScrollingEnabled(false);
        recyclerFiles.setHasFixedSize(true);
        recyclerFiles.setItemAnimator(null);   // ← no cross-fade on bind

        adapter = new FileManagerAdapter(this, this);
        recyclerFiles.setAdapter(adapter);

        recyclerStorage.setLayoutManager(new LinearLayoutManager(this));
        recyclerStorage.setNestedScrollingEnabled(false);
        storageAdapter = new StorageAdapter(this, item -> {
            drawerLayout.closeDrawers();
            currentDir = new File(item.path);
            loadDirectory(currentDir);
        });
        recyclerStorage.setAdapter(storageAdapter);
    }

    private void setupListeners() {
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(drawerPanel));
        btnMore.setOnClickListener(v -> showActionsMenu(v));
        btnSort.setOnClickListener(v -> showSortMenu(v));

        // ── Batch 2: search icon toggles the search bar ────────────
        btnSearch.setOnClickListener(v -> toggleSearchBar());

        btnSearchClear.setOnClickListener(v -> {
            etSearch.setText("");
            hideSearchBar();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                currentSearchQuery = s.toString().trim().toLowerCase(Locale.US);
                applySearchFilter();
            }
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        btnCopy.setOnClickListener(v -> {
            List<File> sel = new ArrayList<>(adapter.getSelectedFiles());
            if (!sel.isEmpty()) doCopy(sel);
        });

        btnMove.setOnClickListener(v -> {
            List<File> sel = new ArrayList<>(adapter.getSelectedFiles());
            if (!sel.isEmpty()) doCut(sel);
        });

        btnDelete.setOnClickListener(v -> {
            List<File> sel = new ArrayList<>(adapter.getSelectedFiles());
            if (!sel.isEmpty()) confirmDelete(sel);
        });

        btnCloseSelection.setOnClickListener(v -> {
            adapter.setSelectionMode(false);
            updateSelectionUI();
        });

        btnFooterCancel.setOnClickListener(v -> {
            clipboard.clear();
            isCutOperation = false;
            updateFooterBar();
        });

        btnFooterPaste.setOnClickListener(v -> {
            if (!isTransferring) startPaste();
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isTransferring) return;

                if (searchBar.getVisibility() == View.VISIBLE) {
                    hideSearchBar();
                    return;
                }

                if (drawerLayout.isDrawerOpen(drawerPanel)) {
                    drawerLayout.closeDrawer(drawerPanel);
                    return;
                }

                if (adapter != null && adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    updateSelectionUI();
                    return;
                }

                if (!clipboard.isEmpty()) {
                    blackDialogBuilder()
                            .setTitle("Discard clipboard?")
                            .setMessage(clipboard.size() + " item(s) will be forgotten")
                            .setPositiveButton("Discard", (d, w) -> {
                                clipboard.clear();
                                isCutOperation = false;
                                updateFooterBar();
                            })
                            .setNegativeButton("Keep", null)
                            .show();
                    return;
                }

                if (launchedForIncomingFile) {
                    setEnabled(false);
                    finishAndRemoveTask();
                    return;
                }

                if (currentDir != null && !currentDir.getAbsolutePath().equals(HOME_DIR)) {
                    File parent = currentDir.getParentFile();
                    if (parent != null && parent.canRead()) {
                        currentDir = parent;
                        loadDirectory(currentDir);
                        return;
                    }
                }

                setEnabled(false);
                finish();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch 2 — Search bar show / hide / filter
    // ═══════════════════════════════════════════════════════════════════

    private void toggleSearchBar() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            hideSearchBar();
        } else {
            showSearchBar();
        }
    }

    private void showSearchBar() {
        searchBar.setVisibility(View.VISIBLE);
        etSearch.requestFocus();
        etSearch.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
    }
    private void hideSearchBar() {
        hideKeyboard();
        searchBar.setVisibility(View.GONE);
        etSearch.setText("");
        currentSearchQuery = "";
        searchGeneration++;
        adapter.setSearchResults(null);     // clear sub-path line
        applySearchFilter();                 // repopulates with currentFileList
    }
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        etSearch.clearFocus();
    }

    private void applySearchFilter() {
        // No query → show the plain current directory.
        if (currentSearchQuery.isEmpty()) {
            displayedFileList = new ArrayList<>(currentFileList);
            adapter.setFiles(displayedFileList);
            adapter.setSearchResults(null);

            boolean empty = displayedFileList.isEmpty();
            recyclerFiles.setVisibility(empty ? View.GONE : View.VISIBLE);
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            tvEmpty.setText("Empty folder");
            return;
        }

        final File root = currentDir;
        final String query = currentSearchQuery;
        final int generation = ++searchGeneration;

        // Cancel any in-flight search and show "Searching…" briefly.
        tvEmpty.setText("Searching…");
        tvEmpty.setVisibility(View.VISIBLE);
        recyclerFiles.setVisibility(View.GONE);

        executor.execute(() -> {
            List<SearchResult> results = new ArrayList<>();
            recursiveSearch(root, root, query, results, generation);

            if (generation != searchGeneration) return;

            Collections.sort(results, (a, b) -> {
                int cmp = Integer.compare(a.relativePath.length(), b.relativePath.length());
                if (cmp != 0) return cmp;
                return a.file.getName().compareToIgnoreCase(b.file.getName());
            });

            mainHandler.post(() -> {
                if (generation != searchGeneration) return;

                displayedFileList = new ArrayList<>();
                for (SearchResult r : results) displayedFileList.add(r.file);

                // ORDER MATTERS: setFiles() triggers a rebind, so we must
                // hand the adapter both lists before the rebind paints.
                adapter.setFiles(displayedFileList);
                adapter.setSearchResults(results);

                boolean empty = results.isEmpty();
                recyclerFiles.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) {
                    tvEmpty.setText("No matches for \"" + query + "\"");
                }
            });
        });
    }

    /**
     * Depth-first search for files whose name contains `query` (case-insensitive).
     * Adds matches as SearchResult(file, relativePathFromRoot).
     * Directories are traversed but not added to results themselves.
     * Aborts early if the generation counter changes.
     */
    private void recursiveSearch(File root,
                                 File current,
                                 String query,
                                 List<SearchResult> out,
                                 int generation) {
        if (generation != searchGeneration) return;
        if (out.size() >= 2000) return;

        File[] children = current.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (generation != searchGeneration) return;

            // Skip Android/data + Android/obb — permission-protected and huge.
            String name = child.getName();
            if (current.getName().equals("Android")
                    && (name.equals("data") || name.equals("obb"))) {
                continue;
            }

            String lower = name.toLowerCase(Locale.US);
            if (lower.contains(query)) {
                String rel = relativize(root, child);
                out.add(new SearchResult(child, rel));
            }

            if (child.isDirectory()) {
                recursiveSearch(root, child, query, out, generation);
            }
        }
    }

    /**
     * Returns the path of `child` relative to `root`, with forward slashes.
     * Example: root=/sdcard, child=/sdcard/a/b.txt → "a/b.txt"
     */
    private static String relativize(File root, File child) {
        String rootPath = root.getAbsolutePath();
        String childPath = child.getAbsolutePath();
        if (childPath.startsWith(rootPath)) {
            String rel = childPath.substring(rootPath.length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }
        return childPath;
    }
    private void setupStorageDrawer() {
        List<StorageAdapter.StorageItem> items = new ArrayList<>();

        File internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.exists()) {
            items.add(new StorageAdapter.StorageItem("Internal Storage", internal.getAbsolutePath()));
        }

        File[] externalDirs = getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File dir : externalDirs) {
                if (dir != null) {
                    String path = dir.getAbsolutePath();
                    int idx = path.indexOf("/Android/");
                    if (idx > 0) path = path.substring(0, idx);
                    File extRoot = new File(path);
                    if (extRoot.exists() && !extRoot.equals(internal)) {
                        items.add(new StorageAdapter.StorageItem("SD Card", extRoot.getAbsolutePath()));
                    }
                }
            }
        }

        items.add(new StorageAdapter.StorageItem("Root", "/"));
        storageAdapter.setItems(items);
    }

    private AlertDialog.Builder blackDialogBuilder() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.BlackDialog));
    }

    // ==================== Sort ====================

    private void showSortMenu(View anchor) {
        ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(wrapper, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_sort_options, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            int newMode;
            if (id == R.id.sort_name_asc) newMode = SORT_NAME_ASC;
            else if (id == R.id.sort_name_desc) newMode = SORT_NAME_DESC;
            else if (id == R.id.sort_date_new) newMode = SORT_DATE_NEW;
            else if (id == R.id.sort_date_old) newMode = SORT_DATE_OLD;
            else if (id == R.id.sort_size_big) newMode = SORT_SIZE_BIG;
            else if (id == R.id.sort_size_small) newMode = SORT_SIZE_SMALL;
            else return false;

            currentSortMode = newMode;
            applySortAndRefresh();
            return true;
        });

        forceBlackPopupBackground(popup);
        popup.show();
    }

    private void applySortAndRefresh() {
        final List<File> snapshot = new ArrayList<>(currentFileList);
        final int sortModeSnapshot = currentSortMode;

        recyclerFiles.setEnabled(false);

        executor.execute(() -> {
            if (sortModeSnapshot == SORT_SIZE_BIG || sortModeSnapshot == SORT_SIZE_SMALL) {
                precomputeSizes(snapshot);
            }

            Collections.sort(snapshot, buildComparator());

            mainHandler.post(() -> {
                currentFileList = snapshot;
                recyclerFiles.setEnabled(true);
                applySearchFilter();
            });
        });
    }

    private Comparator<File> buildComparator() {
        return new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;

                switch (currentSortMode) {
                    case SORT_NAME_ASC:
                        return a.getName().compareToIgnoreCase(b.getName());
                    case SORT_NAME_DESC:
                        return b.getName().compareToIgnoreCase(a.getName());
                    case SORT_DATE_NEW:
                        return Long.compare(b.lastModified(), a.lastModified());
                    case SORT_DATE_OLD:
                        return Long.compare(a.lastModified(), b.lastModified());
                    case SORT_SIZE_BIG: {
                        Long sa = sizeCache.get(a.getAbsolutePath());
                        Long sb = sizeCache.get(b.getAbsolutePath());
                        long va = sa != null ? sa : 0L;
                        long vb = sb != null ? sb : 0L;
                        return Long.compare(vb, va);
                    }
                    case SORT_SIZE_SMALL: {
                        Long sa = sizeCache.get(a.getAbsolutePath());
                        Long sb = sizeCache.get(b.getAbsolutePath());
                        long va = sa != null ? sa : 0L;
                        long vb = sb != null ? sb : 0L;
                        return Long.compare(va, vb);
                    }
                    default:
                        return a.getName().compareToIgnoreCase(b.getName());
                }
            }
        };
    }

    private void precomputeSizes(List<File> files) {
        for (File f : files) {
            String key = f.getAbsolutePath();
            if (!sizeCache.containsKey(key)) {
                sizeCache.put(key, computeSizeRecursive(f));
            }
        }
    }

    private long computeSizeRecursive(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                total += computeSizeRecursive(c);
            }
        }
        return total;
    }

    // ==================== Directory Loading ====================

    private void loadDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.canRead()) {
            return;
        }

        currentDir = dir;
        tvPath.setText(dir.getAbsolutePath());

        // Directory changed → clear active search filter
        if (!currentSearchQuery.isEmpty()) {
            currentSearchQuery = "";
            etSearch.setText("");
            searchBar.setVisibility(View.GONE);
        }
        searchGeneration++;
        adapter.setSearchResults(null);

        final int sortModeSnapshot = currentSortMode;

        executor.execute(() -> {
            File[] files = dir.listFiles();
            List<File> fileList = new ArrayList<>();
            if (files != null) fileList.addAll(Arrays.asList(files));

            if (sortModeSnapshot == SORT_SIZE_BIG || sortModeSnapshot == SORT_SIZE_SMALL) {
                precomputeSizes(fileList);
            }
            Collections.sort(fileList, buildComparator());

            mainHandler.post(() -> {
                currentFileList = fileList;
                adapter.setSelectionMode(false);
                updateSelectionUI();
                updateFooterBar();
                applySearchFilter();
            });
        });
    }
    // ==================== UI Updates ====================

    private void updateSelectionUI() {
        boolean selectionMode = adapter.isSelectionMode();
        int count = adapter.getSelectedFiles().size();

        btnMore.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        btnCopy.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        btnMove.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        btnDelete.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        btnCloseSelection.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        tvSelectionInfo.setVisibility(selectionMode ? View.VISIBLE : View.GONE);

        if (selectionMode) tvSelectionInfo.setText(count + " selected");
    }

    private void updateFooterBar() {
        if (isTransferring) {
            footerBar.setVisibility(View.VISIBLE);
            footerActions.setVisibility(View.GONE);
            footerProgress.setVisibility(View.VISIBLE);
        } else if (!clipboard.isEmpty()) {
            footerBar.setVisibility(View.VISIBLE);
            footerActions.setVisibility(View.VISIBLE);
            footerProgress.setVisibility(View.GONE);
            String action = isCutOperation ? "MOVE" : "COPY";
            btnFooterPaste.setText(action + " HERE (" + clipboard.size() + ")");
        } else {
            footerBar.setVisibility(View.GONE);
            footerActions.setVisibility(View.VISIBLE);
            footerProgress.setVisibility(View.GONE);
        }
    }

    @Override
    public void onFileClick(File file, int position) {
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(file);
            updateSelectionUI();
            return;
        }

        if (file.isDirectory()) {
            // Directories still navigate — but clear the search first so
            // the user lands in a clean view of the folder.
            if (!currentSearchQuery.isEmpty()) {
                hideSearchBar();
            }
            loadDirectory(file);
            return;
        }

        // File (whether or not we're in search mode) → just open it.
        // Don't navigate anywhere.
        openFileWithMime(file, getMimeType(file));
    }
    @Override
    public void onFileLongClick(File file, int position) {
        adapter.setSelectionMode(true);
        adapter.toggleSelection(file);
        updateSelectionUI();
    }

    // ==================== UNIVERSAL FILE OPENING ====================

    private void openFileWithMime(File file, String mimeType) {
        if (!file.exists()) {
            Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!StoragePermissionHelper.hasFullStorageAccess(this)
                && file.getAbsolutePath().startsWith(
                Environment.getExternalStorageDirectory().getAbsolutePath())) {
            pendingFileOpenAfterPermission = file;
            StoragePermissionHelper.requestStorageAccess(this);
            return;
        }

        String fileName = file.getName().toLowerCase(Locale.US);
        if (mimeType == null) mimeType = getMimeType(file);

        if (fileName.endsWith(".apk")) {
            requestInstallApk(file);
            return;
        }
        if (fileName.endsWith(".zip")) {
            showZipFileOptions(file);
            return;
        }
        if (mimeType.equals("application/pdf") || fileName.endsWith(".pdf")) {
            openPdfNative(file);
            return;
        }
        if (isOfficeDocument(mimeType, fileName)) {
            if (fileName.endsWith(".docx") || fileName.endsWith(".docm") || fileName.endsWith(".doc")) {
                openOfficeNative(file);
            } else {
                openOfficeDocument(file, mimeType);
            }
            return;
        }
        if (isTextBasedFile(file) || mimeType.startsWith("text/")) {
            openTextEditor(file);
            return;
        }
        if (mimeType.startsWith("image/")) {
            openImagePreview(file);
            return;
        }
        if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            tryOpenWithDefaultApp(file, mimeType);
            return;
        }
        tryOpenWithDefaultApp(file, mimeType);
    }


    /**
     * Show a full-screen image preview dialog.
     *
     * Features:
     *  - Scales the image to fit the screen (FIT_CENTER).
     *  - Pinch-to-zoom + pan (via built-in zoom controls).
     *  - Double-tap toggles between fit and 2× zoom.
     *  - Tap on the image closes the dialog.
     *  - Loaded on a background thread to avoid blocking the UI.
     *  - Info bar shows name + size.
     */
    private void openImagePreview(File imageFile) {
        if (!imageFile.exists() || !imageFile.canRead()) {
            Toast.makeText(this, "Cannot read image", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Build the dialog shell on the UI thread first ──
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        // Info bar
        TextView infoBar = new TextView(this);
        infoBar.setText(imageFile.getName() + "  •  "
                + FileManagerAdapter.formatSize(imageFile.length()));
        infoBar.setTextColor(0xFF00FF00);
        infoBar.setBackgroundColor(0xFF001100);
        infoBar.setPadding(24, 24, 24, 24);
        infoBar.setTextSize(12);
        infoBar.setTypeface(android.graphics.Typeface.MONOSPACE);
        infoBar.setSingleLine(true);
        infoBar.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(infoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Loading indicator while the bitmap decodes
        final ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerLp.gravity = Gravity.CENTER_HORIZONTAL;
        spinnerLp.topMargin = 40;
        root.addView(spinner, spinnerLp);

        // Zoomable image container
        final android.widget.HorizontalScrollView hScroll =
                new android.widget.HorizontalScrollView(this);
        hScroll.setBackgroundColor(0xFF000000);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setFillViewport(true);
        hScroll.setVisibility(View.GONE);

        final android.widget.ImageView imageView = new android.widget.ImageView(this);
        imageView.setBackgroundColor(0xFF000000);
        imageView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        hScroll.addView(imageView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(hScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom bar with action buttons
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(0xFF001100);
        bottomBar.setPadding(8, 8, 8, 8);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);

        Button btnOpenWith = createEditorButton("OPEN WITH…", 0xFF00FF00);
        Button btnShare = createEditorButton("SHARE", 0xFF00FF00);
        Button btnClose = createEditorButton("CLOSE", 0xFFFF5555);

        bottomBar.addView(btnOpenWith, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(btnShare, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(btnClose, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        dialog.show();

        // ── Decode the bitmap on a background thread ──
        executor.execute(() -> {
            Bitmap bmp = null;
            try {
                // First pass: get the dimensions without loading the full bitmap
                android.graphics.BitmapFactory.Options bounds =
                        new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(
                        imageFile.getAbsolutePath(), bounds);

                int maxW = 2048;
                int maxH = 2048;
                int scale = 1;
                while (bounds.outWidth / scale > maxW
                        || bounds.outHeight / scale > maxH) {
                    scale *= 2;
                }

                android.graphics.BitmapFactory.Options opts =
                        new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = scale;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

                bmp = android.graphics.BitmapFactory.decodeFile(
                        imageFile.getAbsolutePath(), opts);
            } catch (OutOfMemoryError oom) {
                bmp = null;
            } catch (Exception e) {
                bmp = null;
            }

            final Bitmap finalBmp = bmp;

            mainHandler.post(() -> {
                spinner.setVisibility(View.GONE);

                if (finalBmp == null) {
                    Toast.makeText(this,
                            "Could not load image (too large or unsupported format)",
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    return;
                }

                imageView.setImageBitmap(finalBmp);
                hScroll.setVisibility(View.VISIBLE);

                // ── Enable pinch-zoom + pan via Matrix ──
                // (ImageView doesn't natively zoom; wire up a ScaleGestureDetector.)
                final android.graphics.Matrix matrix = new android.graphics.Matrix();
                imageView.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
                imageView.setImageMatrix(matrix);

                // Center the image initially
                final Runnable centerImage = () -> {
                    float vw = imageView.getWidth();
                    float vh = imageView.getHeight();
                    float bw = finalBmp.getWidth();
                    float bh = finalBmp.getHeight();
                    if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) return;

                    float fitScale = Math.min(vw / bw, vh / bh);
                    matrix.reset();
                    matrix.postScale(fitScale, fitScale);
                    matrix.postTranslate(
                            (vw - bw * fitScale) / 2f,
                            (vh - bh * fitScale) / 2f);
                    imageView.setImageMatrix(matrix);
                };
                imageView.post(centerImage);

                final float[] baseScale = { 1f };

                final android.view.ScaleGestureDetector scaleDetector =
                        new android.view.ScaleGestureDetector(this,
                                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                    @Override
                                    public boolean onScale(android.view.ScaleGestureDetector d) {
                                        float factor = d.getScaleFactor();
                                        float cur = getMatrixScale(matrix);
                                        float next = cur * factor;
                                        if (next < 0.5f) factor = 0.5f / cur;
                                        else if (next > 8f) factor = 8f / cur;

                                        matrix.postScale(factor, factor,
                                                d.getFocusX(), d.getFocusY());
                                        imageView.setImageMatrix(matrix);
                                        return true;
                                    }
                                });

                final float[] lastTouch = { 0f, 0f };

                imageView.setOnTouchListener((v, ev) -> {
                    scaleDetector.onTouchEvent(ev);

                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            lastTouch[0] = ev.getX();
                            lastTouch[1] = ev.getY();
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            if (ev.getPointerCount() > 1) return true;
                            float dx = ev.getX() - lastTouch[0];
                            float dy = ev.getY() - lastTouch[1];
                            lastTouch[0] = ev.getX();
                            lastTouch[1] = ev.getY();
                            matrix.postTranslate(dx, dy);
                            imageView.setImageMatrix(matrix);
                            return true;
                        }
                    }
                    return true;
                });

                // Double-tap toggles between fit and 2× zoom
                final android.view.GestureDetector gesture =
                        new android.view.GestureDetector(this,
                                new android.view.GestureDetector.SimpleOnGestureListener() {
                                    @Override
                                    public boolean onDown(MotionEvent e) {
                                        return true;
                                    }

                                    @Override
                                    public boolean onDoubleTap(MotionEvent e) {
                                        float cur = getMatrixScale(matrix);
                                        if (cur > 1.2f) {
                                            centerImage.run();
                                        } else {
                                            matrix.postScale(2f, 2f,
                                                    e.getX(), e.getY());
                                            imageView.setImageMatrix(matrix);
                                        }
                                        return true;
                                    }
                                });

                imageView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent ev) {
                        gesture.onTouchEvent(ev);
                        scaleDetector.onTouchEvent(ev);

                        switch (ev.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                lastTouch[0] = ev.getX();
                                lastTouch[1] = ev.getY();
                                return true;
                            case MotionEvent.ACTION_MOVE: {
                                if (ev.getPointerCount() > 1) return true;
                                float dx = ev.getX() - lastTouch[0];
                                float dy = ev.getY() - lastTouch[1];
                                lastTouch[0] = ev.getX();
                                lastTouch[1] = ev.getY();
                                matrix.postTranslate(dx, dy);
                                imageView.setImageMatrix(matrix);
                                return true;
                            }
                        }
                        return true;
                    }
                });
            });
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnOpenWith.setOnClickListener(v -> {
            dialog.dismiss();
            openWithChooser(imageFile, getMimeType(imageFile));
        });
        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            shareFile(imageFile);
        });

        dialog.setOnDismissListener(d -> {
            // Free the bitmap to avoid leaking memory
            android.graphics.drawable.Drawable dr = imageView.getDrawable();
            if (dr instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap b = ((android.graphics.drawable.BitmapDrawable) dr).getBitmap();
                if (b != null && !b.isRecycled()) b.recycle();
            }
            imageView.setImageDrawable(null);
        });
    }

    /** Extract the current uniform scale factor from a Matrix. */
    private static float getMatrixScale(android.graphics.Matrix m) {
        float[] v = new float[9];
        m.getValues(v);
        float sx = v[android.graphics.Matrix.MSCALE_X];
        float sy = v[android.graphics.Matrix.MSCALE_Y];
        return (float) Math.sqrt(sx * sx + sy * sy);
    }
    private boolean isOfficeDocument(String mimeType, String fileName) {
        if (mimeType == null) return false;
        if (mimeType.contains("msword")) return true;
        if (mimeType.contains("wordprocessing")) return true;
        if (mimeType.contains("ms-excel")) return true;
        if (mimeType.contains("spreadsheet")) return true;
        if (mimeType.contains("ms-powerpoint")) return true;
        if (mimeType.contains("presentation")) return true;
        if (mimeType.contains("opendocument")) return true;
        if (mimeType.equals("application/rtf")) return true;

        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || lower.endsWith(".odt") || lower.endsWith(".ods")
                || lower.endsWith(".odp") || lower.endsWith(".rtf");
    }

    private void openPdfNative(File pdfFile) {
        try {
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, pdfFile.getAbsolutePath());
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_NAME, pdfFile.getName());
            startActivity(intent);
            return;
        } catch (Exception ignored) { }

        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pdfFile);
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.setData(uri);
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_NAME, pdfFile.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return;
        } catch (Exception ignored) { }

        tryOpenWithDefaultApp(pdfFile, "application/pdf");
    }

    private void openOfficeDocument(File file, String mimeType) {
        if (file.length() == 0) {
            Toast.makeText(this,
                    "File is empty (size 0). The share from the source app failed.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Uri fileUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                fileUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot share file: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                showUnknownFileDialog(file, mimeType);
                return;
            }
        } else {
            fileUri = Uri.fromFile(file);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        List<ResolveInfo> activities = resolveExcludingSelf(intent);

        if (activities.isEmpty()) {
            blackDialogBuilder()
                    .setTitle("No Office App Found")
                    .setMessage("No app is installed to open " + file.getName()
                            + ".\n\nYou can view the raw file content as text, "
                            + "share it, or install an office app.")
                    .setPositiveButton("View as Text", (d, w) -> openAsText(file))
                    .setNeutralButton("Share", (d, w) -> shareFile(file))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        for (ResolveInfo ri : activities) {
            try {
                grantUriPermission(ri.activityInfo.packageName, fileUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }

        try {
            if (activities.size() == 1) {
                intent.setComponent(new android.content.ComponentName(
                        activities.get(0).activityInfo.packageName,
                        activities.get(0).activityInfo.name));
                startActivity(intent);
            } else {
                Intent chooser = Intent.createChooser(intent,
                        "Open " + file.getName() + " with");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== APK INSTALL ====================

    private void requestInstallApk(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageManager pm = getPackageManager();
            if (!pm.canRequestPackageInstalls()) {
                pendingApkInstall = apkFile;
                Toast.makeText(this,
                        "Enable 'Install unknown apps' to install APKs",
                        Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_INSTALL_PACKAGES);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_INSTALL_PACKAGES);
                    } catch (Exception e2) {
                        Toast.makeText(this,
                                "Cannot open settings. Install manually from a file manager.",
                                Toast.LENGTH_LONG).show();
                    }
                }
                return;
            }
        }

        launchApkInstaller(apkFile);
    }

    private void launchApkInstaller(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", apkFile);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

            List<ResolveInfo> resInfoList = getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

            for (ResolveInfo ri : resInfoList) {
                String pkg = ri.activityInfo.packageName;
                grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            if (resInfoList.isEmpty()) {
                showNoInstallerDialog(apkFile);
                return;
            }

            startActivity(intent);

        } catch (ActivityNotFoundException e) {
            showNoInstallerDialog(apkFile);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open installer: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showNoInstallerDialog(File apkFile) {
        blackDialogBuilder()
                .setTitle("No Package Installer")
                .setMessage("This device has no package installer app available.\n\n"
                        + "APK: " + apkFile.getName() + "\n\n"
                        + "You can share the file or open it with another app.")
                .setPositiveButton("Share", (d, w) -> shareFile(apkFile))
                .setNeutralButton("Open with...", (d, w) ->
                        openWithChooser(apkFile, "application/vnd.android.package-archive"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ==================== ZIP Options ====================

    private void showZipFileOptions(File zipFile) {
        blackDialogBuilder()
                .setTitle(zipFile.getName())
                .setItems(new CharSequence[]{"Extract here", "Open with...", "Cancel"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startExtract(zipFile);
                            break;
                        case 1:
                            tryOpenWithDefaultApp(zipFile, "application/zip");
                            break;
                        case 2:
                            dialog.dismiss();
                            break;
                    }
                })
                .show();
    }

    // ==================== Open With Default App ====================

    private void tryOpenWithDefaultApp(File file, String mimeType) {
        if (!file.exists()) {
            Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!file.canRead()) {
            Toast.makeText(this, "File is not readable", Toast.LENGTH_SHORT).show();
            return;
        }
        if (file.length() == 0) {
            Toast.makeText(this, "File is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri fileUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                fileUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                showUnknownFileDialog(file, mimeType);
                return;
            }
        } else {
            fileUri = Uri.fromFile(file);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        List<ResolveInfo> activities = resolveExcludingSelf(intent);

        if (activities.isEmpty()) {
            showUnknownFileDialog(file, mimeType);
            return;
        }

        for (ResolveInfo ri : activities) {
            try {
                grantUriPermission(ri.activityInfo.packageName, fileUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }

        try {
            if (activities.size() == 1) {
                intent.setComponent(new android.content.ComponentName(
                        activities.get(0).activityInfo.packageName,
                        activities.get(0).activityInfo.name));
                startActivity(intent);
            } else {
                Intent chooser = Intent.createChooser(intent, "Open with");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
            }
        } catch (Exception e) {
            showUnknownFileDialog(file, mimeType);
        }
    }

    private void showUnknownFileDialog(File file, String mimeType) {
        String fileName = file.getName();
        String fileSize = FileManagerAdapter.formatSize(file.length());
        boolean isText = isTextBasedFile(file);

        StringBuilder message = new StringBuilder();
        message.append("No app found to open this file.\n\n");
        message.append("File: ").append(fileName).append("\n");
        message.append("Size: ").append(fileSize).append("\n");
        message.append("Type: ").append(getReadableType(mimeType)).append("\n\n");
        message.append("You can open it in the built-in text editor or choose another action.");

        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        options.add("📝  Open in Text Editor" + (isText ? " (recommended)" : ""));
        actions.add(() -> openTextEditor(file));

        options.add("👁  View as Text (read-only)");
        actions.add(() -> openAsText(file));

        options.add("📤  Share file");
        actions.add(() -> shareFile(file));

        options.add("🔍  Open with... (system chooser)");
        actions.add(() -> openWithChooser(file, mimeType));

        options.add("ℹ  Show file info");
        actions.add(() -> showFileInfo(file));

        options.add("✖  Cancel");
        actions.add(() -> { });

        String[] optionArray = options.toArray(new String[0]);

        blackDialogBuilder()
                .setTitle("Unknown File Type")
                .setMessage(message.toString())
                .setItems(optionArray, (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ==================== Text Editor ====================

    private void openTextEditor(File file) {
        executor.execute(() -> {
            try {
                long maxEditSize = 5 * 1024 * 1024;
                long fileSize = file.length();
                boolean tooBig = fileSize > maxEditSize;

                if (tooBig) {
                    mainHandler.post(() -> blackDialogBuilder()
                            .setTitle("File Too Large")
                            .setMessage("This file is " + FileManagerAdapter.formatSize(fileSize)
                                    + ".\n\nThe text editor supports files up to 5 MB.\n"
                                    + "Use 'View as Text' instead.")
                            .setPositiveButton("View as Text", (d, w) -> openAsText(file))
                            .setNegativeButton("Cancel", null)
                            .show());
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
                    char[] buf = new char[8192];
                    int read;
                    while ((read = reader.read(buf)) > 0) {
                        sb.append(buf, 0, read);
                    }
                }

                final String content = sb.toString();

                mainHandler.post(() -> showEditorDialog(file, content));

            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Failed to read file: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showEditorDialog(File file, String content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        TextView infoBar = new TextView(this);
        infoBar.setText(file.getName() + "  •  " + FileManagerAdapter.formatSize(file.length()));
        infoBar.setTextColor(0xFF00AA00);
        infoBar.setBackgroundColor(0xFF001100);
        infoBar.setPadding(24, 16, 24, 16);
        infoBar.setTextSize(11);
        infoBar.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(infoBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText editor = new EditText(this);
        editor.setText(content);
        editor.setTextColor(0xFF00FF00);
        editor.setHintTextColor(0xFF006600);
        editor.setHint("Empty file...");
        editor.setBackgroundColor(0xFF000000);
        editor.setPadding(24, 24, 24, 24);
        editor.setTextSize(13);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setMaxLines(Integer.MAX_VALUE);
        editor.setVerticalScrollBarEnabled(true);
        editor.setHorizontalScrollBarEnabled(false);
        editor.setIncludeFontPadding(true);

        final WrapAwareHorizontalScrollView editorHScroll =
                new WrapAwareHorizontalScrollView(this);
        editorHScroll.setBackgroundColor(0xFF000000);
        editorHScroll.setFillViewport(true);
        editorHScroll.setHorizontalScrollBarEnabled(false);
        editorHScroll.setHorizontalFadingEdgeEnabled(false);
        editorHScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        editorHScroll.addView(editor, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF000000);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(editorHScroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setBackgroundColor(0xFF001100);
        buttonBar.setPadding(8, 8, 8, 8);
        buttonBar.setGravity(Gravity.CENTER_VERTICAL);

        Button btnSave = createEditorButton("SAVE", 0xFF00FF00);
        Button btnSaveAs = createEditorButton("SAVE AS", 0xFF00FF00);
        Button btnWrap = createEditorButton("WRAP", 0xFF00FF00);
        Button btnClose = createEditorButton("CLOSE", 0xFFFF5555);

        buttonBar.addView(btnSave, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttonBar.addView(btnSaveAs, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttonBar.addView(btnWrap, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttonBar.addView(btnClose, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttonBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = blackDialogBuilder()
                .setView(root)
                .create();

        final String[] original = { content };
        final boolean[] wrapEnabled = { false };

        btnSave.setOnClickListener(v -> {
            String newContent = editor.getText().toString();
            saveFile(file, newContent, dialog, original);
        });

        btnSaveAs.setOnClickListener(v -> {
            String newContent = editor.getText().toString();
            showSaveAsDialog(file, newContent);
        });

        final Runnable applyWrapMode = () -> {
            if (wrapEnabled[0]) {
                editorHScroll.setWrapWidthLocked(true);
                editor.setHorizontallyScrolling(false);
                editor.setSingleLine(false);
                editor.setMaxLines(Integer.MAX_VALUE);

                editorHScroll.setFillViewport(true);
                editorHScroll.setHorizontalScrollBarEnabled(false);
                editorHScroll.setHorizontalFadingEdgeEnabled(false);
                editorHScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                editorHScroll.scrollTo(0, 0);

                scrollView.scrollTo(0, scrollView.getScrollY());

                ViewGroup.LayoutParams lp = editor.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                editor.setLayoutParams(lp);

                editorHScroll.setWrapWidthLocked(true);

                btnWrap.setText("WRAP ON");
                btnWrap.setTextColor(0xFFFFFF00);

            } else {
                editorHScroll.setWrapWidthLocked(false);
                editor.setHorizontallyScrolling(true);
                editor.setSingleLine(false);
                editor.setMaxLines(Integer.MAX_VALUE);

                editorHScroll.setFillViewport(false);
                editorHScroll.setHorizontalScrollBarEnabled(true);
                editorHScroll.setHorizontalFadingEdgeEnabled(false);
                editorHScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

                ViewGroup.LayoutParams lp = editor.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                editor.setLayoutParams(lp);

                editorHScroll.setWrapWidthLocked(false);

                btnWrap.setText("WRAP");
                btnWrap.setTextColor(0xFF00FF00);
            }

            editor.requestLayout();
            editorHScroll.requestLayout();
            scrollView.requestLayout();
            root.requestLayout();

            editorHScroll.post(() -> {
                if (wrapEnabled[0]) {
                    editorHScroll.setWrapWidthLocked(true);
                }

                editor.requestLayout();
                editorHScroll.requestLayout();
                scrollView.requestLayout();
            });
        };

        editorHScroll.getViewTreeObserver().addOnGlobalLayoutListener(
                () -> {
                    if (!wrapEnabled[0]) return;

                    int width = editorHScroll.getWidth();
                    if (width <= 0) return;

                    editorHScroll.setWrapWidthLocked(true);
                    editor.requestLayout();

                    if (editorHScroll.getScrollX() != 0) {
                        editorHScroll.scrollTo(0, editorHScroll.getScrollY());
                    }
                });

        btnWrap.setOnClickListener(v -> {
            int anchorOffset = editor.getSelectionStart();
            if (anchorOffset < 0) anchorOffset = 0;
            if (anchorOffset > editor.length()) anchorOffset = editor.length();

            int oldScrollY = scrollView.getScrollY();
            android.text.Layout oldLayout = editor.getLayout();
            if (oldLayout != null && editor.length() > 0) {
                int visibleTop = oldScrollY + editor.getPaddingTop();
                int line = oldLayout.getLineForVertical(
                        Math.max(0, visibleTop - editor.getTop()));
                if (line >= 0 && line < oldLayout.getLineCount()) {
                    anchorOffset = Math.max(
                            0,
                            Math.min(
                                    editor.length(),
                                    oldLayout.getOffsetForHorizontal(
                                            line,
                                            oldLayout.getLineLeft(line)
                                    )
                            )
                    );
                }
            }

            wrapEnabled[0] = !wrapEnabled[0];
            applyWrapMode.run();

            final int finalAnchorOffset = anchorOffset;

            editor.post(() -> editor.post(() -> {
                android.text.Layout newLayout = editor.getLayout();
                if (newLayout == null || newLayout.getLineCount() == 0) return;

                int offset = Math.max(
                        0,
                        Math.min(finalAnchorOffset, editor.length())
                );

                int line = newLayout.getLineForOffset(offset);
                line = Math.max(
                        0,
                        Math.min(
                                line,
                                newLayout.getLineCount() - 1
                        )
                );

                int targetY = newLayout.getLineTop(line)
                        - editor.getPaddingTop()
                        - dp(8);

                if (targetY < 0) targetY = 0;

                scrollView.scrollTo(0, targetY);

                if (wrapEnabled[0]) {
                    editorHScroll.scrollTo(0, editorHScroll.getScrollY());
                }
            }));
        });

        btnClose.setOnClickListener(v -> {
            String currentContent = editor.getText().toString();
            if (!currentContent.equals(original[0])) {
                blackDialogBuilder()
                        .setTitle("Unsaved Changes")
                        .setMessage("You have unsaved changes. Save before closing?")
                        .setPositiveButton("Save", (d, w) -> {
                            saveFile(file, currentContent, dialog, original);
                        })
                        .setNeutralButton("Discard", (d, w) -> dialog.dismiss())
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                dialog.dismiss();
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
        }

        editor.post(() -> {
            applyWrapMode.run();
            editor.requestFocus();
        });
    }

    private static class WrapAwareHorizontalScrollView
            extends android.widget.HorizontalScrollView {

        private boolean wrapWidthLocked = false;

        WrapAwareHorizontalScrollView(Context context) {
            super(context);
            setFillViewport(true);
            setClipToPadding(true);
        }

        void setWrapWidthLocked(boolean locked) {
            if (wrapWidthLocked == locked) {
                requestLayout();
                return;
            }

            wrapWidthLocked = locked;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            if (!wrapWidthLocked || getChildCount() == 0) {
                return;
            }

            View child = getChildAt(0);

            int availableWidth = getMeasuredWidth()
                    - getPaddingLeft()
                    - getPaddingRight();

            if (availableWidth <= 0) {
                return;
            }

            int childWidthSpec = MeasureSpec.makeMeasureSpec(
                    availableWidth,
                    MeasureSpec.EXACTLY
            );

            int childHeightSpec = getChildMeasureSpec(
                    heightMeasureSpec,
                    getPaddingTop() + getPaddingBottom(),
                    child.getLayoutParams().height
            );

            child.measure(childWidthSpec, childHeightSpec);

            setMeasuredDimension(
                    getMeasuredWidth(),
                    Math.max(
                            getMeasuredHeight(),
                            child.getMeasuredHeight()
                                    + getPaddingTop()
                                    + getPaddingBottom()
                    )
            );
        }

        @Override
        protected void onLayout(
                boolean changed,
                int left,
                int top,
                int right,
                int bottom) {

            if (wrapWidthLocked && getChildCount() > 0) {
                View child = getChildAt(0);

                int childLeft = getPaddingLeft();
                int childTop = getPaddingTop();

                child.layout(
                        childLeft,
                        childTop,
                        childLeft + getMeasuredWidth()
                                - getPaddingLeft()
                                - getPaddingRight(),
                        childTop + child.getMeasuredHeight()
                );
                return;
            }

            super.onLayout(changed, left, top, right, bottom);
        }
    }

    private Button createEditorButton(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setBackgroundColor(0xFF001100);
        btn.setTextSize(11);
        btn.setTypeface(android.graphics.Typeface.MONOSPACE);
        btn.setPadding(8, 8, 8, 8);
        return btn;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void saveFile(File file, String content, AlertDialog dialog, String[] original) {
        executor.execute(() -> {
            try {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content.getBytes("UTF-8"));
                    fos.flush();
                }
                mainHandler.post(() -> {
                    original[0] = content;
                    Toast.makeText(this, "Saved: " + file.getName(), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Save failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showSaveAsDialog(File originalFile, String content) {
        EditText input = new EditText(this);
        input.setText(originalFile.getName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFF00FF00);
        input.setHintTextColor(0xFF00AA00);
        input.setBackgroundColor(0xFF1A1A1A);
        input.setPadding(24, 24, 24, 24);

        blackDialogBuilder()
                .setTitle("Save As")
                .setMessage("Saving in: " + originalFile.getParent())
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) return;

                    File newFile = new File(originalFile.getParent(), newName);
                    if (newFile.exists()) {
                        blackDialogBuilder()
                                .setTitle("File Exists")
                                .setMessage(newName + " already exists. Overwrite?")
                                .setPositiveButton("Overwrite", (d, w) -> writeFile(newFile, content))
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        writeFile(newFile, content);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void writeFile(File file, String content) {
        executor.execute(() -> {
            try {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content.getBytes("UTF-8"));
                    fos.flush();
                }
                mainHandler.post(() -> {
                    Toast.makeText(this, "Saved: " + file.getName(), Toast.LENGTH_SHORT).show();
                    loadDirectory(currentDir);
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Save failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openAsText(File file) {
        executor.execute(() -> {
            try {
                long maxSize = 1024 * 1024;
                long fileSize = file.length();
                boolean truncated = fileSize > maxSize;

                byte[] buffer = new byte[(int) Math.min(fileSize, maxSize)];
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read = 0;
                    int total = 0;
                    while (total < buffer.length
                            && (read = fis.read(buffer, total, buffer.length - total)) > 0) {
                        total += read;
                    }
                    if (total < buffer.length) {
                        byte[] smaller = new byte[total];
                        System.arraycopy(buffer, 0, smaller, 0, total);
                        buffer = smaller;
                    }
                }

                String content = new String(buffer, "UTF-8");
                if (truncated) {
                    content += "\n\n... [File truncated - showing first 1MB of "
                            + FileManagerAdapter.formatSize(fileSize) + "]";
                }

                final String finalContent = content;
                final boolean finalTruncated = truncated;

                mainHandler.post(() -> {
                    ScrollView scrollView = new ScrollView(this);
                    TextView textView = new TextView(this);
                    textView.setText(finalContent);
                    textView.setTextColor(0xFF00FF00);
                    textView.setBackgroundColor(0xFF000000);
                    textView.setPadding(32, 32, 32, 32);
                    textView.setTextSize(12);
                    textView.setTypeface(android.graphics.Typeface.MONOSPACE);
                    textView.setTextIsSelectable(true);
                    scrollView.addView(textView);

                    blackDialogBuilder()
                            .setTitle(file.getName() + (finalTruncated ? " (truncated)" : ""))
                            .setView(scrollView)
                            .setPositiveButton("Close", null)
                            .setNeutralButton("Edit", (d, w) -> openTextEditor(file))
                            .setNegativeButton("Share", (d, w) -> shareFile(file))
                            .show();
                });

            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Failed to read file: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            PackageManager pm = getPackageManager();
            List<ResolveInfo> all = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo ri : all) {
                try {
                    grantUriPermission(ri.activityInfo.packageName, uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
            }

            startActivity(Intent.createChooser(intent, "Share via"));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to share: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');

        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);

            switch (ext) {
                case "apk": return "application/vnd.android.package-archive";
                case "zip": return "application/zip";
                case "rar": return "application/x-rar-compressed";
                case "7z": return "application/x-7z-compressed";
                case "tar": return "application/x-tar";
                case "gz": return "application/gzip";
                case "pdf": return "application/pdf";
                case "txt": return "text/plain";
                case "log": return "text/plain";
                case "md": return "text/markdown";
                case "json": return "application/json";
                case "xml": return "application/xml";
                case "properties": return "text/plain";
                case "config": return "text/plain";
                case "cfg": return "text/plain";
                case "conf": return "text/plain";
                case "ini": return "text/plain";
                case "yml": return "text/yaml";
                case "yaml": return "text/yaml";
                case "sh": return "text/x-shellscript";
                case "bat": return "text/plain";
                case "gradle": return "text/plain";
                case "pro": return "text/plain";
                case "html":
                case "htm": return "text/html";
                case "css": return "text/css";
                case "js": return "application/javascript";
                case "jpg":
                case "jpeg": return "image/jpeg";
                case "png": return "image/png";
                case "gif": return "image/gif";
                case "webp": return "image/webp";
                case "bmp": return "image/bmp";
                case "svg": return "image/svg+xml";
                case "mp3": return "audio/mpeg";
                case "wav": return "audio/wav";
                case "ogg": return "audio/ogg";
                case "flac": return "audio/flac";
                case "m4a": return "audio/mp4";
                case "mp4": return "video/mp4";
                case "mkv": return "video/x-matroska";
                case "avi": return "video/x-msvideo";
                case "mov": return "video/quicktime";
                case "webm": return "video/webm";
                case "3gp": return "video/3gpp";
                case "doc": return "application/msword";
                case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "xls": return "application/vnd.ms-excel";
                case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "ppt": return "application/vnd.ms-powerpoint";
                case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "odt": return "application/vnd.oasis.opendocument.text";
                case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
                case "odp": return "application/vnd.oasis.opendocument.presentation";
                case "rtf": return "application/rtf";
                case "epub": return "application/epub+zip";
                case "mobi": return "application/x-mobipocket-ebook";
            }

            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }

        return "*/*";
    }

    private String getReadableType(String mimeType) {
        if (mimeType == null) return "Unknown";

        if (mimeType.startsWith("text/")) return "Text file";
        if (mimeType.startsWith("image/")) return "Image";
        if (mimeType.startsWith("video/")) return "Video";
        if (mimeType.startsWith("audio/")) return "Audio";
        if (mimeType.equals("application/pdf")) return "PDF Document";
        if (mimeType.equals("application/zip")) return "ZIP Archive";
        if (mimeType.contains("android.package-archive")) return "Android Package";
        if (mimeType.contains("msword") || mimeType.contains("wordprocessing")) return "Word Document";
        if (mimeType.contains("excel") || mimeType.contains("spreadsheet")) return "Excel Spreadsheet";
        if (mimeType.contains("powerpoint") || mimeType.contains("presentation")) return "PowerPoint Presentation";

        int slashIndex = mimeType.indexOf('/');
        if (slashIndex > 0 && slashIndex < mimeType.length() - 1) {
            String subtype = mimeType.substring(slashIndex + 1);
            return subtype.toUpperCase(Locale.US) + " file";
        }

        return mimeType;
    }

    private boolean isTextBasedFile(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        String[] textExtensions = {
                ".txt", ".log", ".md", ".json", ".xml", ".html", ".htm",
                ".css", ".js", ".java", ".kt", ".py", ".c", ".cpp", ".h",
                ".csv", ".tsv", ".ini", ".cfg", ".conf", ".properties",
                ".gradle", ".pro", ".sh", ".bat", ".yml", ".yaml", ".sql",
                ".config", ".env", ".gitignore", ".editorconfig", ".toml",
                ".lock", ".list", ".rc", ".text", ".me", ".readme",
                ".php", ".xhtml", ".db", ".cs", ".rb", ".go", ".rs", ".swift"
        };

        for (String ext : textExtensions) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private void openWithChooser(File file, String mimeType) {
        Uri fileUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                fileUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            fileUri = Uri.fromFile(file);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        List<ResolveInfo> all = resolveExcludingSelf(intent);
        for (ResolveInfo ri : all) {
            try {
                grantUriPermission(ri.activityInfo.packageName, fileUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }

        Intent chooser = Intent.createChooser(intent, "Open with");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No apps available", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Actions Menu ====================

    private void showActionsMenu(View anchor) {
        List<File> selected = new ArrayList<>(adapter.getSelectedFiles());
        boolean hasSelection = !selected.isEmpty();
        boolean singleFile = selected.size() == 1;
        boolean multiple = selected.size() > 1;

        boolean hasZip = false;
        for (File f : selected) {
            if (f.getName().toLowerCase().endsWith(".zip")) {
                hasZip = true;
                break;
            }
        }

        ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(wrapper, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_file_actions, popup.getMenu());

        Menu menu = popup.getMenu();
        menu.findItem(R.id.action_copy).setVisible(hasSelection);
        menu.findItem(R.id.action_cut).setVisible(hasSelection);
        menu.findItem(R.id.action_rename).setVisible(singleFile);
        menu.findItem(R.id.action_delete).setVisible(hasSelection);
        menu.findItem(R.id.action_extract).setVisible(singleFile && hasZip);
        menu.findItem(R.id.action_zip).setVisible(hasSelection);
        menu.findItem(R.id.action_share).setVisible(hasSelection && multiple);
        menu.findItem(R.id.action_info).setVisible(singleFile);
        menu.findItem(R.id.action_select_all).setVisible(true);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_copy) { doCopy(selected); return true; }
            else if (id == R.id.action_cut) { doCut(selected); return true; }
            else if (id == R.id.action_rename) { showRenameDialog(selected.get(0)); return true; }
            else if (id == R.id.action_delete) { confirmDelete(selected); return true; }
            else if (id == R.id.action_extract) { startExtract(selected.get(0)); return true; }
            else if (id == R.id.action_zip) { startZip(selected); return true; }
            else if (id == R.id.action_share) { shareFiles(selected); return true; }
            else if (id == R.id.action_info) { showFileInfo(selected.get(0)); return true; }
            else if (id == R.id.action_select_all) {
                adapter.selectAll();
                updateSelectionUI();
                return true;
            }
            return false;
        });

        forceBlackPopupBackground(popup);
        popup.show();
    }

    private void forceBlackPopupBackground(PopupMenu popup) {
        try {
            java.lang.reflect.Field[] fields = popup.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                if (!"mPopup".equals(field.getName())) continue;
                field.setAccessible(true);
                Object menuPopupHelper = field.get(popup);

                try {
                    java.lang.reflect.Method m = menuPopupHelper.getClass()
                            .getMethod("setForceShowIcon", boolean.class);
                    m.invoke(menuPopupHelper, true);
                } catch (Exception ignored) { }

                try {
                    java.lang.reflect.Field popupField = menuPopupHelper.getClass()
                            .getDeclaredField("mPopup");
                    popupField.setAccessible(true);
                    Object listPopupWindow = popupField.get(menuPopupHelper);
                    java.lang.reflect.Method setBg = listPopupWindow.getClass()
                            .getMethod("setBackgroundDrawable", android.graphics.drawable.Drawable.class);
                    setBg.invoke(listPopupWindow,
                            ContextCompat.getDrawable(this, R.drawable.popup_bg));
                } catch (Exception ignored) { }

                break;
            }
        } catch (Exception ignored) { }
    }

    // ==================== Clipboard ====================

    private void doCopy(List<File> selected) {
        clipboard.clear();
        clipboard.addAll(selected);
        isCutOperation = false;
        adapter.setSelectionMode(false);
        updateSelectionUI();
        updateFooterBar();
    }

    private void doCut(List<File> selected) {
        clipboard.clear();
        clipboard.addAll(selected);
        isCutOperation = true;
        adapter.setSelectionMode(false);
        updateSelectionUI();
        updateFooterBar();
    }

    // ==================== Paste ====================

    private void startPaste() {
        if (clipboard.isEmpty()) return;

        final List<File> sources = new ArrayList<>(clipboard);
        final boolean isCut = isCutOperation;
        final File destDir = currentDir;

        long totalBytes = 0;
        for (File src : sources) {
            Long cached = sizeCache.get(src.getAbsolutePath());
            totalBytes += cached != null ? cached : computeSizeRecursive(src);
        }
        final long finalTotalBytes = Math.max(totalBytes, 1);

        beginProgress(isCut ? "Moving…" : "Copying…");

        executor.execute(() -> {
            final long[] transferred = {0};
            final long startTime = System.currentTimeMillis();
            final int[] fileCount = {0};
            final int[] fileTotal = {0};

            for (File src : sources) fileTotal[0] += countFiles(src);

            for (File src : sources) {
                File dest = new File(destDir, src.getName());
                try {
                    transferRecursive(src, dest, isCut,
                            bytes -> {
                                transferred[0] += bytes;
                                postProgress(transferred[0], finalTotalBytes, startTime,
                                        src.getName(), src.getAbsolutePath(),
                                        destDir.getAbsolutePath(),
                                        fileCount[0], fileTotal[0],
                                        isCut ? "Moving" : "Copying");
                            },
                            () -> fileCount[0]++);
                } catch (Exception ignored) { }
            }

            mainHandler.post(() -> {
                isTransferring = false;
                clipboard.clear();
                isCutOperation = false;
                updateFooterBar();
                loadDirectory(currentDir);
            });
        });
    }

    private void transferRecursive(File src, File dest, boolean isMove,
                                   ProgressCallback onBytes,
                                   FileCompletedCallback onFileCompleted) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot create " + dest);
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    transferRecursive(child, new File(dest, child.getName()),
                            isMove, onBytes, onFileCompleted);
                }
            }
            if (isMove) src.delete();
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                    onBytes.onBytes(len);
                }
                out.flush();
            }
            if (isMove) src.delete();
            onFileCompleted.onFileCompleted();
        }
    }

    // ==================== Delete ====================

    private void confirmDelete(List<File> files) {
        blackDialogBuilder()
                .setTitle("Delete")
                .setMessage("Delete " + files.size() + " item(s)?")
                .setPositiveButton("Delete", (dialog, which) -> startDelete(files))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDelete(List<File> files) {
        final List<File> targets = new ArrayList<>(files);

        long totalBytes = 0;
        int totalFiles = 0;
        for (File f : targets) {
            Long cached = sizeCache.get(f.getAbsolutePath());
            totalBytes += cached != null ? cached : computeSizeRecursive(f);
            totalFiles += countFiles(f);
        }
        final long finalTotal = Math.max(totalBytes, 1);
        final int finalFileTotal = Math.max(totalFiles, 1);

        beginProgress("Deleting…");

        executor.execute(() -> {
            final long[] deleted = {0};
            final int[] fileCount = {0};
            final long startTime = System.currentTimeMillis();

            for (File f : targets) {
                deleteRecursiveWithProgress(f, deleted,
                        bytes -> postProgress(deleted[0], finalTotal, startTime,
                                f.getName(), f.getAbsolutePath(), "(deleted)",
                                fileCount[0], finalFileTotal, "Deleting"),
                        () -> fileCount[0]++);
            }

            mainHandler.post(() -> {
                isTransferring = false;
                updateFooterBar();
                loadDirectory(currentDir);
            });
        });
    }

    private void deleteRecursiveWithProgress(File file,
                                             long[] deletedBytes,
                                             ProgressCallback onBytes,
                                             FileCompletedCallback onFileCompleted) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursiveWithProgress(child, deletedBytes, onBytes, onFileCompleted);
                }
            }
            file.delete();
        } else {
            long size = file.length();
            if (file.delete()) {
                deletedBytes[0] += size;
                onBytes.onBytes(size);
                onFileCompleted.onFileCompleted();
            }
        }
    }

    // ==================== Extract ====================

    private void startExtract(File zipFile) {
        long totalBytes = Math.max(zipFile.length(), 1);
        final long finalTotal = totalBytes;

        beginProgress("Extracting…");
        adapter.setSelectionMode(false);
        updateSelectionUI();

        executor.execute(() -> {
            String destDir = zipFile.getParent() + "/" + zipFile.getName().replace(".zip", "");
            new File(destDir).mkdirs();

            final long startTime = System.currentTimeMillis();
            final long[] written = {0};
            final int[] fileCount = {0};

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(destDir, entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[64 * 1024];
                            int len;
                            while ((len = zis.read(buf)) > 0) {
                                fos.write(buf, 0, len);
                                written[0] += len;
                                long w = written[0];
                                int fc = fileCount[0];
                                postProgress(w, finalTotal, startTime,
                                        entry.getName(), zipFile.getAbsolutePath(),
                                        destDir, fc, 0, "Extracting");
                            }
                        }
                        fileCount[0]++;
                    }
                    zis.closeEntry();
                }
            } catch (Exception ignored) { }

            mainHandler.post(() -> {
                isTransferring = false;
                updateFooterBar();
                loadDirectory(currentDir);
            });
        });
    }

    // ==================== Zip ====================

    private void startZip(List<File> files) {
        final List<File> targets = new ArrayList<>(files);

        long totalBytes = 0;
        int totalFiles = 0;
        for (File f : targets) {
            totalBytes += sizeOf(f);
            totalFiles += countFiles(f);
        }
        final long finalTotal = Math.max(totalBytes, 1);
        final int finalFileTotal = Math.max(totalFiles, 1);

        String zipName = "archive_" + System.currentTimeMillis() + ".zip";
        final File zipFile = new File(currentDir, zipName);

        beginProgress("Compressing…");
        adapter.setSelectionMode(false);
        updateSelectionUI();

        executor.execute(() -> {
            final long startTime = System.currentTimeMillis();
            final long[] processed = {0};
            final int[] fileCount = {0};

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                for (File f : targets) {
                    zipRecursiveWithProgress(f, f.getName(), zos, processed, fileCount,
                            bytes -> postProgress(processed[0], finalTotal, startTime,
                                    f.getName(), f.getAbsolutePath(), zipFile.getAbsolutePath(),
                                    fileCount[0], finalFileTotal, "Compressing"));
                }
            } catch (Exception ignored) { }

            mainHandler.post(() -> {
                isTransferring = false;
                updateFooterBar();
                loadDirectory(currentDir);
            });
        });
    }

    private void zipRecursiveWithProgress(File file, String entryName, ZipOutputStream zos,
                                          long[] processed, int[] fileCount,
                                          ProgressCallback onBytes) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipRecursiveWithProgress(child, entryName + "/" + child.getName(),
                            zos, processed, fileCount, onBytes);
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(file)) {
                zos.putNextEntry(new ZipEntry(entryName));
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                    processed[0] += len;
                    onBytes.onBytes(len);
                }
                zos.closeEntry();
                fileCount[0]++;
            }
        }
    }

    // ==================== Progress ====================

    private void beginProgress(String title) {
        isTransferring = true;
        updateFooterBar();
        progressBar.setProgress(0);
        tvProgressTitle.setText(title);
        tvProgressStats.setText("Preparing…");
        tvProgressPath.setText("");
    }

    private void postProgress(long transferred, long total, long startTime,
                              String currentFile, String srcPath, String destPath,
                              int completedFiles, int totalFiles, String verb) {
        mainHandler.post(() -> {
            int percent = (int) Math.min(100, (transferred * 100) / total);
            progressBar.setProgress(percent);

            long elapsed = System.currentTimeMillis() - startTime;
            double speed = elapsed > 0 ? (transferred * 1000.0 / elapsed) : 0;

            String fileCounter = totalFiles > 0
                    ? "[" + completedFiles + "/" + totalFiles + "]  "
                    : "";

            String stats = fileCounter
                    + percent + "%  "
                    + formatSize(transferred) + " / " + formatSize(total)
                    + "  •  " + formatSpeed(speed);

            tvProgressStats.setText(stats);
            tvProgressTitle.setText(verb + ": " + currentFile);
            tvProgressPath.setText(srcPath + "  →  " + destPath);
        });
    }

    private long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) total += sizeOf(c);
        return total;
    }

    private int countFiles(File f) {
        if (f.isFile()) return 1;
        int n = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) n += countFiles(c);
        return n;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format(Locale.US, "%.0f B/s", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024));
    }

    private interface ProgressCallback { void onBytes(long bytes); }
    private interface FileCompletedCallback { void onFileCompleted(); }

    // ==================== Rename ====================

    private void showRenameDialog(File file) {
        EditText input = new EditText(this);
        input.setText(file.getName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFF00FF00);
        input.setHintTextColor(0xFF00AA00);
        input.setBackgroundColor(0xFF1A1A1A);

        blackDialogBuilder()
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) return;

                    File newFile = new File(file.getParent(), newName);
                    if (file.renameTo(newFile)) {
                        adapter.setSelectionMode(false);
                        loadDirectory(currentDir);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ==================== Share Multiple ====================

    private void shareFiles(List<File> selected) {
        if (selected.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        String mimeType = "*/*";

        for (File f : selected) {
            if (!f.isFile()) continue;
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", f);
                uris.add(uri);
                mimeType = getMimeType(f);
            } catch (Exception ignored) { }
        }

        if (uris.isEmpty()) return;

        Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.putExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.setType(mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo ri : all) {
            for (Uri u : uris) {
                try {
                    grantUriPermission(ri.activityInfo.packageName, u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
            }
        }

        try {
            startActivity(Intent.createChooser(intent, "Share via"));
        } catch (Exception ignored) { }

        adapter.setSelectionMode(false);
        updateSelectionUI();
    }

    // ==================== File Info ====================

    private void showFileInfo(File file) {
        String info = "Name: " + file.getName() + "\n"
                + "Path: " + file.getAbsolutePath() + "\n"
                + "Type: " + (file.isDirectory() ? "Folder" : getReadableType(getMimeType(file))) + "\n"
                + "Size: " + (file.isDirectory()
                ? getFolderSize(file) + " bytes"
                : FileManagerAdapter.formatSize(file.length())) + "\n"
                + "Modified: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                Locale.getDefault()).format(file.lastModified()) + "\n"
                + "Readable: " + file.canRead() + "\n"
                + "Writable: " + file.canWrite();

        blackDialogBuilder()
                .setTitle("Properties")
                .setMessage(info)
                .setPositiveButton("OK", null)
                .show();
    }

    static class SearchResult {
        final File file;
        final String relativePath;   // e.g. "subdir/nested/deep.txt" or "" for top-level

        SearchResult(File file, String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
        }
    }

    // ── Thumbnail infra ─────────────────────────────────────────────────
    private static final int THUMB_SIZE_DP = 80;

    /** Cache key is the file's absolute path ONLY. mtime is not part of the key
     *  so scrolling doesn't cause a cache miss every frame. */
    private final android.util.LruCache<String, Bitmap> thumbCache =
            new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) { // 8 MB
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Paths currently being decoded. Prevents duplicate work. */
    private final java.util.Set<String> thumbInFlight =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** Two decode threads — enough to hide latency, not enough to thrash I/O. */
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);

    /** Notified once when the whole batch is ready, so we call notifyDataSetChanged once. */
    private final android.os.Handler thumbBatchHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicInteger pendingThumbs =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private boolean thumbBatchScheduled = false;
    private long getFolderSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? getFolderSize(f) : f.length();
            }
        }
        return size;
    }
}