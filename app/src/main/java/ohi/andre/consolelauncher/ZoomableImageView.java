package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * An ImageView supporting:
 *  - pinch-to-zoom
 *  - double-tap-to-zoom (toggle)
 *  - one-finger pan when zoomed
 *  - no pan when fully zoomed out (so parent ViewPager2 can swipe between pages)
 *
 * IMPORTANT: For correct behavior with a parent ViewPager2, this view must
 * call requestDisallowInterceptTouchEvent(true) ONLY when it is currently
 * zoomed in or actively zooming. When zoomed out, it must let events
 * propagate so ViewPager2 can handle horizontal swipes.
 */
public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix imageMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final PointF lastTouch = new PointF();
    private final PointF startTouch = new PointF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private float currentScale = 1.0f;
    private float baseScale = 1.0f;      // scale to fit image into view
    private boolean isZoomed = false;

    /** Callback when the user taps (single tap) — used to toggle chrome. */
    public interface OnTapListener {
        void onTap();
    }
    private OnTapListener onTapListener;
    public void setOnTapListener(OnTapListener l) { this.onTapListener = l; }

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        // We're starting a pinch → block ViewPager from intercepting
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float factor = d.getScaleFactor();
                        float newScale = currentScale * factor;
                        if (newScale < baseScale) newScale = baseScale;
                        if (newScale > baseScale * MAX_SCALE) newScale = baseScale * MAX_SCALE;

                        float realFactor = newScale / currentScale;
                        currentScale = newScale;

                        imageMatrix.postScale(realFactor, realFactor,
                                d.getFocusX(), d.getFocusY());
                        setImageMatrix(imageMatrix);
                        fixTranslation();
                        updateZoomState();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {

                    @Override
                    public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (onTapListener != null) onTapListener.onTap();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (isZoomed) {
                            // zoom out to fit
                            currentScale = baseScale;
                            setScaleTo(baseScale, e.getX(), e.getY());
                        } else {
                            // zoom in to DOUBLE_TAP_SCALE
                            currentScale = baseScale * DOUBLE_TAP_SCALE;
                            setScaleTo(currentScale, e.getX(), e.getY());
                        }
                        updateZoomState();
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float dx, float dy) {
                        // Only pan when zoomed in
                        if (!isZoomed) return false;

                        imageMatrix.postTranslate(-dx, -dy);
                        setImageMatrix(imageMatrix);
                        fixTranslation();
                        return true;
                    }
                });
    }

    /** Programmatically set scale to a target value, pivoting on (px, py). */
    private void setScaleTo(float targetScale, float px, float py) {
        imageMatrix.getValues(matrixValues);
        float currentMatrixScale = matrixValues[Matrix.MSCALE_X];
        if (currentMatrixScale == 0f) return;
        float factor = targetScale / currentMatrixScale;
        imageMatrix.postScale(factor, factor, px, py);
        setImageMatrix(imageMatrix);
        fixTranslation();
    }

    /** Keeps the image from drifting off-screen. */
    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;

        imageMatrix.getValues(matrixValues);
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];
        float scaleX = matrixValues[Matrix.MSCALE_X];
        float scaleY = matrixValues[Matrix.MSCALE_Y];

        float viewW = getWidth();
        float viewH = getHeight();
        float imgW = d.getIntrinsicWidth() * scaleX;
        float imgH = d.getIntrinsicHeight() * scaleY;

        float deltaX = 0f, deltaY = 0f;

        // If the (scaled) image is narrower than the view → center it
        if (imgW <= viewW) {
            deltaX = (viewW - imgW) / 2f - transX;
        } else {
            // Otherwise clamp edges
            if (transX > 0) deltaX = -transX;
            if (transX + imgW < viewW) deltaX = viewW - (transX + imgW);
        }

        if (imgH <= viewH) {
            deltaY = (viewH - imgH) / 2f - transY;
        } else {
            if (transY > 0) deltaY = -transY;
            if (transY + imgH < viewH) deltaY = viewH - (transY + imgH);
        }

        imageMatrix.postTranslate(deltaX, deltaY);
        setImageMatrix(imageMatrix);
    }

    private void updateZoomState() {
        imageMatrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        isZoomed = scale > baseScale * 1.05f;
    }

    /**
     * Called whenever the drawable is set. Resets zoom and computes base scale
     * to fit the image inside the view.
     */
    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetToFit);
    }

    private void resetToFit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;

        float viewW = getWidth();
        float viewH = getHeight();
        float imgW = d.getIntrinsicWidth();
        float imgH = d.getIntrinsicHeight();
        if (imgW <= 0 || imgH <= 0) return;

        float scale = Math.min(viewW / imgW, viewH / imgH);
        baseScale = scale;
        currentScale = scale;

        imageMatrix.reset();
        imageMatrix.postScale(scale, scale);
        // center it
        float dx = (viewW - imgW * scale) / 2f;
        float dy = (viewH - imgH * scale) / 2f;
        imageMatrix.postTranslate(dx, dy);
        setImageMatrix(imageMatrix);
        isZoomed = false;
    }

    // ── Touch dispatch ─────────────────────────────────────────────
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                startTouch.set(event.getX(), event.getY());
                // If zoomed in, block parent from stealing events
                if (isZoomed && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                // If we've moved horizontally enough and are NOT zoomed in,
                // release to parent so ViewPager2 can page.
                if (!isZoomed && getParent() != null) {
                    float dx = Math.abs(event.getX() - startTouch.x);
                    float dy = Math.abs(event.getY() - startTouch.y);
                    if (dx > dy) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
        }
        return true;
    }

    // Required for gestures to work with parent scroll containers
    @Override
    public boolean canScrollHorizontally(int direction) {
        // When zoomed, we consume horizontal scrolling; when not, let parent have it
        return isZoomed;
    }

    /** Toggles between "fit" and "2.5× fit". Called from FullscreenViewerActivity. */
    public void toggleZoom() {
        if (isZoomed()) {
            currentScale = baseScale;
            // Reset matrix to fit
            resetToFit();
        } else {
            currentScale = baseScale * 2.5f;
            setScaleTo(currentScale, getWidth() / 2f, getHeight() / 2f);
            updateZoomState();
        }
    }

    public boolean isZoomed() {
        // Re-read the matrix in case it was changed externally
        imageMatrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        return scale > baseScale * 1.05f;
    }
}