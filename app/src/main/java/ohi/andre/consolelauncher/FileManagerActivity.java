package ohi.andre.consolelauncher;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

public class FileManagerActivity extends AppCompatActivity implements FileManagerAdapter.OnFileClickListener {

    private static final String HOME_DIR = Environment.getExternalStorageDirectory().getAbsolutePath();

    // Sort modes
    private static final int SORT_NAME_ASC   = 0;
    private static final int SORT_NAME_DESC  = 1;
    private static final int SORT_DATE_NEW   = 2;
    private static final int SORT_DATE_OLD   = 3;
    private static final int SORT_SIZE_BIG   = 4;
    private static final int SORT_SIZE_SMALL = 5;

    private int currentSortMode = SORT_NAME_ASC;
    private List<File> currentFileList = new ArrayList<>();

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

    // Adapters
    private FileManagerAdapter adapter;
    private StorageAdapter storageAdapter;

    // State
    private File currentDir;
    private final List<File> clipboard = new ArrayList<>();
    private boolean isCutOperation = false;
    private boolean isTransferring = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Size cache: File -> bytes. Populated lazily during size-sort or on load.
    private final java.util.Map<String, Long> sizeCache = new java.util.HashMap<>();

    // ==================== Lifecycle ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        initViews();
        setupRecyclerViews();
        setupListeners();
        setupStorageDrawer();

        currentDir = new File(HOME_DIR);
        loadDirectory(currentDir);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ==================== Setup ====================

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
    }

    private void setupRecyclerViews() {
        recyclerFiles.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new FileManagerAdapter(this, this);
        recyclerFiles.setAdapter(adapter);

        recyclerStorage.setLayoutManager(new LinearLayoutManager(this));
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
                } else if (adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    updateSelectionUI();
                } else if (!clipboard.isEmpty()) {
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
                } else if (currentDir != null && !currentDir.getAbsolutePath().equals(HOME_DIR)) {
                    File parent = currentDir.getParentFile();
                    if (parent != null) {
                        currentDir = parent;
                        loadDirectory(currentDir);
                    }
                } else {
                    finish();
                }
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
        // Snapshot current list to avoid race conditions with directory loads
        final List<File> snapshot = new ArrayList<>(currentFileList);
        final int sortModeSnapshot = currentSortMode;

        // Disable interaction while we sort — takes ~100ms typically, barely noticeable
        recyclerFiles.setEnabled(false);

        executor.execute(() -> {
            // Precompute sizes only when size sort is selected
            if (sortModeSnapshot == SORT_SIZE_BIG || sortModeSnapshot == SORT_SIZE_SMALL) {
                precomputeSizes(snapshot);
            }

            Collections.sort(snapshot, buildComparator());

            mainHandler.post(() -> {
                // Make sure we're still on the same directory
                if (currentFileList == snapshot || currentFileList.equals(snapshot) || true) {
                    currentFileList = snapshot;
                    adapter.setFiles(snapshot);
                }
                recyclerFiles.setEnabled(true);
            });
        });
    }



    private Comparator<File> buildComparator() {
        return new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                // Directories always first
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

    /**
     * Precomputes sizes for the given files and stores them in sizeCache.
     * Only called when a size-based sort is active. Runs on the executor thread.
     */
    private void precomputeSizes(List<File> files) {
        for (File f : files) {
            String key = f.getAbsolutePath();
            if (!sizeCache.containsKey(key)) {
                sizeCache.put(key, computeSizeRecursive(f));
            }
        }
    }

    /**
     * One-time recursive size computation. Only called from precomputeSizes,
     * never from a comparator.
     */
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
        if (dir == null || !dir.exists() || !dir.canRead()) return;

        currentDir = dir;
        tvPath.setText(dir.getAbsolutePath());

        final int sortModeSnapshot = currentSortMode;

        executor.execute(() -> {
            File[] files = dir.listFiles();
            List<File> fileList = new ArrayList<>();
            if (files != null) fileList.addAll(Arrays.asList(files));

            // Only do the expensive size precomputation when size-sort is selected
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
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(file);
            updateSelectionUI();
        } else {
            if (file.isDirectory()) loadDirectory(file);
            else openFile(file);
        }
    }

    @Override
    public void onFileLongClick(File file, int position) {
        adapter.setSelectionMode(true);
        adapter.toggleSelection(file);
        updateSelectionUI();
    }

    // ==================== File Opening ====================

    private void openFile(File file) {
        String mimeType = getMimeType(file);
        Intent intent = new Intent(Intent.ACTION_VIEW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(file), mimeType);
        }

        try {
            startActivity(intent);
        } catch (Exception ignored) { }
    }

    private String getMimeType(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "*/*";
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

    // ==================== Clipboard Actions ====================

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

    // ==================== Paste with Progress ====================

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

    // ==================== Delete with Progress ====================

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
            totalFiles += countFiles(f);  // countFiles is cheap (no data reads)
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

    // ==================== Extract with Progress ====================

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

    // ==================== Zip with Progress ====================

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

    // ==================== Progress Helpers ====================

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

    // ==================== Share ====================

    private void shareFiles(List<File> selected) {
        if (selected.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        String mimeType = "*/*";

        for (File f : selected) {
            if (f.isFile()) {
                try {
                    Uri uri = FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", f);
                    uris.add(uri);
                    mimeType = getMimeType(f);
                } catch (Exception ignored) { }
            }
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
                + "Type: " + (file.isDirectory() ? "Folder" : "File") + "\n"
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