package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {

    private static final String LOG_TAG = "gallery-tui";
    private final String id = Integer.toHexString(System.identityHashCode(this));
    private void log(String msg) { Log.d(LOG_TAG, "[ZoomableImageView:" + id + "] " + msg); }

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
    private float baseScale = 1.0f;
    private boolean isZoomed = false;

    public interface OnTapListener { void onTap(); }
    private OnTapListener onTapListener;
    public void setOnTapListener(OnTapListener l) { this.onTapListener = l; }

    public ZoomableImageView(Context context) { super(context); init(context); }
    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(context); }
    public ZoomableImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
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

                        imageMatrix.postScale(realFactor, realFactor, d.getFocusX(), d.getFocusY());
                        setImageMatrix(imageMatrix);
                        fixTranslation();
                        updateZoomState();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (onTapListener != null) onTapListener.onTap();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (isZoomed) {
                            currentScale = baseScale;
                            setScaleTo(baseScale, e.getX(), e.getY());
                        } else {
                            currentScale = baseScale * DOUBLE_TAP_SCALE;
                            setScaleTo(currentScale, e.getX(), e.getY());
                        }
                        updateZoomState();
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (!isZoomed) return false;
                        imageMatrix.postTranslate(-dx, -dy);
                        setImageMatrix(imageMatrix);
                        fixTranslation();
                        return true;
                    }
                });
    }

    private void setScaleTo(float targetScale, float px, float py) {
        imageMatrix.getValues(matrixValues);
        float currentMatrixScale = matrixValues[Matrix.MSCALE_X];
        if (currentMatrixScale == 0f) return;
        float factor = targetScale / currentMatrixScale;
        imageMatrix.postScale(factor, factor, px, py);
        setImageMatrix(imageMatrix);
        fixTranslation();
    }

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
        if (viewW <= 0 || viewH <= 0) return;

        // Use drawable intrinsic size; if 0 (e.g., bitmap), fall back to bounds
        float imgW = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : d.getBounds().width();
        float imgH = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : d.getBounds().height();
        imgW *= scaleX;
        imgH *= scaleY;

        float deltaX = 0f, deltaY = 0f;

        if (imgW <= viewW) deltaX = (viewW - imgW) / 2f - transX;
        else {
            if (transX > 0) deltaX = -transX;
            if (transX + imgW < viewW) deltaX = viewW - (transX + imgW);
        }

        if (imgH <= viewH) deltaY = (viewH - imgH) / 2f - transY;
        else {
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

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        log("setImageDrawable");
        post(this::resetToFit);
    }

    /** ★★ FIX: Re-fit when the view is measured/laid out. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        log("onSizeChanged: " + oldw + "x" + oldh + " → " + w + "x" + h);
        if ((oldw == 0 || oldh == 0) && w > 0 && h > 0) {
            resetToFit();
        }
    }

    private void resetToFit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) {
            log("resetToFit bailed (drawable=" + (d != null)
                    + " w=" + getWidth() + " h=" + getHeight() + ")");
            return;
        }

        float viewW = getWidth();
        float viewH = getHeight();

        // Intrinsic size for bitmaps is often -1; use bounds as fallback
        float imgW = d.getIntrinsicWidth();
        float imgH = d.getIntrinsicHeight();
        if (imgW <= 0 || imgH <= 0) {
            imgW = d.getBounds().width();
            imgH = d.getBounds().height();
        }
        if (imgW <= 0 || imgH <= 0) {
            // Last resort: use bitmap directly if it's a BitmapDrawable
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (bmp != null) { imgW = bmp.getWidth(); imgH = bmp.getHeight(); }
            }
        }
        if (imgW <= 0 || imgH <= 0) {
            log("resetToFit bailed (imgW=" + imgW + " imgH=" + imgH + ")");
            return;
        }

        float scale = Math.min(viewW / imgW, viewH / imgH);
        baseScale = scale;
        currentScale = scale;

        imageMatrix.reset();
        imageMatrix.postScale(scale, scale);
        float dx = (viewW - imgW * scale) / 2f;
        float dy = (viewH - imgH * scale) / 2f;
        imageMatrix.postTranslate(dx, dy);
        setImageMatrix(imageMatrix);
        isZoomed = false;
        log("resetToFit: img=" + imgW + "x" + imgH
                + " view=" + viewW + "x" + viewH
                + " scale=" + scale);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                startTouch.set(event.getX(), event.getY());
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isZoomed && event.getPointerCount() == 1) {
                    float dx = Math.abs(event.getX() - startTouch.x);
                    float dy = Math.abs(event.getY() - startTouch.y);
                    if (dx > 24 && dx > dy) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    }
                } else {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean canScrollHorizontally(int direction) { return isZoomed; }

    public void toggleZoom() {
        if (isZoomed()) { currentScale = baseScale; resetToFit(); }
        else { currentScale = baseScale * 2.5f; setScaleTo(currentScale, getWidth() / 2f, getHeight() / 2f); updateZoomState(); }
    }

    public boolean isZoomed() {
        imageMatrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        return scale > baseScale * 1.05f;
    }
}