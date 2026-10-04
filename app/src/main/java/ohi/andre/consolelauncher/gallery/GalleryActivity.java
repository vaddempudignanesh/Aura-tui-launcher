package ohi.andre.consolelauncher.gallery;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
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
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ohi.andre.consolelauncher.R;

public class GalleryActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "gallery_prefs";
    private static final String PREF_FAVORITES = "favorite_paths";
    private SharedPreferences prefs;

    private final Handler mediaRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingMediaRefresh;
    private static final long MEDIA_REFRESH_DEBOUNCE_MS = 400L;

    private RecyclerView recyclerView;
    private RecyclerView albumRecycler;
    private GalleryAdapter adapter;
    private ProgressBar loadingSpinner;

    private static final int AUTO_SCROLL_EDGE_DP = 80;
    private static final long AUTO_SCROLL_TICK_MS = 16L;
    private boolean autoScrollActive = false;
    private int autoScrollDx = 0;
    private int autoScrollDy = 0;

    private TextView mediaHudVolume;
    private TextView mediaHudBrightness;
    private Runnable mediaHudHideRunnable;
    private static final long MEDIA_HUD_VISIBLE_MS = 800L;

    private View fullscreenInfoHeader;
    private TextView fullscreenInfoName;
    private TextView fullscreenInfoDetails;
    private TextView fullscreenInfoPath;
    private boolean fullscreenChromeVisible = false;

    private boolean dragSelectActive = false;
    private boolean dragSelectDeselectMode = false;
    private String dragAnchorPath = null;
    private final Set<Integer> dragVisitedPositions = new HashSet<>();

    private float dragLastY = -1f;
    private float dragLastX = -1f;
    private int fullscreenCurrentIndex = 0;

    private AlbumAdapter albumAdapter;
    private final List<MediaItem> mediaItems = new ArrayList<>();
    private List<MediaItem> displayedItems = new ArrayList<>();
    private final List<String> albumList = new ArrayList<>();
    private final LinkedHashMap<String, String> albumDisplayNames = new LinkedHashMap<>();
    private boolean showAlbums = false;

    private final HashMap<String, String> canonicalAlbumCache = new HashMap<>();
    private boolean albumsCacheValid = false;

    private final LinkedHashMap<String, MediaItem> trashedByPath = new LinkedHashMap<>();
    private boolean trashedIndexDirty = true;
    private boolean whatsappOnly = false;
    private String whatsappPath = null;

    private static final int GRID_COLUMNS = 3;
    private final Set<String> selectionBeforeDrag = new HashSet<>();
    private final Set<String> dragRectanglePaths = new HashSet<>();

    private RelativeLayout fullscreenOverlay;
    private ViewPager2 fullscreenViewPager;

    private int overlayPendingSeekMs = -1;
    private int savedVideoPositionMs = -1;
    private String savedVideoPath = null;
    private boolean savedVideoWasPlaying = false;
    private FullscreenAdapter fullscreenAdapter;
    private final List<String> fullscreenMediaPaths = new ArrayList<>();
    private int fullscreenCurrentPosition = 0;

    private CustomVideoView currentFullscreenVideo = null;
    private int currentFullscreenVideoPosition = -1;
    private boolean currentFullscreenPageIsVideo = false;

    private LinearLayout videoControlContainer;
    private ImageButton btnCenterPlayPause;
    private ImageButton btnSkipForwardOverlay;
    private ImageButton btnSkipBackwardOverlay;
    private LinearLayout fullscreenBottomBar;
    private View fsBtnBin;
    private View fsBtnShare;
    private View fsBtnInfo;
    private View fsBtnFavorite;
    private ImageView fsBtnFavoriteIcon;
    private View videoControlsOverlay;
    private ImageButton btnFavoriteOverlay;
    private ImageButton btnInfoOverlay;
    private ImageButton btnDeleteOverlay;
    private ImageButton btnRotateOverlay;
    private TextView videoTimeCurrent;
    private TextView videoTimeTotal;
    private TextView videoTitleOverlay;
    private SeekBar videoSeekBar;
    private boolean isOverlayVideoPlaying = false;
    private boolean overlayControlsVisible = false;
    private Runnable overlayHideControlsRunnable;
    private Runnable overlayProgressRunnable;

    private int dragLastRectMinRow = -1;
    private int dragLastRectMaxRow = -1;
    private int dragLastRectMinCol = -1;
    private int dragLastRectMaxCol = -1;
    private GestureDetector overlayTapDetector;

    private GestureDetector overlayVideoGestureDetector;
    private android.view.View.OnTouchListener volumeBrightnessHandler;
    private float playbackSpeedBeforeLongPress = 1.0f;
    private static final float LONG_PRESS_SPEED = 2.0f;

    private static final int OVERLAY_CONTROLS_TIMEOUT = 3000;
    private static final int SKIP_FORWARD_MS = 10000;
    private static final int SKIP_BACKWARD_MS = 10000;

    private TextView titleView;
    private LinearLayout sortOptions;
    private String currentAlbum = null;
    private boolean selectionMode = false;
    private final List<String> selectedItems = new ArrayList<>();
    private LinearLayout bottomBar, selectionBar, binBottomBar;
    private View selectionTopBar;
    private TextView selectionCount;

    private enum FilterMode { ALL, IMAGES, VIDEOS, FAVORITES, BIN }
    private FilterMode currentFilter = FilterMode.ALL;

    private RelativeLayout videoPlayerContainer;
    private VideoView videoView;
    private ImageButton btnPlayPause, btnCloseVideo;
    private TextView videoTime;
    private LinearLayout videoCenterControls, videoBottomControls;
    private final Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable updateVideoProgress;
    private Runnable hideControlsRunnable;
    private boolean isLandscape = false;
    private boolean isPlaying = false;
    private boolean isVideoPlaying = false;
    private boolean controlsVisible = true;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean isGranted = true;
                        for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                            if (!entry.getValue()) { isGranted = false; break; }
                        }
                        if (isGranted) loadMedia();
                        else {
                            Toast.makeText(this, "Permission denied.", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });

    private boolean isActivityAlive() { return !isFinishing() && !isDestroyed(); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(getWindow().getAttributes());
        }
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        recyclerView = findViewById(R.id.galleryRecycler);
        albumRecycler = findViewById(R.id.albumRecycler);
        titleView = findViewById(R.id.titleGallery);
        sortOptions = findViewById(R.id.sortOptions);
        videoPlayerContainer = findViewById(R.id.videoPlayerContainer);
        videoView = findViewById(R.id.videoView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnCloseVideo = findViewById(R.id.btnCloseVideo);
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
            if (showAlbums || currentAlbum != null) navigateBackFromAlbum();
            else finish();
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
            currentFilter = FilterMode.ALL;
            currentAlbum = null;
            whatsappOnly = false;
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
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
                albumRecycler.setVisibility(View.GONE);
                recyclerView.setVisibility(View.GONE);
                loadAlbums();
                sortOptions.setVisibility(View.GONE);
            }
            updateBarsVisibility();
        });

        btnSort.setOnClickListener(v -> sortOptions.setVisibility(
                sortOptions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        fullscreenOverlay = findViewById(R.id.fullscreenOverlay);
        fullscreenViewPager = findViewById(R.id.fullscreenViewPager);
        fullscreenInfoHeader = findViewById(R.id.fullscreenInfoHeader);
        fullscreenInfoName = findViewById(R.id.fullscreenInfoName);
        fullscreenInfoDetails = findViewById(R.id.fullscreenInfoDetails);
        fullscreenInfoPath = findViewById(R.id.fullscreenInfoPath);
        loadingSpinner = findViewById(R.id.loadingSpinner);
        if (loadingSpinner != null) {
            int strokePx = Math.round(3 * getResources().getDisplayMetrics().density);
            loadingSpinner.setIndeterminateDrawable(
                    new HexRingDrawable(strokePx, Color.WHITE));
        }
        videoControlContainer = findViewById(R.id.videoControlContainer);
        btnCenterPlayPause = findViewById(R.id.btnCenterPlayPause);
        btnSkipForwardOverlay = findViewById(R.id.btnSkipForward);
        btnSkipBackwardOverlay = findViewById(R.id.btnSkipBackward);
        videoTimeCurrent = findViewById(R.id.videoTimeCurrent);
        videoTimeTotal = findViewById(R.id.videoTimeTotal);
        mediaHudVolume = findViewById(R.id.mediaHudVolume);
        mediaHudBrightness = findViewById(R.id.mediaHudBrightness);
        videoSeekBar = findViewById(R.id.videoSeekBar);
        videoControlsOverlay = findViewById(R.id.videoControlsOverlay);
        fullscreenBottomBar = findViewById(R.id.fullscreenBottomBar);
        fsBtnBin = findViewById(R.id.fsBtnBin);
        fsBtnShare = findViewById(R.id.fsBtnShare);
        fsBtnInfo = findViewById(R.id.fsBtnInfo);
        fsBtnFavorite = findViewById(R.id.fsBtnFavorite);
        fsBtnFavoriteIcon = findViewById(R.id.fsBtnFavoriteIcon);

        btnFavoriteOverlay = findViewById(R.id.btnFavorite);
        btnInfoOverlay = findViewById(R.id.btnInfo);
        btnDeleteOverlay = findViewById(R.id.btnDelete);
        btnRotateOverlay = findViewById(R.id.btnRotate);

        if (fsBtnBin != null) fsBtnBin.setOnClickListener(v -> {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(pos);
                for (MediaItem item : mediaItems) {
                    if (item.path.equals(path)) { moveToTrash(item); break; }
                }
                closeFullscreenViewer();
                scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);
            }
        });

        if (fsBtnShare != null) fsBtnShare.setOnClickListener(v -> {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(pos);
                List<String> saved = new ArrayList<>(selectedItems);
                selectedItems.clear();
                selectedItems.add(path);
                shareSelectedItems();
                selectedItems.clear();
                selectedItems.addAll(saved);
            }
        });

        if (fsBtnInfo != null) fsBtnInfo.setOnClickListener(v -> {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                showFileInfoDialog(fullscreenMediaPaths.get(pos));
            }
        });

        if (fsBtnFavorite != null) fsBtnFavorite.setOnClickListener(v -> {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(pos);
                boolean nowFav = toggleFavoriteForPath(path);
                updateFullscreenFavoriteIcon(path);
                applyFilter();
                Toast.makeText(this,
                        nowFav ? "Added to Favorites" : "Removed from Favorites",
                        Toast.LENGTH_SHORT).show();
            }
        });

        overlayTapDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(MotionEvent e) {
                        onFullscreenTap();
                        return true;
                    }
                });

        overlayVideoGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) { return true; }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (!currentFullscreenPageIsVideo || currentFullscreenVideo == null) return false;
                        float tapX = e.getX();
                        float w = fullscreenOverlay.getWidth();
                        if (w <= 0) return false;
                        float leftThird  = w / 3f;
                        float rightThird = w * 2f / 3f;
                        if (tapX < leftThird) skipByMs(-SKIP_BACKWARD_MS);
                        else if (tapX > rightThird) skipByMs(SKIP_FORWARD_MS);
                        else toggleOverlayPlayPause();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (!currentFullscreenPageIsVideo || currentFullscreenVideo == null) return;
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
                        try {
                            if (!currentFullscreenVideo.isPlaying()) return;
                        } catch (Exception ex) { return; }
                        try {
                            android.media.PlaybackParams params =
                                    currentFullscreenVideo.getPlaybackParams();
                            if (params != null) {
                                playbackSpeedBeforeLongPress = params.getSpeed();
                            } else {
                                params = new android.media.PlaybackParams();
                            }
                            params.setSpeed(LONG_PRESS_SPEED);
                            currentFullscreenVideo.setPlaybackParams(params);
                        } catch (Exception ignored) {}
                    }
                });

        setupOverlayVideoControls();

        fullscreenAdapter = new FullscreenAdapter(fullscreenMediaPaths, this);
        fullscreenAdapter.setTapCallback(this::onFullscreenTap);

        fullscreenAdapter.setVideoTouchForwarder(event -> {
            if (overlayVideoGestureDetector != null) {
                overlayVideoGestureDetector.onTouchEvent(event);
            }
            if (volumeBrightnessHandler != null) {
                volumeBrightnessHandler.onTouch(fullscreenOverlay, event);
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                resetPlaybackSpeed();
            }
        });

        setupVolumeAndBrightnessGestures();

        fullscreenAdapter.setPageTypeCallback(new FullscreenAdapter.PageTypeCallback() {
            @Override
            public void onImageVisible(int position) {
                if (position != fullscreenCurrentPosition) return;
                currentFullscreenPageIsVideo = false;
                isOverlayVideoPlaying = false;
                if (currentFullscreenVideo != null) {
                    try { currentFullscreenVideo.pause(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                }
                videoHandler.removeCallbacks(overlayProgressRunnable);

                // Belt-and-braces: hide the video control container
                // in case the page-change callback already missed it.
                if (videoControlContainer != null) {
                    videoControlContainer.setVisibility(View.GONE);
                }
                overlayControlsVisible = false;
            }

            @Override
            public void onVideoVisible(CustomVideoView videoView, int position) {
                if (position != fullscreenCurrentPosition) return;

                currentFullscreenPageIsVideo = true;
                currentFullscreenVideo = videoView;
                currentFullscreenVideoPosition = position;
                overlayPendingSeekMs = -1;

                String path = (position >= 0 && position < fullscreenMediaPaths.size())
                        ? fullscreenMediaPaths.get(position) : null;

                int restoreMs = -1;
                boolean restorePlaying = true;
                if (path != null && path.equals(savedVideoPath) && savedVideoPositionMs >= 0) {
                    restoreMs = savedVideoPositionMs;
                    restorePlaying = savedVideoWasPlaying;
                }

                try {
                    if (restoreMs > 0) videoView.seekTo(restoreMs);
                } catch (Exception ignored) {}

                try {
                    if (restorePlaying) videoView.start();
                    else videoView.pause();
                } catch (Exception ignored) {}

                syncOverlayPlayPauseIcon();

                updateOverlayTitle();
                updateOverlaySeekBar();
                startOverlayProgressUpdate();

                if (!userIsSwiping) {
                    if (videoControlContainer != null) {
                        videoControlContainer.setVisibility(View.VISIBLE);
                    }
                    setFullscreenChromeVisible(true);
                    overlayControlsVisible = true;
                }
            }
        });

        fullscreenViewPager.setAdapter(fullscreenAdapter);
        fullscreenViewPager.setOffscreenPageLimit(1);

        fullscreenViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                userIsSwiping = (state != ViewPager2.SCROLL_STATE_IDLE);
            }

            @Override
            public void onPageSelected(int position) {
                // Always tear down the previous video's state
                if (currentFullscreenVideo != null
                        && currentFullscreenVideoPosition != position) {
                    try { currentFullscreenVideo.pause(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                }
                videoHandler.removeCallbacks(overlayProgressRunnable);

                overlayPendingSeekMs = -1;
                fullscreenCurrentPosition = position;
                fullscreenCurrentIndex = position;

                // Look at the destination page: is it a video or an image?
                String path = (position >= 0 && position < fullscreenMediaPaths.size())
                        ? fullscreenMediaPaths.get(position) : null;
                boolean targetIsVideo = isVideoPath(path);

                if (targetIsVideo) {
                    currentFullscreenPageIsVideo = true;
                    // showAllControlsWithTimeout() is called later by onVideoVisible
                } else {
                    // Landing on an image → hide video controls immediately
                    currentFullscreenPageIsVideo = false;
                    isOverlayVideoPlaying = false;
                    if (videoControlContainer != null) {
                        videoControlContainer.setVisibility(View.GONE);
                    }
                    overlayControlsVisible = false;
                }

                updateFullscreenInfo(position);

                if (targetIsVideo) {
                    findAndStartVideoForPosition(position);
                }
            }
        });

        btnBinSelected.setOnClickListener(v -> moveSelectedToTrash());
        btnShareSelected.setOnClickListener(v -> shareSelectedItems());
        btnInfoSelected.setOnClickListener(v -> showSelectedItemInfo());
        btnFavoriteSelected.setOnClickListener(v -> addSelectedToFavorites());

        sortImages.setOnClickListener(v -> {
            currentFilter = FilterMode.IMAGES; currentAlbum = null;
            showAlbums = false;
            whatsappOnly = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            titleView.setText("Images");
            sortOptions.setVisibility(View.GONE);
            applyFilter();
            updateBarsVisibility();
            updateTopNavBar();
        });
        sortVideos.setOnClickListener(v -> {
            currentFilter = FilterMode.VIDEOS; currentAlbum = null;
            showAlbums = false;
            whatsappOnly = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            titleView.setText("Videos");
            sortOptions.setVisibility(View.GONE);
            applyFilter();
            updateBarsVisibility();
            updateTopNavBar();
        });
        sortFavorites.setOnClickListener(v -> {
            currentFilter = FilterMode.FAVORITES; currentAlbum = null;
            showAlbums = false;
            whatsappOnly = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            titleView.setText("Favorites");
            sortOptions.setVisibility(View.GONE);
            applyFilter();
            updateBarsVisibility();
            updateTopNavBar();
        });
        sortBin.setOnClickListener(v -> {
            currentFilter = FilterMode.BIN; currentAlbum = null;
            showAlbums = false;
            whatsappOnly = false;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            titleView.setText("Bin");
            sortOptions.setVisibility(View.GONE);
            applyFilter();
            updateBarsVisibility();
            updateTopNavBar();
        });

        setupVideoControls();

        View topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) updateTopNavBar();

        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);

        recyclerView.post(() -> checkAndRequestMediaPermissions());
    }

    private boolean userIsSwiping = false;

    private void showMediaHud(boolean isVolume, int percent) {
        if (mediaHudVolume == null || mediaHudBrightness == null) return;
        TextView target = isVolume ? mediaHudVolume : mediaHudBrightness;
        TextView other  = isVolume ? mediaHudBrightness : mediaHudVolume;
        other.setVisibility(View.GONE);
        target.setText(percent + "%");
        target.setVisibility(View.VISIBLE);
        target.bringToFront();
        videoHandler.removeCallbacks(mediaHudHideRunnable);
        mediaHudHideRunnable = () -> {
            if (mediaHudVolume != null) mediaHudVolume.setVisibility(View.GONE);
            if (mediaHudBrightness != null) mediaHudBrightness.setVisibility(View.GONE);
        };
        videoHandler.postDelayed(mediaHudHideRunnable, MEDIA_HUD_VISIBLE_MS);
    }

    private void hideMediaHudImmediately() {
        if (mediaHudVolume != null) mediaHudVolume.setVisibility(View.GONE);
        if (mediaHudBrightness != null) mediaHudBrightness.setVisibility(View.GONE);
        if (videoHandler != null) videoHandler.removeCallbacks(mediaHudHideRunnable);
    }

    private int getEffectivePositionMs() {
        if (currentFullscreenVideo == null) return 0;
        if (overlayPendingSeekMs >= 0) return overlayPendingSeekMs;
        try { return currentFullscreenVideo.getCurrentPosition(); }
        catch (Exception e) { return 0; }
    }

    private void skipByMs(int deltaMs) {
        if (currentFullscreenVideo == null) return;
        int dur;
        try { dur = currentFullscreenVideo.getDuration(); }
        catch (Exception e) { return; }
        if (dur <= 0) return;
        int current = getEffectivePositionMs();
        int target = Math.max(0, Math.min(current + deltaMs, dur));
        overlayPendingSeekMs = target;
        try { currentFullscreenVideo.seekTo(target); }
        catch (Exception ignored) {}
        if (videoTimeCurrent != null) videoTimeCurrent.setText(formatTime(target));
        if (videoSeekBar != null)
            videoSeekBar.setProgress((int) ((target / (float) dur) * 1000));
        videoHandler.postDelayed(() -> overlayPendingSeekMs = -1, 350);
    }

    private void resetPlaybackSpeed() {
        if (currentFullscreenVideo == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (!currentFullscreenVideo.isPlaying()) {
            playbackSpeedBeforeLongPress = 1.0f;
            return;
        }
        try {
            android.media.PlaybackParams params = currentFullscreenVideo.getPlaybackParams();
            if (params == null) params = new android.media.PlaybackParams();
            params.setSpeed(playbackSpeedBeforeLongPress > 0
                    ? playbackSpeedBeforeLongPress : 1.0f);
            currentFullscreenVideo.setPlaybackParams(params);
        } catch (Exception ignored) {}
        playbackSpeedBeforeLongPress = 1.0f;
    }

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoScrollActive || recyclerView == null) return;
            recyclerView.scrollBy(autoScrollDx, autoScrollDy);
            recyclerView.postDelayed(this, AUTO_SCROLL_TICK_MS);
        }
    };

    private String getCachedCanonical(String path) {
        if (path == null) return null;
        String cached = canonicalAlbumCache.get(path);
        if (cached != null) return cached;
        String canonical;
        try { canonical = new File(path).getCanonicalPath(); }
        catch (Exception e) { canonical = path; }
        canonicalAlbumCache.put(path, canonical);
        return canonical;
    }

    private void applyStarIcon(ImageView v, boolean fav) {
        if (v == null) return;
        v.setImageResource(fav ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
        int color = fav
                ? android.graphics.Color.parseColor("#FFD700")
                : android.graphics.Color.WHITE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            v.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        } else {
            v.setColorFilter(color);
        }
    }

    private Set<String> loadFavoritePaths() {
        if (prefs == null) return new HashSet<>();
        String raw = prefs.getString(PREF_FAVORITES, "");
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split("\n")) {
                if (!p.isEmpty()) set.add(p);
            }
        }
        return set;
    }

    private void saveFavoritePaths(Set<String> paths) {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String p : paths) sb.append(p).append("\n");
        prefs.edit().putString(PREF_FAVORITES, sb.toString()).apply();
    }

    private boolean toggleFavoriteForPath(String path) {
        if (path == null) return false;
        Set<String> favs = loadFavoritePaths();
        boolean nowFav;

        if (favs.contains(path)) {
            favs.remove(path);
            nowFav = false;
        } else {
            favs.add(path);
            nowFav = true;
        }

        saveFavoritePaths(favs);

        for (MediaItem item : mediaItems) {
            if (item.path.equals(path)) {
                item.isFavorite = nowFav;
                break;
            }
        }

        if (adapter != null) adapter.notifyDataSetChanged();
        applyFilter();

        return nowFav;
    }

    private void updateFullscreenFavoriteIcon(String path) {
        if (path == null) return;
        boolean fav = false;
        for (MediaItem item : mediaItems) {
            if (item.path.equals(path)) { fav = item.isFavorite; break; }
        }
        if (!fav && loadFavoritePaths().contains(path)) fav = true;
        applyStarIcon(fsBtnFavoriteIcon, fav);
        applyStarIcon(btnFavoriteOverlay, fav);
    }

    private void onFullscreenTap() {
        boolean newVisible = !fullscreenChromeVisible;
        if (newVisible) showAllControlsWithTimeout();
        else hideAllControls();
    }

    private void showAllControlsWithTimeout() {
        setFullscreenChromeVisible(true);

        if (currentFullscreenPageIsVideo && videoControlContainer != null) {
            videoControlContainer.setVisibility(View.VISIBLE);
            overlayControlsVisible = true;
        } else {
            if (videoControlContainer != null) {
                videoControlContainer.setVisibility(View.GONE);
            }
            overlayControlsVisible = false;
        }

        videoHandler.removeCallbacks(overlayHideControlsRunnable);

        // Only auto-hide when there are actually video controls to hide.
        if (currentFullscreenPageIsVideo) {
            overlayHideControlsRunnable = () -> {
                if (!userIsSwiping) hideAllControls();
            };
            videoHandler.postDelayed(overlayHideControlsRunnable, OVERLAY_CONTROLS_TIMEOUT);
        }
    }

    private void hideAllControls() {
        setFullscreenChromeVisible(false);
        if (videoControlContainer != null)
            videoControlContainer.setVisibility(View.GONE);
        overlayControlsVisible = false;
        videoHandler.removeCallbacks(overlayHideControlsRunnable);
    }

    private void setFullscreenChromeVisible(boolean visible) {
        fullscreenChromeVisible = visible;
        if (fullscreenInfoHeader != null)
            fullscreenInfoHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (fullscreenBottomBar != null)
            fullscreenBottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                updateFullscreenFavoriteIcon(fullscreenMediaPaths.get(pos));
            }
        }
    }

    private boolean renameFileRobust(File src, File dst) {
        if (src == null || dst == null) return false;
        if (!src.exists()) return false;
        try { if (src.renameTo(dst)) return true; } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(
                        src.toPath(),
                        dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void scanPathsAndThen(List<String> paths, Runnable callback) {
        if (paths == null || paths.isEmpty()) {
            if (callback != null) mediaRefreshHandler.post(callback);
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(paths.size());
        final List<String> toScan = new ArrayList<>(paths);
        final MediaScannerConnection[] connHolder = new MediaScannerConnection[1];

        connHolder[0] = new MediaScannerConnection(this,
                new MediaScannerConnection.MediaScannerConnectionClient() {
                    @Override
                    public void onMediaScannerConnected() {
                        for (String p : toScan) {
                            try { connHolder[0].scanFile(p, null); }
                            catch (Exception e) {
                                if (remaining.decrementAndGet() == 0) finish();
                            }
                        }
                    }
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        if (remaining.decrementAndGet() == 0) finish();
                    }
                    private void finish() {
                        try { connHolder[0].disconnect(); } catch (Exception ignored) {}
                        if (callback != null) mediaRefreshHandler.post(callback);
                    }
                });
        connHolder[0].connect();
    }

    private void scheduleMediaRefresh(long delayMs) {
        if (!isActivityAlive()) return;
        if (pendingMediaRefresh != null) {
            mediaRefreshHandler.removeCallbacks(pendingMediaRefresh);
        }
        pendingMediaRefresh = () -> {
            albumsCacheValid = false;
            trashedIndexDirty = true;
            loadMedia();
            pendingMediaRefresh = null;
        };
        mediaRefreshHandler.postDelayed(pendingMediaRefresh, delayMs);
    }

    private void setupOverlayVideoControls() {
        if (btnCenterPlayPause != null)
            btnCenterPlayPause.setOnClickListener(v -> toggleOverlayPlayPause());

        if (btnSkipForwardOverlay != null)
            btnSkipForwardOverlay.setOnClickListener(v -> skipByMs(SKIP_FORWARD_MS));

        if (btnSkipBackwardOverlay != null)
            btnSkipBackwardOverlay.setOnClickListener(v -> skipByMs(-SKIP_BACKWARD_MS));

        if (btnRotateOverlay != null)
            btnRotateOverlay.setOnClickListener(v -> toggleOrientation());

        if (btnFavoriteOverlay != null)
            btnFavoriteOverlay.setOnClickListener(v -> {
                if (fullscreenCurrentPosition >= 0
                        && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
                    String path = fullscreenMediaPaths.get(fullscreenCurrentPosition);
                    boolean nowFav = toggleFavoriteForPath(path);
                    updateFullscreenFavoriteIcon(path);
                    applyFilter();
                    Toast.makeText(this, nowFav ? "Added" : "Removed",
                            Toast.LENGTH_SHORT).show();
                }
            });

        if (btnInfoOverlay != null)
            btnInfoOverlay.setOnClickListener(v -> {
                if (fullscreenCurrentPosition >= 0
                        && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
                    showFileInfoDialog(fullscreenMediaPaths.get(fullscreenCurrentPosition));
                }
            });

        if (btnDeleteOverlay != null)
            btnDeleteOverlay.setOnClickListener(v -> {
                if (fullscreenCurrentPosition >= 0
                        && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
                    String path = fullscreenMediaPaths.get(fullscreenCurrentPosition);
                    for (MediaItem item : mediaItems) {
                        if (item.path.equals(path)) {
                            moveToTrash(item);
                            closeFullscreenViewer();
                            break;
                        }
                    }
                }
            });

        if (videoSeekBar != null) {
            videoSeekBar.setMax(1000);
            videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && currentFullscreenVideo != null) {
                        int dur = currentFullscreenVideo.getDuration();
                        if (dur > 0) {
                            int targetMs = (int) ((progress / 1000.0) * dur);
                            overlayPendingSeekMs = targetMs;
                            currentFullscreenVideo.seekTo(targetMs);
                            if (videoTimeCurrent != null)
                                videoTimeCurrent.setText(formatTime(targetMs));
                            videoHandler.postDelayed(() -> overlayPendingSeekMs = -1, 350);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                    videoHandler.removeCallbacks(overlayHideControlsRunnable);
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    showAllControlsWithTimeout();
                }
            });
        }
    }

    private void toggleOverlayPlayPause() {
        if (currentFullscreenVideo == null) return;
        overlayPendingSeekMs = -1;

        boolean actuallyPlaying = false;
        try { actuallyPlaying = currentFullscreenVideo.isPlaying(); }
        catch (Exception ignored) {}

        if (actuallyPlaying) {
            currentFullscreenVideo.pause();
            isOverlayVideoPlaying = false;
            if (btnCenterPlayPause != null)
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            videoHandler.removeCallbacks(overlayProgressRunnable);
        } else {
            currentFullscreenVideo.start();
            isOverlayVideoPlaying = true;
            if (btnCenterPlayPause != null)
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            startOverlayProgressUpdate();
        }

        savedVideoPositionMs = getEffectivePositionMs();
        savedVideoPath = currentFullscreenVideoPosition >= 0
                && currentFullscreenVideoPosition < fullscreenMediaPaths.size()
                ? fullscreenMediaPaths.get(currentFullscreenVideoPosition) : null;
        savedVideoWasPlaying = isOverlayVideoPlaying;
    }

    private void startOverlayProgressUpdate() {
        videoHandler.removeCallbacks(overlayProgressRunnable);
        overlayProgressRunnable = new Runnable() {
            @Override
            public void run() {
                updateOverlaySeekBar();
                if (isOverlayVideoPlaying) videoHandler.postDelayed(this, 250);
            }
        };
        videoHandler.post(overlayProgressRunnable);
    }

    private void updateOverlaySeekBar() {
        if (currentFullscreenVideo == null) return;
        try {
            int cur = getEffectivePositionMs();
            int dur = currentFullscreenVideo.getDuration();
            if (dur > 0) {
                if (videoTimeCurrent != null) videoTimeCurrent.setText(formatTime(cur));
                if (videoTimeTotal != null) videoTimeTotal.setText(formatTime(dur));
                if (videoSeekBar != null)
                    videoSeekBar.setProgress((int) ((cur / (float) dur) * 1000));
            }
        } catch (Exception ignored) {}
    }

    private void updateOverlayTitle() {
        if (videoTitleOverlay == null) return;
        if (fullscreenCurrentPosition >= 0
                && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
            File f = new File(fullscreenMediaPaths.get(fullscreenCurrentPosition));
            videoTitleOverlay.setText(f.getName());
        }
    }

    private boolean isVideoPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    private void findAndStartVideoForPosition(int targetPosition) {
        try {
            RecyclerView rv = (RecyclerView) fullscreenViewPager.getChildAt(0);
            if (rv == null) {
                fullscreenViewPager.post(() -> {
                    if (fullscreenCurrentPosition == targetPosition)
                        findAndStartVideoForPosition(targetPosition);
                });
                return;
            }

            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
                if (vh != null && vh.getBindingAdapterPosition() == targetPosition) {
                    CustomVideoView vv = child.findViewById(R.id.fullscreen_video);
                    if (vv != null && vv.getVisibility() == View.VISIBLE) {
                        boolean landscape = getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_LANDSCAPE;
                        vv.setFit(landscape
                                ? CustomVideoView.Fit.LARGER
                                : CustomVideoView.Fit.SMALLER);

                        vv.setOnCompletionListener(mp -> {
                            try {
                                mp.seekTo(0);
                                mp.start();
                                isOverlayVideoPlaying = true;
                                syncOverlayPlayPauseIcon();
                                startOverlayProgressUpdate();
                            } catch (Exception ignored) {}
                        });

                        if (currentFullscreenVideo == vv
                                && currentFullscreenVideoPosition == targetPosition) {
                            restoreSavedPlaybackState(vv, targetPosition);
                            syncOverlayPlayPauseIcon();
                            showAllControlsWithTimeout();
                            return;
                        }

                        currentFullscreenVideo = vv;
                        currentFullscreenVideoPosition = targetPosition;
                        currentFullscreenPageIsVideo = true;
                        overlayPendingSeekMs = -1;

                        vv.setOnTapListener(this::onFullscreenTap);
                        vv.setOnTouchListener((v, event) -> {
                            if (overlayVideoGestureDetector != null) {
                                overlayVideoGestureDetector.onTouchEvent(event);
                            }
                            if (volumeBrightnessHandler != null) {
                                volumeBrightnessHandler.onTouch(v, event);
                            }
                            if (event.getActionMasked() == MotionEvent.ACTION_UP
                                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                                resetPlaybackSpeed();
                            }
                            return true;
                        });

                        restoreSavedPlaybackState(vv, targetPosition);
                        syncOverlayPlayPauseIcon();

                        updateOverlayTitle();
                        updateOverlaySeekBar();
                        startOverlayProgressUpdate();
                        showAllControlsWithTimeout();
                        return;
                    }
                }
            }

            fullscreenViewPager.post(() -> {
                if (fullscreenCurrentPosition == targetPosition)
                    findAndStartVideoForPosition(targetPosition);
            });
        } catch (Exception ignored) {}
    }

    private void restoreSavedPlaybackState(CustomVideoView vv, int position) {
        String path = (position >= 0 && position < fullscreenMediaPaths.size())
                ? fullscreenMediaPaths.get(position) : null;

        int restoreMs = -1;
        boolean restorePlaying = true;
        if (path != null && path.equals(savedVideoPath) && savedVideoPositionMs >= 0) {
            restoreMs = savedVideoPositionMs;
            restorePlaying = savedVideoWasPlaying;
        }

        try {
            if (restoreMs > 0) vv.seekTo(restoreMs);
        } catch (Exception ignored) {}

        try {
            if (restorePlaying) {
                vv.start();
                isOverlayVideoPlaying = true;
            } else {
                vv.pause();
                isOverlayVideoPlaying = false;
            }
        } catch (Exception ignored) {}
    }

    private void syncOverlayPlayPauseIcon() {
        boolean playing = false;
        if (currentFullscreenVideo != null) {
            try { playing = currentFullscreenVideo.isPlaying(); }
            catch (Exception ignored) {}
        }
        isOverlayVideoPlaying = playing;
        if (btnCenterPlayPause != null) {
            btnCenterPlayPause.setImageResource(playing
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play);
        }
    }

    private void toggleOrientation() {
        int cur = getResources().getConfiguration().orientation;
        if (cur == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isLandscape = false;
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            isLandscape = true;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterImmersiveLandscape();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(getWindow().getAttributes());
            }
            if (currentFullscreenVideo != null)
                currentFullscreenVideo.setFit(CustomVideoView.Fit.LARGER);
        } else {
            exitImmersiveLandscape();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                getWindow().setAttributes(getWindow().getAttributes());
            }
            if (currentFullscreenVideo != null)
                currentFullscreenVideo.setFit(CustomVideoView.Fit.SMALLER);
        }
        if (currentFullscreenVideo != null) {
            fullscreenViewPager.post(() -> {
                if (currentFullscreenVideo != null)
                    currentFullscreenVideo.requestLayout();
            });
        }
    }

    private void enterImmersiveLandscape() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitImmersiveLandscape() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void openFullscreenViewer(String path) {
        if (fullscreenViewPager != null) {
            fullscreenViewPager.setUserInputEnabled(true);
        }

        fullscreenMediaPaths.clear();
        int currentIndex = 0;
        for (int i = 0; i < displayedItems.size(); i++) {
            MediaItem item = displayedItems.get(i);
            if (!item.isTrashed) {
                fullscreenMediaPaths.add(item.path);
                if (item.path.equals(path)) currentIndex = fullscreenMediaPaths.size() - 1;
            }
        }

        if (fullscreenMediaPaths.isEmpty()) {
            Toast.makeText(this, "No media to view", Toast.LENGTH_SHORT).show();
            return;
        }

        fullscreenCurrentPosition = currentIndex;
        fullscreenCurrentIndex = currentIndex;
        currentFullscreenVideo = null;
        currentFullscreenVideoPosition = -1;
        currentFullscreenPageIsVideo = false;
        isOverlayVideoPlaying = false;
        overlayControlsVisible = false;
        overlayPendingSeekMs = -1;

        fullscreenAdapter.notifyDataSetChanged();
        fullscreenViewPager.setCurrentItem(currentIndex, false);

        fullscreenOverlay.setVisibility(View.VISIBLE);
        fullscreenOverlay.bringToFront();

        View topNav = findViewById(R.id.topNavBar);
        if (topNav != null) topNav.setVisibility(View.GONE);
        View selTop = findViewById(R.id.selectionTopBar);
        if (selTop != null) selTop.setVisibility(View.GONE);

        updateFullscreenInfo(currentIndex);

        setFullscreenChromeVisible(true);
        if (videoControlContainer != null) {
            String initialPath = fullscreenMediaPaths.get(currentIndex);
            if (isVideoPath(initialPath)) {
                videoControlContainer.setVisibility(View.VISIBLE);
                overlayControlsVisible = true;
            } else {
                videoControlContainer.setVisibility(View.GONE);
                overlayControlsVisible = false;
            }
        }
        videoHandler.removeCallbacks(overlayHideControlsRunnable);

        bottomBar.setVisibility(View.GONE);

        String initialPath = fullscreenMediaPaths.get(currentIndex);
        if (isVideoPath(initialPath)) {
            int finalIndex = currentIndex;
            fullscreenViewPager.post(() -> findAndStartVideoForPosition(finalIndex));
        }
    }

    private void closeFullscreenViewer() {
        if (currentFullscreenVideo != null) {
            fullscreenViewPager.setUserInputEnabled(true);
            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
            currentFullscreenVideo = null;
        }
        currentFullscreenVideoPosition = -1;
        currentFullscreenPageIsVideo = false;
        isOverlayVideoPlaying = false;
        overlayControlsVisible = false;
        overlayPendingSeekMs = -1;
        savedVideoPositionMs = -1;
        savedVideoPath = null;
        savedVideoWasPlaying = false;
        hideMediaHudImmediately();
        videoHandler.removeCallbacks(overlayProgressRunnable);
        videoHandler.removeCallbacks(overlayHideControlsRunnable);

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
        } catch (Exception ignored) {}

        if (videoControlContainer != null) videoControlContainer.setVisibility(View.GONE);
        fullscreenOverlay.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);

        View topNav = findViewById(R.id.topNavBar);
        if (topNav != null) topNav.setVisibility(View.VISIBLE);
        updateTopNavBar();
    }

    private void updateFullscreenInfo(int position) {
        if (position < 0 || position >= fullscreenMediaPaths.size()) return;
        String path = fullscreenMediaPaths.get(position);
        File file = new File(path);

        if (fullscreenInfoName != null) fullscreenInfoName.setText(file.getName());
        if (fullscreenInfoDetails != null) {
            String size = file.exists() ? formatFileSize(file.length()) : "-";
            fullscreenInfoDetails.setText(getFileType(path) + " - " + size);
        }
        if (fullscreenInfoPath != null) {
            String parent = file.getParent();
            fullscreenInfoPath.setText(parent != null ? parent : "");
        }
        updateOverlayTitle();
        updateFullscreenFavoriteIcon(path);
    }

    private void showFileInfoDialog(String path) {
        File file = new File(path);
        if (!file.exists()) return;
        long tsSec = file.lastModified() / 1000L;
        if (tsSec <= 0) tsSec = System.currentTimeMillis() / 1000L;
        StringBuilder info = new StringBuilder();
        info.append("File: ").append(file.getName()).append("\n");
        info.append("Size: ").append(formatFileSize(file.length())).append("\n");
        info.append("Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm",
                Locale.getDefault()).format(new Date(tsSec * 1000L))).append("\n");
        info.append("Type: ").append(getFileType(path)).append("\n");
        info.append("Path: ").append(file.getAbsolutePath());
        showInfoDialog(info.toString(), "File Info");
    }

    private void updateTopNavBar() {
        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        TextView title = findViewById(R.id.titleGallery);
        if (btnBack == null || title == null) return;

        boolean showBack = currentFilter == FilterMode.BIN || currentAlbum != null
                || showAlbums || whatsappOnly;
        btnBack.setVisibility(showBack ? View.VISIBLE : View.GONE);

        if (currentFilter == FilterMode.BIN) title.setText("Bin");
        else if (whatsappOnly) {
            String shown = whatsappPath != null
                    ? albumDisplayNames.get(whatsappPath) : null;
            title.setText(shown != null ? shown : "WhatsApp");
        }
        else if (currentAlbum != null) {
            String shown = albumDisplayNames.get(currentAlbum);
            title.setText(shown != null ? shown : new File(currentAlbum).getName());
        }
        else if (showAlbums) title.setText("Albums");
        else title.setText("Gallery");

        btnBack.setOnClickListener(v -> {
            if (currentFilter == FilterMode.BIN) {
                currentFilter = FilterMode.ALL;
                applyFilter(); updateTopNavBar(); updateBarsVisibility();
            } else if (whatsappOnly) {
                whatsappOnly = false;
                whatsappPath = null;
                showAlbums = true;
                loadAlbums();
                albumRecycler.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
                titleView.setText("Albums");
                updateTopNavBar();
                updateBarsVisibility();
            } else if (currentAlbum != null || showAlbums) {
                navigateBackFromAlbum();
            } else {
                finish();
            }
        });
    }

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

        boolean binLoading = currentFilter == FilterMode.BIN
                && trashedIndexDirty
                && trashedByPath.isEmpty();

        boolean mediaLoading = displayedItems.isEmpty()
                && mediaItems.isEmpty()
                && currentFilter != FilterMode.BIN;

        if (binLoading || mediaLoading) {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.GONE);
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
            return;
        }

        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);

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

    private void navigateBackFromAlbum() {
        whatsappOnly = false;
        whatsappPath = null;
        if (currentAlbum != null) {
            currentAlbum = null;
            showAlbums = true;
            recyclerView.setVisibility(View.GONE);

            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
            albumRecycler.setVisibility(View.GONE);

            loadAlbums();
            albumRecycler.setBackgroundColor(Color.parseColor("#FF000000"));
            titleView.setText("Albums");
            updateBarsVisibility();
            updateTopNavBar();
        } else if (showAlbums) {
            showAlbums = false;
            currentFilter = FilterMode.ALL;
            currentAlbum = null;
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            titleView.setText("Gallery");
            applyFilter();
            updateBarsVisibility();
            updateTopNavBar();
        }
    }

    private void loadMedia() { loadMediaInternal(true); }
    private void loadMediaQuiet() { loadMediaInternal(false); }

    private void loadMediaInternal(boolean fullRefresh) {
        executor.execute(() -> {
            List<MediaItem> newItems = new ArrayList<>();
            Set<String> knownPaths = new HashSet<>();

            String[] imageProjection = {
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_MODIFIED
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
                    while (imageCursor.moveToNext()) {
                        String path = dataIndex >= 0 ? imageCursor.getString(dataIndex) : null;
                        String name = nameIndex >= 0 ? imageCursor.getString(nameIndex) : "image";
                        long storeDate = dateIndex >= 0 ? imageCursor.getLong(dateIndex) : 0;
                        if (path != null && new File(path).exists()) {
                            long date = resolveTimestamp(path, storeDate);
                            boolean isTrashed = path.contains(".trashed.");
                            String parentPath = new File(path).getParent();
                            String albumKey = parentPath != null
                                    ? getCachedCanonical(parentPath) : "";
                            newItems.add(new MediaItem(path, name,
                                    MediaItem.TYPE_IMAGE, date, isTrashed, albumKey));
                            knownPaths.add(path);
                        }
                    }
                }
            } catch (SecurityException ignored) {
            } finally {
                if (imageCursor != null) imageCursor.close();
            }

            String[] videoProjection = {
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_MODIFIED
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
                    while (videoCursor.moveToNext()) {
                        String path = dataIndex >= 0 ? videoCursor.getString(dataIndex) : null;
                        String name = nameIndex >= 0 ? videoCursor.getString(nameIndex) : "video";
                        long storeDate = dateIndex >= 0 ? videoCursor.getLong(dateIndex) : 0;
                        if (path != null && new File(path).exists()) {
                            long date = resolveTimestamp(path, storeDate);
                            boolean isTrashed = path.contains(".trashed.");
                            String parentPath = new File(path).getParent();
                            String albumKey = parentPath != null
                                    ? getCachedCanonical(parentPath) : "";
                            newItems.add(new MediaItem(path, name,
                                    MediaItem.TYPE_VIDEO, date, isTrashed, albumKey));
                            knownPaths.add(path);
                        }
                    }
                }
            } catch (SecurityException ignored) {
            } finally {
                if (videoCursor != null) videoCursor.close();
            }

            List<File> roots = getStorageDirectoriesProper();
            for (File root : roots) {
                if (root != null && root.exists()) {
                    scanFilesystemForMedia(root, newItems, knownPaths, 0, 8);
                }
            }

            scanWhatsAppImagesTopLevel(newItems, knownPaths);

            for (MediaItem item : newItems) {
                try {
                    File f = new File(item.path);
                    long fileSec = f.lastModified() / 1000L;
                    if (fileSec > 0) item.dateModified = fileSec;
                } catch (Exception ignored) {}
            }
            Collections.sort(newItems, (a, b) -> Long.compare(b.dateModified, a.dateModified));

            Set<String> favs = loadFavoritePaths();
            for (MediaItem item : newItems) {
                item.isFavorite = favs.contains(item.path);
            }

            final List<MediaItem> result = newItems;
            final boolean fullRefreshFinal = fullRefresh;
            runOnMain(() -> {
                if (!isActivityAlive()) return;
                mediaItems.clear();
                mediaItems.addAll(result);

                if (loadingSpinner != null) {
                    loadingSpinner.setVisibility(View.GONE);
                }

                trashedIndexDirty = true;
                applyFilter();
                if (fullRefreshFinal) {
                    setupRecyclerView();
                }
                showEmptyState();
            });
        });
    }

    private void scanWhatsAppImagesTopLevel(List<MediaItem> out, Set<String> knownPaths) {
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return;

        File waImages1 = new File(root,
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
        addTopLevelWaFiles(waImages1, out, knownPaths);

        File accountsDir = new File(root,
                "Android/media/com.whatsapp/WhatsApp/accounts");
        if (accountsDir.exists() && accountsDir.isDirectory()) {
            File[] accounts = accountsDir.listFiles();
            if (accounts != null) {
                for (File acc : accounts) {
                    if (acc != null && acc.isDirectory()) {
                        File waImages = new File(acc, "Media/WhatsApp Images");
                        addTopLevelWaFiles(waImages, out, knownPaths);
                    }
                }
            }
        }
    }

    private void addTopLevelWaFiles(File dir,
                                    List<MediaItem> out,
                                    Set<String> knownPaths) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f == null || !f.isFile()) continue;

            String fileName = f.getName();
            if (fileName.startsWith(".")) continue;

            String lower = fileName.toLowerCase(Locale.ROOT);
            boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".gif")
                    || lower.endsWith(".bmp") || lower.endsWith(".webp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif");
            if (!isImage) continue;

            String path;
            try { path = f.getCanonicalPath(); }
            catch (Exception e) { path = f.getAbsolutePath(); }

            if (knownPaths.contains(path)) continue;
            knownPaths.add(path);

            long date = resolveTimestamp(path, 0);
            String parent = f.getParent();
            String albumKey = parent != null ? getCachedCanonical(parent) : "";
            out.add(new MediaItem(path, fileName, MediaItem.TYPE_IMAGE, date, false, albumKey));
        }
    }

    private void showWhatsAppOnly(String virtualPath) {
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);

        recyclerView.post(() -> {
            final List<MediaItem> wa = new ArrayList<>();

            if (virtualPath == null || virtualPath.equals("whatsapp://all")) {
                for (MediaItem item : mediaItems) {
                    if (item.path == null) continue;
                    if (item.path.contains(".trashed.")) continue;
                    if (!item.path.contains("/com.whatsapp/")) continue;
                    if (!item.path.contains("/Media/WhatsApp Images/")) continue;

                    String afterMarker = item.path.substring(
                            item.path.indexOf("/Media/WhatsApp Images/")
                                    + "/Media/WhatsApp Images/".length());
                    if (afterMarker.contains("/")) continue;

                    wa.add(item);
                }
            } else {
                String accountId = virtualPath.substring("whatsapp://".length());
                String needle = "/Media/WhatsApp Images/";
                for (MediaItem item : mediaItems) {
                    if (item.path == null) continue;
                    if (item.path.contains(".trashed.")) continue;
                    if (!item.path.contains("/com.whatsapp/")) continue;
                    if (!item.path.contains(needle)) continue;
                    if (!item.path.contains("/accounts/" + accountId + "/")) continue;
                    wa.add(item);
                }
            }

            displayedItems = wa;
            if (adapter == null) {
                setupRecyclerView();
            } else {
                adapter.updateItems(wa);
                adapter.updateSelectedItems(selectedItems);
            }
            updateSelectionUI();
            showEmptyState();
            updateTopNavBar();

            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        });
    }

    private long resolveTimestamp(String path, long storeDate) {
        try {
            File f = new File(path);
            long sec = f.lastModified() / 1000L;
            if (sec > 0) return sec;
        } catch (Exception ignored) {}
        return storeDate;
    }

    private void scanFilesystemForMedia(File directory, List<MediaItem> out,
                                        Set<String> knownPaths, int depth, int maxDepth) {
        if (depth > maxDepth || directory == null
                || !directory.exists() || !directory.isDirectory()) return;

        String dirPath = directory.getAbsolutePath();
        if (dirPath.contains("/com.whatsapp/")) return;

        String dirName = directory.getName().toLowerCase(Locale.ROOT);
        if (dirName.equals("android") || dirName.equals("system")
                || dirName.equals("cache") || dirName.equals("tmp")
                || dirName.equals("lost+found") || dirName.equals("obb")) return;

        File[] files;
        try { files = directory.listFiles(); }
        catch (Exception e) { return; }
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    scanFilesystemForMedia(f, out, knownPaths, depth + 1, maxDepth);
                }
            } else if (f.isFile()) {
                String name = f.getName();
                if (name.startsWith(".")) continue;
                if (name.contains(".trashed.")) continue;
                if (!isMediaFile(name)) continue;

                String path;
                try { path = f.getCanonicalPath(); }
                catch (Exception e) { path = f.getAbsolutePath(); }

                if (knownPaths.contains(path)) continue;
                knownPaths.add(path);

                boolean isVideo = isVideoPath(name);
                long date = resolveTimestamp(path, 0);
                String parent = f.getParent();
                String albumKey = parent != null ? getCachedCanonical(parent) : "";
                out.add(new MediaItem(path, name,
                        isVideo ? MediaItem.TYPE_VIDEO : MediaItem.TYPE_IMAGE,
                        date, false, albumKey));
            }
        }
    }

    private void getTrashedFilesAsync(boolean forceRescan, OnTrashedFilesReady callback) {
        if (callback == null) return;

        if (!forceRescan && !trashedIndexDirty) {
            final List<MediaItem> snapshot = new ArrayList<>(trashedByPath.values());
            runOnMain(() -> {
                if (isActivityAlive()) callback.onReady(snapshot);
            });
            return;
        }

        executor.execute(() -> {
            trashedByPath.clear();

            for (MediaItem item : new ArrayList<>(mediaItems)) {
                if (item.path != null && item.path.contains(".trashed.")) {
                    trashedByPath.put(item.path, item);
                }
            }

            List<File> directories = getStorageDirectoriesProper();
            for (File dir : directories) {
                if (dir != null && dir.exists()) {
                    scanDirectoryForTrashedFiles(dir, trashedByPath, 0, 10);
                }
            }

            scanWhatsAppForTrashed();

            trashedIndexDirty = false;
            final List<MediaItem> snapshot = new ArrayList<>(trashedByPath.values());

            runOnMain(() -> {
                if (isActivityAlive()) callback.onReady(snapshot);
            });
        });
    }

    private void scanWhatsAppForTrashed() {
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return;

        File whatsappBase1 = new File(root,
                "Android/media/com.whatsapp/WhatsApp/Media");
        scanDirForTrashedRecursive(whatsappBase1, 0, 6);

        File accountsDir = new File(root,
                "Android/media/com.whatsapp/WhatsApp/accounts");
        if (accountsDir.exists() && accountsDir.isDirectory()) {
            File[] accounts = accountsDir.listFiles();
            if (accounts != null) {
                for (File acc : accounts) {
                    if (acc != null && acc.isDirectory()) {
                        File accMedia = new File(acc, "Media");
                        scanDirForTrashedRecursive(accMedia, 0, 6);
                    }
                }
            }
        }
    }

    private void scanDirForTrashedRecursive(File dir, int depth, int maxDepth) {
        if (depth > maxDepth || dir == null
                || !dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    scanDirForTrashedRecursive(f, depth + 1, maxDepth);
                }
                continue;
            }
            if (!f.isFile()) continue;

            String fileName = f.getName();
            if (!fileName.contains(".trashed.")) continue;

            String lower = fileName.toLowerCase(Locale.ROOT);
            boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".gif")
                    || lower.endsWith(".bmp") || lower.endsWith(".webp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif");
            boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".avi")
                    || lower.endsWith(".mkv") || lower.endsWith(".mov")
                    || lower.endsWith(".wmv") || lower.endsWith(".flv")
                    || lower.endsWith(".3gp") || lower.endsWith(".m4v")
                    || lower.endsWith(".webm");
            if (!isImage && !isVideo) continue;

            String path;
            try { path = f.getCanonicalPath(); }
            catch (Exception e) { path = f.getAbsolutePath(); }

            if (trashedByPath.containsKey(path)) continue;

            String album = f.getParentFile() != null
                    ? f.getParentFile().getName() : "";
            long dateModified = f.lastModified() / 1000;
            int type = isImage ? MediaItem.TYPE_IMAGE : MediaItem.TYPE_VIDEO;
            trashedByPath.put(path, new MediaItem(path, fileName, type, dateModified, true, album));
        }
    }

    public interface OnTrashedFilesReady {
        void onReady(List<MediaItem> trashedItems);
    }

    private List<File> getStorageDirectoriesProper() {
        List<File> directories = new ArrayList<>();
        List<String> normalizedPaths = new ArrayList<>();

        try {
            StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                List<StorageVolume> volumes = sm.getStorageVolumes();
                for (StorageVolume volume : volumes) {
                    try {
                        File vf = volume.getDirectory();
                        if (vf != null && vf.exists()) {
                            String np = normalizePath(vf.getAbsolutePath());
                            if (!normalizedPaths.contains(np)) {
                                directories.add(vf); normalizedPaths.add(np);
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
            File f = new File(path);
            if (f.exists() && f.isDirectory()) {
                String np = normalizePath(f.getAbsolutePath());
                if (!normalizedPaths.contains(np)) {
                    directories.add(f); normalizedPaths.add(np);
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
                            String np = normalizePath(child.getAbsolutePath());
                            File dcim = new File(child, "DCIM");
                            File pictures = new File(child, "Pictures");
                            File movies = new File(child, "Movies");
                            File downloads = new File(child, "Download");
                            if ((dcim.exists() || pictures.exists() || movies.exists() || downloads.exists())
                                    && !normalizedPaths.contains(np)) {
                                directories.add(child); normalizedPaths.add(np);
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
                                String np = normalizePath(current.getAbsolutePath());
                                if (!normalizedPaths.contains(np)) {
                                    directories.add(current); normalizedPaths.add(np);
                                }
                                break;
                            }
                            current = current.getParentFile();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        List<File> unique = new ArrayList<>();
        List<String> uniquePaths = new ArrayList<>();
        for (File dir : directories) {
            try {
                String cp = dir.getCanonicalPath();
                if (!uniquePaths.contains(cp)) { unique.add(dir); uniquePaths.add(cp); }
            } catch (Exception e) {
                String ap = dir.getAbsolutePath();
                if (!uniquePaths.contains(ap)) { unique.add(dir); uniquePaths.add(ap); }
            }
        }
        return unique;
    }

    private void scanDirectoryForTrashedFiles(File directory,
                                              LinkedHashMap<String, MediaItem> out,
                                              int depth, int maxDepth) {
        if (depth > maxDepth || directory == null
                || !directory.exists() || !directory.isDirectory()) return;

        File[] files;
        try { files = directory.listFiles(); }
        catch (Exception e) { return; }
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith(".")) continue;
                scanDirectoryForTrashedFiles(file, out, depth + 1, maxDepth);
                continue;
            }

            String fileName = file.getName();
            if (!fileName.contains(".trashed.")) continue;

            String lower = fileName.toLowerCase(Locale.ROOT);
            boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".gif")
                    || lower.endsWith(".bmp") || lower.endsWith(".webp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif");
            boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".avi")
                    || lower.endsWith(".mkv") || lower.endsWith(".mov")
                    || lower.endsWith(".wmv") || lower.endsWith(".flv")
                    || lower.endsWith(".3gp") || lower.endsWith(".m4v")
                    || lower.endsWith(".webm");
            if (!isImage && !isVideo) continue;

            String path;
            try { path = file.getCanonicalPath(); }
            catch (Exception e) { path = file.getAbsolutePath(); }

            if (out.containsKey(path)) continue;

            String album = file.getParentFile() != null
                    ? file.getParentFile().getName() : "";
            long dateModified = file.lastModified() / 1000;
            int type = isImage ? MediaItem.TYPE_IMAGE : MediaItem.TYPE_VIDEO;
            out.put(path, new MediaItem(path, fileName, type, dateModified, true, album));
        }
    }

    private void applyFilter() {
        if (!isActivityAlive()) return;

        if (currentFilter == FilterMode.BIN) {
            if (trashedIndexDirty && trashedByPath.isEmpty()) {
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
                View emptyView = findViewById(R.id.emptyStateContainer);
                if (emptyView != null) emptyView.setVisibility(View.GONE);
            }

            List<MediaItem> snapshot = new ArrayList<>(trashedByPath.values());
            displayedItems = snapshot;
            if (adapter == null) {
                setupRecyclerView();
            } else {
                adapter.updateItems(snapshot);
                adapter.updateSelectedItems(selectedItems);
            }
            updateSelectionUI();
            showEmptyState();
            updateTopNavBar();

            getTrashedFilesAsync(false, trashed -> {
                if (!isActivityAlive()) return;
                if (currentFilter != FilterMode.BIN) return;
                final List<MediaItem> fresh = new ArrayList<>(trashed);
                runOnMain(() -> {
                    if (!isActivityAlive()) return;
                    if (currentFilter != FilterMode.BIN) return;

                    if (loadingSpinner != null) {
                        loadingSpinner.setVisibility(View.GONE);
                    }
                    displayedItems = fresh;
                    if (adapter == null) {
                        setupRecyclerView();
                    } else {
                        adapter.updateItems(fresh);
                        adapter.updateSelectedItems(selectedItems);
                    }
                    updateSelectionUI();
                    showEmptyState();
                    updateTopNavBar();
                });
            });
            return;
        }

        final String targetCanon = currentAlbum == null
                ? null : getCachedCanonical(currentAlbum);
        final List<MediaItem> filtered = new ArrayList<>();

        for (MediaItem item : mediaItems) {
            boolean matchesAlbum;
            if (targetCanon == null) {
                matchesAlbum = true;
            } else {
                String itemCanon = getCachedCanonical(item.album);
                matchesAlbum = itemCanon != null && itemCanon.equals(targetCanon);
            }
            if (!matchesAlbum) continue;

            if (targetCanon == null && item.path != null
                    && item.path.contains("/com.whatsapp/")) {
                int idxImages = item.path.indexOf("/Media/WhatsApp Images/");
                int idxVideos = item.path.indexOf("/Media/WhatsApp Video/");

                boolean isTopLevelWhatsApp = false;
                if (idxImages >= 0) {
                    String after = item.path.substring(
                            idxImages + "/Media/WhatsApp Images/".length());
                    if (!after.contains("/")) isTopLevelWhatsApp = true;
                } else if (idxVideos >= 0) {
                    String after = item.path.substring(
                            idxVideos + "/Media/WhatsApp Video/".length());
                    if (!after.contains("/")) isTopLevelWhatsApp = true;
                }

                if (!isTopLevelWhatsApp) continue;
            }

            boolean isCurrentlyTrashed = item.path.contains(".trashed.");
            item.isTrashed = isCurrentlyTrashed;

            switch (currentFilter) {
                case ALL:       if (!isCurrentlyTrashed) filtered.add(item); break;
                case IMAGES:    if (!isCurrentlyTrashed && item.type == MediaItem.TYPE_IMAGE) filtered.add(item); break;
                case VIDEOS:    if (!isCurrentlyTrashed && item.type == MediaItem.TYPE_VIDEO) filtered.add(item); break;
                case FAVORITES: if (!isCurrentlyTrashed && item.isFavorite) filtered.add(item); break;
                default: break;
            }
        }

        final List<MediaItem> snapshot = filtered;
        runOnMain(() -> {
            if (!isActivityAlive()) return;
            displayedItems = snapshot;
            if (adapter == null) {
                setupRecyclerView();
                showEmptyState();
                updateTopNavBar();
            } else {
                adapter.updateItems(snapshot);
                adapter.updateSelectedItems(selectedItems);
                updateSelectionUI();
                showEmptyState();
                updateTopNavBar();
            }
        });
    }

    private void checkAndRequestMediaPermissions() {
        String[] toRequest;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toRequest = new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        } else {
            toRequest = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }

        boolean allGranted = true;
        for (String p : toRequest) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }

        if (allGranted) {
            loadMedia();
        } else {
            requestMultiplePermissionsLauncher.launch(toRequest);
        }
    }

    private void setupRecyclerView() {
        if (!isActivityAlive()) return;
        if (displayedItems.isEmpty()) { showEmptyState(); return; }

        List<MediaItem> initialSnapshot = new ArrayList<>(displayedItems);
        adapter = new GalleryAdapter(this, initialSnapshot, selectedItems, new GalleryAdapter.OnItemClickListener() {
            @Override public void onFavoriteToggle(MediaItem item) {
                if (isActivityAlive()) {
                    toggleFavoriteForPath(item.path);
                    applyFilter();
                }
            }
            @Override public void onDelete(MediaItem item) {
                if (isActivityAlive()) moveToTrash(item);
            }
            @Override public void onItemClick(String path) {
                if (isActivityAlive()) toggleSelection(path);
            }
            @Override public void onItemLongPress(String path) {
                if (isActivityAlive()) startLongPressDrag(path);
            }
            @Override public boolean isSelectionMode() {
                return selectionMode && isActivityAlive();
            }
            @Override public void onImageClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN)
                    openFullscreenViewer(path);
            }
            @Override public void onVideoClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN)
                    openFullscreenViewer(path);
            }
            @Override public void onRestore(MediaItem item) {
                if (isActivityAlive()) restoreFromTrash(item);
            }
        });

        recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        recyclerView.setAdapter(adapter);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(40);
        recyclerView.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        setupDragToSelect();
    }

    private void startLongPressDrag(String path) {
        if (!isActivityAlive()) return;

        int anchorIdx = indexOfPath(path);
        if (anchorIdx < 0) return;

        boolean alreadySelected = selectedItems.contains(path);

        selectionBeforeDrag.clear();
        selectionBeforeDrag.addAll(selectedItems);

        if (!selectionMode) {
            selectionMode = true;
            dragSelectDeselectMode = false;
        } else {
            dragSelectDeselectMode = alreadySelected;
        }

        dragAnchorPath = path;
        dragSelectActive = true;
        dragVisitedPositions.clear();
        dragRectanglePaths.clear();

        dragLastRectMinRow = -1;
        dragLastRectMaxRow = -1;
        dragLastRectMinCol = -1;
        dragLastRectMaxCol = -1;

        dragLastX = -1f;
        dragLastY = -1f;

        applyDragRectangle(anchorIdx, anchorIdx);
        updateSelectionUI();
    }

    private void setupDragToSelect() {
        if (recyclerView == null) return;

        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (!dragSelectActive) return false;

                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        handleDragMove(rv, e.getX(), e.getY());
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        resetDragState();
                        return true;
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (!dragSelectActive) return;

                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        handleDragMove(rv, e.getX(), e.getY());
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        resetDragState();
                        break;
                }
            }
        });
    }

    private void resetDragState() {
        dragSelectActive = false;
        dragVisitedPositions.clear();
        dragRectanglePaths.clear();
        selectionBeforeDrag.clear();
        dragAnchorPath = null;
        dragLastX = -1f;
        dragLastY = -1f;
        dragLastRectMinRow = -1;
        dragLastRectMaxRow = -1;
        dragLastRectMinCol = -1;
        dragLastRectMaxCol = -1;
        stopAutoScroll();
    }

    private int indexOfPath(String path) {
        for (int i = 0; i < displayedItems.size(); i++) {
            if (displayedItems.get(i).path.equals(path)) return i;
        }
        return -1;
    }

    private void handleDragMove(RecyclerView rv, float x, float y) {
        if (Math.abs(x - dragLastX) < 2 && Math.abs(y - dragLastY) < 2) {
            updateAutoScroll(rv, x, y);
            return;
        }
        dragLastX = x;
        dragLastY = y;

        View child = rv.findChildViewUnder(x, y);
        if (child != null) {
            int pos = rv.getChildAdapterPosition(child);
            if (pos != RecyclerView.NO_POSITION) {
                int anchorIdx = indexOfPath(dragAnchorPath);
                if (anchorIdx >= 0) {
                    applyDragRectangle(anchorIdx, pos);
                }
            }
        }
        updateAutoScroll(rv, x, y);
    }

    private void updateAutoScroll(RecyclerView rv, float x, float y) {
        if (rv == null) { stopAutoScroll(); return; }

        int edge = dpToPx(AUTO_SCROLL_EDGE_DP);
        int h = rv.getHeight();
        int w = rv.getWidth();

        int dy = 0;
        int dx = 0;

        if (y < edge) {
            float t = 1f - (y / (float) edge);
            dy = -(int) (4 + t * 16);
        } else if (y > h - edge) {
            float t = 1f - ((h - y) / (float) edge);
            dy = (int) (4 + t * 16);
        }

        if (x < edge) {
            float t = 1f - (x / (float) edge);
            dx = -(int) (4 + t * 16);
        } else if (x > w - edge) {
            float t = 1f - ((w - x) / (float) edge);
            dx = (int) (4 + t * 16);
        }

        if (dx == 0 && dy == 0) { stopAutoScroll(); return; }

        autoScrollDx = dx;
        autoScrollDy = dy;

        if (!autoScrollActive) {
            autoScrollActive = true;
            rv.postDelayed(autoScrollRunnable, AUTO_SCROLL_TICK_MS);
        }
    }

    private void stopAutoScroll() {
        autoScrollActive = false;
        autoScrollDx = 0;
        autoScrollDy = 0;
        if (recyclerView != null) {
            recyclerView.removeCallbacks(autoScrollRunnable);
        }
    }

    private void applyDragRectangle(int anchorIndex, int currentIndex) {
        int anchorRow = anchorIndex / GRID_COLUMNS;
        int anchorCol = anchorIndex % GRID_COLUMNS;
        int currentRow = currentIndex / GRID_COLUMNS;
        int currentCol = currentIndex % GRID_COLUMNS;

        int minRow = Math.min(anchorRow, currentRow);
        int maxRow = Math.max(anchorRow, currentRow);
        int minCol = Math.min(anchorCol, currentCol);
        int maxCol = Math.max(anchorCol, currentCol);

        if (dragLastRectMinRow == minRow && dragLastRectMaxRow == maxRow
                && dragLastRectMinCol == minCol && dragLastRectMaxCol == maxCol) {
            return;
        }
        dragLastRectMinRow = minRow;
        dragLastRectMaxRow = maxRow;
        dragLastRectMinCol = minCol;
        dragLastRectMaxCol = maxCol;

        Set<String> newRectangle = new HashSet<>();
        int total = displayedItems.size();

        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minCol; c <= maxCol; c++) {
                int idx = r * GRID_COLUMNS + c;
                if (idx >= 0 && idx < total) {
                    newRectangle.add(displayedItems.get(idx).path);
                }
            }
        }

        Set<String> desiredSelection = new HashSet<>(selectionBeforeDrag);
        if (dragSelectDeselectMode) {
            desiredSelection.removeAll(newRectangle);
        } else {
            desiredSelection.addAll(newRectangle);
        }

        if (desiredSelection.equals(selectedItems)) {
            dragRectanglePaths.clear();
            dragRectanglePaths.addAll(newRectangle);
            return;
        }

        selectedItems.clear();
        selectedItems.addAll(desiredSelection);

        Set<Integer> affectedPositions = new HashSet<>();
        for (int i = 0; i < displayedItems.size(); i++) {
            MediaItem item = displayedItems.get(i);
            boolean nowSelected = selectedItems.contains(item.path);
            boolean wasSelected = selectionBeforeDrag.contains(item.path);
            if (nowSelected != wasSelected) affectedPositions.add(i);
        }
        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minCol; c <= maxCol; c++) {
                int idx = r * GRID_COLUMNS + c;
                if (idx >= 0 && idx < displayedItems.size()) affectedPositions.add(idx);
            }
        }
        for (String prev : dragRectanglePaths) {
            if (!newRectangle.contains(prev)) {
                int idx = indexOfPath(prev);
                if (idx >= 0) affectedPositions.add(idx);
            }
        }

        if (adapter != null) {
            for (int p : affectedPositions) {
                adapter.notifyItemChanged(p, "selection");
            }
        }

        dragRectanglePaths.clear();
        dragRectanglePaths.addAll(newRectangle);
        updateSelectionUI();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void moveToTrash(MediaItem item) {
        if (item.isTrashed) return;
        File file = new File(item.path);
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        String parent = file.getParent();
        String name = file.getName();
        String cleanName = cleanFileName(name);
        File trashedFile = new File(parent, ".trashed." + cleanName);

        if (trashedFile.exists()) {
            int count = 1;
            String baseName = cleanName;
            String ext = "";
            int dotIndex = cleanName.lastIndexOf(".");
            if (dotIndex > 0) {
                baseName = cleanName.substring(0, dotIndex);
                ext = cleanName.substring(dotIndex);
            }
            while (trashedFile.exists()) {
                trashedFile = new File(parent, ".trashed." + baseName + "_" + count + ext);
                count++;
            }
        }

        if (!renameFileRobust(file, trashedFile)) {
            Toast.makeText(this, "Could not move to Bin: " + name, Toast.LENGTH_SHORT).show();
            return;
        }

        String oldPath = item.path;
        item.path = trashedFile.getAbsolutePath();
        item.isTrashed = true;
        item.name = trashedFile.getName();

        for (int i = 0; i < mediaItems.size(); i++) {
            if (mediaItems.get(i).path.equals(oldPath)) {
                mediaItems.set(i, item);
                break;
            }
        }
        albumsCacheValid = false;
        trashedIndexDirty = true;
        applyFilter();
    }

    private boolean restoreFromTrash(MediaItem item) {
        File file = new File(item.path);
        if (!file.exists()) return false;

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

        if (!renameFileRobust(file, restoredFile)) return false;

        String oldPath = item.path;
        item.path = restoredFile.getAbsolutePath();
        item.isTrashed = false;
        item.name = restoredFile.getName();

        for (int i = 0; i < mediaItems.size(); i++) {
            if (mediaItems.get(i).path.equals(oldPath)) {
                mediaItems.set(i, item);
                break;
            }
        }

        trashedIndexDirty = true;
        return true;
    }

    private void moveSelectedToTrash() {
        if (selectedItems.isEmpty()) return;

        final List<String> paths = new ArrayList<>(selectedItems);
        final Toast progressToast = Toast.makeText(this, "Moving...", Toast.LENGTH_SHORT);
        progressToast.show();

        executor.execute(() -> {
            int moved = 0, failed = 0;
            final List<String> newTrashPaths = new ArrayList<>();

            for (String path : paths) {
                File f = new File(path);
                if (!f.exists()) { failed++; continue; }

                MediaItem item = new MediaItem(path, f.getName(),
                        isVideoPath(path) ? MediaItem.TYPE_VIDEO : MediaItem.TYPE_IMAGE,
                        0, false, "");

                if (!f.getName().startsWith(".trashed.")) {
                    String newPath = moveToTrashAndReturnPath(item);
                    if (newPath != null) {
                        moved++;
                        newTrashPaths.add(newPath);
                    } else {
                        failed++;
                    }
                } else {
                    moved++;
                }
            }

            if (!newTrashPaths.isEmpty()) {
                MediaScannerConnection.scanFile(
                        this,
                        newTrashPaths.toArray(new String[0]),
                        null, null);
            }

            final int fm = moved, ff = failed;
            runOnUiThread(() -> {
                if (progressToast != null) progressToast.cancel();
                if (!isActivityAlive()) return;

                clearSelection();
                trashedIndexDirty = true;

                applyFilter();
                loadMediaQuiet();

                Toast.makeText(this, "Moved " + fm + " item(s) to Bin"
                                + (ff > 0 ? " (" + ff + " failed)" : ""),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String moveToTrashAndReturnPath(MediaItem item) {
        if (item.isTrashed) return null;
        File file = new File(item.path);
        if (!file.exists()) return null;

        String parent = file.getParent();
        String name = file.getName();
        String cleanName = cleanFileName(name);
        File trashedFile = new File(parent, ".trashed." + cleanName);

        if (trashedFile.exists()) {
            int count = 1;
            String baseName = cleanName;
            String ext = "";
            int dotIndex = cleanName.lastIndexOf(".");
            if (dotIndex > 0) {
                baseName = cleanName.substring(0, dotIndex);
                ext = cleanName.substring(dotIndex);
            }
            while (trashedFile.exists()) {
                trashedFile = new File(parent, ".trashed." + baseName + "_" + count + ext);
                count++;
            }
        }

        if (!renameFileRobust(file, trashedFile)) {
            Toast.makeText(this, "Could not move to Bin: " + name, Toast.LENGTH_SHORT).show();
            return null;
        }

        String oldPath = item.path;
        item.path = trashedFile.getAbsolutePath();
        item.isTrashed = true;
        item.name = trashedFile.getName();

        for (int i = 0; i < mediaItems.size(); i++) {
            if (mediaItems.get(i).path.equals(oldPath)) {
                mediaItems.set(i, item);
                break;
            }
        }

        albumsCacheValid = false;
        trashedIndexDirty = true;
        return item.path;
    }

    private void restoreSelectedItems() {
        if (selectedItems.isEmpty()) return;

        final List<String> paths = new ArrayList<>(selectedItems);
        final Toast progressToast = Toast.makeText(this, "Restoring...", Toast.LENGTH_SHORT);
        progressToast.show();

        executor.execute(() -> {
            int restored = 0, failed = 0;
            final List<String> restoredPaths = new ArrayList<>();

            for (String path : paths) {
                File file = new File(path);
                if (!file.exists()) { failed++; continue; }

                MediaItem item = new MediaItem(path, file.getName(),
                        isVideoPath(path) ? MediaItem.TYPE_VIDEO : MediaItem.TYPE_IMAGE,
                        0, true, "");

                String newPath = restoreFromTrashAndReturnPath(item);
                if (newPath != null) {
                    restored++;
                    restoredPaths.add(newPath);
                } else {
                    failed++;
                }
            }

            if (!restoredPaths.isEmpty()) {
                MediaScannerConnection.scanFile(
                        this,
                        restoredPaths.toArray(new String[0]),
                        null, null);
            }

            final int r = restored, f = failed;
            runOnUiThread(() -> {
                if (progressToast != null) progressToast.cancel();
                if (!isActivityAlive()) return;

                clearSelection();
                trashedIndexDirty = true;

                applyFilter();
                loadMediaQuiet();

                if (f == 0) {
                    Toast.makeText(this, "Restored " + r + " item(s)",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Restored " + r + ", failed " + f,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private String restoreFromTrashAndReturnPath(MediaItem item) {
        File file = new File(item.path);
        if (!file.exists()) return null;

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

        if (!renameFileRobust(file, restoredFile)) return null;

        String oldPath = item.path;
        item.path = restoredFile.getAbsolutePath();
        item.isTrashed = false;
        item.name = restoredFile.getName();

        for (int i = 0; i < mediaItems.size(); i++) {
            if (mediaItems.get(i).path.equals(oldPath)) {
                mediaItems.set(i, item);
                break;
            }
        }

        trashedIndexDirty = true;
        return item.path;
    }

    private void deletePermanentlySelectedItems() {
        if (selectedItems.isEmpty()) return;

        final List<String> paths = new ArrayList<>(selectedItems);

        new AlertDialog.Builder(this)
                .setTitle("Delete Permanently")
                .setMessage("Are you sure you want to permanently delete "
                        + paths.size() + " item(s)? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    final Toast progressToast = Toast.makeText(this, "Deleting...", Toast.LENGTH_SHORT);
                    progressToast.show();

                    executor.execute(() -> {
                        int deleted = 0, failed = 0;

                        for (String path : paths) {
                            File f = new File(path);
                            boolean ok = false;
                            if (f.exists()) {
                                try { ok = f.delete(); } catch (Exception ignored) {}
                            } else ok = true;

                            if (ok) deleted++;
                            else failed++;
                        }
                        final int delCount = deleted, failCount = failed;
                        runOnUiThread(() -> {
                            if (progressToast != null) progressToast.cancel();
                            if (!isActivityAlive()) return;

                            for (String path : paths) {
                                for (int i = mediaItems.size() - 1; i >= 0; i--) {
                                    if (mediaItems.get(i).path.equals(path)) {
                                        mediaItems.remove(i);
                                        break;
                                    }
                                }
                            }

                            albumsCacheValid = false;
                            trashedIndexDirty = true;
                            clearSelection();

                            applyFilter();
                            loadMediaQuiet();

                            if (failCount == 0) {
                                Toast.makeText(this, "Deleted " + delCount + " item(s)",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "Deleted " + delCount + ", failed " + failCount,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String cleanFileName(String name) {
        while (name.startsWith(".trashed.")) name = name.substring(9);
        return name;
    }

    private void toggleSelection(String path) {
        if (selectedItems.contains(path)) selectedItems.remove(path);
        else selectedItems.add(path);
        updateSelectionUI();
        if (adapter != null) adapter.updateSelectedItems(selectedItems);
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
                if (btnBin != null) btnBin.setText("Bin (" + selectedItems.size() + ")");
            }
        }
    }

    private void runOnMain(Runnable r) {
        if (r == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            runOnUiThread(r);
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
        resetDragState();
    }

    private void shareSelectedItems() {
        if (selectedItems.isEmpty()) return;
        try {
            if (selectedItems.size() == 1) {
                File file = new File(selectedItems.get(0));
                if (!file.exists()) {
                    Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(getMimeType(file.getAbsolutePath()));
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share via"));
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                for (String path : selectedItems) {
                    File file = new File(path);
                    if (file.exists()) {
                        uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
                    }
                }
                if (uris.isEmpty()) {
                    Toast.makeText(this, "No valid files", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share files"));
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
            if (!file.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
                return;
            }
            long tsSec = file.lastModified() / 1000L;
            if (tsSec <= 0) tsSec = System.currentTimeMillis() / 1000L;
            StringBuilder info = new StringBuilder();
            info.append("Type: ").append(getFileType(path)).append("\n");
            info.append("Size: ").append(formatFileSize(file.length())).append("\n");
            info.append("Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                    .format(new Date(tsSec * 1000L))).append("\n");
            info.append("Path: ").append(file.getAbsolutePath());
            showInfoDialog(info.toString(), "File Info");
        } else {
            long totalSize = 0; int imageCount = 0, videoCount = 0;
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
            info.append("Selected: ").append(selectedItems.size()).append(" items\n");
            info.append("Total Size: ").append(formatFileSize(totalSize)).append("\n");
            info.append("Images: ").append(imageCount).append("\n");
            info.append("Videos: ").append(videoCount);
            showInfoDialog(info.toString(), "Multi-Select Info");
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

    private void setupVideoControls() {
        if (videoPlayerContainer == null) return;
        videoPlayerContainer.setOnTouchListener((v, event) -> true);
    }

    private String formatTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void loadAlbums() {
        if (albumsCacheValid && !albumList.isEmpty()) {
            runOnUiThread(() -> {
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                albumRecycler.setVisibility(View.VISIBLE);
                showAlbumGrid();
            });
            return;
        }

        executor.execute(() -> {
            LinkedHashMap<String, String> albums = new LinkedHashMap<>();

            // ── 1. WhatsApp virtual albums ──
            File root = Environment.getExternalStorageDirectory();
            if (root != null) {
                File waImages1 = new File(root,
                        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
                if (waImages1.exists() && waImages1.isDirectory()) {
                    File[] top = waImages1.listFiles();
                    boolean hasAny = false;
                    if (top != null) {
                        for (File f : top) {
                            if (f != null && f.isFile()) { hasAny = true; break; }
                        }
                    }
                    if (hasAny) albums.put("whatsapp://all", "WhatsApp");
                }

                File accountsDir = new File(root,
                        "Android/media/com.whatsapp/WhatsApp/accounts");
                if (accountsDir.exists() && accountsDir.isDirectory()) {
                    File[] accounts = accountsDir.listFiles();
                    if (accounts != null) {
                        for (File acc : accounts) {
                            if (acc == null || !acc.isDirectory()) continue;
                            File waImages = new File(acc, "Media/WhatsApp Images");
                            if (!waImages.exists() || !waImages.isDirectory()) continue;

                            File[] top = waImages.listFiles();
                            boolean hasAny = false;
                            if (top != null) {
                                for (File f : top) {
                                    if (f != null && f.isFile()) { hasAny = true; break; }
                                }
                            }
                            if (!hasAny) continue;

                            String accName = acc.getName();
                            albums.put("whatsapp://" + accName,
                                    "WhatsApp (" + accName + ")");
                        }
                    }
                }
            }

            // ── 2. MediaStore image sweep ──
            String[] imgProjection = {
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            };
            Cursor imgCursor = null;
            try {
                imgCursor = getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imgProjection, null, null, null);
                if (imgCursor != null) {
                    int dataIdx = imgCursor.getColumnIndex(MediaStore.Images.Media.DATA);
                    int nameIdx = imgCursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                    while (imgCursor.moveToNext()) {
                        String path = dataIdx >= 0 ? imgCursor.getString(dataIdx) : null;
                        String name = nameIdx >= 0 ? imgCursor.getString(nameIdx) : null;
                        if (path == null) continue;
                        if (path.contains("/com.whatsapp/")) continue;
                        File parent = new File(path).getParentFile();
                        if (parent == null || !parent.exists() || !parent.isDirectory()) continue;
                        String parentPath = getCachedCanonical(parent.getAbsolutePath());
                        String display = (name != null && !name.isEmpty()) ? name : parent.getName();
                        if (!albums.containsKey(parentPath)) albums.put(parentPath, display);
                    }
                }
            } catch (Exception ignored) {
            } finally { if (imgCursor != null) imgCursor.close(); }

            // ── 3. MediaStore video sweep ──
            String[] vidProjection = {
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            };
            Cursor vidCursor = null;
            try {
                vidCursor = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        vidProjection, null, null, null);
                if (vidCursor != null) {
                    int dataIdx = vidCursor.getColumnIndex(MediaStore.Video.Media.DATA);
                    int nameIdx = vidCursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    while (vidCursor.moveToNext()) {
                        String path = dataIdx >= 0 ? vidCursor.getString(dataIdx) : null;
                        String name = nameIdx >= 0 ? vidCursor.getString(nameIdx) : null;
                        if (path == null) continue;
                        if (path.contains("/com.whatsapp/")) continue;
                        File parent = new File(path).getParentFile();
                        if (parent == null || !parent.exists() || !parent.isDirectory()) continue;
                        String parentPath = getCachedCanonical(parent.getAbsolutePath());
                        String display = (name != null && !name.isEmpty()) ? name : parent.getName();
                        if (!albums.containsKey(parentPath)) albums.put(parentPath, display);
                    }
                }
            } catch (Exception ignored) {
            } finally { if (vidCursor != null) vidCursor.close(); }

            // ── 4. Filesystem sweep ──
            List<File> roots = getStorageDirectoriesProper();
            for (File r : roots) {
                if (r != null && r.exists()) {
                    scanFoldersForMedia(r, albums, 0, 8);
                }
            }

            // ── 5. Validate: drop empty albums and the storage root ──
            File storageRoot = Environment.getExternalStorageDirectory();
            String storageRootPath = storageRoot != null
                    ? getCachedCanonical(storageRoot.getAbsolutePath()) : null;

            Iterator<Map.Entry<String, String>> it = albums.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                String key = e.getKey();

                if (key.startsWith("whatsapp://")) continue;

                if (storageRootPath != null && key.equals(storageRootPath)) {
                    it.remove();
                    continue;
                }

                File dir = new File(key);
                if (!dir.exists() || !dir.isDirectory()) { it.remove(); continue; }

                File[] children = dir.listFiles();
                if (children == null || children.length == 0) { it.remove(); continue; }

                boolean hasAnyMedia = false;
                for (File f : children) {
                    if (f.isFile() && isMediaFile(f.getName())
                            && !f.getName().contains(".trashed.")) {
                        hasAnyMedia = true;
                        break;
                    }
                    if (f.isDirectory()) {
                        File[] sub = f.listFiles();
                        if (sub == null) continue;
                        for (File s : sub) {
                            if (s.isFile() && isMediaFile(s.getName())
                                    && !s.getName().contains(".trashed.")) {
                                hasAnyMedia = true;
                                break;
                            }
                        }
                    }
                    if (hasAnyMedia) break;
                }
                if (!hasAnyMedia) it.remove();
            }

            // ── 6. Sort: WhatsApp first, then alpha ──
            List<String> paths = new ArrayList<>(albums.keySet());
            Collections.sort(paths, (a, b) -> {
                boolean aWa = a.startsWith("whatsapp://");
                boolean bWa = b.startsWith("whatsapp://");
                if (aWa != bWa) return aWa ? -1 : 1;
                String da = albums.get(a);
                String db = albums.get(b);
                if (da == null) da = "";
                if (db == null) db = "";
                return da.compareToIgnoreCase(db);
            });

            Map<String, Integer> nameCounts = new HashMap<>();
            for (String p : paths) {
                if (p.startsWith("whatsapp://")) continue;
                String d = albums.get(p);
                if (d == null) d = "";
                nameCounts.put(d, nameCounts.getOrDefault(d, 0) + 1);
            }

            Map<String, String> finalDisplayNames = new HashMap<>();
            for (String p : paths) {
                if (p.startsWith("whatsapp://")) {
                    String base = albums.get(p);
                    finalDisplayNames.put(p, base != null ? base : "WhatsApp");
                    continue;
                }
                String d = albums.get(p);
                if (d == null) d = "";
                if (nameCounts.get(d) > 1) {
                    File parent = new File(p).getParentFile();
                    String parentName = parent != null ? parent.getName() : "";
                    finalDisplayNames.put(p, d + " (" + parentName + ")");
                } else {
                    finalDisplayNames.put(p, d);
                }
            }

            final List<String> finalPaths = new ArrayList<>(paths);
            final Map<String, String> finalNames = new HashMap<>(finalDisplayNames);

            runOnUiThread(() -> {
                albumList.clear();
                albumDisplayNames.clear();
                albumList.addAll(finalPaths);
                albumDisplayNames.putAll(finalNames);
                albumsCacheValid = true;

                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                showAlbumGrid();
            });
        });
    }

    private void scanFoldersForMedia(File directory,
                                     LinkedHashMap<String, String> albums,
                                     int depth, int maxDepth) {
        if (depth > maxDepth || directory == null
                || !directory.exists() || !directory.isDirectory()) return;

        File[] files;
        try { files = directory.listFiles(); }
        catch (Exception e) { return; }
        if (files == null) return;

        String dirPath = directory.getAbsolutePath();
        if (dirPath.contains("/com.whatsapp/")) return;

        boolean hasMedia = false;
        for (File f : files) {
            if (f.isFile() && isMediaFile(f.getName())
                    && !f.getName().contains(".trashed.")) {
                hasMedia = true;
                break;
            }
            if (f.isDirectory()) {
                File[] sub = f.listFiles();
                if (sub != null) {
                    for (File s : sub) {
                        if (s.isFile() && isMediaFile(s.getName())
                                && !s.getName().contains(".trashed.")) {
                            hasMedia = true;
                            break;
                        }
                    }
                }
                if (hasMedia) break;
            }
        }

        if (hasMedia) {
            String path = getCachedCanonical(directory.getAbsolutePath());
            if (!albums.containsKey(path)) albums.put(path, directory.getName());
        }

        String name = directory.getName().toLowerCase(Locale.ROOT);
        if (name.equals("android") || name.equals("system")
                || name.equals("cache") || name.equals("tmp")
                || name.equals("lost+found") || name.equals("app")
                || name.equals("data") || name.equals("obb")) return;

        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                scanFoldersForMedia(f, albums, depth + 1, maxDepth);
            }
        }
    }

    private boolean isMediaFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".webp")
                || lower.endsWith(".heic") || lower.endsWith(".heif")
                || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".3gp")
                || lower.endsWith(".m4v") || lower.endsWith(".flv")
                || lower.endsWith(".wmv");
    }

    private void showAlbumGrid() {
        if (albumList.isEmpty()) {
            Toast.makeText(this, "No albums found", Toast.LENGTH_SHORT).show();
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            return;
        }

        List<String> displayList = new ArrayList<>(albumList.size());
        for (String path : albumList) {
            String d = albumDisplayNames.get(path);
            if (d == null || d.isEmpty()) d = new File(path).getName();
            displayList.add(d);
        }

        // Build the adapter (was missing — this is why the grid stayed blank)
        albumAdapter = new AlbumAdapter(
                this,
                displayList,
                albumPathList(),
                displayName -> {
                    // Resolve displayName → canonical path
                    String chosenPath = null;
                    for (String p : albumList) {
                        String d = albumDisplayNames.get(p);
                        if (d == null) d = new File(p).getName();
                        if (d.equals(displayName)) { chosenPath = p; break; }
                    }
                    if (chosenPath == null) return;

                    if (chosenPath.startsWith("whatsapp://")) {
                        currentAlbum = null;
                        whatsappOnly = true;
                        whatsappPath = chosenPath;
                        showAlbums = false;

                        titleView.setText(albumDisplayNames.get(chosenPath) != null
                                ? albumDisplayNames.get(chosenPath) : "WhatsApp");

                        albumRecycler.setVisibility(View.GONE);
                        recyclerView.setVisibility(View.VISIBLE);

                        showWhatsAppOnly(chosenPath);
                        updateBarsVisibility();
                        updateTopNavBar();
                        return;
                    }

                    whatsappOnly = false;
                    whatsappPath = null;
                    currentAlbum = chosenPath;
                    showAlbums = false;

                    String shown = albumDisplayNames.get(chosenPath);
                    if (shown == null) shown = new File(chosenPath).getName();
                    titleView.setText(shown);

                    albumRecycler.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);

                    applyFilter();
                    updateBarsVisibility();
                    updateTopNavBar();
                });

        albumRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        albumRecycler.setAdapter(albumAdapter);
        albumRecycler.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
        titleView.setText("Albums");
    }

    private List<String> albumPathList() {
        return new ArrayList<>(albumList);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
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

        Set<String> favs = loadFavoritePaths();
        boolean anyChanged = false;

        for (String path : selectedItems) {
            if (favs.contains(path)) {
                favs.remove(path);
                anyChanged = true;
            } else {
                favs.add(path);
                anyChanged = true;
            }

            for (MediaItem item : mediaItems) {
                if (item.path.equals(path)) {
                    item.isFavorite = favs.contains(path);
                    break;
                }
            }
        }

        if (anyChanged) {
            saveFavoritePaths(favs);

            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            applyFilter();

            Toast.makeText(this, "Favorites updated", Toast.LENGTH_SHORT).show();
        }

        clearSelection();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenOverlay != null && fullscreenOverlay.getVisibility() == View.VISIBLE) {
            closeFullscreenViewer(); return;
        }
        if (whatsappOnly) {
            whatsappOnly = false;
            whatsappPath = null;
            showAlbums = true;
            loadAlbums();
            albumRecycler.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            titleView.setText("Albums");
            updateTopNavBar();
            updateBarsVisibility();
            return;
        }
        if (currentAlbum != null || showAlbums) { navigateBackFromAlbum(); return; }
        if (selectionMode) { clearSelection(); return; }
        super.onBackPressed();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isVideoPlaying = false;
        if (videoView != null) videoView.stopPlayback();
        if (currentFullscreenVideo != null) {
            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
            currentFullscreenVideo = null;
        }
        currentFullscreenVideoPosition = -1;
        videoHandler.removeCallbacks(updateVideoProgress);
        videoHandler.removeCallbacks(hideControlsRunnable);
        videoHandler.removeCallbacks(overlayProgressRunnable);
        videoHandler.removeCallbacks(overlayHideControlsRunnable);
        mediaRefreshHandler.removeCallbacksAndMessages(null);
        hideMediaHudImmediately();
        stopAutoScroll();
        pendingMediaRefresh = null;
        executor.shutdown();
    }

    private void setupVolumeAndBrightnessGestures() {
        if (fullscreenOverlay == null) return;

        final AudioManager audioManager =
                (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        final int maxVolume = audioManager != null
                ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                : 15;

        final float STEP_PX_VOLUME     = 45f;
        final float STEP_PX_BRIGHTNESS = 45f;
        final float BRIGHTNESS_STEPS = 20f;
        final float BRIGHTNESS_STEP_VALUE = 1.0f / BRIGHTNESS_STEPS;
        final int DEAD_ZONE_PX = 12;

        final long DOUBLE_TAP_TIMEOUT =
                android.view.ViewConfiguration.getDoubleTapTimeout();

        final float[] startY = {0f};
        final float[] startX = {0f};
        final float[] lastY  = {0f};
        final boolean[] isVolumeSide = {false};
        final boolean[] committed = {false};
        final boolean[] pagerLocked = {false};
        final float[] accumulator = {0f};
        final int[]   appliedVolume = {-1};
        final float[] appliedBrightness = {-1f};

        final boolean[] tapPending = {false};
        final Runnable[] pendingTap = {null};
        final boolean[] suppressNextUpTap = {false};

        final Runnable lockPager = () -> {
            if (fullscreenViewPager != null && !pagerLocked[0]) {
                fullscreenViewPager.setUserInputEnabled(false);
                pagerLocked[0] = true;
            }
        };
        final Runnable unlockPager = () -> {
            if (fullscreenViewPager != null && pagerLocked[0]) {
                fullscreenViewPager.setUserInputEnabled(true);
                pagerLocked[0] = false;
            }
        };

        final View.OnTouchListener handler = (v, event) -> {
            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN: {
                    if (tapPending[0] && pendingTap[0] != null) {
                        videoHandler.removeCallbacks(pendingTap[0]);
                        tapPending[0] = false;
                        pendingTap[0] = null;
                        suppressNextUpTap[0] = true;
                    } else {
                        suppressNextUpTap[0] = false;
                    }

                    startY[0] = event.getY();
                    startX[0] = event.getX();
                    lastY[0]  = event.getY();
                    committed[0] = false;
                    accumulator[0] = 0f;
                    appliedVolume[0] = -1;
                    appliedBrightness[0] = -1f;

                    float w = v.getWidth();
                    isVolumeSide[0] = (w > 0) && (event.getX() >= w / 2f);

                    if (isVolumeSide[0] && audioManager != null) {
                        try {
                            appliedVolume[0] = audioManager.getStreamVolume(
                                    AudioManager.STREAM_MUSIC);
                        } catch (Exception ignored) {}
                    } else {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        appliedBrightness[0] = lp.screenBrightness < 0
                                ? 0.5f : lp.screenBrightness;
                    }
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    float currentY = event.getY();
                    float currentX = event.getX();

                    float dyTotal = Math.abs(currentY - startY[0]);
                    float dxTotal = Math.abs(currentX - startX[0]);

                    if (dyTotal > DEAD_ZONE_PX || dxTotal > DEAD_ZONE_PX) {
                        if (tapPending[0] && pendingTap[0] != null) {
                            videoHandler.removeCallbacks(pendingTap[0]);
                            tapPending[0] = false;
                            pendingTap[0] = null;
                        }
                        suppressNextUpTap[0] = false;
                    }

                    if (!committed[0]) {
                        if (dyTotal < DEAD_ZONE_PX && dxTotal < DEAD_ZONE_PX) {
                            return true;
                        }
                        if (dyTotal >= dxTotal) {
                            committed[0] = true;
                            lockPager.run();
                            lastY[0] = currentY;
                            accumulator[0] = 0f;
                        } else {
                            committed[0] = false;
                            unlockPager.run();
                            return false;
                        }
                    }

                    float dyPixels = lastY[0] - currentY;
                    lastY[0] = currentY;
                    accumulator[0] += dyPixels;

                    if (isVolumeSide[0]) {
                        if (audioManager == null) return true;

                        boolean changed = false;
                        while (accumulator[0] >= STEP_PX_VOLUME) {
                            accumulator[0] -= STEP_PX_VOLUME;
                            appliedVolume[0] = Math.min(maxVolume,
                                    Math.max(0, appliedVolume[0] + 1));
                            changed = true;
                        }
                        while (accumulator[0] <= -STEP_PX_VOLUME) {
                            accumulator[0] += STEP_PX_VOLUME;
                            appliedVolume[0] = Math.min(maxVolume,
                                    Math.max(0, appliedVolume[0] - 1));
                            changed = true;
                        }

                        try {
                            audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    appliedVolume[0], 0);
                        } catch (Exception ignored) {}

                        if (changed) {
                            int percent = maxVolume > 0
                                    ? (int) Math.round(
                                    (appliedVolume[0] * 100.0) / maxVolume)
                                    : 0;
                            percent = Math.max(0, Math.min(100, percent));
                            showMediaHud(true, percent);
                        }
                    } else {
                        int stepAdvances = 0;
                        while (accumulator[0] >= STEP_PX_BRIGHTNESS) {
                            accumulator[0] -= STEP_PX_BRIGHTNESS;
                            stepAdvances++;
                        }
                        while (accumulator[0] <= -STEP_PX_BRIGHTNESS) {
                            accumulator[0] += STEP_PX_BRIGHTNESS;
                            stepAdvances--;
                        }

                        if (stepAdvances != 0) {
                            appliedBrightness[0] = Math.min(1f, Math.max(0.02f,
                                    appliedBrightness[0]
                                            + stepAdvances * BRIGHTNESS_STEP_VALUE));

                            try {
                                WindowManager.LayoutParams lp = getWindow().getAttributes();
                                lp.screenBrightness = appliedBrightness[0];
                                getWindow().setAttributes(lp);
                            } catch (Exception ignored) {}

                            int percent = (int) Math.round(appliedBrightness[0] * 100);
                            percent = Math.max(0, Math.min(100, percent));
                            showMediaHud(false, percent);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    unlockPager.run();

                    boolean wasTap = !committed[0]
                            && Math.abs(event.getY() - startY[0]) < DEAD_ZONE_PX
                            && Math.abs(event.getX() - startX[0]) < DEAD_ZONE_PX;

                    if (wasTap) {
                        if (suppressNextUpTap[0]) {
                            suppressNextUpTap[0] = false;
                        } else {
                            final Runnable[] holder = new Runnable[1];
                            holder[0] = () -> {
                                tapPending[0] = false;
                                pendingTap[0] = null;
                                onFullscreenTap();
                            };
                            pendingTap[0] = holder[0];
                            tapPending[0] = true;
                            videoHandler.postDelayed(holder[0], DOUBLE_TAP_TIMEOUT);
                        }
                    } else {
                        suppressNextUpTap[0] = false;
                    }

                    committed[0] = false;
                    accumulator[0] = 0f;
                    appliedVolume[0] = -1;
                    appliedBrightness[0] = -1f;
                    return true;
                }
            }
            return true;
        };

        fullscreenOverlay.setOnTouchListener(handler);
        this.volumeBrightnessHandler = handler;
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