package vaddempudi.gnanesh.syntaxcli.gallery;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Rotating hexagon outline that grows from a single dot to the full
 * hexagon, then shrinks back to a dot, and repeats — no disappearing.
 * Stroke is thin so the 120° corners are clearly visible.
 */
public class HexRingDrawable extends Drawable implements Animatable {

    private static final long CYCLE_MS  = 1200L; // grow + shrink, 2× faster
    private static final long ROTATE_MS = 1200L; // one full spin per cycle

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fullHex  = new Path();
    private final Path drawPath = new Path();
    private final PathMeasure pm = new PathMeasure();

    private long startTime = 0L;
    private boolean running = false;

    private final int strokeWidthPx;
    private final int color;

    private final Runnable invalidator = this::invalidateSelf;

    public HexRingDrawable(int strokeWidthPx, int color) {
        this.strokeWidthPx = strokeWidthPx;
        this.color = color;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidthPx);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setDither(true);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float radius = Math.min(b.width(), b.height()) / 2f - strokeWidthPx;

        // Build the full hexagon path (source geometry)
        fullHex.reset();
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(60 * i - 90);
            float x = cx + (float) (radius * Math.cos(angle));
            float y = cy + (float) (radius * Math.sin(angle));
            if (i == 0) fullHex.moveTo(x, y);
            else fullHex.lineTo(x, y);
        }
        fullHex.close();

        pm.setPath(fullHex, false);
        float totalLen = pm.getLength();
        if (totalLen <= 0f) return;

        // Cycle progress [0, 1)
        float progress;
        if (running) {
            long now = SystemClock.uptimeMillis();
            long t = (now - startTime) % CYCLE_MS;
            progress = t / (float) CYCLE_MS;
        } else {
            progress = 0f;
        }

        final float MIN_VISIBLE = 0.008f;
        float visible;
        float startFrac;
        if (progress < 0.5f) {
            // GROW phase: head advances from start (0%) to full (100%).
            // The tail stays at 0% of the path.
            float p = progress / 0.5f;
            visible    = MIN_VISIBLE + p * (1f - MIN_VISIBLE);
            startFrac  = 0f;
        } else {
            // SHRINK phase: reverse unwind.
            // The visible segment is the LAST `visible` fraction of the
            // hexagon, so the head stays at the end of the path and the
            // tail moves forward — visually the trail retracts back the
            // opposite way it grew.
            float p = (progress - 0.5f) / 0.5f;
            visible    = 1f - p * (1f - MIN_VISIBLE);
            startFrac  = 1f - visible;
        }

        drawPath.reset();
        pm.getSegment(totalLen * startFrac, totalLen * (startFrac + visible),
                drawPath, true);

        // Continuous rotation
        float deg = 0f;
        if (running) {
            long now = SystemClock.uptimeMillis();
            deg = ((now - startTime) % ROTATE_MS) * 360f / ROTATE_MS;
        }

        int save = canvas.save();
        canvas.rotate(deg, cx, cy);
        canvas.drawPath(drawPath, paint);
        canvas.restoreToCount(save);

        if (running) scheduleSelf(invalidator, SystemClock.uptimeMillis() + 16L);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override
    public void start() {
        if (!running) {
            running = true;
            startTime = SystemClock.uptimeMillis();
            invalidateSelf();
        }
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            unscheduleSelf(invalidator);
        }
    }

    @Override public boolean isRunning() { return running; }
}