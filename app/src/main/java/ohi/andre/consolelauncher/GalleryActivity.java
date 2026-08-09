package ohi.andre.consolelauncher;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
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
    private LinearLayout bottomBar, selectionBar;

    private enum FilterMode { ALL, IMAGES, VIDEOS, FAVORITES, BIN }
    private FilterMode currentFilter = FilterMode.ALL;

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

        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        btnBack.setOnClickListener(v -> finish());

        TextView btnHome = findViewById(R.id.btnHome);
        TextView btnAlbums = findViewById(R.id.btnAlbums);
        TextView btnSort = findViewById(R.id.btnSort);
        TextView btnBinSelected = findViewById(R.id.btnBinSelected);
        TextView btnShareSelected = findViewById(R.id.btnShareSelected);
        TextView btnCopySelected = findViewById(R.id.btnCopySelected);
        TextView btnMoveSelected = findViewById(R.id.btnMoveSelected);

        TextView sortImages = findViewById(R.id.sortImages);
        TextView sortVideos = findViewById(R.id.sortVideos);
        TextView sortFavorites = findViewById(R.id.sortFavorites);
        TextView sortBin = findViewById(R.id.sortBin);

        btnHome.setOnClickListener(v -> {
            currentFilter = FilterMode.ALL;
            currentAlbum = null;
            titleView.setText("Gallery");
            showAlbums = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            applyFilter();
            sortOptions.setVisibility(View.GONE);
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
        });

        btnSort.setOnClickListener(v -> {
            if (sortOptions.getVisibility() == View.VISIBLE) {
                sortOptions.setVisibility(View.GONE);
            } else {
                sortOptions.setVisibility(View.VISIBLE);
            }
        });
        btnBinSelected.setOnClickListener(v -> deleteSelectedItems());
        btnShareSelected.setOnClickListener(v -> shareSelectedItems());
        btnCopySelected.setOnClickListener(v -> Toast.makeText(this, "Copy (TODO)", Toast.LENGTH_SHORT).show());
        btnMoveSelected.setOnClickListener(v -> Toast.makeText(this, "Move (TODO)", Toast.LENGTH_SHORT).show());

        sortImages.setOnClickListener(v -> {
            currentFilter = FilterMode.IMAGES;
            currentAlbum = null;
            titleView.setText("📷 Images");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
        });

        sortVideos.setOnClickListener(v -> {
            currentFilter = FilterMode.VIDEOS;
            currentAlbum = null;
            titleView.setText("🎬 Videos");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
        });

        sortFavorites.setOnClickListener(v -> {
            currentFilter = FilterMode.FAVORITES;
            currentAlbum = null;
            titleView.setText("⭐ Favorites");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
        });

        sortBin.setOnClickListener(v -> {
            currentFilter = FilterMode.BIN;
            currentAlbum = null;
            titleView.setText("🗑️ Bin");
            applyFilter();
            sortOptions.setVisibility(View.GONE);
        });

        setupVideoControls();
        applyGlassmorphism(findViewById(R.id.bottomBar));
        applyGlassmorphism(sortOptions);
        checkAndRequestMediaPermissions();
    }

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
            });
        });
    }

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
        }
    }

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

    private void setupRecyclerView() {
        if (!isActivityAlive()) return;

        if (displayedItems.isEmpty()) {
            Toast.makeText(this, "No media found", Toast.LENGTH_SHORT).show();
            return;
        }

        adapter = new GalleryAdapter(this, displayedItems, selectedItems, new GalleryAdapter.OnItemClickListener() {
            @Override
            public void onImageClick(String path) {
                if (!selectionMode && isActivityAlive()) openImageViewer(path);
            }

            @Override
            public void onVideoClick(String path) {
                if (!selectionMode && isActivityAlive()) playVideo(path);
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

    private void moveToTrash(MediaItem item) {
        if (item.isTrashed) {
            Toast.makeText(this, "File is already in Bin", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(item.path);
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Moved to Bin: " + cleanName, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to move to Bin", Toast.LENGTH_SHORT).show();
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

    private String cleanFileName(String name) {
        while (name.startsWith(".trashed.")) {
            name = name.substring(9);
        }
        return name;
    }

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

        if (selectedItems.isEmpty()) {
            selectionMode = false;
            if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
            if (selectionBar != null) selectionBar.setVisibility(View.GONE);
        } else {
            selectionMode = true;
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
            if (selectionBar != null) selectionBar.setVisibility(View.VISIBLE);
            TextView btnBin = findViewById(R.id.btnBinSelected);
            if (btnBin != null) {
                btnBin.setText("🗑️ Bin (" + selectedItems.size() + ")");
            }
        }
    }

    private void clearSelection() {
        selectedItems.clear();
        selectionMode = false;
        bottomBar.setVisibility(View.VISIBLE);
        selectionBar.setVisibility(View.GONE);
    }

    private void deleteSelectedItems() {
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

    private void shareSelectedItems() {
        if (selectedItems.isEmpty()) return;

        String firstPath = selectedItems.get(0);
        File file = new File(firstPath);

        try {
            Uri uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("*/*");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share via"));
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void openImageViewer(String path) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra("image_path", path);
        startActivity(intent);
    }

    private void playVideo(String path) {
        currentVideoPath = path;
        isVideoPlaying = true;

        findViewById(R.id.bottomBar).setVisibility(View.GONE);
        findViewById(R.id.sortOptions).setVisibility(View.GONE);

        videoPlayerContainer.setVisibility(View.VISIBLE);
        videoView.setVideoPath(path);
        videoView.start();
        isPlaying = true;
        controlsVisible = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }

        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);

        updateVideoProgress();
        updateFavoriteButton(path);
        showControls();
    }

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
        });

        albumRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        albumRecycler.setAdapter(albumAdapter);
        albumRecycler.setVisibility(View.VISIBLE);
    }

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

        Toast.makeText(this, info.toString(), Toast.LENGTH_LONG).show();
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
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp")) return "Image";
        if (ext.matches("mp4|avi|mkv|mov|wmv|flv|3gp")) return "Video";
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

    private void refreshMediaStore(String path) {
        try {
            MediaScannerConnection.scanFile(this, new String[]{path}, null, new MediaScannerConnection.OnScanCompletedListener() {
                @Override
                public void onScanCompleted(String path, Uri uri) {
                    runOnUiThread(() -> {
                        loadMedia();
                    });
                }
            });
        } catch (Exception ignored) {}
    }

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

        if (showAlbums) {
            showAlbums = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            applyFilter();
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

    private String normalizePath(String path) {
        try {
            File file = new File(path);
            return file.getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }

    private boolean isSameStorageLocation(File file1, File file2) {
        try {
            String path1 = file1.getCanonicalPath();
            String path2 = file2.getCanonicalPath();
            return path1.equals(path2);
        } catch (Exception e) {
            return file1.getAbsolutePath().equals(file2.getAbsolutePath());
        }
    }

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
}