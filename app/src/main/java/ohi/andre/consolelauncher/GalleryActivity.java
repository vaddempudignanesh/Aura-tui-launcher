package ohi.andre.consolelauncher;

import android.Manifest;
import android.annotation.SuppressLint;
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

    // Add these fields
    private int currentOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
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

        // Setup views
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

        // Back button
        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        btnBack.setOnClickListener(v -> finish());

        // Bottom bar
        TextView btnHome = findViewById(R.id.btnHome);
        TextView btnAlbums = findViewById(R.id.btnAlbums);
        TextView btnSort = findViewById(R.id.btnSort);
        TextView btnBinSelected = findViewById(R.id.btnBinSelected);
        TextView btnShareSelected = findViewById(R.id.btnShareSelected);
        TextView btnCopySelected = findViewById(R.id.btnCopySelected);
        TextView btnMoveSelected = findViewById(R.id.btnMoveSelected);


        // Sort options
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

        // Video controls
        setupVideoControls();

        // Apply glassmorphism
        applyGlassmorphism(findViewById(R.id.bottomBar));
        applyGlassmorphism(sortOptions);

        checkAndRequestMediaPermissions();
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

    private void restoreFromTrash(MediaItem item) {
        File file = new File(item.path);
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String parent = file.getParent();
        String name = file.getName();

        // Clean the name - remove all .trashed. prefixes
        String cleanName = cleanFileName(name);
        File restoredFile = new File(parent, cleanName);

        // Check if restored file already exists
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

            // Scan the restored file
            MediaScannerConnection.scanFile(this, new String[]{restoredFile.getAbsolutePath()}, null, null);

            // Scan the old path to remove from MediaStore
            MediaScannerConnection.scanFile(this, new String[]{oldPath}, null, null);

            // Update in mediaItems list
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

    private void updateSelectionUI() {
        if (selectedItems.isEmpty()) {
            selectionMode = false;
            bottomBar.setVisibility(View.VISIBLE);
            selectionBar.setVisibility(View.GONE);
        } else {
            selectionMode = true;
            bottomBar.setVisibility(View.GONE);
            selectionBar.setVisibility(View.VISIBLE);

            // Update bin button text with count
            TextView btnBin = findViewById(R.id.btnBinSelected);
            btnBin.setText("🗑️ Bin (" + selectedItems.size() + ")");
        }
    }

    private void clearSelection() {
        selectedItems.clear();
        selectionMode = false;
        bottomBar.setVisibility(View.VISIBLE);
        selectionBar.setVisibility(View.GONE);
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

    private void loadMedia() {
        executor.execute(() -> {
            List<MediaItem> newItems = new ArrayList<>();

            // First, check if there are any .trashed. files in the directories
            // This is a fallback for files that MediaStore doesn't know about
            File[] externalDirs = getExternalMediaDirs();
            List<File> trashedFiles = new ArrayList<>();

            // Check Download directory and DCIM directory for trashed files
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

            // Function to scan directory for .trashed. files
            scanDirectoryForTrashedFiles(downloadDir, trashedFiles);
            scanDirectoryForTrashedFiles(dcimDir, trashedFiles);
            scanDirectoryForTrashedFiles(picturesDir, trashedFiles);

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
                        if (path != null) {
                            boolean isTrashed = path.contains(".trashed.");
                            // Check if file exists OR if it's a trashed file
                            if (new File(path).exists() || isTrashed) {
                                // If the path doesn't exist but it's trashed, try to find the actual trashed file
                                if (!new File(path).exists() && isTrashed) {
                                    // Try to find the trashed file by scanning directories
                                    String fileName = new File(path).getName();
                                    String parentDir = new File(path).getParent();
                                    if (parentDir != null) {
                                        File parent = new File(parentDir);
                                        if (parent.exists()) {
                                            File[] files = parent.listFiles();
                                            if (files != null) {
                                                for (File f : files) {
                                                    if (f.getName().contains(fileName) && f.getName().contains(".trashed.")) {
                                                        path = f.getAbsolutePath();
                                                        name = f.getName();
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                newItems.add(new MediaItem(path, name, MediaItem.TYPE_IMAGE, date, isTrashed, album));
                            }
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
                        if (path != null) {
                            boolean isTrashed = path.contains(".trashed.");
                            if (new File(path).exists() || isTrashed) {
                                if (!new File(path).exists() && isTrashed) {
                                    String fileName = new File(path).getName();
                                    String parentDir = new File(path).getParent();
                                    if (parentDir != null) {
                                        File parent = new File(parentDir);
                                        if (parent.exists()) {
                                            File[] files = parent.listFiles();
                                            if (files != null) {
                                                for (File f : files) {
                                                    if (f.getName().contains(fileName) && f.getName().contains(".trashed.")) {
                                                        path = f.getAbsolutePath();
                                                        name = f.getName();
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                newItems.add(new MediaItem(path, name, MediaItem.TYPE_VIDEO, date, isTrashed, album));
                            }
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

            // Add any trashed files found in directory scan that weren't in MediaStore
            for (File trashedFile : trashedFiles) {
                boolean alreadyAdded = false;
                for (MediaItem item : newItems) {
                    if (item.path.equals(trashedFile.getAbsolutePath())) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    boolean isImage = isImageFile(trashedFile.getName());
                    int type = isImage ? MediaItem.TYPE_IMAGE : MediaItem.TYPE_VIDEO;
                    newItems.add(new MediaItem(
                            trashedFile.getAbsolutePath(),
                            trashedFile.getName(),
                            type,
                            trashedFile.lastModified() / 1000,
                            true,
                            ""
                    ));
                }
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

    private void scanDirectoryForTrashedFiles(File directory, List<File> trashedFiles) {
        if (directory == null || !directory.exists()) return;

        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // Recursively scan subdirectories (but limit depth to avoid performance issues)
                scanDirectoryForTrashedFiles(file, trashedFiles);
            } else if (file.getName().contains(".trashed.")) {
                trashedFiles.add(file);
            }
        }
    }

    private boolean isImageFile(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return ext.matches("jpg|jpeg|png|gif|bmp|webp|heic|heif");
    }

    private void applyFilter() {
        displayedItems.clear();
        for (MediaItem item : mediaItems) {
            // Check if item should be shown based on current filter
            boolean matchesAlbum = currentAlbum == null || (item.album != null && item.album.equals(currentAlbum));
            if (!matchesAlbum) continue;

            // Re-check if file is trashed (in case it was moved after initial load)
            boolean isCurrentlyTrashed = item.path.contains(".trashed.");
            item.isTrashed = isCurrentlyTrashed; // Update the flag

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
                case BIN:
                    if (isCurrentlyTrashed) displayedItems.add(item);
                    break;
            }
        }
        if (adapter != null) {
            adapter.updateItems(displayedItems);
            adapter.updateSelectedItems(selectedItems);
            updateSelectionUI();
        }
    }

    private void setupRecyclerView() {
        if (displayedItems.isEmpty()) {
            Toast.makeText(this, "No media found", Toast.LENGTH_SHORT).show();
            return;
        }

        adapter = new GalleryAdapter(this, displayedItems, selectedItems, new GalleryAdapter.OnItemClickListener() {
            @Override
            public void onImageClick(String path) {
                if (!selectionMode) openImageViewer(path);
            }

            @Override
            public void onVideoClick(String path) {
                if (!selectionMode) playVideo(path);
            }

            @Override
            public void onFavoriteToggle(MediaItem item) {
                item.isFavorite = !item.isFavorite;
                applyFilter();
            }

            @Override
            public void onDelete(MediaItem item) {
                moveToTrash(item);
            }

            @Override
            public void onItemClick(String path) {
                toggleSelection(path);
            }

            @Override
            public boolean isSelectionMode() {
                return selectionMode;
            }

            // Add this new method for restore
            @Override
            public void onRestore(MediaItem item) {
                restoreFromTrash(item);
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
        // Check if file is already trashed
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

        // Remove any existing .trashed. prefix from name (if any)
        String cleanName = cleanFileName(name);
        String trashedName = ".trashed." + cleanName;
        File trashedFile = new File(parent, trashedName);

        // Check if trashed file already exists
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
            // Update the item path and trashed status
            String oldPath = item.path;
            item.path = trashedFile.getAbsolutePath();
            item.isTrashed = true;
            item.name = trashedFile.getName();

            // Scan the new file to update MediaStore
            MediaScannerConnection.scanFile(this, new String[]{trashedFile.getAbsolutePath()}, null, null);

            // IMPORTANT: Also scan the old path to remove from MediaStore
            MediaScannerConnection.scanFile(this, new String[]{oldPath}, null, null);

            // Update in mediaItems list
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
    private void openImageViewer(String path) {
        Intent intent = new Intent(this, ImageViewerActivity.class);
        intent.putExtra("image_path", path);
        startActivity(intent);
    }


    private void refreshMediaStore(String path) {
        try {
            // Notify MediaStore about the file change
            MediaScannerConnection.scanFile(this, new String[]{path}, null, new MediaScannerConnection.OnScanCompletedListener() {
                @Override
                public void onScanCompleted(String path, Uri uri) {
                    // MediaStore updated
                    runOnUiThread(() -> {
                        // Reload media after scan completes
                        loadMedia();
                    });
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void updateFavoriteButton(String path) {
        for (MediaItem item : mediaItems) {
            if (item.path.equals(path)) {
                btnFavorite.setImageResource(item.isFavorite ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
                break;
            }
        }
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

    // ===================== VIDEO CONTROLS =====================

    // ===================== VIDEO CONTROLS =====================

    // ===================== VIDEO CONTROLS =====================

    // ===================== VIDEO CONTROLS =====================

    private void setupVideoControls() {
        // Toggle controls on video tap
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

        // Center controls
        btnCenterPlayPause.setOnClickListener(v -> togglePlayPause());

        // Bottom controls
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
            // Toggle between portrait and landscape
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
        // If in landscape, rotate to portrait first
        if (getRequestedOrientation() == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            isLandscape = true;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            // Wait for rotation to complete, then close
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

        // Restore bottom bar
        findViewById(R.id.bottomBar).setVisibility(View.VISIBLE);

        // Restore status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        // Reset orientation to portrait
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }
    private String cleanFileName(String name) {
        // Remove all .trashed. prefixes
        while (name.startsWith(".trashed.")) {
            name = name.substring(9); // ".trashed." length is 9
        }
        return name;
    }


    @Override
    public void onBackPressed() {
        // If video is playing
        if (videoPlayerContainer.getVisibility() == View.VISIBLE) {
            // If in landscape, rotate to portrait first
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

        // If in albums view
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

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Video continues playing, no restart needed
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