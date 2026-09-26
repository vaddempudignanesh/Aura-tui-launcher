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
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FileManagerActivity extends AppCompatActivity implements FileManagerAdapter.OnFileClickListener {

    private static final String HOME_DIR = Environment.getExternalStorageDirectory().getAbsolutePath();

    private DrawerLayout drawerLayout;
    private RecyclerView recyclerFiles;
    private RecyclerView recyclerStorage;
    private View drawerPanel;
    private TextView tvPath;
    private TextView tvEmpty;
    private TextView tvSelectionInfo;
    private ImageView btnMenu;
    private ImageView btnSelectAll;
    private ImageView btnCloseSelection;

    private FileManagerAdapter adapter;
    private StorageAdapter storageAdapter;

    private File currentDir;
    private final List<File> clipboard = new ArrayList<>();
    private boolean isCutOperation = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        initViews();
        setupRecyclerViews();
        setupListeners();
        setupStorageDrawer();

        // Start at home directory
        currentDir = new File(HOME_DIR);
        loadDirectory(currentDir);
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        recyclerFiles = findViewById(R.id.recycler_files);
        recyclerStorage = findViewById(R.id.recycler_storage);
        tvPath = findViewById(R.id.tv_path);
        tvEmpty = findViewById(R.id.tv_empty);
        tvSelectionInfo = findViewById(R.id.tv_selection_info);
        btnMenu = findViewById(R.id.btn_menu);
        btnSelectAll = findViewById(R.id.btn_select_all);
        btnCloseSelection = findViewById(R.id.btn_close_selection);
        drawerPanel = findViewById(R.id.drawer_panel);
    }

    private void setupRecyclerViews() {
        // Two columns per row
        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 2);
        recyclerFiles.setLayoutManager(gridLayoutManager);
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

        btnSelectAll.setOnClickListener(v -> {
            adapter.selectAll();
            updateSelectionUI();
        });

        btnCloseSelection.setOnClickListener(v -> {
            adapter.setSelectionMode(false);
            updateSelectionUI();
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(drawerPanel)) {
                    drawerLayout.closeDrawer(drawerPanel);
                } else if (adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    updateSelectionUI();
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

        // Internal storage
        File internal = Environment.getExternalStorageDirectory();
        if (internal != null && internal.exists()) {
            items.add(new StorageAdapter.StorageItem("Internal Storage", internal.getAbsolutePath()));
        }

        // External storage / SD cards
        File[] externalDirs = getExternalFilesDirs(null);
        if (externalDirs != null) {
            for (File dir : externalDirs) {
                if (dir != null) {
                    // Get the root of external storage (remove Android/data/...)
                    String path = dir.getAbsolutePath();
                    int idx = path.indexOf("/Android/");
                    if (idx > 0) {
                        path = path.substring(0, idx);
                    }
                    File extRoot = new File(path);
                    if (extRoot.exists() && !extRoot.equals(internal)) {
                        items.add(new StorageAdapter.StorageItem("SD Card", extRoot.getAbsolutePath()));
                    }
                }
            }
        }

        // Root
        File root = new File("/");
        items.add(new StorageAdapter.StorageItem("Root", root.getAbsolutePath()));

        storageAdapter.setItems(items);
    }

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
            if (files != null) {
                fileList.addAll(Arrays.asList(files));
            }

            // Sort: directories first, then files, alphabetically
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
                tvEmpty.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
                recyclerFiles.setVisibility(fileList.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void updateSelectionUI() {
        boolean selectionMode = adapter.isSelectionMode();
        int count = adapter.getSelectedFiles().size();

        btnSelectAll.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        btnCloseSelection.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        tvSelectionInfo.setVisibility(selectionMode ? View.VISIBLE : View.GONE);

        if (selectionMode) {
            tvSelectionInfo.setText(count + " selected");
        }
    }

    @Override
    public void onFileClick(File file, int position) {
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(file);
            updateSelectionUI();
        } else {
            if (file.isDirectory()) {
                loadDirectory(file);
            } else {
                openFile(file);
            }
        }
    }

    @Override
    public void onFileLongClick(File file, int position) {
        adapter.setSelectionMode(true);
        adapter.toggleSelection(file);
        updateSelectionUI();
    }

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

    // ==================== Context Menu Operations ====================

    private void showFileOptions(File file) {
        // Not used directly - handled in selection mode
    }

    private void showSelectionMenu() {
        List<File> selected = new ArrayList<>(adapter.getSelectedFiles());
        if (selected.isEmpty()) return;

        boolean singleFile = selected.size() == 1;
        boolean hasZip = false;
        for (File f : selected) {
            if (f.getName().toLowerCase().endsWith(".zip")) {
                hasZip = true;
                break;
            }
        }

        List<String> options = new ArrayList<>();
        options.add("Copy");
        options.add("Cut");
        if (singleFile) {
            options.add("Rename");
        }
        options.add("Delete");
        if (singleFile && hasZip) {
            options.add("Extract");
        }
        options.add("Zip");

        new AlertDialog.Builder(this)
                .setTitle(selected.size() + " item(s)")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String choice = options.get(which);
                    switch (choice) {
                        case "Copy":
                            clipboard.clear();
                            clipboard.addAll(selected);
                            isCutOperation = false;
                            adapter.setSelectionMode(false);
                            updateSelectionUI();
                            Toast.makeText(this, "Copied " + selected.size() + " item(s)", Toast.LENGTH_SHORT).show();
                            showPasteOption();
                            break;
                        case "Cut":
                            clipboard.clear();
                            clipboard.addAll(selected);
                            isCutOperation = true;
                            adapter.setSelectionMode(false);
                            updateSelectionUI();
                            Toast.makeText(this, "Cut " + selected.size() + " item(s)", Toast.LENGTH_SHORT).show();
                            showPasteOption();
                            break;
                        case "Rename":
                            showRenameDialog(selected.get(0));
                            break;
                        case "Delete":
                            confirmDelete(selected);
                            break;
                        case "Extract":
                            extractZip(selected.get(0));
                            break;
                        case "Zip":
                            zipFiles(selected);
                            break;
                    }
                })
                .show();
    }

    private void showPasteOption() {
        new AlertDialog.Builder(this)
                .setTitle("Clipboard")
                .setMessage(clipboard.size() + " item(s) in clipboard")
                .setPositiveButton("Paste Here", (dialog, which) -> pasteFiles())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameDialog(File file) {
        EditText input = new EditText(this);
        input.setText(file.getName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(0xFF00FF00);
        input.setBackgroundColor(0xFF1A1A1A);

        new AlertDialog.Builder(this)
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

    private void confirmDelete(List<File> files) {
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete " + files.size() + " item(s)?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    executor.execute(() -> {
                        for (File f : files) {
                            deleteRecursive(f);
                        }
                        mainHandler.post(() -> {
                            adapter.setSelectionMode(false);
                            loadDirectory(currentDir);
                            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void pasteFiles() {
        if (clipboard.isEmpty()) return;

        executor.execute(() -> {
            for (File src : clipboard) {
                try {
                    File dest = new File(currentDir, src.getName());
                    if (isCutOperation) {
                        if (!src.renameTo(dest)) {
                            copyRecursive(src, dest);
                            deleteRecursive(src);
                        }
                    } else {
                        copyRecursive(src, dest);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            mainHandler.post(() -> {
                clipboard.clear();
                isCutOperation = false;
                loadDirectory(currentDir);
                Toast.makeText(this, "Pasted", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void copyRecursive(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(dest, child.getName()));
                }
            }
        } else {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    private void extractZip(File zipFile) {
        executor.execute(() -> {
            String destDir = zipFile.getParent() + "/" + zipFile.getName().replace(".zip", "");
            File dest = new File(destDir);
            dest.mkdirs();

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
                            while ((len = zis.read(buf)) > 0) {
                                fos.write(buf, 0, len);
                            }
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
                for (File f : files) {
                    zipRecursive(f, f.getName(), zos);
                }
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
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}