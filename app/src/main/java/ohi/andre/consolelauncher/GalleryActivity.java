package ohi.andre.consolelauncher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
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

    private View fullscreenInfoHeader;
    private TextView fullscreenInfoName;
    private TextView fullscreenInfoDetails;

    // ── Drag-to-select state ──
    private boolean dragSelectActive = false;
    private boolean dragSelectDeselectMode = false;
    private final java.util.Set<Integer> dragVisitedPositions = new java.util.HashSet<>();
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
    private GestureDetector overlayTapDetector;

    // ★ NEW: gesture fields for double-tap and long-press
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
        videoTitleOverlay = findViewById(R.id.videoTitle);
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
                    if (item.path.equals(path)) {
                        moveToTrash(item);
                        break;
                    }
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

        // ── Tap detector for fullscreen overlay (single tap toggles chrome) ──
        overlayTapDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        // Fires immediately on finger-up — no 300ms delay.
                        onFullscreenTap();
                        return true;
                    }
                });

// Video-specific gestures: double-tap and long-press.
        overlayVideoGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {

                    @Override
                    public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        // Video surface itself does NOT toggle chrome here —
                        // the volume/brightness handler detects a "real tap"
                        // (no movement) and calls onFullscreenTap().
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

// Overlay surface gets its own listener (no-op tap; volume/brightness
// handler below replaces it once installed).
        fullscreenOverlay.setOnTouchListener((v, event) -> false);

        setupOverlayVideoControls();

// ── Fullscreen adapter ──
        fullscreenAdapter = new FullscreenAdapter(fullscreenMediaPaths, this);
        log("fullscreenAdapter created: " + Integer.toHexString(System.identityHashCode(fullscreenAdapter)));
        fullscreenAdapter.setTapCallback(this::onFullscreenTap);

// Forward video touches through BOTH detectors + the volume/brightness handler.
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

// Install the volume/brightness handler (it also drives single-tap chrome toggle).
        setupVolumeAndBrightnessGestures();

        fullscreenAdapter.setPageTypeCallback(new FullscreenAdapter.PageTypeCallback() {
            @Override
            public void onImageVisible(int position) {
                log("★★ onImageVisible pos=" + position
                        + " current=" + fullscreenCurrentPosition);
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
                log("★★ onVideoVisible pos=" + position
                        + " current=" + fullscreenCurrentPosition
                        + " videoView=" + Integer.toHexString(System.identityHashCode(videoView)));

                if (position == fullscreenCurrentPosition) {
                    currentFullscreenPageIsVideo = true;
                    currentFullscreenVideo = videoView;
                    currentFullscreenVideoPosition = position;
                    isOverlayVideoPlaying = true;
                    overlayPendingSeekMs = -1;
                    try {
                        videoView.start();
                    } catch (Exception e) {
                        Log.e(LOG_TAG, src() + " videoView.start() threw", e);
                    }
                    updateOverlayTitle();
                    updateOverlaySeekBar();
                    startOverlayProgressUpdate();

                    // ★ Show the video controls immediately — do not wait for a tap.
                    if (!userIsSwiping) {
                        if (videoControlContainer != null) {
                            videoControlContainer.setVisibility(View.VISIBLE);
                        }
                        setFullscreenChromeVisible(true);
                        overlayControlsVisible = true;
                    }
                    showAllControlsWithTimeout();
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
                log("onPageSelected: " + position + " (was " + fullscreenCurrentPosition + ")");

                if (currentFullscreenVideo != null
                        && currentFullscreenVideoPosition != position) {
                    try { currentFullscreenVideo.pause(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                    currentFullscreenPageIsVideo = false;
                    isOverlayVideoPlaying = false;
                    videoHandler.removeCallbacks(overlayProgressRunnable);
                }

                overlayPendingSeekMs = -1;   // ★ clear stale seek
                fullscreenCurrentPosition = position;
                fullscreenCurrentIndex = position;

                updateFullscreenInfo(position);

                if (position >= 0 && position < fullscreenMediaPaths.size()) {
                    String path = fullscreenMediaPaths.get(position);
                    if (isVideoPath(path)) {
                        log("  new page is video — starting");
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
            currentFilter = FilterMode.IMAGES; currentAlbum = null;
            titleView.setText("📷 Images"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });
        sortVideos.setOnClickListener(v -> {
            currentFilter = FilterMode.VIDEOS; currentAlbum = null;
            titleView.setText("🎬 Videos"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });
        sortFavorites.setOnClickListener(v -> {
            currentFilter = FilterMode.FAVORITES; currentAlbum = null;
            titleView.setText("⭐ Favorites"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });
        sortBin.setOnClickListener(v -> {
            currentFilter = FilterMode.BIN; currentAlbum = null;
            titleView.setText("🗑️ Bin"); applyFilter();
            sortOptions.setVisibility(View.GONE); updateBarsVisibility();
        });

        setupVideoControls();
        checkAndRequestMediaPermissions();

        View topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) updateTopNavBar();
    }

    // ===================== EFFECTIVE SEEK POSITION =====================

    private int getEffectivePositionMs() {
        if (currentFullscreenVideo == null) return 0;
        if (overlayPendingSeekMs >= 0) return overlayPendingSeekMs;
        try {
            return currentFullscreenVideo.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
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

        if (videoTimeCurrent != null)
            videoTimeCurrent.setText(formatTime(target));
        if (videoSeekBar != null)
            videoSeekBar.setProgress((int) ((target / (float) dur) * 1000));

        videoHandler.postDelayed(() -> overlayPendingSeekMs = -1, 350);
        showAllControlsWithTimeout();
    }
    private void resetPlaybackSpeed() {
        if (currentFullscreenVideo == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        // Only reset if the player is still active — setPlaybackParams on a
        // paused MediaPlayer can resume it on some ROMs.
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
        if (newVisible) {
            showAllControlsWithTimeout();
        } else {
            hideAllControls();
        }
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
        // Reset the hide timer to start AFTER the tap completes.
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
        if (!src.exists()) {
            Log.e(LOG_TAG, "renameFileRobust: src does not exist: " + src);
            return false;
        }
        try {
            if (src.renameTo(dst)) return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, "renameFileRobust: renameTo threw", e);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(
                        src.toPath(),
                        dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) {
                Log.e(LOG_TAG, "renameFileRobust: Files.move failed", e);
            }
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
                            try {
                                connHolder[0].scanFile(p, null);
                            } catch (Exception e) {
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
                    Toast.makeText(this,
                            nowFav ? "⭐ Added" : "Removed",
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
            showAllControlsWithTimeout();
        } else {
            currentFullscreenVideo.start();
            isOverlayVideoPlaying = true;
            if (btnCenterPlayPause != null)
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            startOverlayProgressUpdate();
            showAllControlsWithTimeout();
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
            int cur = getEffectivePositionMs();   // ★ use effective position
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

                        // ★ Auto-loop when the video reaches the end
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
        log("openFullscreenViewer: " + path);

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

        // ★ Always show chrome first. Video controls row will appear
        //    automatically the moment onVideoVisible fires.
        setFullscreenChromeVisible(true);
        if (videoControlContainer != null) {
            // Pre-show if this page is a video so the row is visible from
            // the very first frame.
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

    private void showAllControls() {
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
    }
    /**
     * Right-half vertical swipe  → system music volume
     * Left-half  vertical swipe  → screen brightness
     *
     * Also detects "real taps" (no movement) to toggle the chrome.
     *
     * When the user reverses swipe direction, we re-anchor the baseline
     * so both directions work smoothly — even after hitting an edge.
     */
    private void setupVolumeAndBrightnessGestures() {
        if (fullscreenOverlay == null) return;

        final android.media.AudioManager audioManager =
                (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);

        final int maxVolume = audioManager != null
                ? audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                : 15;

        // Gesture state
        final float[] gestureStartY = {0f};
        final float[] gestureStartX = {0f};
        final int[]   gestureStartVolume = {0};
        final float[] gestureStartBrightness = {0f};
        final boolean[] isVolumeGesture = {false};
        final boolean[] isBrightnessGesture = {false};
        final boolean[] fingerMoved = {false};

        // After this many pixels of movement, we commit to being a
        // volume/brightness gesture and never treat the touch as a tap.
        final int MOVE_THRESHOLD_PX = 30;

        final android.view.View.OnTouchListener handler = (v, event) -> {

            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN: {
                    gestureStartY[0] = event.getY();
                    gestureStartX[0] = event.getX();
                    isVolumeGesture[0] = false;
                    isBrightnessGesture[0] = false;
                    fingerMoved[0] = false;
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    float dyTotal = Math.abs(event.getY() - gestureStartY[0]);
                    float dxTotal = Math.abs(event.getX() - gestureStartX[0]);

                    // Commit to gesture mode once movement exceeds threshold.
                    if (!fingerMoved[0]
                            && (dyTotal > MOVE_THRESHOLD_PX || dxTotal > MOVE_THRESHOLD_PX)) {
                        fingerMoved[0] = true;
                    }
                    if (!fingerMoved[0]) break;

                    float dy = event.getY() - gestureStartY[0]; // positive = finger down
                    float screenW = v.getWidth();
                    boolean onRightSide = gestureStartX[0] >= screenW / 2f;

                    if (onRightSide) {
                        // ── VOLUME ──
                        if (!isVolumeGesture[0]) {
                            isVolumeGesture[0] = true;
                            isBrightnessGesture[0] = false;
                            gestureStartY[0] = event.getY();
                            gestureStartVolume[0] = audioManager != null
                                    ? audioManager.getStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC)
                                    : 0;
                            break;
                        }
                        if (audioManager == null) break;

                        // A downward finger = volume down, upward = volume up.
                        // Since we track from gestureStartY, we want the delta
                        // relative to where the gesture re-anchored.
                        float deltaY = gestureStartY[0] - event.getY(); // up = positive
                        float fraction = deltaY / (v.getHeight() * 0.6f);
                        int target = Math.round(gestureStartVolume[0] + fraction * maxVolume);
                        target = Math.max(0, Math.min(maxVolume, target));

                        int currentVol = audioManager.getStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC);

                        // ★ Interactive fix: if the user reverses direction and
                        //    we're pinned at an edge, re-anchor to the current
                        //    volume so the next movement immediately takes effect.
                        if (target == currentVol) {
                            boolean pushingUp = fraction > 0;
                            boolean atEdge = (pushingUp && currentVol >= maxVolume)
                                    || (!pushingUp && currentVol <= 0);
                            if (atEdge) {
                                // Re-anchor — user must "unstick" from the edge.
                                gestureStartY[0] = event.getY();
                                gestureStartVolume[0] = currentVol;
                                break;
                            }
                            // Otherwise it's just a small in-range nudge; still
                            // apply (harmless) but re-anchor whenever the
                            // computed target equals current — keeps things
                            // responsive on the very next pixel.
                            if (Math.abs(deltaY) < 8) break;
                            gestureStartY[0] = event.getY();
                            gestureStartVolume[0] = currentVol;
                            break;
                        }

                        audioManager.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                target, 0);

                        // ★ Re-anchor every time we successfully apply a change.
                        //    This makes the gesture fully responsive and
                        //    unaffected by edge saturation.
                        gestureStartY[0] = event.getY();
                        gestureStartVolume[0] = target;

                    } else {
                        // ── BRIGHTNESS ──
                        if (!isBrightnessGesture[0]) {
                            isBrightnessGesture[0] = true;
                            isVolumeGesture[0] = false;
                            gestureStartY[0] = event.getY();
                            WindowManager.LayoutParams lp = getWindow().getAttributes();
                            gestureStartBrightness[0] =
                                    lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
                            break;
                        }

                        float deltaY = gestureStartY[0] - event.getY();
                        float fraction = deltaY / (v.getHeight() * 0.6f);
                        float target = gestureStartBrightness[0] + fraction;
                        target = Math.max(0.02f, Math.min(1f, target));

                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        float current = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;

                        if (Math.abs(target - current) < 0.005f) {
                            // At/near saturation — re-anchor so reversing works.
                            gestureStartY[0] = event.getY();
                            gestureStartBrightness[0] = current;
                            break;
                        }

                        lp.screenBrightness = target;
                        getWindow().setAttributes(lp);

                        // ★ Re-anchor on every successful change.
                        gestureStartY[0] = event.getY();
                        gestureStartBrightness[0] = target;
                    }
                    break;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // If the finger never moved meaningfully → it was a tap.
                    if (!fingerMoved[0]) {
                        onFullscreenTap();
                    }
                    isVolumeGesture[0] = false;
                    isBrightnessGesture[0] = false;
                    fingerMoved[0] = false;
                    break;
                }
            }
            return true;
        };

        fullscreenOverlay.setOnTouchListener(handler);
        this.volumeBrightnessHandler = handler;
    }

    /** Reused by the video view's touch forwarder. */

    private void closeFullscreenViewer() {
        log("closeFullscreenViewer");

        if (currentFullscreenVideo != null) {
            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
            currentFullscreenVideo = null;
        }
        currentFullscreenVideoPosition = -1;
        currentFullscreenPageIsVideo = false;
        isOverlayVideoPlaying = false;
        overlayControlsVisible = false;
        overlayPendingSeekMs = -1;
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
        } catch (Exception e) {
            Log.e(LOG_TAG, src() + " error stopping videos", e);
        }

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
        StringBuilder info = new StringBuilder();
        info.append("📄 File: ").append(file.getName()).append("\n");
        info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
        info.append("📅 Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm",
                Locale.getDefault()).format(new Date(file.lastModified()))).append("\n");
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
                            String parentPath = new File(path).getParent();
                            String albumKey;
                            if (parentPath != null) {
                                albumKey = getCachedCanonical(parentPath);
                            } else {
                                albumKey = album;
                            }
                            newItems.add(new MediaItem(path, name, MediaItem.TYPE_IMAGE, date, isTrashed, albumKey));
                        }
                    }
                }
            } catch (SecurityException e) {
                runOnUiThread(() -> { if (isActivityAlive()) { applyFilter(); setupRecyclerView(); } });
                return;
            } finally { if (imageCursor != null) imageCursor.close(); }

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
                            String parentPath = new File(path).getParent();
                            String albumKey;
                            if (parentPath != null) {
                                albumKey = getCachedCanonical(parentPath);
                            } else {
                                albumKey = album;
                            }
                            newItems.add(new MediaItem(path, name, MediaItem.TYPE_VIDEO, date, isTrashed, albumKey));
                        }
                    }
                }
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Cannot access media files", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            } finally { if (videoCursor != null) videoCursor.close(); }

            Collections.sort(newItems, (a, b) -> Long.compare(b.dateModified, a.dateModified));

            mediaItems.clear();
            mediaItems.addAll(newItems);

            Set<String> favs = loadFavoritePaths();
            for (MediaItem item : mediaItems) {
                item.isFavorite = favs.contains(item.path);
            }

            runOnUiThread(() -> { applyFilter(); setupRecyclerView(); showEmptyState(); });
        });
    }

    // ===================== BIN SCAN =====================

    private List<MediaItem> scanForTrashedFiles() {
        List<MediaItem> trashedItems = new ArrayList<>();
        List<File> directories = getStorageDirectoriesProper();
        for (File dir : directories) {
            if (dir != null && dir.exists())
                scanDirectoryForTrashedFiles(dir, trashedItems, 0, 6);
        }
        return trashedItems;
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

    private void scanDirectoryForTrashedFiles(File directory, List<MediaItem> items, int depth, int maxDepth) {
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
            List<MediaItem> trashed = scanForTrashedFiles();
            for (MediaItem item : mediaItems) {
                if (item.path.contains(".trashed.")) {
                    boolean exists = false;
                    for (MediaItem e : trashed) {
                        if (e.path.equals(item.path)) { exists = true; break; }
                    }
                    if (!exists) trashed.add(item);
                }
            }
            displayedItems.addAll(trashed);
        } else {
            final String targetCanon = currentAlbum == null ? null : getCachedCanonical(currentAlbum);
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

        adapter = new GalleryAdapter(this, displayedItems, selectedItems, new GalleryAdapter.OnItemClickListener() {
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
        recyclerView.setItemAnimator(null);
        setupDragToSelect();
    }

    private void startLongPressDrag(String path) {
        if (!isActivityAlive()) return;
        if (!selectedItems.contains(path)) {
            selectedItems.add(path);
        }
        updateSelectionUI();
        dragSelectDeselectMode = false;
        dragSelectActive = true;
        dragVisitedPositions.clear();

        int idx = indexOfPath(path);
        if (idx >= 0) {
            dragVisitedPositions.add(idx);
            if (adapter != null) adapter.notifyItemChanged(idx, "selection");
        }

        dragLastX = -1f;
        dragLastY = -1f;

        if (recyclerView != null) {
            recyclerView.requestDisallowInterceptTouchEvent(false);
        }
    }

    private int indexOfPath(String path) {
        for (int i = 0; i < displayedItems.size(); i++) {
            if (displayedItems.get(i).path.equals(path)) return i;
        }
        return -1;
    }

    private void setupDragToSelect() {
        if (recyclerView == null) return;

        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (!dragSelectActive) return false;

                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE: {
                        handleDragMove(rv, e.getX(), e.getY());
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        dragSelectActive = false;
                        dragVisitedPositions.clear();
                        dragLastX = -1f;
                        dragLastY = -1f;
                        return true;
                    }
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
                        dragLastX = -1f;
                        dragLastY = -1f;
                        break;
                }
            }
        });
    }

    private void handleDragMove(RecyclerView rv, float x, float y) {
        if (Math.abs(x - dragLastX) < 4 && Math.abs(y - dragLastY) < 4) return;
        dragLastX = x;
        dragLastY = y;

        View child = rv.findChildViewUnder(x, y);
        if (child != null) {
            int pos = rv.getChildAdapterPosition(child);
            if (pos != RecyclerView.NO_POSITION) {
                handleDragTouch(pos);
            }
        }

        int threshold = dpToPx(60);
        if (y < threshold) {
            rv.scrollBy(0, -dpToPx(8));
        } else if (y > rv.getHeight() - threshold) {
            rv.scrollBy(0, dpToPx(8));
        }
    }

    private void handleDragTouch(int position) {
        if (dragVisitedPositions.contains(position)) return;
        if (position < 0 || position >= displayedItems.size()) return;

        dragVisitedPositions.add(position);
        MediaItem item = displayedItems.get(position);

        if (dragSelectDeselectMode) {
            selectedItems.remove(item.path);
        } else {
            if (!selectedItems.contains(item.path)) {
                selectedItems.add(item.path);
            }
        }

        updateSelectionUI();
        if (adapter != null) {
            adapter.notifyItemChanged(position, "selection");
        }
    }

    private int dpToPx(int dp) {
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

        MediaScannerConnection.scanFile(this, new String[]{trashedFile.getAbsolutePath()}, null, null);
        MediaScannerConnection.scanFile(this, new String[]{oldPath}, null, null);

        for (int i = 0; i < mediaItems.size(); i++) {
            if (mediaItems.get(i).path.equals(oldPath)) {
                mediaItems.set(i, item);
                break;
            }
        }

        albumsCacheValid = false;
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

        scanPathsAndThen(
                Arrays.asList(restoredFile.getAbsolutePath(), oldPath),
                () -> scheduleMediaRefresh(0L));

        return true;
    }

    private void moveSelectedToTrash() {
        if (selectedItems.isEmpty()) return;

        List<String> paths = new ArrayList<>(selectedItems);
        int moved = 0, failed = 0;

        for (String path : paths) {
            File f = new File(path);
            if (!f.exists()) { failed++; continue; }

            MediaItem item = new MediaItem(path, f.getName(),
                    MediaItem.TYPE_IMAGE, 0, false, "");
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                    || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv")) {
                item.type = MediaItem.TYPE_VIDEO;
            }

            if (!f.getName().startsWith(".trashed.")) {
                moveToTrash(item);
                moved++;
            } else {
                moved++;
            }
        }

        clearSelection();
        applyFilter();
        scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);
        Toast.makeText(this, "Moved " + moved + " item(s) to Bin"
                        + (failed > 0 ? " (" + failed + " failed)" : ""),
                Toast.LENGTH_SHORT).show();
    }

    private void restoreSelectedItems() {
        if (selectedItems.isEmpty()) return;

        List<String> paths = new ArrayList<>(selectedItems);
        int restored = 0, failed = 0;

        for (String path : paths) {
            File file = new File(path);
            if (!file.exists()) { failed++; continue; }

            MediaItem item = new MediaItem(path, file.getName(),
                    MediaItem.TYPE_IMAGE, 0, true, "");
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                    || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv")) {
                item.type = MediaItem.TYPE_VIDEO;
            }

            if (restoreFromTrash(item)) restored++;
            else failed++;
        }

        clearSelection();
        applyFilter();

        if (restored > 0) scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);

        if (failed == 0) {
            Toast.makeText(this, "Restored " + restored + " item(s)", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Restored " + restored + ", failed " + failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void deletePermanentlySelectedItems() {
        if (selectedItems.isEmpty()) return;

        final List<String> paths = new ArrayList<>(selectedItems);

        new AlertDialog.Builder(this)
                .setTitle("Delete Permanently")
                .setMessage("Are you sure you want to permanently delete "
                        + paths.size() + " item(s)? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    int deleted = 0, failed = 0;

                    for (String path : paths) {
                        File f = new File(path);
                        boolean ok = false;

                        if (f.exists()) {
                            try { ok = f.delete(); }
                            catch (Exception e) { Log.e(LOG_TAG, "delete failed: " + path, e); }
                        } else {
                            ok = true;
                        }

                        if (ok) {
                            deleted++;
                            for (int i = mediaItems.size() - 1; i >= 0; i--) {
                                if (mediaItems.get(i).path.equals(path)) {
                                    mediaItems.remove(i);
                                    break;
                                }
                            }
                        } else failed++;
                    }

                    albumsCacheValid = false;
                    clearSelection();
                    applyFilter();

                    if (deleted > 0) scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);

                    if (failed == 0) {
                        Toast.makeText(this, "Deleted " + deleted + " item(s)",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Deleted " + deleted + ", failed " + failed,
                                Toast.LENGTH_LONG).show();
                    }
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
        dragLastX = -1f;
        dragLastY = -1f;
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
                        String display = (name != null && !name.isEmpty())
                                ? name : parent.getName();
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
                        String display = (name != null && !name.isEmpty())
                                ? name : parent.getName();
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

        albumAdapter = new AlbumAdapter(this, displayList, displayName -> {
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
        videoHandler.removeCallbacks(overlayProgressRunnable);
        videoHandler.removeCallbacks(overlayHideControlsRunnable);
        mediaRefreshHandler.removeCallbacksAndMessages(null);
        pendingMediaRefresh = null;
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