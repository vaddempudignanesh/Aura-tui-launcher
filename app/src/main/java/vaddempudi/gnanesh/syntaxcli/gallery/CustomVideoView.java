package vaddempudi.gnanesh.syntaxcli.gallery;

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
 * Aspect-ratio handling ported from VLC's vout_display_PlacePicture().
 *
 * VLC computes the "placed rectangle" of the video inside the display bounds:
 *   - DAR (Display Aspect Ratio) = (visible_width * SAR_num) / (visible_height * SAR_den)
 *   - Then it computes a uniform scale so that the DAR-fitted rectangle
 *     fits inside the available bounds, according to the fitting mode.
 *
 * For the common case SAR = 1:1 (all phone-camera, WhatsApp, and standard MP4
 * videos), the DAR equals visible_width : visible_height, and the scale factor
 * is simply:
 *
 *   scale = min(availW / visW, availH / visH)   for SMALLER (letterbox)
 *   scale = max(availW / visW, availH / visH)   for LARGER  (crop)
 *   scale = availW / visW                        for WIDTH
 *   scale = availH / visH                        for HEIGHT
 *
 * The view's measured width = visW * scale, height = visH * scale.
 * This preserves the original aspect ratio exactly — no stretching.
 */
public class CustomVideoView extends SurfaceView implements SurfaceHolder.Callback {

    public static final String LOG_TAG = "gallery-tui";
    private static final String SRC = "[CustomVideoView]";

    private final String id = Integer.toHexString(System.identityHashCode(this));

    public enum Fit {
        NONE,       // 1:1 pixel
        SMALLER,    // letterbox (fit whole video)
        LARGER,     // crop to fill
        WIDTH,      // match width
        HEIGHT;     // match height
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

    // ── Source geometry ──
    private int videoWidth = 0;
    private int videoHeight = 0;

    // Fit mode
    private Fit fit = Fit.SMALLER;

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
    }

    public void setFit(Fit mode) {
        if (fit != mode) {
            fit = mode;
            requestLayout();
            log("fit mode → " + mode);
        }
    }

    public Fit getFit() { return fit; }

    // ═════════════════════════════════════════════════════════════
    //  Touch: tap only
    // ═════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (tapListener != null) tapListener.onTap();
        }
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    //  VLC-style aspect-ratio-preserving measure
    // ═════════════════════════════════════════════════════════════

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availW = MeasureSpec.getSize(widthMeasureSpec);
        int availH = MeasureSpec.getSize(heightMeasureSpec);

        // Before the video size is known, fill the parent so the SurfaceView
        // gets a valid surface.
        if (videoWidth <= 0 || videoHeight <= 0) {
            setMeasuredDimension(availW, availH);
            return;
        }

        // Guard against pathological sizes.
        if (availW <= 0 || availH <= 0) {
            setMeasuredDimension(availW, availH);
            return;
        }

        // ── VLC: scale factor per fitting mode ──
        // All videos from cameras / WhatsApp / standard MP4 have SAR = 1:1,
        // so DAR == videoWidth : videoHeight and no correction is needed.
        final double scaleW = (double) availW / (double) videoWidth;
        final double scaleH = (double) availH / (double) videoHeight;

        double chosenScale;
        switch (fit) {
            case NONE:
                chosenScale = 1.0;
                break;
            case LARGER:
                chosenScale = Math.max(scaleW, scaleH);
                break;
            case WIDTH:
                chosenScale = scaleW;
                break;
            case HEIGHT:
                chosenScale = scaleH;
                break;
            case SMALLER:
            default:
                chosenScale = Math.min(scaleW, scaleH);
                break;
        }

        int measuredW = (int) Math.round(videoWidth * chosenScale);
        int measuredH = (int) Math.round(videoHeight * chosenScale);

        // Never let the measured size exceed the available bounds for
        // SMALLER (this is what VLC does with the placed rectangle).
        if (fit == Fit.SMALLER || fit == Fit.NONE) {
            if (measuredW > availW) measuredW = availW;
            if (measuredH > availH) measuredH = availH;
        }

        if (measuredW < 1) measuredW = 1;
        if (measuredH < 1) measuredH = 1;

        log("onMeasure: vid=" + videoWidth + "x" + videoHeight
                + " avail=" + availW + "x" + availH
                + " fit=" + fit
                + " scale=" + chosenScale
                + " → " + measuredW + "x" + measuredH);

        setMeasuredDimension(measuredW, measuredH);
    }

    private void applyVideoSize(int w, int h) {
        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
            videoWidth = w;
            videoHeight = h;
            log("applyVideoSize: " + w + "x" + h);
            requestLayout();
        }
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

    /** True once MediaPlayer.onPrepared has completed and no error has occurred. */
    public boolean isPrepared() { return prepared; }

    public int getSafeDuration() {
        if (mediaPlayer == null || !prepared) return -1;
        try {
            int d = mediaPlayer.getDuration();
            return d > 0 ? d : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    public int getSafePosition() {
        if (mediaPlayer == null || !prepared) return -1;
        try {
            int p = mediaPlayer.getCurrentPosition();
            return p >= 0 ? p : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    public boolean seekToSafe(int ms) {
        if (mediaPlayer == null || !prepared) return false;
        try {
            mediaPlayer.seekTo(Math.max(0, ms));
            return true;
        } catch (Exception ignored) {
            return false;
        }
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

    /**
     * Returns the current playback speed parameters, or null if unavailable
     * (pre-API 23, or the player isn't prepared).
     */
    public android.media.PlaybackParams getPlaybackParams() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return null;
        if (mediaPlayer != null && prepared) {
            try {
                return mediaPlayer.getPlaybackParams();
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Sets the playback speed. The MediaPlayer must be prepared and
     * playing/paused (not stopped). Requires API 23+.
     *
     * @param params PlaybackParams with the desired speed, or null to reset to 1.0x
     * @return true if the params were applied successfully
     */
    public boolean setPlaybackParams(android.media.PlaybackParams params) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return false;
        if (mediaPlayer == null || !prepared) return false;
        try {
            if (params == null) params = new android.media.PlaybackParams();
            if (params.getSpeed() == 0f) params.setSpeed(1.0f);
            mediaPlayer.setPlaybackParams(params);
            return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, SRC + " [" + id + "] setPlaybackParams failed: " + e.getMessage());
            return false;
        }
    }
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