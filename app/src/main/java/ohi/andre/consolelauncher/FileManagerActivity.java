package ohi.andre.consolelauncher;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
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

public class FileManagerActivity extends AppCompatActivity implements FileManagerAdapter.OnFileClickListener {

    private static final String HOME_DIR = Environment.getExternalStorageDirectory().getAbsolutePath();

    // Sort modes
    private static final int SORT_NAME_ASC   = 0;
    private static final int SORT_NAME_DESC  = 1;
    private static final int SORT_DATE_NEW   = 2;

    private File pendingFileOpenAfterPermission = null;
    private static final int SORT_DATE_OLD   = 3;
    private static final int SORT_SIZE_BIG   = 4;
    private static final int SORT_SIZE_SMALL = 5;

    private boolean launchedForIncomingFile = false;

    private static final int REQUEST_INSTALL_PACKAGES = 12345;

    private int currentSortMode = SORT_NAME_ASC;
    private List<File> currentFileList = new ArrayList<>();
    private File pendingApkInstall = null;

    // Views
    private DrawerLayout drawerLayout;
    private RecyclerView recyclerFiles;
    private RecyclerView recyclerStorage;
    private View drawerPanel;
    private TextView tvPath;
    private TextView tvEmpty;
    private TextView tvSelectionInfo;
    private ImageView btnMenu;
    private ImageView btnSort;
    private ImageView btnCopy;
    private ImageView btnMove;
    private ImageView btnDelete;
    private ImageView btnMore;
    private ImageView btnCloseSelection;

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
        FileLog.i("FileManagerActivity.onCreate: intent action="
                + (getIntent() != null ? getIntent().getAction() : "null")
                + " data=" + (getIntent() != null ? getIntent().getData() : "null"));


        // -------- STORAGE PERMISSION CHECK --------
        boolean hasAccess = StoragePermissionHelper.hasFullStorageAccess(this);
        FileLog.i("Storage permission granted? " + hasAccess);
        if (!hasAccess) {
            StoragePermissionHelper.requestStorageAccess(this);
        }

        setContentView(R.layout.activity_file_manager);
        launchedForIncomingFile = isIncomingFileIntent(getIntent());
        FileLog.i("launchedForIncomingFile=" + launchedForIncomingFile);

        initViews();
        setupRecyclerViews();
        setupListeners();
        setupStorageDrawer();

        currentDir = new File(HOME_DIR);

        if (!handleIncomingIntent(getIntent())) {
            FileLog.i("No incoming intent — loading HOME_DIR=" + HOME_DIR);
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
        if (!handleIncomingIntent(intent)) {
            // normal reopen
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * Filters out our own package from the resolve list to prevent an infinite
     * loop where our FileManagerActivity handles its own ACTION_VIEW intents.
     */
    private List<ResolveInfo> resolveExcludingSelf(Intent intent) {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = pm.queryIntentActivities(intent, 0);
        List<ResolveInfo> filtered = new ArrayList<>();
        String self = getPackageName();
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (!self.equals(pkg)) {
                filtered.add(ri);
            } else {
                FileLog.d("Skipping self-handler: " + pkg + "/" + ri.activityInfo.name);
            }
        }
        return filtered;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle storage permission result
        if (requestCode == StoragePermissionHelper.REQUEST_CODE_MANAGE_STORAGE
                || requestCode == StoragePermissionHelper.REQUEST_CODE_LEGACY_STORAGE) {
            if (StoragePermissionHelper.handleActivityResult(this, requestCode)) {
                Toast.makeText(this, "Storage access granted", Toast.LENGTH_SHORT).show();
                // Reload current directory now that we can read everything
                loadDirectory(currentDir != null ? currentDir : new File(HOME_DIR));
                // Also retry the pending file open if there was one
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

        // Existing APK install handling
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
        FileLog.i("handleIncomingUri: uri=" + uri + " mime=" + mimeType);

        // Copy SYNCHRONOUSLY on the caller thread (which is the main thread
        // here, invoked from onCreate/onNewIntent). This ensures the URI grant
        // from the caller app (WhatsApp/Gmail) is still valid — it expires
        // as soon as we return from onCreate.

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
                FileLog.e("handleIncomingUri: openInputStream returned null");
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
            FileLog.e("handleIncomingUri: copy failed", e);
            Toast.makeText(this, "Cannot copy file: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        FileLog.i("handleIncomingUri: copied " + copied + " bytes to " + localFile);

        if (copied == 0) {
            FileLog.e("handleIncomingUri: 0 bytes copied — source URI was empty");
            Toast.makeText(this,
                    "Received an empty file from the source app.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final File fileToOpen = localFile;
        final String mime = mimeType != null ? mimeType : getMimeType(fileToOpen);

        // Small delay so the activity is fully resumed before we launch an
        // external intent from within onNewIntent/onCreate
        mainHandler.post(() -> openFileWithMime(fileToOpen, mime));
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return null;

        // For file:// URIs
        if ("file".equals(uri.getScheme())) {
            return new File(uri.getPath()).getName();
        }

        // For content:// URIs, query the display name
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
            // Fallback: use last path segment
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                result = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }

        return result;
    }


    /**
     * Opens a .docx or .doc file using our built-in native readers.
     * Falls back to external app only if native parsing fails.
     */
    private void openOfficeNative(File file) {
        FileLog.i("openOfficeNative: " + file.getAbsolutePath());

        final String fileName = file.getName().toLowerCase(Locale.US);
        final boolean isDocx = fileName.endsWith(".docx") || fileName.endsWith(".docm");
        final boolean isDoc  = fileName.endsWith(".doc");

        if (!isDocx && !isDoc) {
            // Not a Word document — use the external intent path for xlsx/pptx
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
                FileLog.e("openOfficeNative: native parse failed", e);
                mainHandler.post(() -> {
                    // Fallback: try external app
                    Toast.makeText(this,
                            "Native reader failed, trying external app…",
                            Toast.LENGTH_SHORT).show();
                    openOfficeDocument(file, getMimeType(file));
                });
            }
        });
    }

/**
 * Shows a scrollable, searchable, selectable TextView dialog
 * containing the extracted document text.
 */
    /**
     * Shows a scrollable, searchable, selectable TextView dialog
     * containing the extracted document text.
     *
     * - Searches automatically as you type
     * - Centers the highlighted match vertically in the scroll view
     */
    private void showDocumentViewer(File file, String content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        // ---- Info bar ----
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

        // ---- Content view ----
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
        // CRITICAL: without fillViewport, HorizontalScrollView measures its
        // child with UNSPECIFIED width, so MATCH_PARENT on the TextView is
        // ignored and text never wraps. With it, HSV measures the child with
        // AT_MOST(viewportWidth), enabling true wrapping.
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

        // ---- Bottom bar ----
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

        // ---- Dialog ----
        AlertDialog dialog = blackDialogBuilder().setView(root).create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
        }

        // ---- WRAP toggle ----
        // Semantics:
        //   WRAP ON  -> text wraps at screen width, no horizontal scrolling at all.
        //   WRAP OFF -> text is shown exactly as-is (original line breaks only),
        //               long lines scroll horizontally, no auto-wrapping.
        final boolean[] wrapOn = { true };

        // Capture & restore the vertical reading position across toggles so the
        // user doesn't lose their place when the layout reflows.
        final Runnable applyWrapMode = () -> {
            if (wrapOn[0]) {
                // --- WRAP ON ---
                viewer.setHorizontallyScrolling(false);
                viewer.setSingleLine(false);
                viewer.setMaxLines(Integer.MAX_VALUE);
                viewer.setIncludeFontPadding(true);

                // HSV must measure child with AT_MOST(viewportW) so MATCH_PARENT
                // actually constrains the TextView width -> text wraps.
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
                // --- WRAP OFF (show original formatting, horizontal scroll) ---
                viewer.setHorizontallyScrolling(true);
                viewer.setSingleLine(false);
                viewer.setMaxLines(Integer.MAX_VALUE);

                // HSV must measure child with UNSPECIFIED width so the TextView
                // can grow to its natural (unwrapped) width and scroll freely.
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

            // Re-apply text + force full re-measure/re-layout down the chain.
            viewer.setText(viewer.getText());
            viewer.requestLayout();
            hScroll.requestLayout();
            scroll.requestLayout();
        };

        btnWrap.setOnClickListener(v -> {
            // Remember reading position (line index) before reflow.
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

            // Restore reading position after layout settles.
            scroll.post(() -> {
                android.text.Layout newLayout = viewer.getLayout();
                if (newLayout == null) return;
                int line = Math.min(anchorLine, newLayout.getLineCount() - 1);
                if (line < 0) line = 0;
                int targetY = newLayout.getLineTop(line);
                scroll.scrollTo(0, targetY);
            });
        });
        // ---- Search logic ----
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

                // Also scroll horizontally to the match when wrap is OFF
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
        btnSort = findViewById(R.id.btn_sort);
        btnCopy = findViewById(R.id.btn_copy);
        btnMove = findViewById(R.id.btn_move);
        btnDelete = findViewById(R.id.btn_delete);
        btnMore = findViewById(R.id.btn_more);
        btnCloseSelection = findViewById(R.id.btn_close_selection);
        drawerPanel = findViewById(R.id.drawer_panel);

        footerBar = findViewById(R.id.footer_bar);
        footerActions = findViewById(R.id.footer_actions);
        footerProgress = findViewById(R.id.footer_progress);
        btnFooterCancel = findViewById(R.id.btn_footer_cancel);
        btnFooterPaste = findViewById(R.id.btn_footer_paste);
        tvProgressTitle = findViewById(R.id.tv_progress_title);
        tvProgressStats = findViewById(R.id.tv_progress_stats);
        tvProgressPath = findViewById(R.id.tv_progress_path);
        progressBar = findViewById(R.id.progress_bar);
        // Drawer config — must be set AFTER findViewById
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        drawerLayout.setScrimColor(0x99000000);
    }

    private void setupRecyclerViews() {
        GridLayoutManager glm = new GridLayoutManager(this, 2);
        recyclerFiles.setLayoutManager(glm);
        recyclerFiles.setNestedScrollingEnabled(false);   // don't eat horizontal drawer gestures
        recyclerFiles.setHasFixedSize(true);

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

                // If we were launched for an incoming file (WhatsApp/Gmail), pressing
                // back should EXIT the whole file manager immediately. Otherwise the
                // user is stuck because their previous task (WhatsApp) is the caller
                // and Android keeps re-entering us.
                if (launchedForIncomingFile) {
                    setEnabled(false);
                    finishAndRemoveTask();   // <-- important: removes the task entirely
                    return;
                }

                // Normal in-app navigation: go up one directory
                if (currentDir != null && !currentDir.getAbsolutePath().equals(HOME_DIR)) {
                    File parent = currentDir.getParentFile();
                    if (parent != null && parent.canRead()) {
                        currentDir = parent;
                        loadDirectory(currentDir);
                        return;
                    }
                }

                // At home dir, normal launch
                setEnabled(false);
                finish();
            }
        });
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

// ==================== Dialogs ====================

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
                adapter.setFiles(snapshot);
                recyclerFiles.setEnabled(true);
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
        FileLog.i("loadDirectory: " + (dir == null ? "null" : dir.getAbsolutePath()));
        if (dir == null || !dir.exists() || !dir.canRead()) {
            FileLog.w("loadDirectory: cannot read " + dir);
            return;
        }

        currentDir = dir;
        tvPath.setText(dir.getAbsolutePath());
        final int sortModeSnapshot = currentSortMode;

        executor.execute(() -> {
            File[] files = dir.listFiles();
            FileLog.d("loadDirectory: listed " + (files == null ? 0 : files.length) + " entries");
            List<File> fileList = new ArrayList<>();
            if (files != null) fileList.addAll(Arrays.asList(files));

            if (sortModeSnapshot == SORT_SIZE_BIG || sortModeSnapshot == SORT_SIZE_SMALL) {
                precomputeSizes(fileList);
            }
            Collections.sort(fileList, buildComparator());

            mainHandler.post(() -> {
                currentFileList = fileList;
                adapter.setFiles(fileList);
                adapter.setSelectionMode(false);
                updateSelectionUI();
                updateFooterBar();
                tvEmpty.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
                recyclerFiles.setVisibility(fileList.isEmpty() ? View.GONE : View.VISIBLE);
                FileLog.d("loadDirectory: displayed " + fileList.size() + " entries");
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

    // ==================== Click Handling ====================
    @Override
    public void onFileClick(File file, int position) {
        FileLog.i("onFileClick: " + file.getAbsolutePath()
                + " dir=" + file.isDirectory()
                + " exists=" + file.exists()
                + " readable=" + file.canRead());
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(file);
            updateSelectionUI();
        } else {
            if (file.isDirectory()) {
                loadDirectory(file);
            } else {
                openFileWithMime(file, getMimeType(file));
            }
        }
    }

    @Override
    public void onFileLongClick(File file, int position) {
        adapter.setSelectionMode(true);
        adapter.toggleSelection(file);
        updateSelectionUI();
    }

// ==================== UNIVERSAL FILE OPENING ====================

    /**
     * MASTER ROUTER. Given a File + optional MIME type, decides how to open it.
     */
    private void openFileWithMime(File file, String mimeType) {
        FileLog.i("openFileWithMime: file=" + file.getAbsolutePath()
                + " mime=" + mimeType
                + " exists=" + file.exists()
                + " readable=" + file.canRead()
                + " size=" + file.length());

        if (!file.exists()) {
            FileLog.e("openFileWithMime: file doesn't exist");
            Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!StoragePermissionHelper.hasFullStorageAccess(this)
                && file.getAbsolutePath().startsWith(
                Environment.getExternalStorageDirectory().getAbsolutePath())) {
            FileLog.w("openFileWithMime: no full storage access, requesting");
            pendingFileOpenAfterPermission = file;
            StoragePermissionHelper.requestStorageAccess(this);
            return;
        }

        String fileName = file.getName().toLowerCase(Locale.US);
        if (mimeType == null) mimeType = getMimeType(file);

        if (fileName.endsWith(".apk")) {
            FileLog.d("Router: APK");
            requestInstallApk(file);
            return;
        }
        if (fileName.endsWith(".zip")) {
            FileLog.d("Router: ZIP");
            showZipFileOptions(file);
            return;
        }
        if (mimeType.equals("application/pdf") || fileName.endsWith(".pdf")) {
            FileLog.d("Router: PDF -> PdfViewerActivity");
            openPdfNative(file);
            return;
        }
        if (isOfficeDocument(mimeType, fileName)) {
            FileLog.d("Router: Office");
            // DOCX / DOC → use native reader
            if (fileName.endsWith(".docx") || fileName.endsWith(".docm") || fileName.endsWith(".doc")) {
                openOfficeNative(file);
            } else {
                // xlsx / pptx / odt / rtf etc → external app
                openOfficeDocument(file, mimeType);
            }
            return;
        }
        if (isTextBasedFile(file) || mimeType.startsWith("text/")) {
            FileLog.d("Router: Text -> built-in editor");
            openTextEditor(file);
            return;
        }
        if (mimeType.startsWith("image/")) {
            FileLog.d("Router: Image");
            tryOpenWithDefaultApp(file, mimeType);
            return;
        }
        if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            FileLog.d("Router: Media");
            tryOpenWithDefaultApp(file, mimeType);
            return;
        }
        FileLog.d("Router: default -> tryOpenWithDefaultApp");
        tryOpenWithDefaultApp(file, mimeType);
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

    /**
     * Opens PDF using our native PdfViewerActivity.
     */
    private void openPdfNative(File pdfFile) {
        FileLog.i("openPdfNative: " + pdfFile.getAbsolutePath());

        // Strategy 1: pass the absolute path as a string extra. PdfViewerActivity
        // reads it directly as a File — no FileProvider needed at all.
        // This is the most robust approach and works for any file location.
        try {
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, pdfFile.getAbsolutePath());
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_NAME, pdfFile.getName());
            startActivity(intent);
            FileLog.i("openPdfNative: launched PdfViewerActivity with path");
            return;
        } catch (Exception e) {
            FileLog.e("openPdfNative: path-based launch failed", e);
        }

        // Strategy 2: fallback to FileProvider URI
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pdfFile);
            FileLog.i("openPdfNative: FileProvider URI=" + uri);
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.setData(uri);
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_NAME, pdfFile.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return;
        } catch (Exception e) {
            FileLog.e("openPdfNative: FileProvider URI failed", e);
        }

        // Strategy 3: delegate to system PDF viewer
        FileLog.w("openPdfNative: falling back to system viewer");
        tryOpenWithDefaultApp(pdfFile, "application/pdf");
    }

    private void openOfficeDocument(File file, String mimeType) {
        FileLog.i("openOfficeDocument: " + file.getAbsolutePath() + " mime=" + mimeType);

        if (file.length() == 0) {
            FileLog.e("openOfficeDocument: file is 0 bytes — refusing to open");
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
                FileLog.d("openOfficeDocument: URI=" + fileUri);
            } catch (Exception e) {
                FileLog.e("openOfficeDocument: FileProvider failed", e);
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

        // CRITICAL: exclude ourselves from the resolve list, otherwise Android
        // picks our own FileManagerActivity to handle the intent -> infinite loop
        List<ResolveInfo> activities = resolveExcludingSelf(intent);
        FileLog.d("openOfficeDocument: " + activities.size() + " external handlers");

        if (activities.isEmpty()) {
            FileLog.w("openOfficeDocument: no external office app for " + mimeType);
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
                // Set the component explicitly so we don't accidentally match ourselves
                intent.setComponent(new android.content.ComponentName(
                        activities.get(0).activityInfo.packageName,
                        activities.get(0).activityInfo.name));
                FileLog.i("openOfficeDocument: launching "
                        + activities.get(0).activityInfo.packageName);
                startActivity(intent);
            } else {
                // Build a chooser but exclude our own package from it
                Intent chooser = Intent.createChooser(intent,
                        "Open " + file.getName() + " with");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chooser);
            }
        } catch (Exception e) {
            FileLog.e("openOfficeDocument: failed to launch", e);
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
        FileLog.i("launchApkInstaller: " + apkFile.getAbsolutePath());
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Uri apkUri = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", apkFile);
                FileLog.d("launchApkInstaller: URI=" + apkUri);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

            List<ResolveInfo> resInfoList = getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            FileLog.d("launchApkInstaller: " + resInfoList.size() + " installers");

            for (ResolveInfo ri : resInfoList) {
                String pkg = ri.activityInfo.packageName;
                grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                FileLog.d("launchApkInstaller: granted to " + pkg);
            }

            if (resInfoList.isEmpty()) {
                FileLog.w("launchApkInstaller: no package installer");
                showNoInstallerDialog(apkFile);
                return;
            }

            startActivity(intent);
            FileLog.i("launchApkInstaller: launched");

        } catch (ActivityNotFoundException e) {
            FileLog.e("launchApkInstaller: ActivityNotFound", e);
            showNoInstallerDialog(apkFile);
        } catch (Exception e) {
            FileLog.e("launchApkInstaller: failed", e);
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
        FileLog.i("tryOpenWithDefaultApp: file=" + file.getAbsolutePath()
                + " mime=" + mimeType);

        if (!file.exists()) {
            FileLog.e("tryOpenWithDefaultApp: file does not exist");
            Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!file.canRead()) {
            FileLog.e("tryOpenWithDefaultApp: file not readable");
            Toast.makeText(this, "File is not readable", Toast.LENGTH_SHORT).show();
            return;
        }
        if (file.length() == 0) {
            FileLog.e("tryOpenWithDefaultApp: file is 0 bytes");
            Toast.makeText(this, "File is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri fileUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                fileUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                FileLog.e("tryOpenWithDefaultApp: FileProvider failed", e);
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
        FileLog.d("tryOpenWithDefaultApp: " + activities.size() + " external handlers");

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
                // Explicitly set component so Android can't pick us
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
            FileLog.e("tryOpenWithDefaultApp: failed", e);
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

        EditText editor = new EditText(this);
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
        // Start in no-wrap mode (original formatting preserved, horizontal scroll).
        editor.setHorizontallyScrolling(true);
        editor.setSingleLine(false);
        editor.setMaxLines(Integer.MAX_VALUE);
        editor.setVerticalScrollBarEnabled(true);
        editor.setHorizontalScrollBarEnabled(false);

        // HSV wrapper enables horizontal scroll when wrap is OFF, and (with
        // fillViewport=true) constrains editor width to viewport when wrap is ON
        // so text actually wraps.
        final android.widget.HorizontalScrollView editorHScroll =
                new android.widget.HorizontalScrollView(this);
        editorHScroll.setBackgroundColor(0xFF000000);
        editorHScroll.setFillViewport(false);   // start in no-wrap mode
        editorHScroll.setHorizontalScrollBarEnabled(true);
        editorHScroll.addView(editor, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF000000);
        scrollView.setFillViewport(true);
        scrollView.addView(editorHScroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setBackgroundColor(0xFF001100);
        buttonBar.setPadding(8, 8, 8, 8);

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

        btnWrap.setOnClickListener(v -> {
            wrapEnabled[0] = !wrapEnabled[0];
            if (wrapEnabled[0]) {
                // --- WRAP ON: text wraps at screen width, no horizontal scroll ---
                editor.setHorizontallyScrolling(false);
                editor.setSingleLine(false);
                editor.setMaxLines(Integer.MAX_VALUE);

                editorHScroll.setFillViewport(true);
                editorHScroll.setHorizontalScrollBarEnabled(false);
                editorHScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                editorHScroll.scrollTo(0, 0);

                ViewGroup.LayoutParams lp = editor.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                editor.setLayoutParams(lp);

                btnWrap.setTextColor(0xFFFFFF00);
                btnWrap.setText("WRAP ON");
            } else {
                // --- WRAP OFF: original formatting, horizontal scroll ---
                editor.setHorizontallyScrolling(true);
                editor.setSingleLine(false);
                editor.setMaxLines(Integer.MAX_VALUE);

                editorHScroll.setFillViewport(false);
                editorHScroll.setHorizontalScrollBarEnabled(true);
                editorHScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

                ViewGroup.LayoutParams lp = editor.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                editor.setLayoutParams(lp);

                btnWrap.setTextColor(0xFF00FF00);
                btnWrap.setText("WRAP");
            }
            editor.requestLayout();
            editorHScroll.requestLayout();
            scrollView.requestLayout();
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

// ==================== Read-only text viewer ====================

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

// ==================== Share ====================

    private void shareFile(File file) {
        FileLog.i("shareFile: " + file.getAbsolutePath());
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            FileLog.d("shareFile: URI=" + uri);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Pre-grant to every app that might receive the share
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
            FileLog.e("shareFile: failed", e);
            Toast.makeText(this, "Failed to share: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

// ==================== MIME HELPERS ====================

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
        FileLog.i("openWithChooser: " + file.getAbsolutePath() + " mime=" + mimeType);

        Uri fileUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                fileUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                FileLog.e("openWithChooser: FileProvider failed", e);
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
            FileLog.i("openWithChooser: chooser launched, " + all.size() + " apps");
        } catch (ActivityNotFoundException e) {
            FileLog.e("openWithChooser: no apps", e);
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
        FileLog.i("shareFiles: " + selected.size() + " files");
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
                FileLog.d("shareFiles: added " + uri);
            } catch (Exception e) {
                FileLog.e("shareFiles: skipped " + f.getAbsolutePath(), e);
            }
        }

        if (uris.isEmpty()) {
            FileLog.w("shareFiles: no shareable files");
            return;
        }

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

        // Pre-grant to all receivers
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
        } catch (Exception e) {
            FileLog.e("shareFiles: failed", e);
        }

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