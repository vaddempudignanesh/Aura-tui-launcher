package ohi.andre.consolelauncher;

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
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class GalleryActivity extends AppCompatActivity {

    private static final String LOG_TAG = "gallery-tui";
    private final String idHash = Integer.toHexString(System.identityHashCode(this));

    private boolean userIsSwiping = false;
    private String src() { return "[GalleryActivity:" + idHash + "]"; }
    private void log(String msg) { Log.d(LOG_TAG, src() + " " + msg); }

    // ── Favorites persistence ──
    private static final String PREFS_NAME = "gallery_prefs";
    private static final String PREF_FAVORITES = "favorite_paths";
    private SharedPreferences prefs;

    // ── Media refresh debounce ──
    private final Handler mediaRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingMediaRefresh;
    private static final long MEDIA_REFRESH_DEBOUNCE_MS = 400L;

    // ── Views ──
    private RecyclerView recyclerView;
    private RecyclerView albumRecycler;
    private GalleryAdapter adapter;

    // ── Auto-scroll during drag-select ──
    private static final int AUTO_SCROLL_EDGE_DP = 80;
    private static final long AUTO_SCROLL_TICK_MS = 16L;   // ~60 fps
    private boolean autoScrollActive = false;
    private int autoScrollDx = 0;
    private int autoScrollDy = 0;


    // ═══ Volume / Brightness HUD — plain percentage only ═══
    private TextView mediaHudVolume;
    private TextView mediaHudBrightness;
    private Runnable mediaHudHideRunnable;
    private static final long MEDIA_HUD_VISIBLE_MS = 800L;

    private View fullscreenInfoHeader;
    private TextView fullscreenInfoName;
    private TextView fullscreenInfoDetails;

    // ── Drag-to-select state ──
    //  "anchorPath" is the item that was long-pressed. Its selection state
    //  is decided ONCE by the initial press and never toggled again by the
    //  drag path. Only other items are toggled as the finger moves.
    private boolean dragSelectActive = false;
    private boolean dragSelectDeselectMode = false;
    private String dragAnchorPath = null;
    private final Set<Integer> dragVisitedPositions = new HashSet<>();
    private TextView fullscreenInfoPath;
    private boolean fullscreenChromeVisible = false;

    private float dragLastY = -1f;
    private float dragLastX = -1f;
    private int fullscreenCurrentIndex = 0;

    private AlbumAdapter albumAdapter;
    private List<MediaItem> mediaItems = new ArrayList<>();
    private List<MediaItem> displayedItems = new ArrayList<>();
    private List<String> albumList = new ArrayList<>();
    private final LinkedHashMap<String, String> albumDisplayNames = new LinkedHashMap<>();
    private boolean showAlbums = false;

    private final HashMap<String, String> canonicalAlbumCache = new HashMap<>();
    private boolean albumsCacheValid = false;

    // ═══ Fast bin cache ═══
    //  Scanning the filesystem for ".trashed." files takes a long time on
    //  big SD cards. We cache the result and refresh it lazily.
    private List<MediaItem> cachedTrashedItems = null;
    private long cachedTrashedTimestamp = 0L;
    private static final long TRASHED_CACHE_TTL_MS = 2000L;

    // ── Range-select rectangle math ──
    private static final int GRID_COLUMNS = 3;
    // Snapshot of what was selected BEFORE this drag started. Used to
// recompute the entire rectangle each time the finger moves.
    private final Set<String> selectionBeforeDrag = new HashSet<>();
    // Set of items the current rectangle covers. Recomputed on every move.
    private final Set<String> dragRectanglePaths = new HashSet<>();

    // ── Fullscreen ──
    private RelativeLayout fullscreenOverlay;
    private ViewPager2 fullscreenViewPager;

    private int overlayPendingSeekMs = -1;

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

    // ── Rectangle-change guard (skip no-op UI updates during drag) ──
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
    private List<String> selectedItems = new ArrayList<>();
    private LinearLayout bottomBar, selectionBar, binBottomBar;
    private View selectionTopBar;
    private TextView selectionCount;

    private enum FilterMode { ALL, IMAGES, VIDEOS, FAVORITES, BIN }
    private FilterMode currentFilter = FilterMode.ALL;

    // ── Legacy video player (unused) ──
    private RelativeLayout videoPlayerContainer;
    private VideoView videoView;
    private ImageButton btnPlayPause, btnCloseVideo;
    private TextView videoTime;
    private LinearLayout videoCenterControls, videoBottomControls;
    private Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable updateVideoProgress;
    private Runnable hideControlsRunnable;
    private boolean isLandscape = false;
    private boolean isPlaying = false;
    private boolean isVideoPlaying = false;
    private boolean controlsVisible = true;
    private static final int CONTROLS_TIMEOUT = 3000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean isGranted = true;
                        for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                            if (!entry.getValue()) { isGranted = false; break; }
                        }
                        if (isGranted) loadMedia();
                        else { Toast.makeText(this, "Permission denied.", Toast.LENGTH_LONG).show(); finish(); }
                    });

    private boolean isActivityAlive() { return !isFinishing() && !isDestroyed(); }

    // ═════════════════════════════════════════════════════════════
    //  onCreate
    // ═════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log("========================================================");
        log("onCreate — activity hash=" + idHash);
        log("========================================================");
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

        // ── Bind main views ──
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
            if (btnRestore != null) btnRestore.setOnClickListener(v -> {
                animateButtonBounce(v);
                restoreSelectedItems();
            });
            if (btnDeletePermanent != null) btnDeletePermanent.setOnClickListener(v -> {
                animateButtonBounce(v);
                deletePermanentlySelectedItems();
            });
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
            animateButtonBounce(v);
            currentFilter = FilterMode.ALL; currentAlbum = null;
            titleView.setText("Gallery"); showAlbums = false;
            albumRecycler.setVisibility(View.GONE); recyclerView.setVisibility(View.VISIBLE);
            applyFilter(); sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });

        btnAlbums.setOnClickListener(v -> {
            animateButtonBounce(v);
            if (showAlbums) {
                showAlbums = false; albumRecycler.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE); applyFilter();
            } else {
                showAlbums = true; loadAlbums();
                recyclerView.setVisibility(View.GONE); albumRecycler.setVisibility(View.VISIBLE);
                sortOptions.setVisibility(View.GONE);
            }
            updateBarsVisibility();
        });

        btnSort.setOnClickListener(v -> {
            animateButtonBounce(v);
            sortOptions.setVisibility(sortOptions.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });

        // ── Bind fullscreen overlay views ──
        fullscreenOverlay = findViewById(R.id.fullscreenOverlay);
        fullscreenViewPager = findViewById(R.id.fullscreenViewPager);
        fullscreenInfoHeader = findViewById(R.id.fullscreenInfoHeader);
        fullscreenInfoName = findViewById(R.id.fullscreenInfoName);
        fullscreenInfoDetails = findViewById(R.id.fullscreenInfoDetails);
        fullscreenInfoPath = findViewById(R.id.fullscreenInfoPath);

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

        // ── Wire bottom action bar buttons ──
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
                        nowFav ? "⭐ Added to Favorites" : "Removed from Favorites",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // ── Gesture detectors ──
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
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (!currentFullscreenPageIsVideo || currentFullscreenVideo == null)
                            return false;

                        float tapX = e.getX();
                        float w = fullscreenOverlay.getWidth();
                        if (w <= 0) return false;
                        float leftThird  = w / 3f;
                        float rightThird = w * 2f / 3f;

                        if (tapX < leftThird) {
                            skipByMs(-SKIP_BACKWARD_MS);
                        } else if (tapX > rightThird) {
                            skipByMs(SKIP_FORWARD_MS);
                        } else {
                            toggleOverlayPlayPause();
                        }
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
                        } catch (Exception ex) {
                            Log.w(LOG_TAG, "long-press speed failed", ex);
                        }
                    }
                });

        setupOverlayVideoControls();

        // ── Fullscreen adapter ──
        fullscreenAdapter = new FullscreenAdapter(fullscreenMediaPaths, this);
        log("fullscreenAdapter created: " + Integer.toHexString(System.identityHashCode(fullscreenAdapter)));
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
            }

            @Override
            public void onVideoVisible(CustomVideoView videoView, int position) {
                if (position == fullscreenCurrentPosition) {
                    currentFullscreenPageIsVideo = true;
                    currentFullscreenVideo = videoView;
                    currentFullscreenVideoPosition = position;
                    isOverlayVideoPlaying = true;
                    overlayPendingSeekMs = -1;
                    try { videoView.start(); } catch (Exception ignored) {}
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
                if (currentFullscreenVideo != null
                        && currentFullscreenVideoPosition != position) {
                    try { currentFullscreenVideo.pause(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                    currentFullscreenPageIsVideo = false;
                    isOverlayVideoPlaying = false;
                    videoHandler.removeCallbacks(overlayProgressRunnable);
                }

                overlayPendingSeekMs = -1;
                fullscreenCurrentPosition = position;
                fullscreenCurrentIndex = position;

                updateFullscreenInfo(position);

                if (position >= 0 && position < fullscreenMediaPaths.size()) {
                    String path = fullscreenMediaPaths.get(position);
                    if (isVideoPath(path)) {
                        findAndStartVideoForPosition(position);
                    }
                }
            }
        });

        btnBinSelected.setOnClickListener(v -> {
            animateButtonBounce(v);
            moveSelectedToTrash();
        });
        btnShareSelected.setOnClickListener(v -> {
            animateButtonBounce(v);
            shareSelectedItems();
        });
        btnInfoSelected.setOnClickListener(v -> {
            animateButtonBounce(v);
            showSelectedItemInfo();
        });
        btnFavoriteSelected.setOnClickListener(v -> {
            animateButtonBounce(v);
            addSelectedToFavorites();
        });

        sortImages.setOnClickListener(v -> {
            animateButtonBounce(v);
            currentFilter = FilterMode.IMAGES; currentAlbum = null;
            titleView.setText("📷 Images"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });
        sortVideos.setOnClickListener(v -> {
            animateButtonBounce(v);
            currentFilter = FilterMode.VIDEOS; currentAlbum = null;
            titleView.setText("🎬 Videos"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });
        sortFavorites.setOnClickListener(v -> {
            animateButtonBounce(v);
            currentFilter = FilterMode.FAVORITES; currentAlbum = null;
            titleView.setText("⭐ Favorites"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });
        sortBin.setOnClickListener(v -> {
            animateButtonBounce(v);
            currentFilter = FilterMode.BIN; currentAlbum = null;
            titleView.setText("🗑️ Bin"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });

        setupVideoControls();
        checkAndRequestMediaPermissions();

        View topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) updateTopNavBar();
    }

    // ═════════════════════════════════════════════════════════════
    //  Volume / Brightness HUD
    // ═════════════════════════════════════════════════════════════

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

    // ===================== EFFECTIVE SEEK POSITION =====================

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

    // ===================== CANONICAL PATH CACHE =====================

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

    // ===================== FAVORITES =====================

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
        if (favs.contains(path)) { favs.remove(path); nowFav = false; }
        else { favs.add(path); nowFav = true; }
        saveFavoritePaths(favs);

        for (MediaItem item : mediaItems) {
            if (item.path.equals(path)) { item.isFavorite = nowFav; break; }
        }
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

    // ===================== TAP TOGGLING =====================

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
        overlayHideControlsRunnable = () -> {
            if (!userIsSwiping) hideAllControls();
        };
        videoHandler.postDelayed(overlayHideControlsRunnable, OVERLAY_CONTROLS_TIMEOUT);
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

    // ===================== RENAME + SCAN + REFRESH =====================

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
            cachedTrashedItems = null;
            loadMedia();
            pendingMediaRefresh = null;
        };
        mediaRefreshHandler.postDelayed(pendingMediaRefresh, delayMs);
    }

    // ===================== OVERLAY VIDEO CONTROLS =====================

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
                    Toast.makeText(this, nowFav ? "⭐ Added" : "Removed",
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

    // ===================== VIDEO HELPERS =====================

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
                                if (btnCenterPlayPause != null)
                                    btnCenterPlayPause.setImageResource(
                                            android.R.drawable.ic_media_pause);
                                startOverlayProgressUpdate();
                            } catch (Exception ignored) {}
                        });

                        if (currentFullscreenVideo == vv
                                && currentFullscreenVideoPosition == targetPosition) {
                            vv.start();
                            isOverlayVideoPlaying = true;
                            showAllControlsWithTimeout();
                            return;
                        }

                        currentFullscreenVideo = vv;
                        currentFullscreenVideoPosition = targetPosition;
                        currentFullscreenPageIsVideo = true;
                        isOverlayVideoPlaying = true;
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

                        vv.start();
                        if (btnCenterPlayPause != null)
                            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
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
        } catch (Exception e) {
            Log.e(LOG_TAG, src() + " findAndStartVideo threw", e);
        }
    }

    // ===================== ORIENTATION =====================

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

    // ===================== FULLSCREEN OPEN/CLOSE =====================

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
            String size = file.exists() ? formatFileSize(file.length()) : "—";
            fullscreenInfoDetails.setText(getFileType(path) + " · " + size);
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
        info.append("📄 File: ").append(file.getName()).append("\n");
        info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
        info.append("📅 Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm",
                Locale.getDefault()).format(new Date(tsSec * 1000L))).append("\n");
        info.append("🔤 Type: ").append(getFileType(path)).append("\n");
        info.append("📍 Path: ").append(file.getAbsolutePath());
        showInfoDialog(info.toString(), "📄 File Info");
    }

    // ===================== NAV / BARS =====================

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
        else if (currentAlbum != null) {
            String shown = albumDisplayNames.get(currentAlbum);
            title.setText("📁 " + (shown != null ? shown : new File(currentAlbum).getName()));
        }
        else if (showAlbums) title.setText("📁 Albums");
        else title.setText("Gallery");

        btnBack.setOnClickListener(v -> {
            if (currentFilter == FilterMode.BIN) {
                currentFilter = FilterMode.ALL;
                applyFilter(); updateTopNavBar(); updateBarsVisibility();
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
        if (currentAlbum != null) {
            currentAlbum = null;
            showAlbums = true;

            recyclerView.setVisibility(View.GONE);
            loadAlbums();
            albumRecycler.setVisibility(View.VISIBLE);
            albumRecycler.setBackgroundColor(Color.parseColor("#FF000000"));
            titleView.setText("Albums");

            applyFilter();
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

    private void loadMedia() {
        loadMediaInternal(true);
    }

    /**
     * Quiet rescan used after file operations (move-to-bin / restore / delete).
     * Repopulates `mediaItems` on a background thread, then refreshes the
     * existing adapter in place — does NOT create a new adapter, does NOT
     * reset the scroll position, and does NOT cause a flash of the home list.
     */
    private void loadMediaQuiet() {
        loadMediaInternal(false);
    }

    /**
     * @param fullRefresh when true → applyFilter() + setupRecyclerView() +
     *                    showEmptyState() (used on first load / permission grant).
     *                    when false → only repopulates mediaItems and calls
     *                    applyFilter() in place (used after file operations).
     */
    private void loadMediaInternal(boolean fullRefresh) {
        executor.execute(() -> {
            // ── 1. MediaStore (fast, includes app-added files) ──
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
            } catch (SecurityException e) {
                // ignore
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
            } catch (SecurityException e) {
                // ignore
            } finally {
                if (videoCursor != null) videoCursor.close();
            }

            // ── 2. Filesystem fallback — catch what MediaStore missed ──
            List<File> roots = getStorageDirectoriesProper();
            for (File root : roots) {
                if (root != null && root.exists()) {
                    scanFilesystemForMedia(root, newItems, knownPaths, 0, 6);
                }
            }

            // ── 3. Sort newest first ──
            //    Use the file's own lastModified (converted to seconds) as the
            //    authoritative timestamp so the visible "Modified" value in the
            //    Info dialog always matches the sort order.
            for (MediaItem item : newItems) {
                try {
                    File f = new File(item.path);
                    long fileSec = f.lastModified() / 1000L;
                    if (fileSec > 0) item.dateModified = fileSec;
                } catch (Exception ignored) {}
            }
            Collections.sort(newItems, (a, b) -> Long.compare(b.dateModified, a.dateModified));

            // ── 4. Apply favorites ──
            Set<String> favs = loadFavoritePaths();
            for (MediaItem item : newItems) {
                item.isFavorite = favs.contains(item.path);
            }

            // ── 5. Hand the result to the UI thread ──
            final List<MediaItem> result = newItems;
            final boolean fullRefreshFinal = fullRefresh;
            runOnMain(() -> {
                if (!isActivityAlive()) return;
                mediaItems.clear();
                mediaItems.addAll(result);
                if (fullRefreshFinal) {
                    applyFilter();
                    setupRecyclerView();
                    showEmptyState();
                } else {
                    applyFilter();
                    showEmptyState();
                }
            });
        });
    }

    /**
     * Returns the best-known modification timestamp for a media file, in
     * SECONDS since epoch (matching MediaStore.DATE_MODIFIED).
     *
     * Preference order:
     *   1. The file's own lastModified() — the most accurate value and the
     *      one shown in the Info dialog.
     *   2. MediaStore's DATE_MODIFIED (only used if the file timestamp is 0,
     *      which happens on some SD cards / exFAT mounts).
     */
    private long resolveTimestamp(String path, long storeDate) {
        try {
            File f = new File(path);
            long sec = f.lastModified() / 1000L;
            if (sec > 0) return sec;
        } catch (Exception ignored) {}
        return storeDate;
    }

    /**
     * Recursively scans a directory for media files that aren't already in
     * `knownPaths`. Adds found items directly to `out`.
     */
    private void scanFilesystemForMedia(File directory, List<MediaItem> out,
                                        Set<String> knownPaths, int depth, int maxDepth) {
        if (depth > maxDepth || directory == null
                || !directory.exists() || !directory.isDirectory()) return;

        String dirName = directory.getName().toLowerCase(Locale.ROOT);
        // Skip noisy system / cache directories
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
                if (name.startsWith(".")) continue;   // hidden
                if (name.contains(".trashed.")) {
                    // trashed files are surfaced through the Bin scan
                    continue;
                }
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

        long now = System.currentTimeMillis();
        if (!forceRescan && cachedTrashedItems != null
                && (now - cachedTrashedTimestamp) < TRASHED_CACHE_TTL_MS) {
            final List<MediaItem> snapshot = cachedTrashedItems;
            runOnMain(() -> {
                if (isActivityAlive()) callback.onReady(snapshot);
            });
            return;
        }

        executor.execute(() -> {
            List<MediaItem> trashedItems = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (MediaItem item : new ArrayList<>(mediaItems)) {
                if (item.path != null && item.path.contains(".trashed.")) {
                    if (seen.add(item.path)) trashedItems.add(item);
                }
            }

            List<File> directories = getStorageDirectoriesProper();
            for (File dir : directories) {
                if (dir != null && dir.exists()) {
                    scanDirectoryForTrashedFiles(dir, trashedItems, seen, 0, 6);
                }
            }

            cachedTrashedItems = trashedItems;
            cachedTrashedTimestamp = System.currentTimeMillis();

            final List<MediaItem> result = trashedItems;
            runOnMain(() -> {
                if (isActivityAlive()) callback.onReady(result);
            });
        });
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

    private void scanDirectoryForTrashedFiles(File directory, List<MediaItem> items,
                                              Set<String> seen, int depth, int maxDepth) {
        if (depth > maxDepth || directory == null || !directory.exists() || !directory.isDirectory()) return;

        try {
            try {
                String cp = directory.getCanonicalPath();
                if (cp.equals("/storage/emulated/0")
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
                        scanDirectoryForTrashedFiles(file, items, seen, depth + 1, maxDepth);
                    }
                } else if (file.getName().contains(".trashed.")) {
                    String fileName = file.getName().toLowerCase();
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
                        String path;
                        try { path = file.getCanonicalPath(); }
                        catch (Exception e) { path = file.getAbsolutePath(); }
                        if (!seen.add(path)) continue;

                        String album = file.getParentFile() != null ? file.getParentFile().getName() : "";
                        long dateModified = file.lastModified() / 1000;
                        int type = isImage ? MediaItem.TYPE_IMAGE : MediaItem.TYPE_VIDEO;
                        items.add(new MediaItem(path, file.getName(), type, dateModified, true, album));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void applyFilter() {
        if (!isActivityAlive()) return;

        if (currentFilter == FilterMode.BIN) {
            // ── Bin mode ──
            // Step 1: IMMEDIATELY blank the adapter so the old (home) list
            //         doesn't linger for a frame while the bin scan runs.
            //         This is what prevents the "home files flash for a
            //         second when opening Bin" glitch.
            runOnMain(() -> {
                if (!isActivityAlive()) return;
                if (currentFilter != FilterMode.BIN) return;

                displayedItems = new ArrayList<>();

                if (adapter == null) {
                    setupRecyclerView();
                    showEmptyState();
                    updateTopNavBar();
                } else {
                    adapter.updateItems(new ArrayList<>());
                    adapter.updateSelectedItems(selectedItems);
                    updateSelectionUI();
                    showEmptyState();
                    updateTopNavBar();
                }
            });

            // Step 2: If we have a cache, paint it right away — still no
            //         stale home content because step 1 already blanked it.
            if (cachedTrashedItems != null) {
                final List<MediaItem> cachedSnapshot =
                        new ArrayList<>(cachedTrashedItems);
                runOnMain(() -> {
                    if (!isActivityAlive()) return;
                    if (currentFilter != FilterMode.BIN) return;

                    displayedItems = cachedSnapshot;

                    if (adapter != null) {
                        adapter.updateItems(cachedSnapshot);
                        adapter.updateSelectedItems(selectedItems);
                        updateSelectionUI();
                        showEmptyState();
                        updateTopNavBar();
                    }
                });
            }

            // Step 3: Kick off the async scan; when it finishes, swap in the
            //         authoritative list.
            getTrashedFilesAsync(false, trashed -> {
                if (!isActivityAlive()) return;
                if (currentFilter != FilterMode.BIN) return;

                final List<MediaItem> snapshot = new ArrayList<>(trashed);

                runOnMain(() -> {
                    if (!isActivityAlive()) return;
                    if (currentFilter != FilterMode.BIN) return;

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
            });
            return;
        }

        // ── Normal filters (ALL / IMAGES / VIDEOS / FAVORITES) ──
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

    // ===================== PERMISSIONS =====================

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

        if (allGranted) loadMedia();
        else requestMultiplePermissionsLauncher.launch(toRequest);
    }

    // ===================== RECYCLER VIEW =====================

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

    /**
     * Called when an item is long-pressed.
     *
     * Behavior:
     *  • If we're not in selection mode → enter it and select this item.
     *  • If we're already in selection mode:
     *      - If the item is already selected → this is a "deselect drag" anchor.
     *      - If not selected → this is a "select drag" anchor.
     *  The anchor's state is committed ONCE and never toggled again while the
     *  finger is down, regardless of how many times the finger passes over it.
     */
    private void startLongPressDrag(String path) {
        if (!isActivityAlive()) return;

        int anchorIdx = indexOfPath(path);
        if (anchorIdx < 0) return;

        boolean alreadySelected = selectedItems.contains(path);

        // ── Snapshot the pre-drag selection ──
        selectionBeforeDrag.clear();
        selectionBeforeDrag.addAll(selectedItems);

        // Determine the mode of the rectangle:
        //   • If the anchor was already selected → the rectangle will DESELECT.
        //   • Otherwise → the rectangle will SELECT.
        if (!selectionMode) {
            // First long-press → enter selection mode.
            selectionMode = true;
            dragSelectDeselectMode = false;
        } else {
            dragSelectDeselectMode = alreadySelected;
        }

        dragAnchorPath = path;
        dragSelectActive = true;
        dragVisitedPositions.clear();
        dragRectanglePaths.clear();

        // ── Reset the rectangle-change guard ──
        dragLastRectMinRow = -1;
        dragLastRectMaxRow = -1;
        dragLastRectMinCol = -1;
        dragLastRectMaxCol = -1;

        dragLastX = -1f;
        dragLastY = -1f;

        // Apply the initial single-cell rectangle (just the anchor).
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
                        dragSelectActive = false;
                        dragVisitedPositions.clear();
                        dragRectanglePaths.clear();
                        selectionBeforeDrag.clear();
                        dragAnchorPath = null;
                        dragLastX = -1f;
                        dragLastY = -1f;

                        // ★★★ RESET BLOCK ★★★
                        dragLastRectMinRow = -1;
                        dragLastRectMaxRow = -1;
                        dragLastRectMinCol = -1;
                        dragLastRectMaxCol = -1;
                        stopAutoScroll();
                        // ★★★ END RESET BLOCK ★★★

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
                        dragSelectActive = false;
                        dragVisitedPositions.clear();
                        dragRectanglePaths.clear();
                        selectionBeforeDrag.clear();
                        dragAnchorPath = null;
                        dragLastX = -1f;
                        dragLastY = -1f;

                        // ★★★ RESET BLOCK ★★★
                        dragLastRectMinRow = -1;
                        dragLastRectMaxRow = -1;
                        dragLastRectMinCol = -1;
                        dragLastRectMaxCol = -1;
                        stopAutoScroll();
                        // ★★★ END RESET BLOCK ★★★

                        break;
                }
            }
        });
    }
    private int indexOfPath(String path) {
        for (int i = 0; i < displayedItems.size(); i++) {
            if (displayedItems.get(i).path.equals(path)) return i;
        }
        return -1;
    }

    private void handleDragMove(RecyclerView rv, float x, float y) {
        // Skip redundant work on tiny movements
        if (Math.abs(x - dragLastX) < 2 && Math.abs(y - dragLastY) < 2) {
            updateAutoScroll(rv, x, y);
            return;
        }
        dragLastX = x;
        dragLastY = y;

        // Find the cell under the finger
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



    /**
     * Starts, updates, or stops the auto-scroll loop based on where the finger
     * currently is. The scroll speed ramps up the closer the finger gets to the
     * edge — smooth and predictable even for fast drags.
     */
    private void updateAutoScroll(RecyclerView rv, float x, float y) {
        if (rv == null) { stopAutoScroll(); return; }

        int edge = dpToPx(AUTO_SCROLL_EDGE_DP);
        int h = rv.getHeight();
        int w = rv.getWidth();

        int dy = 0;
        int dx = 0;

        // Vertical
        if (y < edge) {
            // Scale: 1 px at the edge boundary, up to 20 px deep inside.
            float t = 1f - (y / (float) edge);            // 0..1
            dy = -(int) (4 + t * 16);
        } else if (y > h - edge) {
            float t = 1f - ((h - y) / (float) edge);
            dy = (int) (4 + t * 16);
        }

        // Horizontal (bonus: works if grid can scroll horizontally)
        if (x < edge) {
            float t = 1f - (x / (float) edge);
            dx = -(int) (4 + t * 16);
        } else if (x > w - edge) {
            float t = 1f - ((w - x) / (float) edge);
            dx = (int) (4 + t * 16);
        }

        if (dx == 0 && dy == 0) {
            stopAutoScroll();
            return;
        }

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

        // ── Guard: if the rectangle hasn't changed since the last call, skip. ──
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

        // ── Fast diff: if the desired selection equals what we already have, bail. ──
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
                // ★ Use payload so the thumbnail isn't reloaded.
                adapter.notifyItemChanged(p, "selection");
            }
        }

        dragRectanglePaths.clear();
        dragRectanglePaths.addAll(newRectangle);
        updateSelectionUI();
    }    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ===================== TRASH =====================

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
        cachedTrashedItems = null;
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

        cachedTrashedItems = null;
        return true;
    }

    private void moveSelectedToTrash() {
        if (selectedItems.isEmpty()) return;

        final List<String> paths = new ArrayList<>(selectedItems);
        final Toast progressToast = Toast.makeText(this, "Moving...", Toast.LENGTH_SHORT);
        progressToast.show();

        executor.execute(() -> {
            int moved = 0, failed = 0;
            final List<String> newTrashPaths = new ArrayList<>();   // ★ collect for scan

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

            // ★ Single MediaScanner call with every new trash path.
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
                cachedTrashedItems = null;

                applyFilter();        // ★ one refresh
                loadMediaQuiet();     // ★ silent re-scan (now actually scans + re-filters)

                Toast.makeText(this, "Moved " + fm + " item(s) to Bin"
                                + (ff > 0 ? " (" + ff + " failed)" : ""),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Variant of moveToTrash that returns the new ".trashed." path (or null on
     * failure). No per-item MediaScanner call, no per-item refresh.
     */
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
        cachedTrashedItems = null;
        return item.path;
    }
    private void restoreSelectedItems() {
        if (selectedItems.isEmpty()) return;

        final List<String> paths = new ArrayList<>(selectedItems);
        final Toast progressToast = Toast.makeText(this, "Restoring...", Toast.LENGTH_SHORT);
        progressToast.show();

        executor.execute(() -> {
            int restored = 0, failed = 0;
            final List<String> restoredPaths = new ArrayList<>();   // ★ collect for single scan

            for (String path : paths) {
                File file = new File(path);
                if (!file.exists()) { failed++; continue; }

                MediaItem item = new MediaItem(path, file.getName(),
                        isVideoPath(path) ? MediaItem.TYPE_VIDEO : MediaItem.TYPE_IMAGE,
                        0, true, "");

                // restoreFromTrash now returns the new path (or null on failure)
                String newPath = restoreFromTrashAndReturnPath(item);
                if (newPath != null) {
                    restored++;
                    restoredPaths.add(newPath);
                } else {
                    failed++;
                }
            }

            // ★ Single MediaScanner call with every restored path.
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
                cachedTrashedItems = null;

                applyFilter();        // ★ one refresh
                loadMediaQuiet();     // ★ silent re-scan (now actually scans + re-filters)

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

    /**
     * Variant of restoreFromTrash that returns the final path (or null on
     * failure) so the caller can batch a single MediaScanner call at the end.
     * Does NOT call scheduleMediaRefresh() — the caller controls refresh.
     */
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

        cachedTrashedItems = null;
        // ★ No scan, no refresh — caller handles both in one batch.
        return item.path;
    }
    /**
     * Permanently delete selected items — asynchronous.
     */
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
                                try { ok = f.delete(); }
                                catch (Exception e) { Log.e(LOG_TAG, "delete failed: " + path, e); }
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
                            cachedTrashedItems = null;
                            clearSelection();

                            applyFilter();        // ★ single refresh
                            loadMediaQuiet();     // ★ silent re-scan (now actually scans + re-filters)

                            if (delCount > 0) {
                                // no scheduleMediaRefresh here — loadMediaQuiet handles it
                            }
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

    // ===================== SELECTION =====================

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
                if (btnBin != null) btnBin.setText("🗑️ Bin (" + selectedItems.size() + ")");
            }
        }
    }

    /** Guarantees the given Runnable runs on the main thread. */
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
        dragSelectActive = false;
        dragVisitedPositions.clear();
        dragRectanglePaths.clear();
        selectionBeforeDrag.clear();
        dragAnchorPath = null;
        dragLastX = -1f;
        dragLastY = -1f;
        stopAutoScroll();   // ← add this
    }

    // ===================== SHARE / INFO =====================

    private void shareSelectedItems() {
        if (selectedItems.isEmpty()) return;
        try {
            if (selectedItems.size() == 1) {
                File file = new File(selectedItems.get(0));
                if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return; }
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
                if (uris.isEmpty()) { Toast.makeText(this, "No valid files", Toast.LENGTH_SHORT).show(); return; }
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
            if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return; }
            StringBuilder info = new StringBuilder();
            info.append("📁 Type: ").append(getFileType(path)).append("\n");
            info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
            info.append("📅 Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                    .format(new Date(file.lastModified()))).append("\n");
            info.append("📍 Path: ").append(file.getAbsolutePath());
            showInfoDialog(info.toString(), "📄 File Info");
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

    // ===================== LEGACY =====================

    private void setupVideoControls() {
        if (videoPlayerContainer == null) return;
        videoPlayerContainer.setOnTouchListener((v, event) -> true);
    }

    private String formatTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ===================== ALBUMS =====================

    private void loadAlbums() {
        if (albumsCacheValid && !albumList.isEmpty()) {
            runOnUiThread(this::showAlbumGrid);
            return;
        }

        executor.execute(() -> {
            LinkedHashMap<String, String> albums = new LinkedHashMap<>();

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
                        File parent = new File(path).getParentFile();
                        if (parent == null) continue;
                        String parentPath = getCachedCanonical(parent.getAbsolutePath());
                        String display = (name != null && !name.isEmpty()) ? name : parent.getName();
                        if (!albums.containsKey(parentPath)) albums.put(parentPath, display);
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "loadAlbums: image query failed", e);
            } finally { if (imgCursor != null) imgCursor.close(); }

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
                        File parent = new File(path).getParentFile();
                        if (parent == null) continue;
                        String parentPath = getCachedCanonical(parent.getAbsolutePath());
                        String display = (name != null && !name.isEmpty()) ? name : parent.getName();
                        if (!albums.containsKey(parentPath)) albums.put(parentPath, display);
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "loadAlbums: video query failed", e);
            } finally { if (vidCursor != null) vidCursor.close(); }

            List<File> roots = getStorageDirectoriesProper();
            for (File root : roots) {
                if (root != null && root.exists()) {
                    scanFoldersForMedia(root, albums, 0, 5);
                }
            }

            List<String> paths = new ArrayList<>(albums.keySet());
            Collections.sort(paths, (a, b) -> {
                String da = albums.get(a);
                String db = albums.get(b);
                if (da == null) da = "";
                if (db == null) db = "";
                return da.compareToIgnoreCase(db);
            });

            Map<String, Integer> nameCounts = new HashMap<>();
            for (String p : paths) {
                String d = albums.get(p);
                if (d == null) d = "";
                nameCounts.put(d, nameCounts.getOrDefault(d, 0) + 1);
            }

            Map<String, String> finalDisplayNames = new HashMap<>();
            for (String p : paths) {
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

        boolean hasMedia = false;
        for (File f : files) {
            if (f.isFile() && isMediaFile(f.getName())) { hasMedia = true; break; }
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
            return;
        }

        List<String> displayList = new ArrayList<>(albumList.size());
        for (String path : albumList) {
            String d = albumDisplayNames.get(path);
            if (d == null || d.isEmpty()) d = new File(path).getName();
            displayList.add(d);
        }

        albumAdapter = new AlbumAdapter(this, displayList, albumPathList(), displayName -> {
            String chosenPath = null;
            for (String p : albumList) {
                String d = albumDisplayNames.get(p);
                if (d == null) d = new File(p).getName();
                if (d.equals(displayName)) { chosenPath = p; break; }
            }
            if (chosenPath == null) return;

            currentAlbum = chosenPath;
            String shown = albumDisplayNames.get(chosenPath);
            if (shown == null) shown = new File(chosenPath).getName();
            titleView.setText("📁 " + shown);
            applyFilter();
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            showAlbums = false;
            updateBarsVisibility();
            updateTopNavBar();
        });
        albumRecycler.setLayoutManager(new GridLayoutManager(this, 2));
        albumRecycler.setAdapter(albumAdapter);
        albumRecycler.setVisibility(View.VISIBLE);
        titleView.setText("Albums");
    }

    private List<String> albumPathList() {
        return new ArrayList<>(albumList);
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
        Set<String> favs = loadFavoritePaths();
        for (String path : selectedItems) {
            File f = new File(path);
            if (!f.exists()) continue;
            favs.add(path);
            for (MediaItem item : mediaItems) {
                if (item.path.equals(path)) { item.isFavorite = true; break; }
            }
        }
        saveFavoritePaths(favs);
        clearSelection();
        applyFilter();
    }

    // ===================== LIFECYCLE =====================

    @Override
    public void onBackPressed() {
        if (fullscreenOverlay != null && fullscreenOverlay.getVisibility() == View.VISIBLE) {
            closeFullscreenViewer(); return;
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