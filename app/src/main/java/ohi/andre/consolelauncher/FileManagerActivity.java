package ohi.andre.consolelauncher;

import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.widget.Toast;

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

    // Header views
    private DrawerLayout drawerLayout;
    private RecyclerView recyclerFiles;
    private RecyclerView recyclerStorage;
    private View drawerPanel;
    private TextView tvPath;
    private TextView tvEmpty;
    private TextView tvSelectionInfo;
    private ImageView btnMenu;
    private ImageView btnCopy;
    private ImageView btnMove;
    private ImageView btnDelete;
    private ImageView btnMore;
    private ImageView btnCloseSelection;

    // Footer views
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

    // Background work
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // ✅ Icon button shortcuts
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
            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show();
        });

        btnFooterPaste.setOnClickListener(v -> {
            if (!isTransferring) startPaste();
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isTransferring) {
                    Toast.makeText(FileManagerActivity.this,
                            "Transfer in progress…", Toast.LENGTH_SHORT).show();
                } else if (drawerLayout.isDrawerOpen(drawerPanel)) {
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

    // ==================== Black Dialog helper ====================

    /**
     * Every dialog in this activity uses this so we get pure black bg + green text
     * consistently across all ROMs.
     */
    private AlertDialog.Builder blackDialogBuilder() {
        return new AlertDialog.Builder(
                new ContextThemeWrapper(this, R.style.BlackDialog));
    }

    // ==================== Directory Loading ====================

    private void loadDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.canRead()) {
            Toast.makeText(this, "Cannot access directory", Toast.LENGTH_SHORT).show();
            return;
        }

        currentDir = dir;
        tvPath.setText(dir.getAbsolutePath());

        executor.execute(() -> {
            File[] files = dir.listFiles();
            List<File> fileList = new ArrayList<>();
            if (files != null) fileList.addAll(Arrays.asList(files));

            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            mainHandler.post(() -> {
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
        } catch (Exception e) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show();
        }
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
            else if (id == R.id.action_extract) { extractZip(selected.get(0)); return true; }
            else if (id == R.id.action_zip) { zipFiles(selected); return true; }
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
        Toast.makeText(this, "Copied " + selected.size() + " item(s) — navigate & paste",
                Toast.LENGTH_SHORT).show();
    }

    private void doCut(List<File> selected) {
        clipboard.clear();
        clipboard.addAll(selected);
        isCutOperation = true;
        adapter.setSelectionMode(false);
        updateSelectionUI();
        updateFooterBar();
        Toast.makeText(this, "Cut " + selected.size() + " item(s) — navigate & paste",
                Toast.LENGTH_SHORT).show();
    }

    // ==================== Paste with Progress ====================

    private void startPaste() {
        if (clipboard.isEmpty()) return;

        final List<File> sources = new ArrayList<>(clipboard);
        final boolean isCut = isCutOperation;
        final File destDir = currentDir;

        long totalBytes = 0;
        for (File src : sources) totalBytes += sizeOf(src);
        final long finalTotalBytes = Math.max(totalBytes, 1);

        isTransferring = true;
        updateFooterBar();
        progressBar.setProgress(0);
        tvProgressTitle.setText(isCut ? "Moving…" : "Copying…");
        tvProgressStats.setText("Preparing…");
        tvProgressPath.setText("");

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
                                        fileCount[0], fileTotal[0]);
                            },
                            () -> fileCount[0]++);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            mainHandler.post(() -> {
                isTransferring = false;
                clipboard.clear();
                isCutOperation = false;
                updateFooterBar();
                loadDirectory(currentDir);
                Toast.makeText(this, isCut ? "Move complete" : "Copy complete",
                        Toast.LENGTH_SHORT).show();
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

    private void postProgress(long transferred, long total, long startTime,
                              String currentFile, String srcPath, String destPath,
                              int completedFiles, int totalFiles) {
        mainHandler.post(() -> {
            int percent = (int) ((transferred * 100) / total);
            progressBar.setProgress(percent);

            long elapsed = System.currentTimeMillis() - startTime;
            double speedBytesPerSec = elapsed > 0 ? (transferred * 1000.0 / elapsed) : 0;

            String progressText = "[" + completedFiles + "/" + totalFiles + "] "
                    + percent + "%  "
                    + formatSize(transferred) + " / " + formatSize(total)
                    + "  •  " + formatSpeed(speedBytesPerSec);

            tvProgressStats.setText(progressText);
            tvProgressTitle.setText(currentFile);
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
                        Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show();
                        adapter.setSelectionMode(false);
                        loadDirectory(currentDir);
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

        // Compute total bytes/files for progress
        long totalBytes = 0;
        int totalFiles = 0;
        for (File f : targets) {
            totalBytes += sizeOf(f);
            totalFiles += countFiles(f);
        }
        final long finalTotal = Math.max(totalBytes, 1);
        final int finalFileTotal = Math.max(totalFiles, 1);

        isTransferring = true;
        adapter.setSelectionMode(false);
        updateSelectionUI();
        updateFooterBar();
        progressBar.setProgress(0);
        tvProgressTitle.setText("Deleting…");
        tvProgressStats.setText("Preparing…");
        tvProgressPath.setText("");

        executor.execute(() -> {
            final long[] deleted = {0};
            final int[] fileCount = {0};
            final long startTime = System.currentTimeMillis();

            for (File f : targets) {
                deleteRecursiveWithProgress(f, deleted,
                        bytes -> postDeleteProgress(deleted[0], finalTotal, startTime,
                                f.getName(), f.getAbsolutePath(),
                                fileCount[0], finalFileTotal),
                        () -> fileCount[0]++);
            }

            mainHandler.post(() -> {
                isTransferring = false;
                updateFooterBar();
                loadDirectory(currentDir);
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
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

    private void postDeleteProgress(long deleted, long total, long startTime,
                                    String currentFile, String path,
                                    int completedFiles, int totalFiles) {
        mainHandler.post(() -> {
            int percent = (int) ((deleted * 100) / total);
            progressBar.setProgress(percent);

            long elapsed = System.currentTimeMillis() - startTime;
            double speed = elapsed > 0 ? (deleted * 1000.0 / elapsed) : 0;

            String text = "[" + completedFiles + "/" + totalFiles + "] "
                    + percent + "%  "
                    + formatSize(deleted) + " / " + formatSize(total)
                    + "  •  " + formatSpeed(speed);

            tvProgressStats.setText(text);
            tvProgressTitle.setText("Deleting: " + currentFile);
            tvProgressPath.setText(path);
        });
    }

    // Simple (non-progress) delete — still used by zip failures etc.
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    // ==================== Zip / Extract ====================

    private void extractZip(File zipFile) {
        executor.execute(() -> {
            String destDir = zipFile.getParent() + "/" + zipFile.getName().replace(".zip", "");
            new File(destDir).mkdirs();

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(destDir, entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                        }
                    }
                    zis.closeEntry();
                }
                mainHandler.post(() -> {
                    Toast.makeText(this, "Extracted to " + destDir, Toast.LENGTH_SHORT).show();
                    loadDirectory(currentDir);
                });
            } catch (IOException e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(this, "Extract failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void zipFiles(List<File> files) {
        String zipName = "archive_" + System.currentTimeMillis() + ".zip";
        File zipFile = new File(currentDir, zipName);

        executor.execute(() -> {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                for (File f : files) zipRecursive(f, f.getName(), zos);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Created " + zipName, Toast.LENGTH_SHORT).show();
                    adapter.setSelectionMode(false);
                    loadDirectory(currentDir);
                });
            } catch (IOException e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(this, "Zip failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void zipRecursive(File file, String entryName, ZipOutputStream zos) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    zipRecursive(child, entryName + "/" + child.getName(), zos);
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(file)) {
                zos.putNextEntry(new ZipEntry(entryName));
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                zos.closeEntry();
            }
        }
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
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "Cannot share folders", Toast.LENGTH_SHORT).show();
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

        try {
            startActivity(Intent.createChooser(intent, "Share via"));
        } catch (Exception e) {
            Toast.makeText(this, "No app to share", Toast.LENGTH_SHORT).show();
        }

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