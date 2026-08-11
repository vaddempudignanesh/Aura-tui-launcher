package ohi.andre.consolelauncher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends AppCompatActivity {

    // ===================== VIEW DECLARATIONS =====================
    private RecyclerView recyclerView;
    private RecyclerView albumRecycler;
    private GalleryAdapter adapter;
    private AlbumAdapter albumAdapter;
    private List<MediaItem> mediaItems = new ArrayList<>();
    private List<MediaItem> displayedItems = new ArrayList<>();
    private List<String> albumList = new ArrayList<>();
    private boolean showAlbums = false;
    private TextView titleView;
    private LinearLayout sortOptions;
    private String currentAlbum = null;
    private boolean selectionMode = false;
    private List<String> selectedItems = new ArrayList<>();
    private LinearLayout bottomBar, selectionBar, binBottomBar;
    private View selectionTopBar;
    private TextView selectionCount;

    private enum FilterMode { ALL, IMAGES, VIDEOS, FAVORITES, BIN }
    private FilterMode currentFilter = FilterMode.ALL;

    // Video player
    private RelativeLayout videoPlayerContainer;
    private VideoView videoView;
    private ImageButton btnPlayPause, btnSkipForward, btnSkipBackward;
    private ImageButton btnCenterPlayPause, btnRotate, btnFavorite, btnDelete, btnInfo, btnCloseVideo;
    private TextView videoTime;
    private LinearLayout videoCenterControls, videoBottomControls;
    private Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable updateVideoProgress;
    private Runnable hideControlsRunnable;
    private boolean isLandscape = false;
    private boolean isPlaying = false;
    private boolean isVideoPlaying = false;
    private String currentVideoPath = null;
    private boolean controlsVisible = true;
    private static final int CONTROLS_TIMEOUT = 3000;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean isGranted = true;
                        for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                            if (!entry.getValue()) {
                                isGranted = false;
                                break;
                            }
                        }
                        if (isGranted) {
                            loadMedia();
                        } else {
                            Toast.makeText(this, "Permission denied. Cannot load media.", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        // ===== INIT VIEWS =====
        recyclerView = findViewById(R.id.galleryRecycler);
        albumRecycler = findViewById(R.id.albumRecycler);
        titleView = findViewById(R.id.titleGallery);
        sortOptions = findViewById(R.id.sortOptions);
        videoPlayerContainer = findViewById(R.id.videoPlayerContainer);
        videoView = findViewById(R.id.videoView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnCenterPlayPause = findViewById(R.id.btnCenterPlayPause);
        btnSkipForward = findViewById(R.id.btnSkipForward);
        btnSkipBackward = findViewById(R.id.btnSkipBackward);
        btnCloseVideo = findViewById(R.id.btnCloseVideo);
        btnRotate = findViewById(R.id.btnRotate);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnDelete = findViewById(R.id.btnDelete);
        btnInfo = findViewById(R.id.btnInfo);
        videoTime = findViewById(R.id.videoTime);
        videoCenterControls = findViewById(R.id.videoCenterControls);
        videoBottomControls = findViewById(R.id.videoBottomControls);
        bottomBar = findViewById(R.id.bottomBar);
        selectionBar = findViewById(R.id.selectionBar);
        selectionTopBar = findViewById(R.id.selectionTopBar);
        selectionCount = findViewById(R.id.selectionCount);

        // ===== BIN SPECIFIC BOTTOM BAR =====
        binBottomBar = findViewById(R.id.binBottomBar);
        if (binBottomBar != null) {
            TextView btnRestore = findViewById(R.id.btnRestore);
            TextView btnDeletePermanent = findViewById(R.id.btnDeletePermanent);
            if (btnRestore != null) {
                btnRestore.setOnClickListener(v -> restoreSelectedItems());
            }
            if (btnDeletePermanent != null) {
                btnDeletePermanent.setOnClickListener(v -> deletePermanentlySelectedItems());
            }
        }

        // ===== SELECTION TOP BAR =====
        ImageButton btnCloseSelection = findViewById(R.id.btnCloseSelection);
        if (btnCloseSelection != null) {
            btnCloseSelection.setOnClickListener(v -> clearSelection());
        }

        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        btnBack.setOnClickListener(v -> {
            if (showAlbums || currentAlbum != null) {
                navigateBackFromAlbum();
            } else {
                finish();
            }
        });

        // ===== BOTTOM BAR =====
        TextView btnHome = findViewById(R.id.btnHome);
        TextView btnAlbums = findViewById(R.id.btnAlbums);
        TextView btnSort = findViewById(R.id.btnSort);
        TextView btnBinSelected = findViewById(R.id.btnBinSelected);
        TextView btnShareSelected = findViewById(R.id.btnShareSelected);
        TextView btnInfoSelected = findViewById(R.id.btnInfoSelected);
        TextView btnFavoriteSelected = findViewById(R.id.btnFavoriteSelected);

        // ===== SORT OPTIONS =====
        TextView sortImages = findViewById(R.id.sortImages);
        TextView sortVideos = findViewById(R.id.sortVideos);
        TextView sortFavorites = findViewById(R.id.sortFavorites);
        TextView sortBin = findViewById(R.id.sortBin);

        // ===== CLICK LISTENERS =====
        btnHome.setOnClickListener(v -> {
            currentFilter = FilterMode.ALL;
            currentAlbum = null;
            titleView.setText("Gallery");
            showAlbums = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            applyFilter();
            sortOptions.setVisibility(View.GONE);
            updateBarsVisibility();
        });

        btnAlbums.setOnClickListener(v -> {
            if (showAlbums) {
                showAlbums = false;
                albumRecycler.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                applyFilter();
            } else {
                showAlbums = true;
                loadAlbums();
                recyclerView.setVisibility(View.GONE);
                albumRecycler.setVisibility(View.VISIBLE);
                sortOptions.setVisibility(View.GONE);
            }
            updateBarsVisibility();
        });

        btnSort.setOnClickListener(v -> {
            if (sortOptions.getVisibility() == View.VISIBLE) {
                sortOptions.setVisibility(View.GONE);
            } else {
                sortOptions.setVisibility(View.VISIBLE);
            }
        });

        btnBinSelected.setOnClickListener(v -> moveSelectedToTrash());

        btnShareSelected.setOnClickListener(v -> shareSelectedItems());

        btnInfoSelected.setOnClickListener(v -> showSelectedItemInfo());

        btnFavoriteSelected.setOnClickListener(v -> addSelectedToFavorites());

        sortImages.setOnClickListener(v -> {
            currentFilter = FilterMode.IMAGES;
            currentAlbum = null;
            titleView.setText("📷 Images");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
            updateBarsVisibility();
        });

        sortVideos.setOnClickListener(v -> {
            currentFilter = FilterMode.VIDEOS;
            currentAlbum = null;
            titleView.setText("🎬 Videos");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
            updateBarsVisibility();
        });

        sortFavorites.setOnClickListener(v -> {
            currentFilter = FilterMode.FAVORITES;
            currentAlbum = null;
            titleView.setText("⭐ Favorites");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
            updateBarsVisibility();
        });

        sortBin.setOnClickListener(v -> {
            currentFilter = FilterMode.BIN;
            currentAlbum = null;
            titleView.setText("🗑️ Bin");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
            updateBarsVisibility();
        });

        setupVideoControls();
        applyGlassmorphism(findViewById(R.id.bottomBar));
        applyGlassmorphism(sortOptions);
        applyButtonBorders();
        checkAndRequestMediaPermissions();
        // ===== TOP NAV BAR =====
        View topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) {
            // Ensure back button is visible in all states except main gallery
            updateTopNavBar();
        }
    }

    private void updateTopNavBar() {
        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        TextView title = findViewById(R.id.titleGallery);

        if (btnBack == null || title == null) return;

        boolean showBack = currentFilter == FilterMode.BIN ||
                currentAlbum != null ||
                showAlbums;

        btnBack.setVisibility(showBack ? View.VISIBLE : View.GONE);

        if (currentFilter == FilterMode.BIN) {
            title.setText("🗑️ Bin");
        } else if (currentAlbum != null) {
            title.setText("📁 " + currentAlbum);
        } else if (showAlbums) {
            title.setText("📁 Albums");
        } else {
            title.setText("Gallery");
        }

        btnBack.setOnClickListener(v -> {
            if (currentFilter == FilterMode.BIN) {
                currentFilter = FilterMode.ALL;
                applyFilter();
                updateTopNavBar();
                updateBarsVisibility();
            } else if (currentAlbum != null) {
                navigateBackFromAlbum();
            } else if (showAlbums) {
                navigateBackFromAlbum();
            } else {
                finish();
            }
        });
    }

    // ===================== BARS VISIBILITY MANAGEMENT =====================

    private void updateBarsVisibility() {
        boolean isBin = currentFilter == FilterMode.BIN;
        boolean isSelection = selectionMode && !isBin;

        if (isBin) {
            bottomBar.setVisibility(View.GONE);
            selectionBar.setVisibility(View.GONE);
            binBottomBar.setVisibility(selectedItems.isEmpty() ? View.GONE : View.VISIBLE);
            if (selectionTopBar != null) selectionTopBar.setVisibility(View.GONE);
        } else if (isSelection) {
            bottomBar.setVisibility(View.GONE);
            selectionBar.setVisibility(View.VISIBLE);
            binBottomBar.setVisibility(View.GONE);
            if (selectionTopBar != null) selectionTopBar.setVisibility(View.VISIBLE);
            if (selectionCount != null) selectionCount.setText(selectedItems.size() + " selected");
        } else {
            bottomBar.setVisibility(View.VISIBLE);
            selectionBar.setVisibility(View.GONE);
            binBottomBar.setVisibility(View.GONE);
            if (selectionTopBar != null) selectionTopBar.setVisibility(View.GONE);
        }
    }

    // ===================== EMPTY STATE =====================

    private void showEmptyState() {
        if (displayedItems.isEmpty()) {
            View emptyView = findViewById(R.id.emptyStateContainer);
            if (emptyView != null) {
                emptyView.setVisibility(View.VISIBLE);
                TextView emptyText = findViewById(R.id.emptyStateText);
                if (emptyText != null) {
                    if (currentFilter == FilterMode.BIN) {
                        emptyText.setText("No files in Bin");
                    } else {
                        emptyText.setText("No media found");
                    }
                }
                recyclerView.setVisibility(View.GONE);
            }
        } else {
            View emptyView = findViewById(R.id.emptyStateContainer);
            if (emptyView != null) {
                emptyView.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            }
        }
    }

    // ===================== NAVIGATION =====================

    private void navigateBackFromAlbum() {
        if (currentAlbum != null) {
            currentAlbum = null;
            showAlbums = true;
            loadAlbums();
            recyclerView.setVisibility(View.GONE);
            albumRecycler.setVisibility(View.VISIBLE);
            titleView.setText("Albums");
            applyFilter();
            updateBarsVisibility();
        } else if (showAlbums) {
            showAlbums = false;
            currentFilter = FilterMode.ALL;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            titleView.setText("Gallery");
            applyFilter();
            updateBarsVisibility();
        }
    }

    // ===================== MEDIA LOADING =====================

    private void loadMedia() {
        executor.execute(() -> {
            List<MediaItem> newItems = new ArrayList<>();

            String[] imageProjection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            };

            Cursor imageCursor = null;
            try {
                imageCursor = getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageProjection,
                        null,
                        null,
                        null
                );

                if (imageCursor != null) {
                    int dataIndex = imageCursor.getColumnIndex(MediaStore.Images.Media.DATA);
                    int nameIndex = imageCursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                    int dateIndex = imageCursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
                    int albumIndex = imageCursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                    while (imageCursor.moveToNext()) {
                        String path = dataIndex >= 0 ? imageCursor.getString(dataIndex) : null;
                        String name = nameIndex >= 0 ? imageCursor.getString(nameIndex) : "image";
                        long date = dateIndex >= 0 ? imageCursor.getLong(dateIndex) : 0;
                        String album = albumIndex >= 0 ? imageCursor.getString(albumIndex) : "";
                        if (path != null && new File(path).exists()) {
                            boolean isTrashed = path.contains(".trashed.");
                            newItems.add(new MediaItem(path, name, MediaItem.TYPE_IMAGE, date, isTrashed, album));
                        }
                    }
                }
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    if (isActivityAlive()) {
                        applyFilter();
                        setupRecyclerView();
                    }
                });
                return;
            } finally {
                if (imageCursor != null) imageCursor.close();
            }

            String[] videoProjection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            };

            Cursor videoCursor = null;
            try {
                videoCursor = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        videoProjection,
                        null,
                        null,
                        null
                );

                if (videoCursor != null) {
                    int dataIndex = videoCursor.getColumnIndex(MediaStore.Video.Media.DATA);
                    int nameIndex = videoCursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    int dateIndex = videoCursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);
                    int albumIndex = videoCursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    while (videoCursor.moveToNext()) {
                        String path = dataIndex >= 0 ? videoCursor.getString(dataIndex) : null;
                        String name = nameIndex >= 0 ? videoCursor.getString(nameIndex) : "video";
                        long date = dateIndex >= 0 ? videoCursor.getLong(dateIndex) : 0;
                        String album = albumIndex >= 0 ? videoCursor.getString(albumIndex) : "";
                        if (path != null && new File(path).exists()) {
                            boolean isTrashed = path.contains(".trashed.");
                            newItems.add(new MediaItem(path, name, MediaItem.TYPE_VIDEO, date, isTrashed, album));
                        }
                    }
                }
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Cannot access media files", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            } finally {
                if (videoCursor != null) videoCursor.close();
            }

            Collections.sort(newItems, (a, b) -> Long.compare(b.dateModified, a.dateModified));

            mediaItems.clear();
            mediaItems.addAll(newItems);

            runOnUiThread(() -> {
                applyFilter();
                setupRecyclerView();
                showEmptyState();
            });
        });
    }

    // ===================== BIN SCAN METHODS =====================

    private List<MediaItem> scanForTrashedFiles() {
        List<MediaItem> trashedItems = new ArrayList<>();
        List<File> directories = getStorageDirectoriesProper();

        for (File dir : directories) {
            if (dir != null && dir.exists()) {
                scanDirectoryForTrashedFiles(dir, trashedItems, 0, 6);
            }
        }

        return trashedItems;
    }

    private List<File> getStorageDirectoriesProper() {
        List<File> directories = new ArrayList<>();
        List<String> normalizedPaths = new ArrayList<>();

        try {
            StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (storageManager != null) {
                List<StorageVolume> volumes = storageManager.getStorageVolumes();

                for (StorageVolume volume : volumes) {
                    try {
                        File volumeFile = volume.getDirectory();
                        if (volumeFile != null && volumeFile.exists()) {
                            String normalizedPath = normalizePath(volumeFile.getAbsolutePath());
                            if (!normalizedPaths.contains(normalizedPath)) {
                                directories.add(volumeFile);
                                normalizedPaths.add(normalizedPath);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        String[] commonPaths = {
                "/storage/extSdCard",
                "/storage/sdcard1",
                "/storage/external_SD",
                "/storage/emulated/0",
                "/mnt/extSdCard",
                "/mnt/sdcard1",
                "/mnt/external_sd",
                "/sdcard1",
                "/external_sd"
        };

        for (String path : commonPaths) {
            File file = new File(path);
            if (file.exists() && file.isDirectory()) {
                String normalizedPath = normalizePath(file.getAbsolutePath());
                if (!normalizedPaths.contains(normalizedPath)) {
                    directories.add(file);
                    normalizedPaths.add(normalizedPath);
                }
            }
        }

        File storage = new File("/storage");
        if (storage.exists() && storage.isDirectory()) {
            File[] children = storage.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && !directories.contains(child)) {
                        String path = child.getAbsolutePath().toLowerCase();
                        if (!path.contains("self") && !path.contains("usb")) {
                            String normalizedPath = normalizePath(child.getAbsolutePath());
                            File dcim = new File(child, "DCIM");
                            File pictures = new File(child, "Pictures");
                            File movies = new File(child, "Movies");
                            File downloads = new File(child, "Download");

                            if ((dcim.exists() || pictures.exists() || movies.exists() || downloads.exists()) &&
                                    !normalizedPaths.contains(normalizedPath)) {
                                directories.add(child);
                                normalizedPaths.add(normalizedPath);
                            }
                        }
                    }
                }
            }
        }

        try {
            File[] externalDirs = getExternalFilesDirs(null);
            if (externalDirs != null) {
                for (File dir : externalDirs) {
                    if (dir != null) {
                        File current = dir;
                        while (current != null && current.getParentFile() != null) {
                            String path = current.getAbsolutePath().toLowerCase();
                            if (path.contains("storage") &&
                                    !path.contains("emulated") &&
                                    !path.contains("self")) {

                                String normalizedPath = normalizePath(current.getAbsolutePath());
                                if (!normalizedPaths.contains(normalizedPath)) {
                                    directories.add(current);
                                    normalizedPaths.add(normalizedPath);
                                }
                                break;
                            }
                            current = current.getParentFile();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        List<File> uniqueDirectories = new ArrayList<>();
        List<String> uniqueNormalizedPaths = new ArrayList<>();
        for (File dir : directories) {
            try {
                String canonicalPath = dir.getCanonicalPath();
                if (!uniqueNormalizedPaths.contains(canonicalPath)) {
                    uniqueDirectories.add(dir);
                    uniqueNormalizedPaths.add(canonicalPath);
                }
            } catch (Exception e) {
                String absPath = dir.getAbsolutePath();
                if (!uniqueNormalizedPaths.contains(absPath)) {
                    uniqueDirectories.add(dir);
                    uniqueNormalizedPaths.add(absPath);
                }
            }
        }

        return uniqueDirectories;
    }

    private void scanDirectoryForTrashedFiles(File directory, List<MediaItem> items, int depth, int maxDepth) {
        if (depth > maxDepth || directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }

        try {
            try {
                String canonicalPath = directory.getCanonicalPath();
                if (canonicalPath.equals("/storage/emulated/0") &&
                        !directory.getAbsolutePath().equals("/storage/emulated/0")) {
                    return;
                }
            } catch (Exception ignored) {}

            File[] files = directory.listFiles();
            if (files == null) {
                return;
            }

            for (File file : files) {
                if (file.isDirectory()) {
                    String name = file.getName().toLowerCase();
                    if (!name.startsWith(".") &&
                            !name.equals("android") &&
                            !name.equals("system") &&
                            !name.equals("cache") &&
                            !name.equals("tmp") &&
                            !name.equals("lost+found") &&
                            !name.equals("app") &&
                            !name.equals("data") &&
                            !name.equals("obb") &&
                            !name.equals("media")) {
                        scanDirectoryForTrashedFiles(file, items, depth + 1, maxDepth);
                    }
                } else {
                    String fileName = file.getName().toLowerCase();
                    if (file.getName().contains(".trashed.")) {
                        boolean isImage = fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                                fileName.endsWith(".png") || fileName.endsWith(".gif") ||
                                fileName.endsWith(".bmp") || fileName.endsWith(".webp") ||
                                fileName.endsWith(".heic") || fileName.endsWith(".heif");

                        boolean isVideo = fileName.endsWith(".mp4") || fileName.endsWith(".avi") ||
                                fileName.endsWith(".mkv") || fileName.endsWith(".mov") ||
                                fileName.endsWith(".wmv") || fileName.endsWith(".flv") ||
                                fileName.endsWith(".3gp") || fileName.endsWith(".m4v") ||
                                fileName.endsWith(".webm");

                        if (isImage || isVideo) {
                            String path = file.getAbsolutePath();
                            boolean exists = false;
                            try {
                                String canonicalPath = new File(path).getCanonicalPath();
                                for (MediaItem item : items) {
                                    try {
                                        String itemCanonicalPath = new File(item.path).getCanonicalPath();
                                        if (itemCanonicalPath.equals(canonicalPath)) {
                                            exists = true;
                                            break;
                                        }
                                    } catch (Exception e) {
                                        if (item.path.equals(path)) {
                                            exists = true;
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                for (MediaItem item : items) {
                                    if (item.path.equals(path)) {
                                        exists = true;
                                        break;
                                    }
                                }
                            }

                            if (!exists) {
                                String album = file.getParentFile() != null ? file.getParentFile().getName() : "";
                                long dateModified = file.lastModified() / 1000;
                                int type = isImage ? MediaItem.TYPE_IMAGE : MediaItem.TYPE_VIDEO;
                                items.add(new MediaItem(path, file.getName(), type, dateModified, true, album));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ===================== FILTER METHODS =====================

    private void applyFilter() {
        if (!isActivityAlive()) return;

        displayedItems.clear();

        if (currentFilter == FilterMode.BIN) {
            List<MediaItem> trashedItems = scanForTrashedFiles();

            for (MediaItem item : mediaItems) {
                if (item.path.contains(".trashed.")) {
                    boolean exists = false;
                    try {
                        String canonicalPath = new File(item.path).getCanonicalPath();
                        for (MediaItem existing : trashedItems) {
                            try {
                                String existingCanonical = new File(existing.path).getCanonicalPath();
                                if (existingCanonical.equals(canonicalPath)) {
                                    exists = true;
                                    break;
                                }
                            } catch (Exception e) {
                                if (existing.path.equals(item.path)) {
                                    exists = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        for (MediaItem existing : trashedItems) {
                            if (existing.path.equals(item.path)) {
                                exists = true;
                                break;
                            }
                        }
                    }

                    if (!exists) {
                        trashedItems.add(item);
                    }
                }
            }

            displayedItems.addAll(trashedItems);
        } else {
            for (MediaItem item : mediaItems) {
                boolean matchesAlbum = currentAlbum == null || (item.album != null && item.album.equals(currentAlbum));
                if (!matchesAlbum) continue;

                boolean isCurrentlyTrashed = item.path.contains(".trashed.");
                item.isTrashed = isCurrentlyTrashed;

                switch (currentFilter) {
                    case ALL:
                        if (!isCurrentlyTrashed) displayedItems.add(item);
                        break;
                    case IMAGES:
                        if (!isCurrentlyTrashed && item.type == MediaItem.TYPE_IMAGE) displayedItems.add(item);
                        break;
                    case VIDEOS:
                        if (!isCurrentlyTrashed && item.type == MediaItem.TYPE_VIDEO) displayedItems.add(item);
                        break;
                    case FAVORITES:
                        if (!isCurrentlyTrashed && item.isFavorite) displayedItems.add(item);
                        break;
                    default:
                        break;
                }
            }
        }

        if (isActivityAlive() && adapter != null) {
            adapter.updateItems(displayedItems);
            adapter.updateSelectedItems(selectedItems);
            updateSelectionUI();
            showEmptyState();
            updateTopNavBar();
        }
    }

    // ===================== PERMISSIONS =====================

    private void checkAndRequestMediaPermissions() {
        String[] permissionsToRequest;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest = new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        } else {
            permissionsToRequest = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        boolean allGranted = true;
        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            loadMedia();
        } else {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest);
        }
    }

    // ===================== RECYCLER VIEW =====================

    private void setupRecyclerView() {
        if (!isActivityAlive()) return;

        if (displayedItems.isEmpty()) {
            showEmptyState();
            return;
        }

        adapter = new GalleryAdapter(this, displayedItems, selectedItems, new GalleryAdapter.OnItemClickListener() {
            @Override
            public void onImageClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN) {
                    openImageViewer(path);
                }
            }

            @Override
            public void onVideoClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN) {
                    playVideo(path);
                }
            }

            @Override
            public void onFavoriteToggle(MediaItem item) {
                if (isActivityAlive()) {
                    item.isFavorite = !item.isFavorite;
                    applyFilter();
                }
            }

            @Override
            public void onDelete(MediaItem item) {
                if (isActivityAlive()) moveToTrash(item);
            }

            @Override
            public void onItemClick(String path) {
                if (isActivityAlive()) toggleSelection(path);
            }

            @Override
            public boolean isSelectionMode() {
                return selectionMode && isActivityAlive();
            }

            @Override
            public void onRestore(MediaItem item) {
                if (isActivityAlive()) restoreFromTrash(item);
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
    }

    // ===================== TRASH OPERATIONS =====================

    private void moveToTrash(MediaItem item) {
        if (item.isTrashed) {
            return;
        }

        File file = new File(item.path);
        if (!file.exists()) {
            return;
        }

        String parent = file.getParent();
        String name = file.getName();

        String cleanName = cleanFileName(name);
        String trashedName = ".trashed." + cleanName;
        File trashedFile = new File(parent, trashedName);

        if (trashedFile.exists()) {
            int count = 1;
            String newName = trashedName;
            File newFile = trashedFile;
            while (newFile.exists()) {
                String baseName = cleanName;
                int dotIndex = cleanName.lastIndexOf(".");
                if (dotIndex > 0) {
                    baseName = cleanName.substring(0, dotIndex);
                    String ext = cleanName.substring(dotIndex);
                    newName = ".trashed." + baseName + "_" + count + ext;
                } else {
                    newName = ".trashed." + cleanName + "_" + count;
                }
                newFile = new File(parent, newName);
                count++;
            }
            trashedFile = newFile;
        }

        if (file.renameTo(trashedFile)) {
            String oldPath = item.path;
            item.path = trashedFile.getAbsolutePath();
            item.isTrashed = true;
            item.name = trashedFile.getName();

            MediaScannerConnection.scanFile(this, new String[]{trashedFile.getAbsolutePath()}, null, null);
            MediaScannerConnection.scanFile(this, new String[]{oldPath}, null, null);

            for (int i = 0; i < mediaItems.size(); i++) {
                if (mediaItems.get(i).path.equals(oldPath)) {
                    mediaItems.set(i, item);
                    break;
                }
            }

            applyFilter();
            // No Toast - just update UI
        }
    }

    private void restoreFromTrash(MediaItem item) {
        File file = new File(item.path);
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String parent = file.getParent();
        String name = file.getName();

        String cleanName = cleanFileName(name);
        File restoredFile = new File(parent, cleanName);

        if (restoredFile.exists()) {
            int count = 1;
            String baseName = cleanName;
            String ext = "";
            int dotIndex = cleanName.lastIndexOf(".");
            if (dotIndex > 0) {
                baseName = cleanName.substring(0, dotIndex);
                ext = cleanName.substring(dotIndex);
            }
            while (restoredFile.exists()) {
                String newName = baseName + "_" + count + ext;
                restoredFile = new File(parent, newName);
                count++;
            }
        }

        if (file.renameTo(restoredFile)) {
            String oldPath = item.path;
            item.path = restoredFile.getAbsolutePath();
            item.isTrashed = false;
            item.name = restoredFile.getName();

            MediaScannerConnection.scanFile(this, new String[]{restoredFile.getAbsolutePath()}, null, null);
            MediaScannerConnection.scanFile(this, new String[]{oldPath}, null, null);

            for (int i = 0; i < mediaItems.size(); i++) {
                if (mediaItems.get(i).path.equals(oldPath)) {
                    mediaItems.set(i, item);
                    break;
                }
            }

            applyFilter();
            Toast.makeText(this, "Restored: " + cleanName, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to restore", Toast.LENGTH_SHORT).show();
        }
    }

    private void moveSelectedToTrash() {
        if (selectedItems.isEmpty()) return;

        for (String path : selectedItems) {
            for (MediaItem item : mediaItems) {
                if (item.path.equals(path)) {
                    moveToTrash(item);
                    break;
                }
            }
        }

        clearSelection();
        applyFilter();
        Toast.makeText(this, "Moved " + selectedItems.size() + " items to Bin", Toast.LENGTH_SHORT).show();
    }

    private void restoreSelectedItems() {
        if (selectedItems.isEmpty()) return;

        List<MediaItem> itemsToRestore = new ArrayList<>();
        for (String path : selectedItems) {
            for (MediaItem item : mediaItems) {
                if (item.path.equals(path) && item.isTrashed) {
                    itemsToRestore.add(item);
                    break;
                }
            }
        }

        for (MediaItem item : itemsToRestore) {
            restoreFromTrash(item);
        }

        clearSelection();
        applyFilter();
        Toast.makeText(this, "Restored " + itemsToRestore.size() + " items", Toast.LENGTH_SHORT).show();
    }

    private void deletePermanentlySelectedItems() {
        if (selectedItems.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle("Delete Permanently")
                .setMessage("Are you sure you want to permanently delete " + selectedItems.size() + " items? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    for (String path : selectedItems) {
                        File file = new File(path);
                        if (file.exists()) {
                            file.delete();
                        }
                        for (int i = mediaItems.size() - 1; i >= 0; i--) {
                            if (mediaItems.get(i).path.equals(path)) {
                                mediaItems.remove(i);
                                break;
                            }
                        }
                    }
                    clearSelection();
                    applyFilter();
                    Toast.makeText(this, "Deleted permanently", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String cleanFileName(String name) {
        while (name.startsWith(".trashed.")) {
            name = name.substring(9);
        }
        return name;
    }

    // ===================== SELECTION OPERATIONS =====================

    private void toggleSelection(String path) {
        if (selectedItems.contains(path)) {
            selectedItems.remove(path);
        } else {
            selectedItems.add(path);
        }

        updateSelectionUI();
        if (adapter != null) {
            adapter.updateSelectedItems(selectedItems);
        }
    }

    private void updateSelectionUI() {
        if (!isActivityAlive()) return;

        boolean isBin = currentFilter == FilterMode.BIN;

        if (selectedItems.isEmpty()) {
            selectionMode = false;
            if (bottomBar != null) bottomBar.setVisibility(isBin ? View.GONE : View.VISIBLE);
            if (selectionBar != null) selectionBar.setVisibility(View.GONE);
            if (binBottomBar != null) binBottomBar.setVisibility(View.GONE);
            if (selectionTopBar != null) selectionTopBar.setVisibility(View.GONE);
        } else {
            selectionMode = true;
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
            if (selectionBar != null) selectionBar.setVisibility(isBin ? View.GONE : View.VISIBLE);
            if (binBottomBar != null) binBottomBar.setVisibility(isBin ? View.VISIBLE : View.GONE);
            if (selectionTopBar != null) selectionTopBar.setVisibility(View.VISIBLE);
            if (selectionCount != null) selectionCount.setText(selectedItems.size() + " selected");

            if (!isBin) {
                TextView btnBin = findViewById(R.id.btnBinSelected);
                if (btnBin != null) {
                    btnBin.setText("🗑️ Bin (" + selectedItems.size() + ")");
                }
            }
        }
    }

    private void clearSelection() {
        selectedItems.clear();
        selectionMode = false;
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        if (selectionBar != null) selectionBar.setVisibility(View.GONE);
        if (binBottomBar != null) binBottomBar.setVisibility(View.GONE);
        if (selectionTopBar != null) selectionTopBar.setVisibility(View.GONE);
        if (adapter != null) {
            adapter.updateSelectedItems(selectedItems);
        }
    }

    // ===================== SHARE / INFO / FAVORITES =====================

    private void shareSelectedItems() {
        if (selectedItems.isEmpty()) return;

        try {
            if (selectedItems.size() == 1) {
                File file = new File(selectedItems.get(0));
                if (!file.exists()) {
                    Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(getMimeType(file.getAbsolutePath()));
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Share via"));
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                for (String path : selectedItems) {
                    File file = new File(path);
                    if (file.exists()) {
                        Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
                        uris.add(uri);
                    }
                }

                if (uris.isEmpty()) {
                    Toast.makeText(this, "No valid files to share", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("*/*");
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Share " + uris.size() + " files"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private String getMimeType(String path) {
        String extension = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "mp4":
                return "video/mp4";
            case "avi":
                return "video/avi";
            case "mkv":
                return "video/x-matroska";
            case "mp3":
                return "audio/mpeg";
            default:
                return "*/*";
        }
    }

    private void showSelectedItemInfo() {
        if (selectedItems.isEmpty()) return;

        if (selectedItems.size() == 1) {
            String path = selectedItems.get(0);
            File file = new File(path);
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder info = new StringBuilder();
            info.append("📁 Type: ").append(getFileType(path)).append("\n");
            info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
            info.append("📅 Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                    .format(new Date(file.lastModified()))).append("\n");
            info.append("📍 Path: ").append(file.getAbsolutePath());
            showInfoDialog(info.toString(), "📄 File Info");
        } else {
            // Multi-select aggregate info
            long totalSize = 0;
            int imageCount = 0;
            int videoCount = 0;

            for (String path : selectedItems) {
                File file = new File(path);
                if (file.exists()) {
                    totalSize += file.length();
                    String type = getFileType(path);
                    if (type.equals("Image")) imageCount++;
                    else if (type.equals("Video")) videoCount++;
                }
            }

            StringBuilder info = new StringBuilder();
            info.append("📊 Selected: ").append(selectedItems.size()).append(" items\n");
            info.append("📏 Total Size: ").append(formatFileSize(totalSize)).append("\n");
            info.append("🖼️ Images: ").append(imageCount).append("\n");
            info.append("🎬 Videos: ").append(videoCount).append("\n");
            info.append("📍 Paths: ").append(selectedItems.size()).append(" files selected");

            showInfoDialog(info.toString(), "📊 Multi-Select Info");
        }
    }

    private void showInfoDialog(String info, String title) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_file_info, null);

        TextView infoTitle = view.findViewById(R.id.infoTitle);
        TextView infoContent = view.findViewById(R.id.infoContent);
        Button infoClose = view.findViewById(R.id.infoClose);

        if (infoTitle != null) infoTitle.setText(title);
        if (infoContent != null) infoContent.setText(info);

        builder.setView(view);
        AlertDialog dialog = builder.create();

        if (infoClose != null) {
            infoClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                WindowManager.LayoutParams.WRAP_CONTENT
        );
        dialog.show();
    }



    // ===================== IMAGE/VIDEO VIEWER =====================

    private void openImageViewer(String path) {
        List<String> mediaPaths = new ArrayList<>();
        int currentIndex = 0;

        for (int i = 0; i < displayedItems.size(); i++) {
            MediaItem item = displayedItems.get(i);
            if (!item.isTrashed) {
                mediaPaths.add(item.path);
                if (item.path.equals(path)) {
                    currentIndex = mediaPaths.size() - 1;
                }
            }
        }

        if (mediaPaths.isEmpty()) {
            Toast.makeText(this, "No media to view", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, FullscreenViewerActivity.class);
        intent.putStringArrayListExtra("media_paths", (ArrayList<String>) mediaPaths);
        intent.putExtra("current_position", currentIndex);
        startActivity(intent);
    }

    private void playVideo(String path) {
        openImageViewer(path);
    }

    // ===================== VIDEO CONTROLS =====================

    private void setupVideoControls() {
        videoPlayerContainer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                toggleControlsVisibility();
                return true;
            }
            return false;
        });

        videoView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                toggleControlsVisibility();
                return true;
            }
            return false;
        });

        btnCenterPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());

        btnSkipForward.setOnClickListener(v -> {
            int current = videoView.getCurrentPosition();
            int duration = videoView.getDuration();
            videoView.seekTo(Math.min(current + 15000, duration));
        });

        btnSkipBackward.setOnClickListener(v -> {
            int current = videoView.getCurrentPosition();
            videoView.seekTo(Math.max(current - 5000, 0));
        });

        btnCloseVideo.setOnClickListener(v -> closeVideo());

        btnRotate.setOnClickListener(v -> {
            if (getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } else {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        });

        btnFavorite.setOnClickListener(v -> {
            if (currentVideoPath == null) return;
            for (MediaItem item : mediaItems) {
                if (item.path.equals(currentVideoPath)) {
                    item.isFavorite = !item.isFavorite;
                    updateFavoriteButton(currentVideoPath);
                    Toast.makeText(this, item.isFavorite ? "⭐ Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                    break;
                }
            }
        });

        btnDelete.setOnClickListener(v -> {
            if (currentVideoPath == null) return;
            for (MediaItem item : mediaItems) {
                if (item.path.equals(currentVideoPath)) {
                    moveToTrash(item);
                    closeVideoInternal();
                    break;
                }
            }
        });

        btnInfo.setOnClickListener(v -> showFileInfo(currentVideoPath));

        videoView.setOnCompletionListener(mp -> {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            isPlaying = false;
            showControls();
        });
    }

    private void closeVideo() {
        if (getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            isLandscape = true;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            videoHandler.postDelayed(() -> {
                closeVideoInternal();
            }, 500);
        } else {
            closeVideoInternal();
        }
    }

    private void closeVideoInternal() {
        videoView.stopPlayback();
        videoPlayerContainer.setVisibility(View.GONE);
        isPlaying = false;
        isVideoPlaying = false;
        isLandscape = false;
        videoHandler.removeCallbacks(updateVideoProgress);
        videoHandler.removeCallbacks(hideControlsRunnable);
        controlsVisible = true;

        findViewById(R.id.bottomBar).setVisibility(View.VISIBLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void toggleControlsVisibility() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        controlsVisible = true;
        videoCenterControls.setVisibility(View.VISIBLE);
        videoBottomControls.setVisibility(View.VISIBLE);
        videoTime.setVisibility(View.VISIBLE);
        btnCenterPlayPause.setVisibility(isPlaying ? View.GONE : View.VISIBLE);

        videoHandler.removeCallbacks(hideControlsRunnable);
        hideControlsRunnable = () -> {
            if (isPlaying) {
                hideControls();
            }
        };
        videoHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
    }

    private void hideControls() {
        controlsVisible = false;
        videoCenterControls.setVisibility(View.GONE);
        videoBottomControls.setVisibility(View.GONE);
        videoTime.setVisibility(View.GONE);
        btnCenterPlayPause.setVisibility(View.GONE);
        videoHandler.removeCallbacks(hideControlsRunnable);
    }

    private void togglePlayPause() {
        if (isPlaying) {
            videoView.pause();
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            isPlaying = false;
        } else {
            videoView.start();
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            isPlaying = true;
            updateVideoProgress();
        }
    }

    private void updateVideoProgress() {
        if (updateVideoProgress != null) {
            videoHandler.removeCallbacks(updateVideoProgress);
        }
        updateVideoProgress = new Runnable() {
            @Override
            public void run() {
                if (videoView != null && isPlaying) {
                    int current = videoView.getCurrentPosition();
                    int duration = videoView.getDuration();
                    if (duration > 0) {
                        videoTime.setText(formatTime(current) + " / " + formatTime(duration));
                    }
                    videoHandler.postDelayed(this, 1000);
                }
            }
        };
        videoHandler.post(updateVideoProgress);
    }

    private String formatTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ===================== ALBUMS =====================

    private void loadAlbums() {
        executor.execute(() -> {
            List<String> albums = new ArrayList<>();
            String[] projection = {MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
            Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " ASC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range") String album = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME));
                    if (album != null && !album.isEmpty() && !albums.contains(album)) {
                        albums.add(album);
                    }
                }
                cursor.close();
            }

            runOnUiThread(() -> {
                albumList = albums;
                showAlbumGrid();
            });
        });
    }

    private void showAlbumGrid() {
        if (albumList.isEmpty()) {
            Toast.makeText(this, "No albums found", Toast.LENGTH_SHORT).show();
            return;
        }

        albumAdapter = new AlbumAdapter(this, albumList, albumName -> {
            currentAlbum = albumName;
            titleView.setText("📁 " + albumName);
            applyFilter();
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            showAlbums = false;
            updateBarsVisibility();
        });

        albumRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        albumRecycler.setAdapter(albumAdapter);
        albumRecycler.setVisibility(View.VISIBLE);
        titleView.setText("Albums");
    }

    // ===================== UTILITY METHODS =====================

    private void updateFavoriteButton(String path) {
        for (MediaItem item : mediaItems) {
            if (item.path.equals(path)) {
                btnFavorite.setImageResource(item.isFavorite ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
                break;
            }
        }
    }

    private void showFileInfo(String path) {
        if (path == null) return;
        File file = new File(path);
        StringBuilder info = new StringBuilder();
        info.append("📁 Directory: ").append(file.getParent()).append("\n");
        info.append("📄 File: ").append(file.getName()).append("\n");
        info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
        info.append("📅 Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date(file.lastModified()))).append("\n");
        info.append("🔤 Type: ").append(getFileType(path));

        showInfoDialog(info.toString(), "📄 File Info");
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String getFileType(String path) {
        if (path == null) return "Unknown";
        String ext = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp|heic|heif")) return "Image";
        if (ext.matches("mp4|avi|mkv|mov|wmv|flv|3gp|webm|m4v")) return "Video";
        return "Unknown";
    }

    private void applyGlassmorphism(View view) {
        if (view == null) return;
        GradientDrawable glass = new GradientDrawable();
        glass.setShape(GradientDrawable.RECTANGLE);
        glass.setCornerRadius(20);
        glass.setColor(Color.argb(180, 255, 255, 255));
        glass.setStroke(1, Color.argb(30, 255, 255, 255));
        view.setBackground(glass);
        view.setElevation(12);
    }

    private void applyButtonBorders() {
        int[] buttonIds = {
                R.id.btnHome, R.id.btnAlbums, R.id.btnSort,
                R.id.btnBinSelected, R.id.btnShareSelected, R.id.btnInfoSelected, R.id.btnFavoriteSelected,
                R.id.btnRestore, R.id.btnDeletePermanent,
                R.id.sortImages, R.id.sortVideos, R.id.sortFavorites, R.id.sortBin
        };

        for (int id : buttonIds) {
            View view = findViewById(id);
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                GradientDrawable border = new GradientDrawable();
                border.setShape(GradientDrawable.RECTANGLE);
                border.setCornerRadius(8);
                border.setStroke(1, Color.WHITE);
                border.setColor(Color.TRANSPARENT);
                tv.setBackground(border);
                tv.setPadding(16, 8, 16, 8);
            }
        }
    }

    private String normalizePath(String path) {
        try {
            File file = new File(path);
            return file.getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }

    // ===================== LIFECYCLE METHODS =====================

    @Override
    public void onBackPressed() {
        if (videoPlayerContainer.getVisibility() == View.VISIBLE) {
            if (getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                videoHandler.postDelayed(() -> {
                    closeVideoInternal();
                }, 500);
                return;
            } else {
                closeVideoInternal();
                return;
            }
        }

        if (currentAlbum != null) {
            navigateBackFromAlbum();
            return;
        }

        if (showAlbums) {
            navigateBackFromAlbum();
            return;
        }

        if (selectionMode) {
            clearSelection();
            return;
        }

        super.onBackPressed();
        finish();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isVideoPlaying = false;
        if (videoView != null) videoView.stopPlayback();
        videoHandler.removeCallbacks(updateVideoProgress);
        videoHandler.removeCallbacks(hideControlsRunnable);
        executor.shutdown();
    }

    // ===================== INNER CLASS =====================

    public static class MediaItem {
        public static final int TYPE_IMAGE = 0;
        public static final int TYPE_VIDEO = 1;

        public String path;
        public String name;
        public int type;
        public long dateModified;
        public boolean isFavorite = false;
        public boolean isTrashed = false;
        public String album;

        public MediaItem(String path, String name, int type, long dateModified, boolean isTrashed, String album) {
            this.path = path;
            this.name = name;
            this.type = type;
            this.dateModified = dateModified;
            this.isTrashed = isTrashed;
            this.album = album;
        }
    }



    private void toggleFavorite(MediaItem item) {
        if (item == null) return;
        item.isFavorite = !item.isFavorite;
        applyFilter();
        // No Toast - just update UI
    }

    private void addSelectedToFavorites() {
        if (selectedItems.isEmpty()) return;

        int count = 0;
        for (String path : selectedItems) {
            for (MediaItem item : mediaItems) {
                if (item.path.equals(path) && !item.isTrashed) {
                    item.isFavorite = true;
                    count++;
                    break;
                }
            }
        }

        clearSelection();
        applyFilter();
        // No Toast - just update UI
    }
}