package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * SurfaceView-based video player. SurfaceView is used instead of TextureView
 * because TextureView does not composite correctly inside a ViewPager2 on
 * most Android 9-13 devices — the surface is created and MediaPlayer renders
 * into it, but the final composite shows the pager background (black screen).
 *
 * SurfaceView punches through the window compositor and always renders.
 */
public class CustomVideoView extends SurfaceView implements SurfaceHolder.Callback {

    public static final String LOG_TAG = "gallery-tui";
    private static final String SRC = "[CustomVideoView]";

    private final String id = Integer.toHexString(System.identityHashCode(this));

    private MediaPlayer mediaPlayer;
    private String videoPath;

    private boolean prepared = false;
    private boolean playWhenReady = false;
    private boolean surfaceReady = false;

    private int mediaGeneration = 0;

    private MediaPlayer.OnPreparedListener preparedListener;
    private MediaPlayer.OnErrorListener errorListener;
    private MediaPlayer.OnCompletionListener completionListener;

    public CustomVideoView(@NonNull Context context) {
        super(context);
        init("ctor1");
    }

    public CustomVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init("ctor2");
    }

    public CustomVideoView(@NonNull Context context,
                           @Nullable AttributeSet attrs,
                           int defStyle) {
        super(context, attrs, defStyle);
        init("ctor3");
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
    }

    // ═════════════════════════════════════════════════════════════
    //  SurfaceHolder.Callback
    // ═════════════════════════════════════════════════════════════

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        log("surfaceCreated");
        surfaceReady = true;
        openVideoIfReady("surfaceCreated");
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        log("surfaceChanged: " + width + "x" + height + " format=" + format);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        log("surfaceDestroyed");
        surfaceReady = false;
        releaseMediaPlayer("surfaceDestroyed");
    }

    // ═════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════

    public void setVideoPath(String path) {
        log("setVideoPath: " + path);
        videoPath = path;
        playWhenReady = false;
        mediaGeneration++;
        log("  mediaGeneration → " + mediaGeneration);
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
        log("start() called: mediaPlayer=" + (mediaPlayer != null)
                + " prepared=" + prepared);
        if (mediaPlayer != null && prepared) {
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                    log("  mediaPlayer.start() OK, isPlaying=" + mediaPlayer.isPlaying());
                } else {
                    log("  already playing");
                }
            } catch (IllegalStateException e) {
                logWarn("  start() IllegalStateException → playWhenReady=true");
                playWhenReady = true;
            }
        } else {
            log("  not prepared yet → playWhenReady=true");
            playWhenReady = true;
        }
    }

    public void pause() {
        log("pause() called: mediaPlayer=" + (mediaPlayer != null)
                + " prepared=" + prepared);
        playWhenReady = false;
        if (mediaPlayer != null && prepared) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    log("  paused");
                }
            } catch (IllegalStateException e) {
                logWarn("  pause() IllegalStateException");
            }
        }
    }

    public void stopPlayback() {
        log("stopPlayback()");
        playWhenReady = false;
        mediaGeneration++;
        releaseMediaPlayer("stopPlayback");
    }

    public void seekTo(int ms) {
        log("seekTo(" + ms + ")");
        if (mediaPlayer != null && prepared) {
            try {
                mediaPlayer.seekTo(Math.max(0, ms));
            } catch (IllegalStateException e) {
                logWarn("  seekTo IllegalStateException");
            }
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

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener listener) {
        log("setOnPreparedListener: " + listener);
        preparedListener = listener;
        if (prepared && mediaPlayer != null && listener != null) {
            log("  already prepared → delivering immediately");
            try {
                listener.onPrepared(mediaPlayer);
            } catch (Exception e) {
                logError("  onPrepared delivery failed", e);
            }
        }
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
        log("setOnErrorListener: " + listener);
        errorListener = listener;
    }

    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        log("setOnCompletionListener: " + listener);
        completionListener = listener;
    }

    // ═════════════════════════════════════════════════════════════
    //  MediaPlayer lifecycle
    // ═════════════════════════════════════════════════════════════

    private void openVideoIfReady(String caller) {
        log("openVideoIfReady (from " + caller + ")"
                + " surfaceReady=" + surfaceReady
                + " videoPath=" + videoPath);

        if (!surfaceReady) {
            log("  surface not ready, waiting");
            return;
        }
        if (videoPath == null || videoPath.isEmpty()) {
            log("  no videoPath, waiting");
            return;
        }

        final int generation = mediaGeneration;
        final String path = videoPath;

        log("  creating MediaPlayer for gen=" + generation);
        releaseMediaPlayer("openVideoIfReady-pre");
        if (generation != mediaGeneration) {
            log("  generation changed during release, aborting");
            return;
        }

        try {
            final MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            prepared = false;
            log("  MediaPlayer=" + Integer.toHexString(System.identityHashCode(player)));

            log("  setDataSource: " + path);
            player.setDataSource(path);

            log("  setDisplay(holder)");
            player.setDisplay(getHolder());

            player.setOnPreparedListener(mp -> {
                log("★ onPrepared fired for gen=" + generation
                        + " currentGen=" + mediaGeneration
                        + " mediaPlayer==" + (mediaPlayer == mp));

                if (mediaPlayer != mp || generation != mediaGeneration) {
                    logWarn("  stale onPrepared → releasing");
                    try { mp.reset(); mp.release(); } catch (Exception ignored) {}
                    return;
                }

                prepared = true;
                log("  prepared=true");

                if (preparedListener != null) {
                    log("  calling preparedListener.onPrepared");
                    try {
                        preparedListener.onPrepared(mp);
                    } catch (Exception e) {
                        logError("  preparedListener threw", e);
                    }
                } else {
                    logWarn("  no preparedListener installed!");
                }

                if (playWhenReady && mediaPlayer == mp) {
                    log("  playWhenReady → starting now");
                    try {
                        mp.start();
                        playWhenReady = false;
                    } catch (IllegalStateException e) {
                        logWarn("  start in onPrepared failed");
                    }
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
                Log.e(LOG_TAG, SRC + " [" + id + "] ★ onError what=" + what + " extra=" + extra);
                if (mediaPlayer != mp || generation != mediaGeneration) {
                    logWarn("  stale onError ignored");
                    return true;
                }
                prepared = false;
                playWhenReady = false;
                if (errorListener != null) {
                    try { return errorListener.onError(mp, what, extra); }
                    catch (Exception ignored) { return true; }
                }
                return true;
            });

            log("  calling prepareAsync()");
            player.prepareAsync();

        } catch (IOException e) {
            logError("prepareAsync IOException: " + e.getMessage(), e);
            if (generation == mediaGeneration) {
                prepared = false;
                releaseMediaPlayer("prepareAsync-IOException");
            }
        } catch (IllegalArgumentException e) {
            logError("prepareAsync IllegalArgumentException: " + e.getMessage(), e);
            if (generation == mediaGeneration) {
                prepared = false;
                releaseMediaPlayer("prepareAsync-IAE");
            }
        } catch (IllegalStateException e) {
            logError("prepareAsync IllegalStateException: " + e.getMessage(), e);
            if (generation == mediaGeneration) {
                prepared = false;
                releaseMediaPlayer("prepareAsync-ISE");
            }
        } catch (Exception e) {
            logError("prepareAsync unexpected: " + e.getMessage(), e);
            prepared = false;
            releaseMediaPlayer("prepareAsync-other");
        }
    }

    private void releaseMediaPlayer(String caller) {
        MediaPlayer player = mediaPlayer;
        mediaPlayer = null;
        prepared = false;

        if (player != null) {
            log("releaseMediaPlayer (from " + caller + "): "
                    + Integer.toHexString(System.identityHashCode(player)));
            try { player.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { player.setOnCompletionListener(null); } catch (Exception ignored) {}
            try { player.setOnErrorListener(null); } catch (Exception ignored) {}
            try { player.setDisplay(null); } catch (Exception ignored) {}
            try { player.stop(); } catch (Exception ignored) {}
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        } else {
            log("releaseMediaPlayer (from " + caller + "): no-op (null)");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        log("onAttachedToWindow");
    }

    @Override
    protected void onDetachedFromWindow() {
        log("onDetachedFromWindow");
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        log("onSizeChanged: " + oldw + "x" + oldh + " → " + w + "x" + h);
    }
}