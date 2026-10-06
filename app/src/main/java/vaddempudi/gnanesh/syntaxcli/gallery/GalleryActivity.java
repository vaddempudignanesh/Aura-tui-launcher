package vaddempudi.gnanesh.syntaxcli.gallery;

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
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vaddempudi.gnanesh.syntaxcli.R;

public class GalleryActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "gallery_prefs";
    private static final String PREF_FAVORITES = "favorite_paths";

    private static final long MEDIA_REFRESH_DEBOUNCE_MS = 400L;
    private static final long MEDIA_HUD_VISIBLE_MS = 800L;
    private static final int AUTO_SCROLL_EDGE_DP = 80;
    private static final long AUTO_SCROLL_TICK_MS = 8L;
    private static final int GRID_COLUMNS = 3;
    private volatile boolean repositoryAtEnd = false;
    private static final int OVERLAY_CONTROLS_TIMEOUT = 3000;
    private static final int SKIP_FORWARD_MS = 10000;
    private static final int SKIP_BACKWARD_MS = 10000;
    private static final float LONG_PRESS_SPEED = 2.0f;
    private boolean mediaEverLoaded = false;
    private boolean transientLoading = false;
    private FilterMode lastRenderedFilter = null;
    private int lastRenderedCount = -1;
    private AlbumsController albumsController;
    private enum FilterMode { ALL, IMAGES, VIDEOS, FAVORITES, BIN }

    private SharedPreferences prefs;
    private GalleryRepository repository;
    private GalleryAdapter screenAdapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mediaRefreshHandler = new Handler(Looper.getMainLooper());
    private final Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingMediaRefresh;

    private String pendingAlbumLoad = null;
    private RecyclerView recyclerView;
    private RecyclerView albumRecycler;
    private ProgressBar loadingSpinner;
    private TextView titleView;
    private LinearLayout sortOptions;
    private LinearLayout bottomBar, selectionBar, binBottomBar;
    private View selectionTopBar;
    private TextView selectionCount;
    private TextView mediaHudVolume, mediaHudBrightness;
    private Runnable mediaHudHideRunnable;
    private static final String PREF_BIN_PATHS = "bin_paths";
    private RelativeLayout fullscreenOverlay;
    private ViewPager2 fullscreenViewPager;
    private View fullscreenInfoHeader;
    private TextView fullscreenInfoName, fullscreenInfoDetails, fullscreenInfoPath;
    private LinearLayout fullscreenBottomBar;
    private LinearLayout videoControlContainer;
    private ImageButton btnCenterPlayPause;
    private ImageButton btnSkipForwardOverlay;
    private ImageButton btnSkipBackwardOverlay;
    private View fsBtnBin, fsBtnShare, fsBtnInfo, fsBtnFavorite;
    private ImageView fsBtnFavoriteIcon;
    private View videoControlsOverlay;
    private ImageButton btnFavoriteOverlay, btnInfoOverlay, btnDeleteOverlay, btnRotateOverlay;
    private TextView videoTimeCurrent, videoTimeTotal, videoTitleOverlay;
    private SeekBar videoSeekBar;
    private ImageButton btnPlayPause, btnCloseVideo;
    private TextView videoTime;
    private LinearLayout videoCenterControls, videoBottomControls;
    private RelativeLayout videoPlayerContainer;
    private VideoView videoView;

    private final LinkedHashMap<String, GalleryMediaItem> trashedByPath = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> albumDisplayNames = new LinkedHashMap<>();

    private FilterMode currentFilter = FilterMode.ALL;
    private String currentAlbum = null;
    private boolean showAlbums = false;
    private boolean whatsappOnly = false;
    private android.widget.FrameLayout fastScrollTrack;
    private android.widget.FrameLayout fastScrollThumb;
    private boolean fastScrollDragging = false;


    private String whatsappPath = null;
    private boolean trashedIndexDirty = true;
    private boolean albumsCacheValid = false;

    private final HashMap<String, String> canonicalAlbumCache = new HashMap<>();

    private boolean selectionMode = false;
    private final List<String> selectedItems = new ArrayList<>();

    private boolean dragSelectActive = false;
    private boolean dragSelectDeselectMode = false;
    private String dragAnchorPath = null;
    private float dragLastY = -1f;
    private float dragLastX = -1f;
    private int dragLastRectMinRow = -1, dragLastRectMaxRow = -1;
    private int dragLastRectMinCol = -1, dragLastRectMaxCol = -1;
    private final Set<String> selectionBeforeDrag = new HashSet<>();
    private final Set<String> dragRectanglePaths = new HashSet<>();
    private boolean autoScrollActive = false;
    private int autoScrollDx = 0, autoScrollDy = 0;
    private final List<GalleryMediaItem> cachedMedia = new ArrayList<>();
    // Media belonging ONLY to the currently selected physical album.
// Never mix this with cachedMedia/global gallery media.
    private final List<GalleryMediaItem> albumMedia = new ArrayList<>();
    private FullscreenAdapter fullscreenAdapter;
    private final List<String> fullscreenMediaPaths = new ArrayList<>();
    private int fullscreenCurrentPosition = 0;
    private CustomVideoView currentFullscreenVideo = null;
    private int currentFullscreenVideoPosition = -1;
    private boolean currentFullscreenPageIsVideo = false;
    private int overlayPendingSeekMs = -1;
    private int savedVideoPositionMs = -1;
    private String savedVideoPath = null;
    private boolean savedVideoWasPlaying = false;
    private boolean isOverlayVideoPlaying = false;
    private boolean overlayControlsVisible = false;
    private boolean fullscreenChromeVisible = false;
    private Runnable overlayHideControlsRunnable;
    private Runnable overlayProgressRunnable;
    private boolean userIsSwiping = false;
    private GestureDetector overlayTapDetector;
    private GestureDetector overlayVideoGestureDetector;
    private android.view.View.OnTouchListener volumeBrightnessHandler;
    private Runnable pendingChromeTapRunnable = null;
    private boolean chromeDoubleTapArmed = false;
    private float playbackSpeedBeforeLongPress = 1.0f;
    private boolean isLandscape = false;
    private View decorView;

    // External intent
    private boolean launchedFromExternalIntent = false;
    private String pendingInitialPath;


    private final ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean granted = true;
                        for (Map.Entry<String, Boolean> e : result.entrySet()) {
                            if (!e.getValue()) { granted = false; break; }
                        }
                        if (granted) {
                            repository.loadCachedFirstPage();
                            repository.refreshFirstPage();
                        } else {
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

        configureWindow();
        bindViews();
        installFastScrollBar();
        buildAdapter();
        setupAlbums();
        wireToolbarButtons();
        wireSortMenu();
        wireSelectionBar();
        buildFullscreenAdapter();
        wireFullscreenControls();
        wireVolumeBrightness();

        repository = new GalleryRepository(this);
        repository.addListener(repositoryListener);

        checkAndRequestMediaPermissions();
        handleViewIntent(getIntent());
    }

    private void configureWindow() {
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
        decorView = getWindow().getDecorView();
    }

    private void bindViews() {
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

        loadingSpinner = findViewById(R.id.loadingSpinner);
        if (loadingSpinner != null) {
            int strokePx = Math.round(3 * getResources().getDisplayMetrics().density);
            loadingSpinner.setIndeterminateDrawable(
                    new HexRingDrawable(strokePx, Color.WHITE));
        }

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
        videoTitleOverlay = findViewById(R.id.videoTitle);
    }


    private void buildAdapter() {
        screenAdapter = new GalleryAdapter(this, new GalleryAdapter.OnItemClickListener() {
            @Override public void onImageClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN)
                    openFullscreenViewer(path);
            }
            @Override public void onVideoClick(String path) {
                if (!selectionMode && isActivityAlive() && currentFilter != FilterMode.BIN)
                    openFullscreenViewer(path);
            }
            @Override public void onFavoriteToggle(GalleryMediaItem item) {
                if (!isActivityAlive()) return;
                toggleFavoriteForPath(item.path);
                // No refreshCurrentList() here — toggleFavoriteForPath already
                // submitted a fresh list with the correct state.
            }
            @Override public void onDelete(GalleryMediaItem item) {
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
            @Override public void onRestore(GalleryMediaItem item) {
                if (isActivityAlive()) restoreFromTrash(item);
            }
        });

        recyclerView.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        recyclerView.setAdapter(screenAdapter);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(40);
        recyclerView.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        setupDragToSelect();



        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }
    private void setupAlbums() {
        AlbumLoader loader = new AlbumLoader(this);

        AlbumAdapter albumAdapter = new AlbumAdapter(this, album -> {
            albumsController.openAlbum(album);
            updateTopNavBar();
            updateBarsVisibility();
        });

        albumsController = new AlbumsController(
                new AlbumsController.Host() {
                    @Override public View findView(int id) { return findViewById(id); }
                    @Override public void onAlbumOpened(AlbumRecord album) {
                        updateTopNavBar();
                        updateBarsVisibility();
                    }
                    @Override public void onAlbumClosed() {
                        updateTopNavBar();
                        updateBarsVisibility();
                    }
                },
                loader, albumAdapter);
    }

    private boolean isInAlbum(GalleryMediaItem it, String albumPath) {
        if (it == null || it.path == null || albumPath == null) return false;

        // Raw path prefix — works regardless of canonical form on either side.
        if (it.path.startsWith(albumPath + "/")) return true;

        // Canonical equality between the item's albumPath and the target.
        String itemCanon = it.albumPath == null ? null : getCachedCanonical(it.albumPath);
        String targetCanon = getCachedCanonical(albumPath);
        if (itemCanon != null && targetCanon != null && itemCanon.equals(targetCanon)) {
            return true;
        }
        return false;
    }
    private boolean repositoryIsFullyLoaded() {

        return repositoryAtEnd;
    }



    private final GalleryRepository.Listener repositoryListener =
            new GalleryRepository.Listener() {

                @Override
                public void onAlbumItemsChanged(
                        String albumPath,
                        List<GalleryMediaItem> items) {

                    if (!isActivityAlive()) return;

                    if (currentAlbum == null) return;

                    if (pendingAlbumLoad == null) return;

                    if (!currentAlbum.equals(albumPath)) return;

                    if (!pendingAlbumLoad.equals(albumPath)) return;

                    // This result belongs to the currently selected album.
                    albumMedia.clear();

                    if (items != null) {
                        albumMedia.addAll(items);
                    }

                    // Mark this particular album request as completed.
                    pendingAlbumLoad = null;
                    transientLoading = false;

                    if (loadingSpinner != null) {
                        loadingSpinner.setVisibility(View.GONE);
                    }

                    View ev = findViewById(R.id.emptyStateContainer);
                    if (ev != null) {
                        ev.setVisibility(View.GONE);
                    }

                    recyclerView.setVisibility(View.VISIBLE);

                    // Directly display ONLY this album's media.
                    screenAdapter.submitList(new ArrayList<>(albumMedia));

                    updateSelectionUI();
                    showEmptyState();
                    updateBarsVisibility();
                    updateTopNavBar();
                }
                @Override
                public void onMediaChanged(List<GalleryMediaItem> items, boolean endReached) {
                    if (!isActivityAlive()) return;

                    if (albumsController != null
                            && (albumsController.isShowingAlbums() || albumsController.isAlbumOpen())) {
                        return;
                    }
                    if (currentAlbum != null) return;

                    if (pendingAlbumLoad != null) {
                        boolean albumItemsPresent = false;
                        for (GalleryMediaItem it : items) {
                            if (isInAlbum(it, pendingAlbumLoad)) {
                                albumItemsPresent = true;
                                break;
                            }
                        }
                        if (!albumItemsPresent && currentAlbum != null) {
                            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
                            return;
                        }
                        pendingAlbumLoad = null;
                    }

                    if ((items == null || items.isEmpty()) && !cachedMedia.isEmpty()) {
                        return;
                    }

                    mediaEverLoaded = true;
                    repositoryAtEnd = endReached;
                    cachedMedia.clear();
                    Set<String> favs = loadFavoritePaths();
                    for (GalleryMediaItem it : items) {
                        boolean trashed = it.path != null && it.path.contains(".trashed.");
                        GalleryMediaItem copy = new GalleryMediaItem(
                                it.id, it.path, it.displayName, it.albumPath,
                                it.dateModifiedSeconds, it.type);
                        copy.isTrashed = trashed;
                        copy.isFavorite = it.isFavorite
                                || (it.path != null && favs.contains(it.path));
                        cachedMedia.add(copy);
                    }

                    if (currentFilter == FilterMode.BIN) {
                        return;
                    }

                    transientLoading = false;
                    refreshCurrentList();
                    tryOpenPendingInitialPath();
                }



                @Override
                public void onBinChanged(List<GalleryMediaItem> binItems) {
                    if (currentFilter == FilterMode.BIN) {
                        refreshCurrentList();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                }
            };

    private void exitBin() {
        if (currentFilter != FilterMode.BIN) return;

        albumsController.leaveAlbums();

        pendingAlbumLoad = null;
        currentFilter = FilterMode.ALL;
        currentAlbum = null;
        whatsappOnly = false;
        whatsappPath = null;
        showAlbums = false;
        titleView.setText("Gallery");
        albumRecycler.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        if (sortOptions != null) sortOptions.setVisibility(View.GONE);

        recyclerView.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        recyclerView.setAdapter(screenAdapter);
        setupDragToSelect();

        clearSelection();

        transientLoading = true;
        refreshCurrentList();
        if (repository != null) {
            repository.refreshFirstPage();
            repositoryAtEnd = false;
        }
        updateBarsVisibility();
        updateTopNavBar();

        recyclerView.postDelayed(() -> {
            transientLoading = false;
            showEmptyState();
        }, 150L);
    }

    private void wireToolbarButtons() {
        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> onBackPressed());
        }

        TextView btnHome = findViewById(R.id.btnHome);
        TextView btnAlbums = findViewById(R.id.btnAlbums);
        TextView btnSort = findViewById(R.id.btnSort);
        if (btnHome == null || btnAlbums == null || btnSort == null) return;

        btnHome.setOnClickListener(v -> {
            albumsController.leaveAlbums();

            pendingAlbumLoad = null;
            currentFilter = FilterMode.ALL;
            currentAlbum = null;
            whatsappOnly = false;
            whatsappPath = null;
            showAlbums = false;
            titleView.setText("Gallery");
            albumRecycler.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            if (sortOptions != null) sortOptions.setVisibility(View.GONE);

            recyclerView.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
            recyclerView.setAdapter(screenAdapter);
            setupDragToSelect();

            clearSelection();
            transientLoading = true;
            refreshCurrentList();
            recyclerView.post(() -> {
                transientLoading = false;
                showEmptyState();
            });

            if (repository != null) {
                repository.refreshFirstPage();
                repositoryAtEnd = false;
            }

            updateBarsVisibility();
            updateTopNavBar();
        });

        btnAlbums.setOnClickListener(v -> {
            if (albumsController.isShowingAlbums() || albumsController.isAlbumOpen()) {
                albumsController.onBack();
            } else {
                albumsController.showAlbums();
            }
            updateTopNavBar();
            updateBarsVisibility();
        });

        btnSort.setOnClickListener(v -> {
            if (sortOptions == null) return;
            refreshBinMenuVisibility();
            sortOptions.setVisibility(sortOptions.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });
    }

    private void wireSortMenu() {
        TextView sortImages = findViewById(R.id.sortImages);
        TextView sortVideos = findViewById(R.id.sortVideos);
        TextView sortFavorites = findViewById(R.id.sortFavorites);
        TextView sortBin = findViewById(R.id.sortBin);

        if (sortImages != null) sortImages.setOnClickListener(v -> applyQuickFilter(FilterMode.IMAGES, "Images"));
        if (sortVideos != null) sortVideos.setOnClickListener(v -> applyQuickFilter(FilterMode.VIDEOS, "Videos"));
        if (sortFavorites != null) sortFavorites.setOnClickListener(v -> applyQuickFilter(FilterMode.FAVORITES, "Favorites"));
        if (sortBin != null) sortBin.setOnClickListener(v -> applyQuickFilter(FilterMode.BIN, "Bin"));

        refreshBinMenuVisibility();
    }

    private void refreshBinMenuVisibility() {
        TextView sortBin = findViewById(R.id.sortBin);
        if (sortBin == null) return;
        boolean hasBin = !loadBinPaths().isEmpty();
        sortBin.setVisibility(hasBin ? View.VISIBLE : View.GONE);
    }
    private void applyQuickFilter(FilterMode mode, String title) {
        albumsController.leaveAlbums();

        pendingAlbumLoad = null;
        currentFilter = mode;
        currentAlbum = null;
        whatsappOnly = false;
        showAlbums = false;
        albumRecycler.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        if (titleView != null) titleView.setText(title);
        if (sortOptions != null) sortOptions.setVisibility(View.GONE);

        recyclerView.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        recyclerView.setAdapter(screenAdapter);
        setupDragToSelect();

        if (cachedMedia.isEmpty() && mode != FilterMode.BIN) {
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            View ev = findViewById(R.id.emptyStateContainer);
            if (ev != null) ev.setVisibility(View.GONE);
            if (repository != null) {
                repository.refreshFirstPage();
                repositoryAtEnd = false;
            }
            updateBarsVisibility();
            updateTopNavBar();
            return;
        }

        refreshCurrentList();
        updateBarsVisibility();
        updateTopNavBar();
    }

    private void wireSelectionBar() {
        if (binBottomBar != null) {
            TextView btnRestore = findViewById(R.id.btnRestore);
            TextView btnDeletePermanent = findViewById(R.id.btnDeletePermanent);
            if (btnRestore != null) btnRestore.setOnClickListener(v -> restoreSelectedItems());
            if (btnDeletePermanent != null) btnDeletePermanent.setOnClickListener(v -> deletePermanentlySelectedItems());
        }
        ImageButton btnCloseSelection = findViewById(R.id.btnCloseSelection);
        if (btnCloseSelection != null) btnCloseSelection.setOnClickListener(v -> clearSelection());

        TextView btnBinSelected = findViewById(R.id.btnBinSelected);
        TextView btnShareSelected = findViewById(R.id.btnShareSelected);
        TextView btnInfoSelected = findViewById(R.id.btnInfoSelected);
        TextView btnFavoriteSelected = findViewById(R.id.btnFavoriteSelected);

        if (btnBinSelected != null) btnBinSelected.setOnClickListener(v -> moveSelectedToTrash());
        if (btnShareSelected != null) btnShareSelected.setOnClickListener(v -> shareSelectedItems());
        if (btnInfoSelected != null) btnInfoSelected.setOnClickListener(v -> showSelectedItemInfo());
        if (btnFavoriteSelected != null) btnFavoriteSelected.setOnClickListener(v -> addSelectedToFavorites());
    }


    private void installFastScrollBar() {
        android.view.ViewGroup content =
                (android.view.ViewGroup) findViewById(android.R.id.content);
        if (content == null) return;

        int barWidthPx = Math.round(6 * getResources().getDisplayMetrics().density);
        int marginPx = Math.round(6 * getResources().getDisplayMetrics().density);

        // Track — a thin transparent column, only used to receive touches.
        fastScrollTrack = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams tlp =
                new android.widget.FrameLayout.LayoutParams(
                        barWidthPx * 4,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        tlp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        tlp.setMargins(0, marginPx * 4, 0, marginPx * 4);
        fastScrollTrack.setLayoutParams(tlp);
        fastScrollTrack.setVisibility(View.GONE);
        content.addView(fastScrollTrack);
        fastScrollTrack.post(this::updateFastScrollBar);
        // Thumb — the visible pill.
        fastScrollThumb = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable pill =
                new android.graphics.drawable.GradientDrawable();
        pill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pill.setColor(0xFF9E9E9E);
        pill.setCornerRadius(24f);
        fastScrollThumb.setBackground(pill);
        fastScrollThumb.setAlpha(0.55f);
        android.widget.FrameLayout.LayoutParams hlp =
                new android.widget.FrameLayout.LayoutParams(
                        barWidthPx,
                        Math.round(48 * getResources().getDisplayMetrics().density));
        hlp.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
        fastScrollThumb.setLayoutParams(hlp);
        fastScrollTrack.addView(fastScrollThumb);

        fastScrollTrack.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE: {
                    fastScrollDragging = true;
                    scrollFromBar(event.getY());
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    fastScrollDragging = false;
                    return true;
                }
            }
            return false;
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!fastScrollDragging) updateFastScrollBar();
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                updateFastScrollBar();
            }
        });
    }

    private void scrollFromBar(float yInTrack) {
        if (fastScrollTrack == null) return;
        int trackH = fastScrollTrack.getHeight();
        if (trackH <= 0) return;

        float frac = yInTrack / (float) trackH;
        if (frac < 0f) frac = 0f;
        if (frac > 1f) frac = 1f;

        int total = screenAdapter.getItemCount();
        if (total <= 0) return;

        int target = (int) (frac * (total - 1));
        recyclerView.scrollToPosition(target);
        updateFastScrollBar();
    }

    private void updateFastScrollBar() {
        if (fastScrollTrack == null || fastScrollThumb == null) return;

        int total = screenAdapter.getItemCount();

        boolean galleryVisible = total > 0
                && currentFilter != FilterMode.BIN
                && currentAlbum == null
                && !whatsappOnly
                && !showAlbums
                && (albumsController == null
                || (!albumsController.isShowingAlbums()
                && !albumsController.isAlbumOpen()));

        if (!galleryVisible) {
            fastScrollTrack.setVisibility(View.GONE);
            return;
        }

        fastScrollTrack.setVisibility(View.VISIBLE);

        GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
        if (lm == null) return;

        int first = lm.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) first = 0;

        float frac = total <= 1 ? 0f : (first / (float) (total - 1));
        if (frac < 0f) frac = 0f;
        if (frac > 1f) frac = 1f;

        int trackH = fastScrollTrack.getHeight();
        if (trackH <= 0) return;

        int thumbH = fastScrollThumb.getHeight();
        if (thumbH <= 0) thumbH = Math.round(48 * getResources().getDisplayMetrics().density);

        if (thumbH > trackH) thumbH = trackH;
        int thumbTop = Math.round(frac * (trackH - thumbH));

        fastScrollThumb.setTranslationY(thumbTop);
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

        boolean granted = true;
        for (String p : toRequest) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
        }

        if (granted) {
            repository.loadCachedFirstPage();
            repository.refreshFirstPage();
        } else {
            requestMultiplePermissionsLauncher.launch(toRequest);
        }
    }


    private Set<String> loadBinPaths() {
        if (prefs == null) return new HashSet<>();
        String raw = prefs.getString(PREF_BIN_PATHS, "");
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split("\n")) if (!p.isEmpty()) set.add(p);
        }
        return set;
    }

    private void saveBinPaths(Set<String> paths) {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String p : paths) sb.append(p).append('\n');
        prefs.edit().putString(PREF_BIN_PATHS, sb.toString()).apply();
    }

    private void addBinPath(String path) {
        if (path == null) return;
        Set<String> set = loadBinPaths();
        if (set.add(path)) saveBinPaths(set);
    }

    private void removeBinPath(String path) {
        if (path == null) return;
        Set<String> set = loadBinPaths();
        if (set.remove(path)) saveBinPaths(set);
    }

    private List<GalleryMediaItem> buildBinListFromRegistry() {
        List<GalleryMediaItem> out = new ArrayList<>();
        for (String path : loadBinPaths()) {
            File f = new File(path);
            if (!f.exists()) continue;
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            int type;
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov")
                    || lower.endsWith(".3gp") || lower.endsWith(".m4v")
                    || lower.endsWith(".flv") || lower.endsWith(".wmv")) {
                type = GalleryMediaItem.TYPE_VIDEO;
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".gif")
                    || lower.endsWith(".bmp") || lower.endsWith(".webp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif")) {
                type = GalleryMediaItem.TYPE_IMAGE;
            } else {
                continue;
            }
            GalleryMediaItem item = new GalleryMediaItem(
                    path.hashCode(),
                    path,
                    name,
                    f.getParent() == null ? "" : f.getParent(),
                    f.lastModified() / 1000L,
                    type);
            item.isTrashed = true;
            out.add(item);
        }
        Collections.sort(out, (a, b) -> {
            int c = Long.compare(b.lastModifiedMillis, a.lastModifiedMillis);
            if (c != 0) return c;
            String pa = a.path == null ? "" : a.path;
            String pb = b.path == null ? "" : b.path;
            return pb.compareTo(pa);
        });
        return out;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_EDIT.equals(action)) return;

        Uri data = intent.getData();
        if (data == null && Intent.ACTION_SEND.equals(action)) {
            data = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (data == null) return;

        final String path = resolveMediaPath(data);
        if (path == null) {
            Toast.makeText(this, "Cannot open this file", Toast.LENGTH_SHORT).show();
            return;
        }
        launchedFromExternalIntent = true;
        pendingInitialPath = path;
        tryOpenPendingInitialPath();
    }

    private String resolveMediaPath(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) return uri.getPath();

        if ("content".equalsIgnoreCase(scheme)) {
            Cursor c = null;
            try {
                c = getContentResolver().query(uri,
                        new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                    if (idx >= 0) {
                        String p = c.getString(idx);
                        if (p != null && new File(p).exists()) return p;
                    }
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.close(); }

            try {
                String name = queryDisplayName(uri);
                if (name == null) name = "incoming_" + System.currentTimeMillis();
                File cacheDir = new File(getCacheDir(), "incoming");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File out = new File(cacheDir, name);
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    if (in == null) return null;
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                return out.getAbsolutePath();
            } catch (Exception e) { return null; }
        }
        return null;
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    private void tryOpenPendingInitialPath() {
        if (pendingInitialPath == null) return;
        if (screenAdapter == null || screenAdapter.getItemCount() == 0) return;

        String path = pendingInitialPath;
        pendingInitialPath = null;

        boolean present = false;
        List<GalleryMediaItem> current = screenAdapter.getItems();
        for (GalleryMediaItem m : current) {
            if (m.path.equals(path)) { present = true; break; }
        }
        if (!present) {
            File f = new File(path);
            if (!f.exists()) return;
            GalleryMediaItem mi = new GalleryMediaItem(
                    f.getAbsolutePath().hashCode(),
                    path,
                    f.getName(),
                    f.getParent() != null ? f.getParent() : "",
                    f.lastModified() / 1000L,
                    isVideoPath(path) ? GalleryMediaItem.TYPE_VIDEO
                            : GalleryMediaItem.TYPE_IMAGE);
            List<GalleryMediaItem> merged = new ArrayList<>(current);
            merged.add(0, mi);
            screenAdapter.submitList(merged);
        }

        currentFilter = FilterMode.ALL;
        currentAlbum = null;
        showAlbums = false;
        whatsappOnly = false;
        recyclerView.post(() -> openFullscreenViewer(path));
    }


    private void refreshCurrentList() {
        if (!isActivityAlive()) return;
        if (albumsController != null
                && (albumsController.isShowingAlbums() || albumsController.isAlbumOpen())) {
            return;
        }

        if (currentAlbum != null) {
            if (pendingAlbumLoad != null) {
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
                View ev = findViewById(R.id.emptyStateContainer);
                if (ev != null) ev.setVisibility(View.GONE);
                return;
            }
            List<GalleryMediaItem> albumSnapshot = new ArrayList<>(albumMedia);
            screenAdapter.submitList(albumSnapshot);
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            updateSelectionUI();
            showEmptyState();
            updateTopNavBar();
            return;
        }

        if (currentFilter == FilterMode.BIN) {
            List<GalleryMediaItem> snapshot = buildBinListFromRegistry();
            trashedByPath.clear();
            for (GalleryMediaItem it : snapshot) trashedByPath.put(it.path, it);
            trashedIndexDirty = false;
            screenAdapter.submitList(new ArrayList<>(snapshot));
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            updateSelectionUI();
            showEmptyState();
            updateTopNavBar();
            return;
        }
        Collections.sort(cachedMedia, (a, b) -> {
            int c = Long.compare(b.lastModifiedMillis, a.lastModifiedMillis);
            if (c != 0) return c;
            String pa = a.path == null ? "" : a.path;
            String pb = b.path == null ? "" : b.path;
            return pb.compareTo(pa);
        });
        final List<GalleryMediaItem> filtered = new ArrayList<>();
        Set<String> favs = null;
        if (currentFilter == FilterMode.FAVORITES) favs = loadFavoritePaths();

        for (GalleryMediaItem item : cachedMedia) {
            if (item.path == null) continue;
            if (currentAlbum != null && !isInAlbum(item, currentAlbum)) continue;

            if (whatsappOnly) {
                String needle = "/Media/WhatsApp Images/";
                if (!item.path.contains("/com.whatsapp/")) continue;
                if (!item.path.contains(needle)) continue;
                if (whatsappPath != null && !whatsappPath.equals("whatsapp://all")) {
                    String accountId = whatsappPath.substring("whatsapp://".length());
                    if (!item.path.contains("/accounts/" + accountId + "/")) continue;
                } else {
                    String after = item.path.substring(
                            item.path.indexOf(needle) + needle.length());
                    if (after.contains("/")) continue;
                }
            }

            boolean trashed = item.path.contains(".trashed.");
            item.isTrashed = trashed;

            switch (currentFilter) {
                case ALL:       if (!trashed) filtered.add(item); break;
                case IMAGES:    if (!trashed && item.type == GalleryMediaItem.TYPE_IMAGE) filtered.add(item); break;
                case VIDEOS:    if (!trashed && item.type == GalleryMediaItem.TYPE_VIDEO) filtered.add(item); break;
                case FAVORITES: if (!trashed && favs != null && favs.contains(item.path)) {
                    item.isFavorite = true;
                    filtered.add(item);
                } break;
            }
        }

        // The list is already in LATEST_FIRST order because cachedMedia
        // is sorted above, and we iterated it in order.
        screenAdapter.submitList(filtered);
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        updateSelectionUI();
        showEmptyState();
        updateTopNavBar();
        updateFastScrollBar();
    }



    private void onAlbumSelected(AlbumRecord album) {
        albumsController.openAlbum(album);
    }

    private void navigateBackFromAlbum() {
        if (!albumsController.onBack()) {
            // Nothing to go back to.
        }
        updateTopNavBar();
        updateBarsVisibility();
    }

    private void updateTopNavBar() {
        ImageButton btnBack = findViewById(R.id.btnBackGallery);
        TextView title = findViewById(R.id.titleGallery);
        if (btnBack == null || title == null) return;

        boolean albumsOpen = albumsController != null
                && (albumsController.isShowingAlbums() || albumsController.isAlbumOpen());
        boolean showBack = currentFilter == FilterMode.BIN
                || albumsOpen || whatsappOnly;
        btnBack.setVisibility(showBack ? View.VISIBLE : View.GONE);

        if (currentFilter == FilterMode.BIN) {
            title.setText("Bin");
        } else if (albumsController != null && albumsController.isAlbumOpen()) {
            AlbumRecord open = albumsController.getOpenAlbum();
            title.setText(open != null ? open.displayName : "Album");
        } else if (albumsController != null && albumsController.isShowingAlbums()) {
            title.setText("Albums");
        } else {
            title.setText("Gallery");
        }
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
            refreshBinMenuVisibility();

    }

    private void showEmptyState() {
        View emptyView = findViewById(R.id.emptyStateContainer);
        if (emptyView == null) return;

        int count = screenAdapter == null ? 0 : screenAdapter.getItemCount();

        boolean binLoading = currentFilter == FilterMode.BIN
                && trashedIndexDirty && trashedByPath.isEmpty();
        boolean mediaLoading = count == 0 && (!mediaEverLoaded || transientLoading)
                && currentFilter != FilterMode.BIN;

        if (binLoading || mediaLoading) {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.GONE);
            if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
            return;
        }
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);

        if (count == 0) {
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
        updateFastScrollBar();

    }

    private Set<String> loadFavoritePaths() {
        if (prefs == null) return new HashSet<>();
        String raw = prefs.getString(PREF_FAVORITES, "");
        Set<String> set = new HashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split("\n")) if (!p.isEmpty()) set.add(p);
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
        boolean nowFav = !favs.contains(path);
        if (nowFav) favs.add(path); else favs.remove(path);
        saveFavoritePaths(favs);

        // Update BOTH cachedMedia and the adapter's current list with
        // fresh instances so DiffUtil sees a real change.
        List<GalleryMediaItem> adapterItems = screenAdapter.getItems();
        List<GalleryMediaItem> replacement = new ArrayList<>(adapterItems.size());
        for (GalleryMediaItem it : adapterItems) {
            if (it.path.equals(path)) {
                GalleryMediaItem copy = new GalleryMediaItem(
                        it.id, it.path, it.displayName, it.albumPath,
                        it.dateModifiedSeconds, it.type);
                copy.isFavorite = nowFav;
                copy.isTrashed = it.isTrashed;
                replacement.add(copy);
            } else {
                replacement.add(it);
            }
        }
        screenAdapter.submitList(replacement);

        // Mirror into cachedMedia so subsequent refreshes keep the flag.
        for (int i = 0; i < cachedMedia.size(); i++) {
            if (cachedMedia.get(i).path.equals(path)) {
                cachedMedia.get(i).isFavorite = nowFav;
                break;
            }
        }

        return nowFav;
    }

    private void addSelectedToFavorites() {
        if (selectedItems.isEmpty()) return;
        Set<String> favs = loadFavoritePaths();
        boolean anyChanged = false;
        Set<String> changedPaths = new HashSet<>();

        for (String path : selectedItems) {
            if (favs.contains(path)) {
                favs.remove(path);
                changedPaths.add(path);
                anyChanged = true;
            } else {
                favs.add(path);
                changedPaths.add(path);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            saveFavoritePaths(favs);

            for (int i = 0; i < cachedMedia.size(); i++) {
                if (changedPaths.contains(cachedMedia.get(i).path)) {
                    cachedMedia.get(i).isFavorite = favs.contains(cachedMedia.get(i).path);
                }
            }

            if (currentFilter == FilterMode.FAVORITES) {
                clearSelection();
                refreshCurrentList();
                Toast.makeText(this, "Favorites updated", Toast.LENGTH_SHORT).show();
                return;
            }

            List<GalleryMediaItem> adapterItems = screenAdapter.getItems();
            List<GalleryMediaItem> replacement = new ArrayList<>(adapterItems.size());
            for (GalleryMediaItem it : adapterItems) {
                if (changedPaths.contains(it.path)) {
                    GalleryMediaItem copy = new GalleryMediaItem(
                            it.id, it.path, it.displayName, it.albumPath,
                            it.dateModifiedSeconds, it.type);
                    copy.isFavorite = favs.contains(it.path);
                    copy.isTrashed = it.isTrashed;
                    replacement.add(copy);
                } else {
                    replacement.add(it);
                }
            }
            screenAdapter.submitList(replacement);
            for (String p : changedPaths) {
                screenAdapter.notifyFavoriteChanged(p);
            }

            Toast.makeText(this, "Favorites updated", Toast.LENGTH_SHORT).show();
            clearSelection();
        } else {
            clearSelection();
        }
    }

    private void updateFullscreenFavoriteIcon(String path) {
        if (path == null) return;
        boolean fav = false;
        for (GalleryMediaItem it : cachedMedia) {
            if (it.path.equals(path)) { fav = it.isFavorite; break; }
        }
        if (!fav && loadFavoritePaths().contains(path)) fav = true;
        applyStarIcon(fsBtnFavoriteIcon, fav);
        applyStarIcon(btnFavoriteOverlay, fav);
    }

    private void applyStarIcon(ImageView v, boolean fav) {
        if (v == null) return;
        v.setImageResource(fav ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
        int color = fav ? Color.parseColor("#FFD700") : Color.WHITE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            v.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        } else {
            v.setColorFilter(color);
        }
    }


    private void toggleSelection(String path) {
        if (selectedItems.contains(path)) selectedItems.remove(path);
        else selectedItems.add(path);
        updateSelectionUI();
        if (screenAdapter != null) screenAdapter.setSelectedItems(selectedItems);
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

    private void clearSelection() {
        selectedItems.clear();
        selectionMode = false;
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        if (selectionBar != null) selectionBar.setVisibility(View.GONE);
        if (binBottomBar != null) binBottomBar.setVisibility(View.GONE);
        if (selectionTopBar != null) selectionTopBar.setVisibility(View.GONE);
        if (screenAdapter != null) screenAdapter.setSelectedItems(selectedItems);
        resetDragState();
        updateFastScrollBar();
    }

    private void startLongPressDrag(String path) {
        if (!isActivityAlive() || screenAdapter == null) return;
        List<GalleryMediaItem> items = screenAdapter.getItems();
        int anchorIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path.equals(path)) { anchorIdx = i; break; }
        }
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
        if (screenAdapter == null) return -1;
        List<GalleryMediaItem> items = screenAdapter.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path.equals(path)) return i;
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
                if (anchorIdx >= 0) applyDragRectangle(anchorIdx, pos);
            }
        }
        updateAutoScroll(rv, x, y);
    }

    private void updateAutoScroll(RecyclerView rv, float x, float y) {
        if (rv == null) { stopAutoScroll(); return; }
        int edge = dpToPx(AUTO_SCROLL_EDGE_DP);
        int h = rv.getHeight(), w = rv.getWidth();

        int dy = 0, dx = 0;
        if (y < edge) dy = -(int) (8 + (1f - y / (float) edge) * 42);
        else if (y > h - edge) dy = (int) (8 + (1f - (h - y) / (float) edge) * 42);
        if (x < edge) dx = -(int) (8 + (1f - x / (float) edge) * 42);
        else if (x > w - edge) dx = (int) (8 + (1f - (w - x) / (float) edge) * 42);

        if (dx == 0 && dy == 0) { stopAutoScroll(); return; }
        autoScrollDx = dx; autoScrollDy = dy;
        if (!autoScrollActive) {
            autoScrollActive = true;
            rv.postDelayed(autoScrollRunnable, AUTO_SCROLL_TICK_MS);
        }
    }

    private void stopAutoScroll() {
        autoScrollActive = false;
        autoScrollDx = 0;
        autoScrollDy = 0;
        if (recyclerView != null) recyclerView.removeCallbacks(autoScrollRunnable);
    }

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override public void run() {
            if (!autoScrollActive || recyclerView == null) return;
            recyclerView.scrollBy(autoScrollDx, autoScrollDy);
            recyclerView.postDelayed(this, AUTO_SCROLL_TICK_MS);
        }
    };

    private void applyDragRectangle(int anchorIndex, int currentIndex) {
        int total = screenAdapter == null ? 0 : screenAdapter.getItems().size();
        if (total == 0) return;

        int anchor = Math.max(0, Math.min(anchorIndex, total - 1));
        int current = Math.max(0, Math.min(currentIndex, total - 1));

        int lo = Math.min(anchor, current);
        int hi = Math.max(anchor, current);

        if (lo == dragLastRectMinRow && hi == dragLastRectMaxRow) return;
        dragLastRectMinRow = lo;
        dragLastRectMaxRow = hi;

        List<GalleryMediaItem> items = screenAdapter.getItems();

        Set<String> rangeNow = new HashSet<>();
        for (int i = lo; i <= hi; i++) {
            rangeNow.add(items.get(i).path);
        }
        dragRectanglePaths.clear();
        dragRectanglePaths.addAll(rangeNow);

        Set<String> desired = new HashSet<>(selectionBeforeDrag);
        if (dragSelectDeselectMode) {
            desired.removeAll(rangeNow);
        } else {
            desired.addAll(rangeNow);
        }

        if (desired.equals(selectedItems)) return;

        selectedItems.clear();
        selectedItems.addAll(desired);

        if (screenAdapter != null) {
            screenAdapter.setSelectedItems(selectedItems);
        }

        updateSelectionUI();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void moveToTrash(GalleryMediaItem item) {
        if (item.isTrashed) return;
        File file = new File(item.path);
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
            return;
        }

        File trashedFile = uniqueTrashTarget(file);
        if (!renameFileRobust(file, trashedFile)) {
            Toast.makeText(this, "Could not move to Bin: " + file.getName(),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String oldPath = item.path;
        String newPath = trashedFile.getAbsolutePath();

        // Replace the entry in cachedMedia with a fresh instance carrying
        // the new path so the differ sees a real change.
        for (int i = 0; i < cachedMedia.size(); i++) {
            if (cachedMedia.get(i).path.equals(oldPath)) {
                GalleryMediaItem repl = new GalleryMediaItem(
                        item.id, newPath, trashedFile.getName(), item.albumPath,
                        item.dateModifiedSeconds, item.type);
                repl.isTrashed = true;
                repl.isFavorite = item.isFavorite;
                cachedMedia.set(i, repl);
                break;
            }
        }

        // Make it visible in Bin immediately without waiting for the scan.
        GalleryMediaItem binItem = new GalleryMediaItem(
                item.id, newPath, trashedFile.getName(), item.albumPath,
                item.dateModifiedSeconds, item.type);
        binItem.isTrashed = true;
        binItem.isFavorite = item.isFavorite;
        trashedByPath.put(newPath, binItem);
        addBinPath(newPath);
        trashedIndexDirty = false;

        albumsCacheValid = false;
        refreshCurrentList();
        scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);
    }

    private boolean restoreFromTrash(GalleryMediaItem item) {
        File file = new File(item.path);
        if (!file.exists()) return false;

        File restored = uniqueRestoreTarget(file);
        if (!renameFileRobust(file, restored)) return false;

        String oldPath = item.path;
        String newPath = restored.getAbsolutePath();

        GalleryMediaItem repl = new GalleryMediaItem(
                item.id, newPath, restored.getName(), item.albumPath,
                item.dateModifiedSeconds, item.type);
        repl.isTrashed = false;
        repl.isFavorite = item.isFavorite;

        for (int i = 0; i < cachedMedia.size(); i++) {
            if (cachedMedia.get(i).path.equals(oldPath)) {
                cachedMedia.set(i, repl);
                break;
            }
        }

        trashedByPath.remove(oldPath);
        removeBinPath(oldPath);
        trashedIndexDirty = false;

        if (repository != null) {
            repository.replaceItemPath(oldPath, repl);
        }

        refreshCurrentList();
        scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);
        return true;
    }
    private File uniqueTrashTarget(File src) {
        String parent = src.getParent();
        String clean = cleanFileName(src.getName());
        File target = new File(parent, ".trashed." + clean);
        if (!target.exists()) return target;

        String base = clean, ext = "";
        int dot = clean.lastIndexOf(".");
        if (dot > 0) { base = clean.substring(0, dot); ext = clean.substring(dot); }
        int count = 1;
        while (target.exists()) {
            target = new File(parent, ".trashed." + base + "_" + count + ext);
            count++;
        }
        return target;
    }

    private File uniqueRestoreTarget(File src) {
        String parent = src.getParent();
        String clean = cleanFileName(src.getName());
        File target = new File(parent, clean);
        if (!target.exists()) return target;

        String base = clean, ext = "";
        int dot = clean.lastIndexOf(".");
        if (dot > 0) { base = clean.substring(0, dot); ext = clean.substring(dot); }
        int count = 1;
        while (target.exists()) {
            target = new File(parent, base + "_" + count + ext);
            count++;
        }
        return target;
    }

    private boolean renameFileRobust(File src, File dst) {
        if (src == null || dst == null || !src.exists()) return false;
        try { if (src.renameTo(dst)) return true; } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                java.nio.file.Files.move(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private String cleanFileName(String name) {
        while (name.startsWith(".trashed.")) name = name.substring(9);
        return name;
    }

    private void moveSelectedToTrash() {
        if (selectedItems.isEmpty()) return;
        final List<String> paths = new ArrayList<>(selectedItems);
        final Toast t = Toast.makeText(this, "Moving…", Toast.LENGTH_SHORT);
        t.show();

        executor.execute(() -> {
            int moved = 0, failed = 0;
            List<String> newTrashPaths = new ArrayList<>();
            for (String path : paths) {
                File f = new File(path);
                if (!f.exists()) { failed++; continue; }
                if (f.getName().startsWith(".trashed.")) { moved++; continue; }
                File target = uniqueTrashTarget(f);
                if (renameFileRobust(f, target)) {
                    moved++;
                    newTrashPaths.add(target.getAbsolutePath());
                } else failed++;
            }
            if (!newTrashPaths.isEmpty()) {
                MediaScannerConnection.scanFile(this,
                        newTrashPaths.toArray(new String[0]), null, null);
            }
            final int fm = moved, ff = failed;
            final List<String> movedPaths = newTrashPaths;
            runOnUiThread(() -> {
                t.cancel();
                if (!isActivityAlive()) return;

                // Update cachedMedia for each moved path.
                for (String newPath : movedPaths) {
                    File nf = new File(newPath);
                    File parent = nf.getParentFile();
                    String originalName = cleanFileName(nf.getName());
                    String originalPath = parent == null ? null
                            : new File(parent, originalName).getAbsolutePath();
                    if (originalPath == null) continue;
                    for (int i = 0; i < cachedMedia.size(); i++) {
                        if (cachedMedia.get(i).path.equals(originalPath)) {
                            GalleryMediaItem old = cachedMedia.get(i);
                            GalleryMediaItem repl = new GalleryMediaItem(
                                    old.id, newPath, nf.getName(), old.albumPath,
                                    old.dateModifiedSeconds, old.type);
                            repl.isTrashed = true;
                            repl.isFavorite = old.isFavorite;
                            cachedMedia.set(i, repl);

                            GalleryMediaItem binItem = new GalleryMediaItem(
                                    old.id, newPath, nf.getName(), old.albumPath,
                                    old.dateModifiedSeconds, old.type);
                            binItem.isTrashed = true;
                            binItem.isFavorite = old.isFavorite;
                            trashedByPath.put(newPath, binItem);
                            addBinPath(newPath);
                            break;
                        }
                    }
                }
                trashedIndexDirty = false;
                clearSelection();
                refreshCurrentList();
                scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);  // <-- ADD THIS

            });
        });
    }

    private void restoreSelectedItems() {
        if (selectedItems.isEmpty()) return;
        final List<String> paths = new ArrayList<>(selectedItems);
        final Toast t = Toast.makeText(this, "Restoring…", Toast.LENGTH_SHORT);
        t.show();

        executor.execute(() -> {
            int restored = 0, failed = 0;
            List<String> restoredPaths = new ArrayList<>();
            for (String path : paths) {
                File f = new File(path);
                if (!f.exists()) { failed++; continue; }
                File target = uniqueRestoreTarget(f);
                if (renameFileRobust(f, target)) {
                    restored++;
                    restoredPaths.add(target.getAbsolutePath());
                } else failed++;
            }
            if (!restoredPaths.isEmpty()) {
                MediaScannerConnection.scanFile(this,
                        restoredPaths.toArray(new String[0]), null, null);
            }
            final int r = restored, f = failed;
            final List<String> movedPaths = restoredPaths;
            final List<String> originalPaths = new ArrayList<>(paths);
            runOnUiThread(() -> {
                t.cancel();
                if (!isActivityAlive()) return;

                for (int k = 0; k < movedPaths.size() && k < originalPaths.size(); k++) {
                    String oldPath = originalPaths.get(k);
                    String newPath = movedPaths.get(k);
                    File nf = new File(newPath);

                    GalleryMediaItem repl = null;
                    for (int i = 0; i < cachedMedia.size(); i++) {
                        if (cachedMedia.get(i).path.equals(oldPath)) {
                            GalleryMediaItem old = cachedMedia.get(i);
                            repl = new GalleryMediaItem(
                                    old.id, newPath, nf.getName(), old.albumPath,
                                    old.dateModifiedSeconds, old.type);
                            repl.isTrashed = false;
                            repl.isFavorite = old.isFavorite;
                            cachedMedia.set(i, repl);
                            break;
                        }
                    }
                    trashedByPath.remove(oldPath);
                    removeBinPath(oldPath);
                    if (repository != null && repl != null) {
                        repository.replaceItemPath(oldPath, repl);
                    }
                }
                trashedIndexDirty = false;
                clearSelection();
                refreshCurrentList();
                scheduleMediaRefresh(MEDIA_REFRESH_DEBOUNCE_MS);
                Toast.makeText(this, "Restored " + r
                                + (f > 0 ? " (" + f + " failed)" : ""),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void deletePermanentlySelectedItems() {
        if (selectedItems.isEmpty()) return;
        final List<String> paths = new ArrayList<>(selectedItems);

        new AlertDialog.Builder(this)
                .setTitle("Delete Permanently")
                .setMessage("Delete " + paths.size() + " item(s)? Cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    final Toast t = Toast.makeText(this, "Deleting…", Toast.LENGTH_SHORT);
                    t.show();
                    executor.execute(() -> {
                        int deleted = 0, failed = 0;
                        for (String path : paths) {
                            File f = new File(path);
                            boolean ok = !f.exists() || f.delete();
                            if (ok) deleted++; else failed++;
                        }
                        final int dd = deleted, ff = failed;
                        runOnUiThread(() -> {
                            t.cancel();
                            if (!isActivityAlive()) return;

                            // Remove from cachedMedia immediately.
                            for (String path : paths) {
                                for (int i = cachedMedia.size() - 1; i >= 0; i--) {
                                    if (cachedMedia.get(i).path.equals(path)) {
                                        cachedMedia.remove(i);
                                        break;
                                    }
                                }
                                trashedByPath.remove(path);
                                removeBinPath(path);
                            }
                            trashedIndexDirty = false;
                            clearSelection();
                            refreshCurrentList();
                            Toast.makeText(this, "Deleted " + dd
                                            + (ff > 0 ? ", failed " + ff : ""),
                                    Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file);
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
                        uris.add(FileProvider.getUriForFile(this,
                                getPackageName() + ".fileprovider", file));
                    }
                }
                if (uris.isEmpty()) return;
                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share files"));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String path) {
        String ext = path.substring(path.lastIndexOf(".") + 1).toLowerCase(Locale.US);
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
            if (!file.exists()) return;
            long tsSec = file.lastModified() / 1000L;
            if (tsSec <= 0) tsSec = System.currentTimeMillis() / 1000L;
            StringBuilder info = new StringBuilder();
            info.append("Type: ").append(getFileType(path)).append("\n");
            info.append("Size: ").append(formatFileSize(file.length())).append("\n");
            info.append("Modified: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                    Locale.getDefault()).format(new Date(tsSec * 1000L))).append("\n");
            info.append("Path: ").append(file.getAbsolutePath());
            showInfoDialog(info.toString(), "File Info");
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

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024L * 1024L)
            return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String getFileType(String path) {
        if (path == null) return "Unknown";
        String ext = path.substring(path.lastIndexOf(".") + 1).toLowerCase(Locale.US);
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp|heic|heif")) return "Image";
        if (ext.matches("mp4|avi|mkv|mov|wmv|flv|3gp|webm|m4v")) return "Video";
        return "Unknown";
    }

    private void buildFullscreenAdapter() {
        fullscreenAdapter = new FullscreenAdapter(fullscreenMediaPaths, this);
        fullscreenAdapter.setTapCallback(null);   // handled by overlay listener

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

        overlayTapDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                });

        overlayVideoGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }

                    @Override public boolean onDoubleTap(MotionEvent e) {
                        cancelPendingChromeTap();
                        onFullscreenTap();
                        if (!currentFullscreenPageIsVideo || currentFullscreenVideo == null) return false;
                        float tapX = e.getX();
                        float w = fullscreenOverlay.getWidth();
                        if (w <= 0) return false;
                        if (tapX < w / 3f) skipByMs(-SKIP_BACKWARD_MS);
                        else if (tapX > w * 2f / 3f) skipByMs(SKIP_FORWARD_MS);
                        else toggleOverlayPlayPause();
                        return true;
                    }

                    @Override public boolean onDoubleTapEvent(MotionEvent e) {
                        cancelPendingChromeTap();
                        return true;
                    }

                    @Override public void onLongPress(MotionEvent e) {
                        cancelPendingChromeTap();
                        if (!currentFullscreenPageIsVideo || currentFullscreenVideo == null) return;
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
                        try { if (!currentFullscreenVideo.isPlaying()) return; }
                        catch (Exception ex) { return; }
                        try {
                            android.media.PlaybackParams params =
                                    currentFullscreenVideo.getPlaybackParams();
                            if (params != null) {
                                playbackSpeedBeforeLongPress = params.getSpeed();
                            } else params = new android.media.PlaybackParams();
                            params.setSpeed(LONG_PRESS_SPEED);
                            currentFullscreenVideo.setPlaybackParams(params);
                        } catch (Exception ignored) {}
                    }
                });

        fullscreenAdapter.setPageTypeCallback(new FullscreenAdapter.PageTypeCallback() {
            @Override public void onImageVisible(int position) {
                if (position != fullscreenCurrentPosition) return;
                currentFullscreenPageIsVideo = false;
                isOverlayVideoPlaying = false;
                if (currentFullscreenVideo != null) {
                    try { currentFullscreenVideo.pause(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                }
                videoHandler.removeCallbacks(overlayProgressRunnable);
                if (videoControlContainer != null)
                    videoControlContainer.setVisibility(View.GONE);
                overlayControlsVisible = false;
            }

            @Override public void onVideoVisible(CustomVideoView videoView, int position) {
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
                try { if (restoreMs > 0) videoView.seekTo(restoreMs); } catch (Exception ignored) {}
                try {
                    if (restorePlaying) videoView.start();
                    else videoView.pause();
                } catch (Exception ignored) {}

                syncOverlayPlayPauseIcon();
                updateOverlayTitle();

                if (currentFullscreenVideo != null
                        && currentFullscreenVideo.getSafeDuration() > 0) {
                    updateOverlaySeekBar();
                    startOverlayProgressUpdate();
                } else {
                    retryProgressStart(15);
                }
                if (!userIsSwiping) {
                    if (videoControlContainer != null)
                        videoControlContainer.setVisibility(View.VISIBLE);
                    setFullscreenChromeVisible(true);
                    overlayControlsVisible = true;
                }
            }
        });

        fullscreenViewPager.setAdapter(fullscreenAdapter);
        fullscreenViewPager.setOffscreenPageLimit(1);

        fullscreenViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageScrollStateChanged(int state) {
                userIsSwiping = (state != ViewPager2.SCROLL_STATE_IDLE);
            }
            @Override public void onPageSelected(int position) {
                cancelPendingChromeTap();
                if (currentFullscreenVideo != null
                        && currentFullscreenVideoPosition != position) {
                    try { currentFullscreenVideo.pause(); } catch (Exception ignored) {}
                    currentFullscreenVideo = null;
                    currentFullscreenVideoPosition = -1;
                }
                videoHandler.removeCallbacks(overlayProgressRunnable);
                overlayPendingSeekMs = -1;
                fullscreenCurrentPosition = position;

                String path = (position >= 0 && position < fullscreenMediaPaths.size())
                        ? fullscreenMediaPaths.get(position) : null;
                boolean targetIsVideo = isVideoPath(path);
                if (targetIsVideo) {
                    currentFullscreenPageIsVideo = true;
                } else {
                    currentFullscreenPageIsVideo = false;
                    isOverlayVideoPlaying = false;
                    if (videoControlContainer != null)
                        videoControlContainer.setVisibility(View.GONE);
                    overlayControlsVisible = false;
                }
                updateFullscreenInfo(position);
                if (targetIsVideo) findAndStartVideoForPosition(position);
            }
        });
    }

    private void wireFullscreenControls() {
        if (fsBtnBin != null) fsBtnBin.setOnClickListener(v -> {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(pos);
                for (GalleryMediaItem item : cachedMedia) {
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
            if (pos >= 0 && pos < fullscreenMediaPaths.size())
                showFileInfoDialog(fullscreenMediaPaths.get(pos));
        });

        if (fsBtnFavorite != null) fsBtnFavorite.setOnClickListener(v -> {
            int pos = fullscreenCurrentPosition;
            if (pos >= 0 && pos < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(pos);
                boolean nowFav = toggleFavoriteForPath(path);
                updateFullscreenFavoriteIcon(path);
                Toast.makeText(this,
                        nowFav ? "Added to Favorites" : "Removed from Favorites",
                        Toast.LENGTH_SHORT).show();
            }
        });

        setupOverlayVideoControls();
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

        if (btnFavoriteOverlay != null) btnFavoriteOverlay.setOnClickListener(v -> {
            if (fullscreenCurrentPosition >= 0
                    && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(fullscreenCurrentPosition);
                boolean nowFav = toggleFavoriteForPath(path);
                updateFullscreenFavoriteIcon(path);
                Toast.makeText(this, nowFav ? "Added" : "Removed",
                        Toast.LENGTH_SHORT).show();
            }
        });
        if (btnInfoOverlay != null) btnInfoOverlay.setOnClickListener(v -> {
            if (fullscreenCurrentPosition >= 0
                    && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
                showFileInfoDialog(fullscreenMediaPaths.get(fullscreenCurrentPosition));
            }
        });
        if (btnDeleteOverlay != null) btnDeleteOverlay.setOnClickListener(v -> {
            if (fullscreenCurrentPosition >= 0
                    && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
                String path = fullscreenMediaPaths.get(fullscreenCurrentPosition);
                for (GalleryMediaItem item : cachedMedia) {
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
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser || currentFullscreenVideo == null) return;
                    int dur = currentFullscreenVideo.getSafeDuration();
                    if (dur <= 0) return;
                    int targetMs = (int) ((progress / 1000.0) * dur);
                    overlayPendingSeekMs = targetMs;
                    boolean seeked = currentFullscreenVideo.seekToSafe(targetMs);
                    if (videoTimeCurrent != null)
                        videoTimeCurrent.setText(formatTime(targetMs));
                    long clearDelay = seeked ? 350L : 60L;
                    videoHandler.postDelayed(() -> overlayPendingSeekMs = -1, clearDelay);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {
                    videoHandler.removeCallbacks(overlayHideControlsRunnable);
                }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    showAllControlsWithTimeout();
                }
            });
        }
    }

    private void openFullscreenViewer(String path) {
        if (fullscreenViewPager != null) fullscreenViewPager.setUserInputEnabled(true);

        fullscreenMediaPaths.clear();
        int currentIndex = 0;
        List<GalleryMediaItem> current = screenAdapter.getItems();
        for (int i = 0; i < current.size(); i++) {
            GalleryMediaItem item = current.get(i);
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
        cancelPendingChromeTap();
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

        if (videoControlContainer != null)
            videoControlContainer.setVisibility(View.GONE);
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

    private void updateOverlayTitle() {
        if (videoTitleOverlay == null) return;
        if (fullscreenCurrentPosition >= 0
                && fullscreenCurrentPosition < fullscreenMediaPaths.size()) {
            File f = new File(fullscreenMediaPaths.get(fullscreenCurrentPosition));
            videoTitleOverlay.setText(f.getName());
        }
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
                        vv.setFit(landscape ? CustomVideoView.Fit.LARGER
                                : CustomVideoView.Fit.SMALLER);
                        vv.setOnCompletionListener(mp -> loopWithRetry(mp, 0));

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
                            if (overlayVideoGestureDetector != null)
                                overlayVideoGestureDetector.onTouchEvent(event);
                            if (volumeBrightnessHandler != null)
                                volumeBrightnessHandler.onTouch(v, event);
                            if (event.getActionMasked() == MotionEvent.ACTION_UP
                                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                                resetPlaybackSpeed();
                            }
                            return true;
                        });

                        restoreSavedPlaybackState(vv, targetPosition);
                        syncOverlayPlayPauseIcon();
                        updateOverlayTitle();

                        if (currentFullscreenVideo != null
                                && currentFullscreenVideo.getSafeDuration() > 0) {
                            updateOverlaySeekBar();
                            startOverlayProgressUpdate();
                        } else {
                            retryProgressStart(15);
                        }
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

    private void loopWithRetry(MediaPlayer mp, int attempt) {
        if (mp == null || currentFullscreenVideo == null) return;
        boolean seeked = false, started = false;
        try { mp.seekTo(0); seeked = true; } catch (Exception ignored) {}
        if (seeked) {
            try { mp.start(); started = true; } catch (Exception ignored) {}
        }
        if (started) {
            isOverlayVideoPlaying = true;
            syncOverlayPlayPauseIcon();
            startOverlayProgressUpdate();
            return;
        }
        if (attempt >= 15) {
            isOverlayVideoPlaying = false;
            syncOverlayPlayPauseIcon();
            return;
        }
        long delay = 100L * (attempt + 1);
        videoHandler.postDelayed(() -> loopWithRetry(mp, attempt + 1), delay);
    }

    private void retryProgressStart(int remaining) {
        if (remaining <= 0) { startOverlayProgressUpdate(); return; }
        videoHandler.postDelayed(() -> {
            if (currentFullscreenVideo == null) return;
            if (currentFullscreenVideo.getSafeDuration() > 0) {
                updateOverlaySeekBar();
                startOverlayProgressUpdate();
            } else retryProgressStart(remaining - 1);
        }, 200L);
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
        if (restoreMs > 0) {
            vv.seekToSafe(restoreMs);
            overlayPendingSeekMs = restoreMs;
            videoHandler.postDelayed(() -> overlayPendingSeekMs = -1, 400L);
        }
        try {
            if (restorePlaying) { vv.start(); isOverlayVideoPlaying = true; }
            else { vv.pause(); isOverlayVideoPlaying = false; }
        } catch (Exception ignored) {}
    }

    private void syncOverlayPlayPauseIcon() {
        boolean playing = false;
        if (currentFullscreenVideo != null) {
            try { playing = currentFullscreenVideo.isPlaying(); } catch (Exception ignored) {}
        }
        isOverlayVideoPlaying = playing;
        if (btnCenterPlayPause != null) {
            btnCenterPlayPause.setImageResource(playing
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play);
        }
    }

    private void skipByMs(int deltaMs) {
        if (currentFullscreenVideo == null) return;
        int dur = currentFullscreenVideo.getSafeDuration();
        if (dur <= 0) return;
        int current = getEffectivePositionMs();
        int target = Math.max(0, Math.min(current + deltaMs, dur));
        overlayPendingSeekMs = target;
        boolean seeked = currentFullscreenVideo.seekToSafe(target);
        if (videoTimeCurrent != null) videoTimeCurrent.setText(formatTime(target));
        if (videoSeekBar != null) {
            int progress = (int) ((target / (float) dur) * 1000);
            if (videoSeekBar.getProgress() != progress) videoSeekBar.setProgress(progress);
        }
        long clearDelay = seeked ? 350L : 60L;
        videoHandler.postDelayed(() -> overlayPendingSeekMs = -1, clearDelay);
    }

    private int getEffectivePositionMs() {
        if (currentFullscreenVideo == null) return 0;
        if (overlayPendingSeekMs >= 0) return overlayPendingSeekMs;
        int p = currentFullscreenVideo.getSafePosition();
        return p >= 0 ? p : 0;
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

    private void toggleOverlayPlayPause() {
        if (currentFullscreenVideo == null) return;
        overlayPendingSeekMs = -1;
        boolean playing = false;
        try { playing = currentFullscreenVideo.isPlaying(); } catch (Exception ignored) {}

        if (playing) {
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
            @Override public void run() {
                if (currentFullscreenVideo == null) {
                    isOverlayVideoPlaying = false;
                    return;
                }
                updateOverlaySeekBar();
                long delay = isOverlayVideoPlaying ? 400L : 1200L;
                if (isActivityAlive() && currentFullscreenVideo != null) {
                    videoHandler.postDelayed(this, delay);
                }
            }
        };
        videoHandler.post(overlayProgressRunnable);
    }

    private void updateOverlaySeekBar() {
        if (currentFullscreenVideo == null) return;
        int cur = currentFullscreenVideo.getSafePosition();
        int dur = currentFullscreenVideo.getSafeDuration();
        if (dur <= 0) return;
        if (cur < 0) cur = 0;
        if (cur > dur) cur = dur;
        if (overlayPendingSeekMs >= 0 && overlayPendingSeekMs <= dur) cur = overlayPendingSeekMs;

        if (videoTimeCurrent != null) videoTimeCurrent.setText(formatTime(cur));
        if (videoTimeTotal != null) videoTimeTotal.setText(formatTime(dur));
        if (videoSeekBar != null) {
            int progress = (int) ((cur / (float) dur) * 1000);
            if (videoSeekBar.getProgress() != progress) videoSeekBar.setProgress(progress);
        }
    }

    private void onFullscreenTap() {
        cancelPendingChromeTap();
        boolean newVisible = !fullscreenChromeVisible;
        if (newVisible) showAllControlsWithTimeout();
        else hideAllControls();
    }

    private void cancelPendingChromeTap() {
        if (pendingChromeTapRunnable != null) {
            videoHandler.removeCallbacks(pendingChromeTapRunnable);
            pendingChromeTapRunnable = null;
        }
        chromeDoubleTapArmed = false;
    }

    private void showAllControlsWithTimeout() {
        setFullscreenChromeVisible(true);
        if (currentFullscreenPageIsVideo && videoControlContainer != null) {
            videoControlContainer.setVisibility(View.VISIBLE);
            overlayControlsVisible = true;
        } else if (videoControlContainer != null) {
            videoControlContainer.setVisibility(View.GONE);
            overlayControlsVisible = false;
        }
        videoHandler.removeCallbacks(overlayHideControlsRunnable);
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
                if (currentFullscreenVideo != null) currentFullscreenVideo.requestLayout();
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

    private boolean isVideoPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    private String formatTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void showMediaHud(boolean isVolume, int percent) {
        if (mediaHudVolume == null || mediaHudBrightness == null) return;
        TextView target = isVolume ? mediaHudVolume : mediaHudBrightness;
        TextView other = isVolume ? mediaHudBrightness : mediaHudVolume;
        other.setVisibility(View.GONE);
        target.setText(percent + "%");
        target.setVisibility(View.VISIBLE);
        target.bringToFront();
        videoHandler.removeCallbacks(mediaHudHideRunnable);
        mediaHudHideRunnable = () -> {
            mediaHudVolume.setVisibility(View.GONE);
            mediaHudBrightness.setVisibility(View.GONE);
        };
        videoHandler.postDelayed(mediaHudHideRunnable, MEDIA_HUD_VISIBLE_MS);
    }

    private void hideMediaHudImmediately() {
        if (mediaHudVolume != null) mediaHudVolume.setVisibility(View.GONE);
        if (mediaHudBrightness != null) mediaHudBrightness.setVisibility(View.GONE);
        if (videoHandler != null) videoHandler.removeCallbacks(mediaHudHideRunnable);
    }

    private void wireVolumeBrightness() {
        if (fullscreenOverlay == null) return;
        final AudioManager audioManager =
                (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        final int maxVolume = audioManager != null
                ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 15;

        final float STEP_PX_VOLUME = 45f;
        final float STEP_PX_BRIGHTNESS = 45f;
        final float BRIGHTNESS_STEP_VALUE = 1.0f / 20f;
        final int DEAD_ZONE_PX = 12;

        final float[] startY = {0f};
        final float[] startX = {0f};
        final float[] lastY = {0f};
        final boolean[] isVolumeSide = {false};
        final boolean[] committed = {false};
        final boolean[] pagerLocked = {false};
        final float[] accumulator = {0f};
        final int[] appliedVolume = {-1};
        final float[] appliedBrightness = {-1f};
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
                    startY[0] = event.getY();
                    startX[0] = event.getX();
                    lastY[0] = event.getY();
                    committed[0] = false;
                    accumulator[0] = 0f;
                    suppressNextUpTap[0] = false;

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
                    float cy = event.getY(), cx = event.getX();
                    float dyTotal = Math.abs(cy - startY[0]);
                    float dxTotal = Math.abs(cx - startX[0]);

                    if (!committed[0]) {
                        if (dyTotal < DEAD_ZONE_PX && dxTotal < DEAD_ZONE_PX) return true;
                        if (dyTotal >= dxTotal) {
                            committed[0] = true;
                            lockPager.run();
                            lastY[0] = cy;
                            accumulator[0] = 0f;
                        } else {
                            committed[0] = false;
                            unlockPager.run();
                            return false;
                        }
                    }

                    float dyPixels = lastY[0] - cy;
                    lastY[0] = cy;
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
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                                    appliedVolume[0], 0);
                        } catch (Exception ignored) {}
                        if (changed) {
                            int percent = maxVolume > 0
                                    ? (int) Math.round((appliedVolume[0] * 100.0) / maxVolume) : 0;
                            showMediaHud(true, Math.max(0, Math.min(100, percent)));
                        }
                    } else {
                        int stepAdvances = 0;
                        while (accumulator[0] >= STEP_PX_BRIGHTNESS) {
                            accumulator[0] -= STEP_PX_BRIGHTNESS; stepAdvances++;
                        }
                        while (accumulator[0] <= -STEP_PX_BRIGHTNESS) {
                            accumulator[0] += STEP_PX_BRIGHTNESS; stepAdvances--;
                        }
                        if (stepAdvances != 0) {
                            appliedBrightness[0] = Math.min(1f, Math.max(0.02f,
                                    appliedBrightness[0] + stepAdvances * BRIGHTNESS_STEP_VALUE));
                            try {
                                WindowManager.LayoutParams lp = getWindow().getAttributes();
                                lp.screenBrightness = appliedBrightness[0];
                                getWindow().setAttributes(lp);
                            } catch (Exception ignored) {}
                            int percent = (int) Math.round(appliedBrightness[0] * 100);
                            showMediaHud(false, Math.max(0, Math.min(100, percent)));
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
                        if (suppressNextUpTap[0]) suppressNextUpTap[0] = false;
                        else onFullscreenTap();
                    } else suppressNextUpTap[0] = false;

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

    private void scheduleMediaRefresh(long delayMs) {
        if (!isActivityAlive()) return;
        if (pendingMediaRefresh != null) mediaRefreshHandler.removeCallbacks(pendingMediaRefresh);
        pendingMediaRefresh = () -> {
            albumsCacheValid = false;
            trashedIndexDirty = true;
            if (repository != null) {
                repository.refreshFirstPage();
            }
            pendingMediaRefresh = null;
        };
        mediaRefreshHandler.postDelayed(pendingMediaRefresh, delayMs);
    }

    @Override
    public void onBackPressed() {
        if (fullscreenOverlay != null && fullscreenOverlay.getVisibility() == View.VISIBLE) {
            closeFullscreenViewer();
            return;
        }
        if (selectionMode) {
            clearSelection();
            return;
        }
        if (currentFilter == FilterMode.BIN) {
            exitBin();
            return;
        }
        if (albumsController != null && albumsController.onBack()) {
            updateTopNavBar();
            updateBarsVisibility();
            return;
        }
        if (whatsappOnly) {
            whatsappOnly = false;
            whatsappPath = null;
            albumsController.showAlbums();
            updateTopNavBar();
            updateBarsVisibility();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null) videoView.stopPlayback();
        if (currentFullscreenVideo != null) {
            try { currentFullscreenVideo.stopPlayback(); } catch (Exception ignored) {}
            currentFullscreenVideo = null;
        }
        videoHandler.removeCallbacksAndMessages(null);
        mediaRefreshHandler.removeCallbacksAndMessages(null);
        hideMediaHudImmediately();
        stopAutoScroll();
        pendingMediaRefresh = null;
        if (repository != null) {
            repository.removeListener(repositoryListener);
            repository.shutdown();
            repository = null;
        }
        executor.shutdown();
    }
}
