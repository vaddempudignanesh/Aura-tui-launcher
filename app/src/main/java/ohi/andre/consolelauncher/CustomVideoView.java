package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * SurfaceView-based video player.
 *
 * Aspect-ratio handling is ported from VLC's vout_display_PlacePicture():
 *   - Video is stored with a Sample Aspect Ratio (SAR), which is separate from
 *     its pixel dimensions. e.g. a 720x480 NTSC video has SAR 32:27, giving
 *     a 16:9 Display Aspect Ratio (DAR), even though its pixels aren't square.
 *   - The view computes the "placed rectangle" that the video should occupy
 *     inside the available bounds, honoring SAR and the requested fit mode.
 *   - Fitting modes match VLC's:
 *       FIT_SMALLER  — letterbox (fit the whole video inside the view)
 *       FIT_LARGER   — crop (fill the view, cropping edges)
 *       FIT_WIDTH    — match view width (may overflow top/bottom)
 *       FIT_HEIGHT   — match view height (may overflow left/right)
 *       FIT_NONE     — use video pixel size as-is (1:1)
 */
public class CustomVideoView extends SurfaceView implements SurfaceHolder.Callback {

    public static final String LOG_TAG = "gallery-tui";
    private static final String SRC = "[CustomVideoView]";

    private final String id = Integer.toHexString(System.identityHashCode(this));

    // ═════════════════════════════════════════════════════════════
    //  Fitting modes (ported from VLC's vlc_video_fitting)
    // ═════════════════════════════════════════════════════════════
    public enum Fit {
        NONE,       // 1:1 pixel — no scaling
        SMALLER,    // letterbox — entire video visible (default for portrait)
        LARGER,     // crop to fill view
        WIDTH,      // match width, may overflow vertically
        HEIGHT;     // match height, may overflow horizontally
    }

    public interface OnTapListener { void onTap(); }
    private OnTapListener tapListener;
    public void setOnTapListener(OnTapListener l) { this.tapListener = l; }

    private MediaPlayer mediaPlayer;
    private String videoPath;

    private boolean prepared = false;
    private boolean playWhenReady = false;
    private boolean surfaceReady = false;

    private int mediaGeneration = 0;

    // ═════════════════════════════════════════════════════════════
    //  Video geometry (source side)
    // ═════════════════════════════════════════════════════════════
    private int videoWidth = 0;        // i_width (coded width)
    private int videoHeight = 0;       // i_height
    private int videoVisibleWidth = 0; // i_visible_width  (may be < coded)
    private int videoVisibleHeight = 0;// i_visible_height
    private int sarNum = 1;            // i_sar_num
    private int sarDen = 1;            // i_sar_den

    // Fitting mode — default SMALLER (letterbox), switch to LARGER in landscape
    private Fit fit = Fit.SMALLER;

    // Pan/pinch
    private float panX = 0f, panY = 0f, currentScale = 1f;
    private final android.graphics.PointF lastTouch = new android.graphics.PointF();
    private final android.graphics.PointF startTouch = new android.graphics.PointF();
    private android.view.ScaleGestureDetector scaleDetector;
    private boolean isDragging = false;
    private boolean isPinching = false;

    private MediaPlayer.OnPreparedListener preparedListener;
    private MediaPlayer.OnErrorListener errorListener;
    private MediaPlayer.OnCompletionListener completionListener;

    public CustomVideoView(@NonNull Context context) { super(context); init("ctor1"); }
    public CustomVideoView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); init("ctor2"); }
    public CustomVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init("ctor3");
    }

    private void log(String msg) { Log.d(LOG_TAG, SRC + " [" + id + "] " + msg); }
    private void logWarn(String msg) { Log.w(LOG_TAG, SRC + " [" + id + "] " + msg); }
    private void logError(String msg, Throwable t) { Log.e(LOG_TAG, SRC + " [" + id + "] " + msg, t); }

    private void init(String source) {
        log("init from " + source);
        setZOrderOnTop(false);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);

        scaleDetector = new android.view.ScaleGestureDetector(getContext(),
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                        isPinching = true; return true;
                    }
                    @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                        float factor = d.getScaleFactor();
                        float ns = currentScale * factor;
                        if (ns < 0.5f) ns = 0.5f;
                        if (ns > 5f) ns = 5f;
                        currentScale = ns;
                        setScaleX(currentScale);
                        setScaleY(currentScale);
                        return true;
                    }
                    @Override public void onScaleEnd(android.view.ScaleGestureDetector d) {
                        isPinching = false;
                    }
                });
    }

    // ═════════════════════════════════════════════════════════════
    //  Fitting mode
    // ═════════════════════════════════════════════════════════════

    public void setFit(Fit mode) {
        if (fit != mode) {
            fit = mode;
            requestLayout();
            log("fit mode → " + mode);
        }
    }

    public Fit getFit() { return fit; }

    // ═════════════════════════════════════════════════════════════
    //  Touch: tap + drag + pinch
    // ═════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                startTouch.set(event.getX(), event.getY());
                isDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (isPinching) return true;
                float dx = event.getX() - lastTouch.x;
                float dy = event.getY() - lastTouch.y;
                if (Math.abs(event.getX() - startTouch.x) > 12
                        || Math.abs(event.getY() - startTouch.y) > 12) {
                    isDragging = true;
                }
                if (isDragging) {
                    panX += dx; panY += dy;
                    setX(panX); setY(panY);
                }
                lastTouch.set(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging && !isPinching && tapListener != null) tapListener.onTap();
                isDragging = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void resetTransform() {
        panX = 0f; panY = 0f; currentScale = 1f;
        setX(0f); setY(0f);
        setScaleX(1f); setScaleY(1f);
    }

    // ═════════════════════════════════════════════════════════════
    //  Aspect-ratio-aware measurement — VLC's vout_display_PlacePicture()
    // ═════════════════════════════════════════════════════════════

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availW = MeasureSpec.getSize(widthMeasureSpec);
        int availH = MeasureSpec.getSize(heightMeasureSpec);

        if (videoWidth <= 0 || videoHeight <= 0) {
            // Not yet known — fill parent
            setMeasuredDimension(availW, availH);
            return;
        }

        // ── Step 1: compute the displayed aspect ratio ──
        // VLC: DAR = (visible_width * SAR_num) / (visible_height * SAR_den)
        // We use the visible region (in case of coded > visible).
        int visW = videoVisibleWidth > 0 ? videoVisibleWidth : videoWidth;
        int visH = videoVisibleHeight > 0 ? videoVisibleHeight : videoHeight;
        if (visW <= 0 || visH <= 0) { visW = videoWidth; visH = videoHeight; }

        // Displayed dimensions after SAR correction — these are "logical" pixels.
        // We keep them as ints using a common denominator.
        long darNum = (long) visW * sarNum;
        long darDen = (long) visH * sarDen;
        if (darNum <= 0 || darDen <= 0) { darNum = visW; darDen = visH; }

        // ── Step 2: compute scaling factor based on fit mode ──
        // scale = min or max of (availW/darW) and (availH/darH)
        // We work in "display pixels" = available bounds, and map back to
        // the video's natural size via the ratio.
        double scaleW = (double) availW / (double) visW;
        double scaleH = (double) availH / (double) visH;

        // Account for SAR: if SAR is not 1:1, effective horizontal scale differs
        // We compute scale in the "display space":
        //    displayedW = visW * scale * (sarNum/sarDen)   ← visual width
        //    displayedH = visH * scale                     ← visual height
        // But since we're returning layout dimensions, we want:
        //    layoutW = visW * scale
        //    layoutH = visH * scale
        // And the visual result is layoutW_visual = layoutW * sarNum/sarDen.
        //
        // To fit visual-in-available:
        //    layoutW * sarNum / sarDen ≤ availW   →   layoutW ≤ availW * sarDen / sarNum
        //    layoutH ≤ availH
        double effAvailW = (double) availW * sarDen / sarNum;

        double chosenScale;
        switch (fit) {
            case NONE:
                // 1:1 — but still account for SAR for visual fit
                chosenScale = 1.0;
                break;
            case LARGER:
                chosenScale = Math.max(effAvailW / visW, (double) availH / visH);
                break;
            case WIDTH:
                chosenScale = effAvailW / visW;
                break;
            case HEIGHT:
                chosenScale = (double) availH / visH;
                break;
            case SMALLER:
            default:
                chosenScale = Math.min(effAvailW / visW, (double) availH / visH);
                break;
        }

        int measuredW = (int) Math.round(visW * chosenScale);
        int measuredH = (int) Math.round(visH * chosenScale);

        // Guard against zero
        if (measuredW < 1) measuredW = 1;
        if (measuredH < 1) measuredH = 1;

        log("onMeasure: vid=" + visW + "x" + visH
                + " sar=" + sarNum + ":" + sarDen
                + " avail=" + availW + "x" + availH
                + " fit=" + fit
                + " → " + measuredW + "x" + measuredH);

        setMeasuredDimension(measuredW, measuredH);
    }

    private void applyVideoSize(int w, int h) {
        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
            videoWidth = w;
            videoHeight = h;
            // Assume visible == coded unless we have better info
            if (videoVisibleWidth <= 0) videoVisibleWidth = w;
            if (videoVisibleHeight <= 0) videoVisibleHeight = h;
            log("applyVideoSize: " + w + "x" + h);
            requestLayout();
        }
    }

    /** Called from the activity if it has better geometry (SAR, visible rect). */
    public void setVideoGeometry(int visibleW, int visibleH, int sar_num, int sar_den) {
        if (visibleW > 0) videoVisibleWidth = visibleW;
        if (visibleH > 0) videoVisibleHeight = visibleH;
        if (sar_num > 0) sarNum = sar_num;
        if (sar_den > 0) sarDen = sar_den;
        requestLayout();
    }

    // ═════════════════════════════════════════════════════════════
    //  SurfaceHolder.Callback
    // ═════════════════════════════════════════════════════════════

    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
        log("surfaceCreated");
        surfaceReady = true;
        openVideoIfReady("surfaceCreated");
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        log("surfaceChanged: " + width + "x" + height);
    }

    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        log("surfaceDestroyed");
        surfaceReady = false;
        releaseMediaPlayer("surfaceDestroyed");
    }

    // ═════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════

    public String getVideoPath() { return videoPath; }
    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }

    public void setVideoPath(String path) {
        log("setVideoPath: " + path);
        if (path != null && path.equals(videoPath) && mediaPlayer != null) {
            log("  same path & already prepared → skipping reload");
            return;
        }
        videoPath = path;
        playWhenReady = false;
        mediaGeneration++;
        releaseMediaPlayer("setVideoPath");
        openVideoIfReady("setVideoPath");
    }

    public void setVideoURI(Uri uri) {
        log("setVideoURI: " + uri);
        videoPath = uri != null ? uri.toString() : null;
        playWhenReady = false;
        mediaGeneration++;
        releaseMediaPlayer("setVideoURI");
        openVideoIfReady("setVideoURI");
    }

    public void start() {
        log("start() called: mediaPlayer=" + (mediaPlayer != null) + " prepared=" + prepared);
        if (mediaPlayer != null && prepared) {
            try {
                if (!mediaPlayer.isPlaying()) { mediaPlayer.start(); log("  started OK"); }
                else log("  already playing");
            } catch (IllegalStateException e) { logWarn("  start() ISE"); playWhenReady = true; }
        } else { log("  not prepared → playWhenReady=true"); playWhenReady = true; }
    }

    public void pause() {
        log("pause()");
        playWhenReady = false;
        if (mediaPlayer != null && prepared) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.pause(); }
            catch (IllegalStateException ignored) {}
        }
    }

    public void stopPlayback() {
        log("stopPlayback()");
        playWhenReady = false;
        mediaGeneration++;
        releaseMediaPlayer("stopPlayback");
    }

    public void seekTo(int ms) {
        if (mediaPlayer != null && prepared) {
            try { mediaPlayer.seekTo(Math.max(0, ms)); } catch (IllegalStateException ignored) {}
        }
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null && prepared) {
            try { return mediaPlayer.getCurrentPosition(); } catch (Exception ignored) {}
        }
        return 0;
    }

    public int getDuration() {
        if (mediaPlayer != null && prepared) {
            try { return mediaPlayer.getDuration(); } catch (Exception ignored) {}
        }
        return 0;
    }

    public boolean isPlaying() {
        if (mediaPlayer == null || !prepared) return false;
        try { return mediaPlayer.isPlaying(); } catch (Exception ignored) { return false; }
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        preparedListener = l;
        if (prepared && mediaPlayer != null && l != null) {
            try { l.onPrepared(mediaPlayer); } catch (Exception e) { logError("onPrepared", e); }
        }
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener l) { errorListener = l; }
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener l) { completionListener = l; }

    // ═════════════════════════════════════════════════════════════
    //  MediaPlayer lifecycle
    // ═════════════════════════════════════════════════════════════

    private void openVideoIfReady(String caller) {
        log("openVideoIfReady (from " + caller + ") surfaceReady=" + surfaceReady
                + " videoPath=" + videoPath);

        if (!surfaceReady) { log("  surface not ready"); return; }
        if (videoPath == null || videoPath.isEmpty()) { log("  no videoPath"); return; }

        final int generation = mediaGeneration;
        final String path = videoPath;

        releaseMediaPlayer("openVideoIfReady-pre");
        if (generation != mediaGeneration) { log("  gen changed"); return; }

        try {
            final MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            prepared = false;
            player.setDataSource(path);
            player.setDisplay(getHolder());

            player.setOnVideoSizeChangedListener((mp, w, h) -> {
                log("★ onVideoSizeChanged: " + w + "x" + h);
                applyVideoSize(w, h);
            });

            player.setOnPreparedListener(mp -> {
                log("★ onPrepared gen=" + generation + " currentGen=" + mediaGeneration);
                if (mediaPlayer != mp || generation != mediaGeneration) {
                    try { mp.reset(); mp.release(); } catch (Exception ignored) {}
                    return;
                }
                prepared = true;
                try {
                    int w = mp.getVideoWidth();
                    int h = mp.getVideoHeight();
                    if (w > 0 && h > 0) applyVideoSize(w, h);
                } catch (Exception ignored) {}

                if (preparedListener != null) {
                    try { preparedListener.onPrepared(mp); } catch (Exception ignored) {}
                }
                if (playWhenReady && mediaPlayer == mp) {
                    try { mp.start(); playWhenReady = false; }
                    catch (IllegalStateException ignored) {}
                }
            });

            player.setOnCompletionListener(mp -> {
                log("onCompletion");
                if (mediaPlayer != mp || generation != mediaGeneration) return;
                playWhenReady = false;
                if (completionListener != null) {
                    try { completionListener.onCompletion(mp); } catch (Exception ignored) {}
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(LOG_TAG, SRC + " [" + id + "] onError " + what + "/" + extra);
                if (mediaPlayer != mp || generation != mediaGeneration) return true;
                prepared = false; playWhenReady = false;
                if (errorListener != null) {
                    try { return errorListener.onError(mp, what, extra); }
                    catch (Exception ignored) { return true; }
                }
                return true;
            });

            player.prepareAsync();

        } catch (IOException e) {
            logError("IOException: " + e.getMessage(), e);
            if (generation == mediaGeneration) { prepared = false; releaseMediaPlayer("io"); }
        } catch (Exception e) {
            logError("Exception: " + e.getMessage(), e);
            prepared = false;
            releaseMediaPlayer("other");
        }
    }

    private void releaseMediaPlayer(String caller) {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        prepared = false;
        if (player != null) {
            log("releaseMediaPlayer (from " + caller + ")");
            try { player.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { player.setOnCompletionListener(null); } catch (Exception ignored) {}
            try { player.setOnErrorListener(null); } catch (Exception ignored) {}
            try { player.setDisplay(null); } catch (Exception ignored) {}
            try { player.stop(); } catch (Exception ignored) {}
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); log("onAttachedToWindow"); }
    @Override protected void onDetachedFromWindow() { log("onDetachedFromWindow"); super.onDetachedFromWindow(); }
}