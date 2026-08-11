package ohi.andre.consolelauncher;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import android.graphics.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FullscreenViewerActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private FullscreenAdapter adapter;
    private List<String> mediaPaths = new ArrayList<>();
    private int currentPosition = 0;
    private VideoView currentVideoView = null;

    private View videoControlContainer;
    private ImageButton btnCenterPlayPause, btnSkipForward, btnSkipBackward;
    private ImageButton btnFavorite, btnInfo, btnDelete, btnRotate, btnScale;
    private TextView videoTimeCurrent, videoTimeTotal, videoTitle;
    private SeekBar videoSeekBar;
    private Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable updateProgressRunnable;
    private Runnable hideControlsRunnable;
    private boolean controlsVisible = true;
    private static final int CONTROLS_TIMEOUT = 3000;
    private static final int SKIP_FORWARD_MS = 15000;
    private static final int SKIP_BACKWARD_MS = 5000;
    private boolean isVideoPlaying = false;
    private boolean isLandscape = false;
    private boolean isVideoPrepared = false;
    private View decorView;
    private int currentSystemUiVisibility;
    private RelativeLayout rootLayout;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_viewer);

        // Initialize decor view for system UI control
        decorView = getWindow().getDecorView();

        // Set fullscreen immersive mode
        setImmersiveFullscreen();

        // Handle window flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        // Setup system UI visibility change listener
        setupSystemUiVisibilityListener();

        viewPager = findViewById(R.id.fullscreenViewPager);
        videoControlContainer = findViewById(R.id.videoControlContainer);
        btnCenterPlayPause = findViewById(R.id.btnCenterPlayPause);
        btnSkipForward = findViewById(R.id.btnSkipForward);
        btnSkipBackward = findViewById(R.id.btnSkipBackward);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnInfo = findViewById(R.id.btnInfo);
        btnDelete = findViewById(R.id.btnDelete);
        btnRotate = findViewById(R.id.btnRotate);
        btnScale = findViewById(R.id.btnScale);
        videoTimeCurrent = findViewById(R.id.videoTimeCurrent);
        videoTimeTotal = findViewById(R.id.videoTimeTotal);
        videoTitle = findViewById(R.id.videoTitle);
        videoSeekBar = findViewById(R.id.videoSeekBar);

        // Get root layout to ensure full screen
        rootLayout = findViewById(R.id.videoControlsOverlay);
        if (rootLayout != null) {
            rootLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        mediaPaths = getIntent().getStringArrayListExtra("media_paths");
        currentPosition = getIntent().getIntExtra("current_position", 0);

        if (mediaPaths == null || mediaPaths.isEmpty()) {
            finish();
            return;
        }

        adapter = new FullscreenAdapter(mediaPaths, this);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);
        viewPager.setOffscreenPageLimit(ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT);
        viewPager.setUserInputEnabled(true);

        // Make ViewPager fill the entire screen including behind system bars
        viewPager.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        // Set ViewPager to fill entire screen
        ViewGroup.LayoutParams vpParams = viewPager.getLayoutParams();
        vpParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        vpParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        viewPager.setLayoutParams(vpParams);

        setupVideoControls();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                // Stop current video before switching
                stopCurrentVideo();
                currentPosition = position;
                currentVideoView = null;
                isVideoPrepared = false;
                isVideoPlaying = false;
                updateTitle();
                showControls();
                // Reset play button
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
                videoTimeCurrent.setText("00:00");
                videoTimeTotal.setText("00:00");
                videoSeekBar.setProgress(0);
                updateScaleButtonIcon();
                // Ensure immersive mode is maintained
                setImmersiveFullscreen();
            }
        });

        updateTitle();
        showControls();
        updateScaleButtonIcon();
    }

    private void setImmersiveFullscreen() {
        if (decorView == null) return;

        // Use the most immersive mode available for the SDK version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }

            decorView.setSystemUiVisibility(flags);
            currentSystemUiVisibility = flags;
        } else {
            // For older devices
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }
    }

    private void setupSystemUiVisibilityListener() {
        if (decorView == null) return;

        decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                // System bars became visible, hide them again
                videoHandler.postDelayed(this::setImmersiveFullscreen, 100);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Re-apply immersive mode when window gains focus
            setImmersiveFullscreen();
        }
    }

    private void stopCurrentVideo() {
        if (currentVideoView != null) {
            try {
                currentVideoView.stopPlayback();
                currentVideoView = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        videoHandler.removeCallbacks(updateProgressRunnable);
        isVideoPlaying = false;
    }

    private void setupVideoControls() {
        btnCenterPlayPause.setOnClickListener(v -> togglePlayPause());

        btnSkipForward.setOnClickListener(v -> {
            if (currentVideoView != null) {
                int current = currentVideoView.getCurrentPosition();
                int duration = currentVideoView.getDuration();
                currentVideoView.seekTo(Math.min(current + SKIP_FORWARD_MS, duration));
                updateSeekBar();
                // Show controls briefly when seeking
                showControls();
            }
        });

        btnSkipBackward.setOnClickListener(v -> {
            if (currentVideoView != null) {
                int current = currentVideoView.getCurrentPosition();
                currentVideoView.seekTo(Math.max(current - SKIP_BACKWARD_MS, 0));
                updateSeekBar();
                // Show controls briefly when seeking
                showControls();
            }
        });

        btnFavorite.setOnClickListener(v -> {
            String path = mediaPaths.get(currentPosition);
            toggleFavorite(path);
        });

        btnInfo.setOnClickListener(v -> {
            String path = mediaPaths.get(currentPosition);
            showFileInfoDialog(path);
        });

        btnDelete.setOnClickListener(v -> {
            String path = mediaPaths.get(currentPosition);
            moveToTrash(path);
        });

        btnRotate.setOnClickListener(v -> toggleOrientation());

        btnScale.setOnClickListener(v -> cycleScaleMode());

        videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && currentVideoView != null) {
                    int duration = currentVideoView.getDuration();
                    int newPosition = (int) ((progress / 100.0) * duration);
                    currentVideoView.seekTo(newPosition);
                    // Show controls during seeking
                    showControls();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Show controls when user starts interacting
                showControls();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Keep controls visible briefly after seeking
                videoHandler.removeCallbacks(hideControlsRunnable);
                hideControlsRunnable = () -> {
                    if (isVideoPlaying) {
                        hideControls();
                    }
                };
                videoHandler.postDelayed(hideControlsRunnable, 2000);
            }
        });

        // Touch overlay to toggle controls
        findViewById(R.id.videoControlsOverlay).setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                toggleControlsVisibility();
                return true;
            }
            return false;
        });
    }

    private void cycleScaleMode() {
        if (adapter == null) return;

        int currentMode = adapter.getScaleMode();
        int newMode = (currentMode + 1) % 5;

        adapter.setScaleMode(newMode);
        updateScaleButtonIcon();

        // Update current video if it exists
        if (currentVideoView != null) {
            // Force re-layout of current video view
            currentVideoView.requestLayout();
        }
    }

    private void updateScaleButtonIcon() {
        if (btnScale == null || adapter == null) return;

        int mode = adapter.getScaleMode();

        switch (mode) {
            case FullscreenAdapter.SCALE_FILL:
                btnScale.setImageResource(R.drawable.ic_scale_fill);
                btnScale.setColorFilter(Color.parseColor("#FFD700")); // Gold - default fill mode
                break;
            case FullscreenAdapter.SCALE_FIT:
                btnScale.setImageResource(R.drawable.ic_fit_screen);
                btnScale.setColorFilter(Color.WHITE);
                break;
            case FullscreenAdapter.SCALE_CENTER:
                btnScale.setImageResource(R.drawable.ic_fit_screen);
                btnScale.setColorFilter(Color.parseColor("#00FF88"));
                break;
            case FullscreenAdapter.SCALE_FIT_WIDTH:
                btnScale.setImageResource(R.drawable.ic_fit_screen);
                btnScale.setColorFilter(Color.parseColor("#FF6B6B"));
                break;
            case FullscreenAdapter.SCALE_FIT_HEIGHT:
                btnScale.setImageResource(R.drawable.ic_fit_screen);
                btnScale.setColorFilter(Color.parseColor("#4ECDC4"));
                break;
            default:
                btnScale.setImageResource(R.drawable.ic_scale_fill);
                btnScale.setColorFilter(Color.parseColor("#FFD700"));
                break;
        }
    }

    public void setCurrentVideoView(VideoView videoView) {
        if (!isActivityAlive()) return;

        if (currentVideoView != null && currentVideoView != videoView) {
            try {
                currentVideoView.stopPlayback();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        this.currentVideoView = videoView;
        this.isVideoPrepared = true;

        if (videoView != null) {
            try {
                videoView.start();
                isVideoPlaying = true;
                btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                startProgressUpdate();
                // Hide controls after video starts
                videoHandler.postDelayed(() -> {
                    if (isVideoPlaying) {
                        hideControls();
                    }
                }, 1500);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void togglePlayPause() {
        if (!isActivityAlive()) return;
        if (currentVideoView == null) return;

        if (isVideoPlaying) {
            currentVideoView.pause();
            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_play);
            isVideoPlaying = false;
            videoHandler.removeCallbacks(updateProgressRunnable);
            // Keep controls visible when paused
            showControls();
        } else {
            currentVideoView.start();
            btnCenterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            isVideoPlaying = true;
            startProgressUpdate();
            // Hide controls after video resumes
            videoHandler.removeCallbacks(hideControlsRunnable);
            hideControlsRunnable = () -> {
                if (isVideoPlaying) {
                    hideControls();
                }
            };
            videoHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
        }
    }

    private void startProgressUpdate() {
        videoHandler.removeCallbacks(updateProgressRunnable);
        updateProgressRunnable = new Runnable() {
            @Override
            public void run() {
                updateSeekBar();
                if (isVideoPlaying) {
                    videoHandler.postDelayed(this, 500);
                }
            }
        };
        videoHandler.post(updateProgressRunnable);
    }

    private void updateSeekBar() {
        if (currentVideoView == null) return;

        try {
            int current = currentVideoView.getCurrentPosition();
            int duration = currentVideoView.getDuration();

            if (duration > 0) {
                videoTimeCurrent.setText(formatTime(current));
                videoTimeTotal.setText(formatTime(duration));
                videoSeekBar.setProgress((int) ((current / (float) duration) * 100));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void toggleFavorite(String path) {
        File file = new File(path);
        btnFavorite.setColorFilter(Color.parseColor("#FFD700"));
        // Toggle favorite state
        if (btnFavorite.getColorFilter() == null) {
            btnFavorite.setColorFilter(Color.parseColor("#FFD700"));
        } else {
            btnFavorite.clearColorFilter();
        }
    }

    private void showFileInfoDialog(String path) {
        File file = new File(path);
        if (!file.exists()) return;

        StringBuilder info = new StringBuilder();
        info.append("📄 File: ").append(file.getName()).append("\n");
        info.append("📏 Size: ").append(formatFileSize(file.length())).append("\n");
        info.append("📅 Modified: ").append(new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(file.lastModified()))).append("\n");
        info.append("🔤 Type: ").append(getFileType(path)).append("\n");
        info.append("📍 Path: ").append(file.getAbsolutePath());

        showInfoDialog(info.toString(), "📄 File Info");
    }

    private void showInfoDialog(String info, String title) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_file_info, null);

        TextView infoTitle = view.findViewById(R.id.infoTitle);
        TextView infoContent = view.findViewById(R.id.infoContent);
        android.widget.Button infoClose = view.findViewById(R.id.infoClose);

        if (infoTitle != null) infoTitle.setText(title);
        if (infoContent != null) infoContent.setText(info);

        builder.setView(view);
        android.app.AlertDialog dialog = builder.create();

        if (infoClose != null) {
            infoClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.85),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
        );
        dialog.show();
    }

    private void moveToTrash(String path) {
        File file = new File(path);
        if (!file.exists()) return;

        String parent = file.getParent();
        String name = file.getName();
        String cleanName = name.replaceAll("^\\.trashed\\.", "");
        String trashedName = ".trashed." + cleanName;
        File trashedFile = new File(parent, trashedName);

        if (file.renameTo(trashedFile)) {
            stopCurrentVideo();
            mediaPaths.remove(currentPosition);
            adapter.notifyDataSetChanged();
            if (mediaPaths.isEmpty()) {
                finish();
            } else if (currentPosition >= mediaPaths.size()) {
                currentPosition = mediaPaths.size() - 1;
                viewPager.setCurrentItem(currentPosition, false);
            }
        }
    }

    private void toggleOrientation() {
        if (isLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isLandscape = false;
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            isLandscape = true;
        }
        // Re-apply immersive mode after orientation change
        videoHandler.postDelayed(this::setImmersiveFullscreen, 300);
    }

    private String getFileType(String path) {
        String ext = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
        if (ext.matches("jpg|jpeg|png|gif|bmp|webp|heic|heif")) return "Image";
        if (ext.matches("mp4|avi|mkv|mov|wmv|flv|3gp|webm|m4v")) return "Video";
        return "Unknown";
    }

    private String formatTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private void toggleControlsVisibility() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    private void showControls() {
        if (!isActivityAlive()) return;

        controlsVisible = true;
        if (videoControlContainer != null) {
            videoControlContainer.setVisibility(View.VISIBLE);
        }
        // Ensure system bars are hidden when showing controls
        setImmersiveFullscreen();

        videoHandler.removeCallbacks(hideControlsRunnable);
        hideControlsRunnable = () -> {
            if (isVideoPlaying || currentVideoView != null) {
                hideControls();
            }
        };
        videoHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT);
    }

    private void hideControls() {
        controlsVisible = false;
        if (videoControlContainer != null) {
            videoControlContainer.setVisibility(View.GONE);
        }
        videoHandler.removeCallbacks(hideControlsRunnable);
        // Ensure full immersive mode when controls are hidden
        setImmersiveFullscreen();
    }

    private void updateTitle() {
        if (currentPosition < mediaPaths.size()) {
            File file = new File(mediaPaths.get(currentPosition));
            if (videoTitle != null) {
                videoTitle.setText(file.getName());
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            isLandscape = false;
            videoHandler.postDelayed(() -> {
                stopCurrentVideo();
                // Restore normal system UI before finishing
                if (decorView != null) {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }
                super.onBackPressed();
                finish();
            }, 300);
        } else {
            stopCurrentVideo();
            // Restore normal system UI before finishing
            if (decorView != null) {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            super.onBackPressed();
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (currentVideoView != null && currentVideoView.isPlaying()) {
            currentVideoView.pause();
        }
        videoHandler.removeCallbacks(updateProgressRunnable);
        videoHandler.removeCallbacks(hideControlsRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply immersive mode when resuming
        setImmersiveFullscreen();

        if (currentVideoView != null && !currentVideoView.isPlaying() && isVideoPlaying) {
            currentVideoView.start();
            startProgressUpdate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCurrentVideo();
        videoHandler.removeCallbacks(updateProgressRunnable);
        videoHandler.removeCallbacks(hideControlsRunnable);
        if (decorView != null) {
            decorView.setOnSystemUiVisibilityChangeListener(null);
        }
    }

    boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }

    public int getCurrentPosition() {
        return currentPosition;
    }
}