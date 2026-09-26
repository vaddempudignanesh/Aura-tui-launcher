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
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

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

    private static final String LOG_TAG = "gallery-tui";
    private final String idHash = Integer.toHexString(System.identityHashCode(this));

    private String src() {
        return "[GalleryActivity:" + idHash + "]";
    }

    private void log(String msg) {
        Log.d(LOG_TAG, src() + " " + msg);
    }

    // ===================== VIEW DECLARATIONS =====================
    private RecyclerView recyclerView;
    private RecyclerView albumRecycler;
    private GalleryAdapter adapter;

    private View fullscreenInfoHeader;
    private TextView fullscreenInfoName;
    private TextView fullscreenInfoDetails;
    private TextView fullscreenInfoPath;
    private boolean fullscreenChromeVisible = false;
    private int fullscreenCurrentIndex = 0;

    private AlbumAdapter albumAdapter;
    private List<MediaItem> mediaItems = new ArrayList<>();
    private List<MediaItem> displayedItems = new ArrayList<>();
    private List<String> albumList = new ArrayList<>();
    private boolean showAlbums = false;

    // Fullscreen viewer (in-activity overlay)
    private RelativeLayout fullscreenOverlay;
    private ViewPager2 fullscreenViewPager;
    private FullscreenAdapter fullscreenAdapter;
    private ImageButton btnCloseFullscreen;
    private final List<String> fullscreenMediaPaths = new ArrayList<>();
    private int fullscreenCurrentPosition = 0;

    // ★ Track the currently-visible CustomVideoView AND the page it belongs to
    private CustomVideoView currentFullscreenVideo = null;
    private int currentFullscreenVideoPosition = -1;
    private boolean currentFullscreenPageIsVideo = false;

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

    // Legacy in-gallery video player (unused now that fullscreen handles video)
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        log("========================================================");
        log("onCreate — activity hash=" + idHash);
        log("========================================================");
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

        binBottomBar = findViewById(R.id.binBottomBar);
        if (binBottomBar != null) {
            TextView btnRestore = findViewById(R.id.btnRestore);
            TextView btnDeletePermanent = findViewById(R.id.btnDeletePermanent);
            if (btnRestore != null) btnRestore.setOnClickListener(v -> restoreSelectedItems());
            if (btnDeletePermanent != null) btnDeletePermanent.setOnClickListener(v -> deletePermanentlySelectedItems());
        }

        ImageButton btnCloseSelection = findViewById(R.id.btnCloseSelection);
        if (btnCloseSelection != null) btnCloseSelection.setOnClickListener(v -> clearSelection());

        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        btnBack.setOnClickListener(v -> {
            if (showAlbums || currentAlbum != null) {
                navigateBackFromAlbum();
            } else {
                finish();
            }
        });

        TextView btnHome = findViewById(R.id.btnHome);
        TextView btnAlbums = findViewById(R.id.btnAlbums);
        TextView btnSort = findViewById(R.id.btnSort);
        TextView btnBinSelected = findViewById(R.id.btnBinSelected);
        TextView btnShareSelected = findViewById(R.id.btnShareSelected);
        TextView btnInfoSelected = findViewById(R.id.btnInfoSelected);
        TextView btnFavoriteSelected = findViewById(R.id.btnFavoriteSelected);

        TextView sortImages = findViewById(R.id.sortImages);
        TextView sortVideos = findViewById(R.id.sortVideos);
        TextView sortFavorites = findViewById(R.id.sortFavorites);
        TextView sortBin = findViewById(R.id.sortBin);

        btnHome.setOnClickListener(v -> {
            animateButtonBounce(v);
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
            animateButtonBounce(v);
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
            animateButtonBounce(v);
            sortOptions.setVisibility(sortOptions.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });

        // ===== FULLSCREEN OVERLAY =====
        fullscreenOverlay = findViewById(R.id.fullscreenOverlay);
        fullscreenViewPager = findViewById(R.id.fullscreenViewPager);
        btnCloseFullscreen = findViewById(R.id.btnCloseFullscreen);
        fullscreenInfoHeader = findViewById(R.id.fullscreenInfoHeader);
        fullscreenInfoName = findViewById(R.id.fullscreenInfoName);
        fullscreenInfoDetails = findViewById(R.id.fullscreenInfoDetails);
        fullscreenInfoPath = findViewById(R.id.fullscreenInfoPath);

        btnCloseFullscreen.setOnClickListener(v -> closeFullscreenViewer());

        // Create adapter with the (mutable) shared list
        fullscreenAdapter = new FullscreenAdapter(fullscreenMediaPaths, this);
        log("fullscreenAdapter created: " + Integer.toHexString(System.identityHashCode(fullscreenAdapter)));

        fullscreenAdapter.setTapCallback(this::toggleFullscreenChrome);

        fullscreenAdapter.setPageTypeCallback(new FullscreenAdapter.PageTypeCallback() {
            @Override
            public void onImageVisible(int position) {
                log("★★ onImageVisible pos=" + position
                        + " current=" + fullscreenCurrentPosition);

                // Only act if this image is the page we're actually on.
                // Images fire for off-screen adjacent pages too.
                if (position != fullscreenCurrentPosition) return;

                currentFullscreenPageIsVideo = false;
                if (currentFullscreenVideo != null) {
                    try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                }
            }

            @Override
            public void onVideoVisible(CustomVideoView videoView, int position) {
                log("★★ onVideoVisible pos=" + position
                        + " current=" + fullscreenCurrentPosition
                        + " videoView=" + Integer.toHexString(System.identityHashCode(videoView)));

                // The adapter fires this as soon as the MediaPlayer is prepared.
                // That can happen BEFORE onPageSelected for the same page
                // (ViewPager2 preloads adjacent pages).
                //
                // If the prepared video IS the current page, start it now.
                // Otherwise defer to onPageSelected, which will start it
                // when the page actually becomes current.
                if (position == fullscreenCurrentPosition) {
                    log("  video is for the current page — starting now");
                    currentFullscreenPageIsVideo = true;
                    currentFullscreenVideo = videoView;
                    currentFullscreenVideoPosition = position;
                    try {
                        videoView.start();
                        log("  isPlaying=" + videoView.isPlaying()
                                + " duration=" + videoView.getDuration());
                    } catch (Exception e) {
                        Log.e(LOG_TAG, src() + " videoView.start() threw", e);
                    }
                } else {
                    log("  video is for off-screen page " + position
                            + " — deferring start to onPageSelected");
                }
            }
        });

        fullscreenViewPager.setAdapter(fullscreenAdapter);
        fullscreenViewPager.setOffscreenPageLimit(1);

        fullscreenViewPager.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        log("onPageSelected: " + position
                                + " (was " + fullscreenCurrentPosition + ")");

                        // ★ Stop the previous video ONLY if it belongs to a
                        // different page than the one we're now on.
                        // Do NOT stop the video that belongs to the current
                        // page — that's the one we just started.
                        if (currentFullscreenVideo != null
                                && currentFullscreenVideoPosition != position) {
                            log("  stopping video from page "
                                    + currentFullscreenVideoPosition
                                    + " (now on " + position + ")");
                            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
                            currentFullscreenVideo = null;
                            currentFullscreenVideoPosition = -1;
                            currentFullscreenPageIsVideo = false;
                        }

                        // Update the "current page" pointer BEFORE starting
                        // the new video so any later onVideoVisible callback
                        // sees the right position.
                        fullscreenCurrentPosition = position;
                        fullscreenCurrentIndex = position;
                        updateFullscreenInfo(position);

                        // If the new page is a video, find its CustomVideoView
                        // and start it. This is the authoritative start point.
                        if (position >= 0 && position < fullscreenMediaPaths.size()) {
                            String path = fullscreenMediaPaths.get(position);
                            if (isVideoPath(path)) {
                                log("  new page is video — searching for its view");
                                findAndStartVideoForPosition(position);
                            }
                        }
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
        checkAndRequestMediaPermissions();

        View topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) updateTopNavBar();
    }

    // ===================== VIDEO HELPERS =====================

    /**
     * Returns true if the given path looks like a video file.
     * Used by both the adapter-facing code and the page-change logic.
     */
    private boolean isVideoPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".avi")
                || lower.endsWith(".mov")
                || lower.endsWith(".3gp")
                || lower.endsWith(".m4v")
                || lower.endsWith(".flv")
                || lower.endsWith(".wmv");
    }

    /**
     * Walk the ViewPager2's child RecyclerView, find the CustomVideoView
     * bound to the given adapter position, and start playback on it.
     *
     * Retries a few times if the view isn't laid out yet (ViewPager2 may
     * still be attaching/measuring the page when onPageSelected fires).
     */
    private void findAndStartVideoForPosition(int targetPosition) {
        try {
            RecyclerView rv = (RecyclerView) fullscreenViewPager.getChildAt(0);
            if (rv == null) {
                log("  findAndStartVideo: RecyclerView not ready, retrying");
                fullscreenViewPager.post(() -> {
                    if (fullscreenCurrentPosition == targetPosition) {
                        findAndStartVideoForPosition(targetPosition);
                    }
                });
                return;
            }

            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
                if (vh != null && vh.getBindingAdapterPosition() == targetPosition) {
                    CustomVideoView vv = child.findViewById(R.id.fullscreen_video);
                    if (vv != null && vv.getVisibility() == View.VISIBLE) {
                        // Idempotent: if we already started this exact view
                        // for this exact position, do nothing.
                        if (currentFullscreenVideo == vv
                                && currentFullscreenVideoPosition == targetPosition) {
                            log("  findAndStartVideo: already on this video, skipping");
                            return;
                        }

                        log("  findAndStartVideo: starting vv="
                                + Integer.toHexString(System.identityHashCode(vv))
                                + " at pos=" + targetPosition);
                        currentFullscreenVideo = vv;
                        currentFullscreenVideoPosition = targetPosition;
                        currentFullscreenPageIsVideo = true;
                        vv.start();
                        log("  started, isPlaying=" + vv.isPlaying()
                                + " duration=" + vv.getDuration());
                        return;
                    }
                }
            }

            // Not found — retry on the next frame. This happens when
            // ViewPager2 hasn't finished attaching the page yet.
            log("  findAndStartVideo: no visible video view for pos="
                    + targetPosition + " (retrying)");
            fullscreenViewPager.post(() -> {
                if (fullscreenCurrentPosition == targetPosition) {
                    findAndStartVideoForPosition(targetPosition);
                }
            });
        } catch (Exception e) {
            Log.e(LOG_TAG, src() + " findAndStartVideo threw", e);
        }
    }

    // ===================== FULLSCREEN OPEN/CLOSE =====================

    private void openFullscreenViewer(String path) {
        log("openFullscreenViewer: " + path);

        fullscreenMediaPaths.clear();
        int currentIndex = 0;

        for (int i = 0; i < displayedItems.size(); i++) {
            MediaItem item = displayedItems.get(i);
            if (!item.isTrashed) {
                fullscreenMediaPaths.add(item.path);
                if (item.path.equals(path)) {
                    currentIndex = fullscreenMediaPaths.size() - 1;
                }
            }
        }

        log("  built fullscreenMediaPaths size=" + fullscreenMediaPaths.size()
                + " currentIndex=" + currentIndex);

        if (fullscreenMediaPaths.isEmpty()) {
            Toast.makeText(this, "No media to view", Toast.LENGTH_SHORT).show();
            return;
        }

        // Reset tracking state BEFORE showing the overlay
        fullscreenCurrentPosition = currentIndex;
        fullscreenCurrentIndex = currentIndex;
        currentFullscreenVideo = null;
        currentFullscreenVideoPosition = -1;
        currentFullscreenPageIsVideo = false;

        // Rebind the adapter with the new list
        fullscreenAdapter.notifyDataSetChanged();
        fullscreenViewPager.setCurrentItem(currentIndex, false);

        fullscreenOverlay.setVisibility(View.VISIBLE);
        fullscreenOverlay.bringToFront();

        setFullscreenChromeVisible(false);
        updateFullscreenInfo(currentIndex);

        bottomBar.setVisibility(View.GONE);
        log("  fullscreenOverlay visible");

        // If the initial page is a video, start it directly.
        // (setCurrentItem(false) may not fire onPageSelected if the
        //  position is unchanged from the pager's current state.)
        String initialPath = fullscreenMediaPaths.get(currentIndex);
        if (isVideoPath(initialPath)) {
            log("  initial page is video, scheduling start");
            int finalCurrentIndex = currentIndex;
            fullscreenViewPager.post(() -> findAndStartVideoForPosition(finalCurrentIndex));
        }
    }

    private void closeFullscreenViewer() {
        log("closeFullscreenViewer");

        // Stop the tracked video
        if (currentFullscreenVideo != null) {
            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
            currentFullscreenVideo = null;
        }
        currentFullscreenVideoPosition = -1;
        currentFullscreenPageIsVideo = false;

        // Belt-and-braces: walk the pager's children and stop any video
        try {
            RecyclerView rv = (RecyclerView) fullscreenViewPager.getChildAt(0);
            if (rv != null) {
                for (int i = 0; i < rv.getChildCount(); i++) {
                    View child = rv.getChildAt(i);
                    if (child != null) {
                        CustomVideoView vv = child.findViewById(R.id.fullscreen_video);
                        if (vv != null) vv.stopPlayback();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, src() + " error while stopping videos", e);
        }

        fullscreenOverlay.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);
    }

    private void toggleFullscreenChrome() {
        setFullscreenChromeVisible(!fullscreenChromeVisible);
    }

    private void setFullscreenChromeVisible(boolean visible) {
        fullscreenChromeVisible = visible;
        if (fullscreenInfoHeader != null) {
            fullscreenInfoHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (btnCloseFullscreen != null) {
            btnCloseFullscreen.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateFullscreenInfo(int position) {
        if (position < 0 || position >= fullscreenMediaPaths.size()) return;
        String path = fullscreenMediaPaths.get(position);
        File file = new File(path);

        if (fullscreenInfoName != null) fullscreenInfoName.setText(file.getName());
        if (fullscreenInfoDetails != null) {
            String size = file.exists() ? formatFileSize(file.length()) : "—";
            fullscreenInfoDetails.setText(getFileType(path) + " · " + size);
        }
        if (fullscreenInfoPath != null) {
            String parent = file.getParent();
            fullscreenInfoPath.setText(parent != null ? parent : "");
        }
    }

    private void animateButtonBounce(View button) {
        if (button == null) return;
        android.view.animation.Animation bounce =
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.bounce_animation);
        button.startAnimation(bounce);
    }

    private void updateTopNavBar() {
        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        TextView title = findViewById(R.id.titleGallery);
        if (btnBack == null || title == null) return;

        boolean showBack = currentFilter == FilterMode.BIN || currentAlbum != null || showAlbums;
        btnBack.setVisibility(showBack ? View.VISIBLE : View.GONE);

        if (currentFilter == FilterMode.BIN) title.setText("🗑️ Bin");
        else if (currentAlbum != null) title.setText("📁 " + currentAlbum);
        else if (showAlbums) title.setText("📁 Albums");
        else title.setText("Gallery");

        btnBack.setOnClickListener(v -> {
            if (currentFilter == FilterMode.BIN) {
                currentFilter = FilterMode.ALL;
                applyFilter();
                updateTopNavBar();
                updateBarsVisibility();
            } else if (currentAlbum != null || showAlbums) {
                navigateBackFromAlbum();
            } else {
                finish();
            }
        });
    }

    // ===================== BARS VISIBILITY =====================

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

    private void showEmptyState() {
        View emptyView = findViewById(R.id.emptyStateContainer);
        if (emptyView == null) return;

        if (displayedItems.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            TextView emptyText = findViewById(R.id.emptyStateText);
            if (emptyText != null) {
                emptyText.setText(currentFilter == FilterMode.BIN
                        ? "No files in Bin" : "No media found");
            }
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
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
                        imageProjection, null, null, null);
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
                    if (isActivityAlive()) { applyFilter(); setupRecyclerView(); }
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
                        videoProjection, null, null, null);
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

    // ===================== BIN SCAN =====================

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
                "/storage/extSdCard", "/storage/sdcard1", "/storage/external_SD",
                "/storage/emulated/0", "/mnt/extSdCard", "/mnt/sdcard1",
                "/mnt/external_sd", "/sdcard1", "/external_sd"
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
                            if ((dcim.exists() || pictures.exists() || movies.exists() || downloads.exists())
                                    && !normalizedPaths.contains(normalizedPath)) {
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
                            if (path.contains("storage") && !path.contains("emulated") && !path.contains("self")) {
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
        if (depth > maxDepth || directory == null || !directory.exists() || !directory.isDirectory()) return;

        try {
            try {
                String canonicalPath = directory.getCanonicalPath();
                if (canonicalPath.equals("/storage/emulated/0")
                        && !directory.getAbsolutePath().equals("/storage/emulated/0")) return;
            } catch (Exception ignored) {}

            File[] files = directory.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file.isDirectory()) {
                    String name = file.getName().toLowerCase();
                    if (!name.startsWith(".") && !name.equals("android") && !name.equals("system")
                            && !name.equals("cache") && !name.equals("tmp") && !name.equals("lost+found")
                            && !name.equals("app") && !name.equals("data") && !name.equals("obb")
                            && !name.equals("media")) {
                        scanDirectoryForTrashedFiles(file, items, depth + 1, maxDepth);
                    }
                } else {
                    String fileName = file.getName().toLowerCase();
                    if (file.getName().contains(".trashed.")) {
                        boolean isImage = fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                                || fileName.endsWith(".png") || fileName.endsWith(".gif")
                                || fileName.endsWith(".bmp") || fileName.endsWith(".webp")
                                || fileName.endsWith(".heic") || fileName.endsWith(".heif");
                        boolean isVideo = fileName.endsWith(".mp4") || fileName.endsWith(".avi")
                                || fileName.endsWith(".mkv") || fileName.endsWith(".mov")
                                || fileName.endsWith(".wmv") || fileName.endsWith(".flv")
                                || fileName.endsWith(".3gp") || fileName.endsWith(".m4v")
                                || fileName.endsWith(".webm");

                        if (isImage || isVideo) {
                            String path = file.getAbsolutePath();
                            boolean exists = false;
                            for (MediaItem item : items) {
                                if (item.path.equals(path)) { exists = true; break; }
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

    // ===================== FILTER =====================

    private void applyFilter() {
        if (!isActivityAlive()) return;
        displayedItems.clear();

        if (currentFilter == FilterMode.BIN) {
            List<MediaItem> trashedItems = scanForTrashedFiles();
            for (MediaItem item : mediaItems) {
                if (item.path.contains(".trashed.")) {
                    boolean exists = false;
                    for (MediaItem existing : trashedItems) {
                        if (existing.path.equals(item.path)) { exists = true; break; }
                    }
                    if (!exists) trashedItems.add(item);
                }
            }
            displayedItems.addAll(trashedItems);
        } else {
            for (MediaItem item : mediaItems) {
                boolean matchesAlbum = currentAlbum == null
                        || (item.album != null && item.album.equals(currentAlbum));
                if (!matchesAlbum) continue;

                boolean isCurrentlyTrashed = item.path.contains(".trashed.");
                item.isTrashed = isCurrentlyTrashed;

                switch (currentFilter) {
                    case ALL: if (!isCurrentlyTrashed) displayedItems.add(item); break;
                    case IMAGES: if (!isCurrentlyTrashed && item.type == MediaItem.TYPE_IMAGE) displayedItems.add(item); break;
                    case VIDEOS: if (!isCurrentlyTrashed && item.type == MediaItem.TYPE_VIDEO) displayedItems.add(item); break;
                    case FAVORITES: if (!isCurrentlyTrashed && item.isFavorite) displayedItems.add(item); break;
                    default: break;
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
            permissionsToRequest = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }

        boolean allGranted = true;
        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) loadMedia();
        else requestMultiplePermissionsLauncher.launch(permissionsToRequest);
    }

    // ===================== RECYCLER VIEW =====================

    private void setupRecyclerView() {
        if (!isActivityAlive()) return;
        if (displayedItems.isEmpty()) { showEmptyState(); return; }

        adapter = new GalleryAdapter(this, displayedItems, selectedItems, new GalleryAdapter.OnItemClickListener() {
            @Override public void onFavoriteToggle(MediaItem item) {
                if (isActivityAlive()) { item.isFavorite = !item.isFavorite; applyFilter(); }
            }

            @Override public void onDelete(MediaItem item) {
                if (isActivityAlive()) moveToTrash(item);
            }

            @Override public void onItemClick(String path) {
                if (isActivityAlive()) toggleSelection(path);
            }

            @Override public boolean isSelectionMode() {
                return selectionMode && isActivityAlive();
            }

            @Override public void onImageClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN) {
                    openFullscreenViewer(path);
                }
            }

            @Override public void onVideoClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN) {
                    openFullscreenViewer(path);
                }
            }

            @Override public void onRestore(MediaItem item) {
                if (isActivityAlive()) restoreFromTrash(item);
            }
        });

        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(40);
        recyclerView.setItemAnimator(null);
    }

    // ===================== TRASH =====================

    private void moveToTrash(MediaItem item) {
        if (item.isTrashed) return;
        File file = new File(item.path);
        if (!file.exists()) return;

        String parent = file.getParent();
        String name = file.getName();
        String cleanName = cleanFileName(name);
        File trashedFile = new File(parent, ".trashed." + cleanName);

        if (trashedFile.exists()) {
            int count = 1;
            String newName;
            File newFile = trashedFile;
            while (newFile.exists()) {
                int dotIndex = cleanName.lastIndexOf(".");
                if (dotIndex > 0) {
                    String baseName = cleanName.substring(0, dotIndex);
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
                restoredFile = new File(parent, baseName + "_" + count + ext);
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
                if (item.path.equals(path)) { moveToTrash(item); break; }
            }
        }
        clearSelection();
        applyFilter();
        Toast.makeText(this, "Moved to Bin", Toast.LENGTH_SHORT).show();
    }

    private void restoreSelectedItems() {
        if (selectedItems.isEmpty()) return;
        List<MediaItem> itemsToRestore = new ArrayList<>();
        for (String path : selectedItems) {
            for (MediaItem item : mediaItems) {
                if (item.path.equals(path) && item.isTrashed) { itemsToRestore.add(item); break; }
            }
        }
        for (MediaItem item : itemsToRestore) restoreFromTrash(item);
        clearSelection();
        applyFilter();
        Toast.makeText(this, "Restored " + itemsToRestore.size() + " items", Toast.LENGTH_SHORT).show();
    }

    private void deletePermanentlySelectedItems() {
        if (selectedItems.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete Permanently")
                .setMessage("Are you sure? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    for (String path : selectedItems) {
                        File file = new File(path);
                        if (file.exists()) file.delete();
                        for (int i = mediaItems.size() - 1; i >= 0; i--) {
                            if (mediaItems.get(i).path.equals(path)) { mediaItems.remove(i); break; }
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
        while (name.startsWith(".trashed.")) name = name.substring(9);
        return name;
    }

    // ===================== SELECTION =====================

    private void toggleSelection(String path) {
        if (selectedItems.contains(path)) selectedItems.remove(path);
        else selectedItems.add(path);
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
                if (btnBin != null) btnBin.setText("🗑️ Bin (" + selectedItems.size() + ")");
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
        if (adapter != null) adapter.updateSelectedItems(selectedItems);
    }

    // ===================== SHARE / INFO =====================

    private void shareSelectedItems() {
        if (selectedItems.isEmpty()) return;
        try {
            if (selectedItems.size() == 1) {
                File file = new File(selectedItems.get(0));
                if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return; }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
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
                        uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
                    }
                }
                if (uris.isEmpty()) { Toast.makeText(this, "No valid files", Toast.LENGTH_SHORT).show(); return; }
                Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("*/*");
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Share files"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String path) {
        String ext = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "mp4": return "video/mp4";
            case "avi": return "video/avi";
            case "mkv": return "video/x-matroska";
            case "mp3": return "audio/mpeg";
            default: return "*/*";
        }
    }

    private void showSelectedItemInfo() {
        if (selectedItems.isEmpty()) return;
        if (selectedItems.size() == 1) {
            String path = selectedItems.get(0);
            File file = new File(path);
            if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return; }
            StringBuilder info = new StringBuilder();
            info.append("📁 Type: ").append(getFileType(path)).append("\n");
            info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
            info.append("📅 Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                    .format(new Date(file.lastModified()))).append("\n");
            info.append("📍 Path: ").append(file.getAbsolutePath());
            showInfoDialog(info.toString(), "📄 File Info");
        } else {
            long totalSize = 0;
            int imageCount = 0, videoCount = 0;
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
            info.append("🎬 Videos: ").append(videoCount);
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
        if (infoClose != null) infoClose.setOnClickListener(v -> dialog.dismiss());
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                WindowManager.LayoutParams.WRAP_CONTENT);
        dialog.show();
    }

    // ===================== LEGACY VIDEO CONTROLS (unused) =====================

    private void setupVideoControls() {
        if (videoPlayerContainer == null) return;
        videoPlayerContainer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) { toggleControlsVisibility(); return true; }
            return false;
        });
        if (videoView != null) {
            videoView.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) { toggleControlsVisibility(); return true; }
                return false;
            });
            videoView.setOnCompletionListener(mp -> {
                if (btnPlayPause != null) btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                if (btnCenterPlayPause != null) btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
                isPlaying = false;
                showControls();
            });
        }
        if (btnCenterPlayPause != null) btnCenterPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());
    }

    private void toggleControlsVisibility() {
        if (controlsVisible) hideControls(); else showControls();
    }

    private void showControls() {
        controlsVisible = true;
        if (videoCenterControls != null) videoCenterControls.setVisibility(View.VISIBLE);
        if (videoBottomControls != null) videoBottomControls.setVisibility(View.VISIBLE);
        if (videoTime != null) videoTime.setVisibility(View.VISIBLE);
        videoHandler.removeCallbacks(hideControlsRunnable);
        hideControlsRunnable = () -> { if (isPlaying) hideControls(); };
        videoHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
    }

    private void hideControls() {
        controlsVisible = false;
        if (videoCenterControls != null) videoCenterControls.setVisibility(View.GONE);
        if (videoBottomControls != null) videoBottomControls.setVisibility(View.GONE);
        if (videoTime != null) videoTime.setVisibility(View.GONE);
        videoHandler.removeCallbacks(hideControlsRunnable);
    }

    private void togglePlayPause() {
        if (videoView == null) return;
        if (isPlaying) {
            videoView.pause();
            if (btnPlayPause != null) btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            if (btnCenterPlayPause != null) btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            isPlaying = false;
        } else {
            videoView.start();
            if (btnPlayPause != null) btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            if (btnCenterPlayPause != null) btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            isPlaying = true;
        }
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
                    projection, null, null,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range") String album = cursor.getString(
                            cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME));
                    if (album != null && !album.isEmpty() && !albums.contains(album)) albums.add(album);
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
        if (albumList.isEmpty()) { Toast.makeText(this, "No albums found", Toast.LENGTH_SHORT).show(); return; }
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

    // ===================== UTIL =====================

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

    private String normalizePath(String path) {
        try { return new File(path).getCanonicalPath(); }
        catch (Exception e) { return path; }
    }

    private void addSelectedToFavorites() {
        if (selectedItems.isEmpty()) return;
        for (String path : selectedItems) {
            for (MediaItem item : mediaItems) {
                if (item.path.equals(path) && !item.isTrashed) { item.isFavorite = true; break; }
            }
        }
        clearSelection();
        applyFilter();
    }

    // ===================== LIFECYCLE =====================

    @Override
    public void onBackPressed() {
        if (fullscreenOverlay != null && fullscreenOverlay.getVisibility() == View.VISIBLE) {
            closeFullscreenViewer();
            return;
        }
        if (videoPlayerContainer != null && videoPlayerContainer.getVisibility() == View.VISIBLE) {
            closeVideoInternal();
            return;
        }
        if (currentAlbum != null || showAlbums) { navigateBackFromAlbum(); return; }
        if (selectionMode) { clearSelection(); return; }
        super.onBackPressed();
        finish();
    }

    private void closeVideoInternal() {
        if (videoView != null) videoView.stopPlayback();
        if (videoPlayerContainer != null) videoPlayerContainer.setVisibility(View.GONE);
        isPlaying = false;
        isLandscape = false;
        videoHandler.removeCallbacks(updateVideoProgress);
        videoHandler.removeCallbacks(hideControlsRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("onDestroy");
        isVideoPlaying = false;
        if (videoView != null) videoView.stopPlayback();
        if (currentFullscreenVideo != null) {
            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
            currentFullscreenVideo = null;
        }
        currentFullscreenVideoPosition = -1;
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
}