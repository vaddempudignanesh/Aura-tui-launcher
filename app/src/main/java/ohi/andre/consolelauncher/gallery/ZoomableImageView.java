package ohi.andre.consolelauncher.gallery;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.BitmapDrawable;
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

    private static final float MAX_SCALE = 5.0f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix imageMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
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
                        log("onSingleTapConfirmed → dispatching");
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

        float imgW = getDrawableWidth(d) * scaleX;
        float imgH = getDrawableHeight(d) * scaleY;

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

    private float getDrawableWidth(Drawable d) {
        int w = d.getIntrinsicWidth();
        if (w > 0) return w;
        w = d.getBounds().width();
        if (w > 0) return w;
        if (d instanceof BitmapDrawable) {
            android.graphics.Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) return b.getWidth();
        }
        return 0;
    }

    private float getDrawableHeight(Drawable d) {
        int h = d.getIntrinsicHeight();
        if (h > 0) return h;
        h = d.getBounds().height();
        if (h > 0) return h;
        if (d instanceof BitmapDrawable) {
            android.graphics.Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) return b.getHeight();
        }
        return 0;
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if ((oldw == 0 || oldh == 0) && w > 0 && h > 0) {
            resetToFit();
        }
    }

    private void resetToFit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;

        float viewW = getWidth();
        float viewH = getHeight();

        float imgW = getDrawableWidth(d);
        float imgH = getDrawableHeight(d);
        if (imgW <= 0 || imgH <= 0) return;

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
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
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

    public boolean isZoomed() {
        imageMatrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        return scale > baseScale * 1.05f;
    }
}