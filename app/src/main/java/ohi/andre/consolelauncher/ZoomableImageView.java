package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private PointF startPoint = new PointF();
    private PointF midPoint = new PointF();
    private float oldDist = 1f;

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private ScaleGestureDetector scaleDetector;
    private float maxScale = 4.0f;
    private float minScale = 1.0f;
    private float currentScale = 1.0f;

    private boolean isZoomed = false;
    private boolean isDragging = false;

    public ZoomableImageView(Context context) {
        super(context);
        init();
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        setOnTouchListener(new TouchListener());
        setClickable(true);
        setFocusable(true);
    }

    public void resetZoom() {
        matrix.reset();
        setImageMatrix(matrix);
        currentScale = 1.0f;
        isZoomed = false;
        invalidate();
    }

    public void toggleZoom() {
        if (getDrawable() == null) return;

        if (isZoomed) {
            // Zoom out
            matrix.reset();
            setImageMatrix(matrix);
            currentScale = 1.0f;
            isZoomed = false;
        } else {
            // Zoom in (center of image)
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            matrix.postScale(2f, 2f, cx, cy);
            setImageMatrix(matrix);
            currentScale = 2.0f;
            isZoomed = true;
        }
        invalidate();
    }

    public boolean isZoomed() {
        return isZoomed;
    }

    private class TouchListener implements OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            // Don't handle touch if image is not loaded
            if (getDrawable() == null) return false;

            // Pass touch events to scale detector
            scaleDetector.onTouchEvent(event);

            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    mode = DRAG;
                    isDragging = false;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist = spacing(event);
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix);
                        midPoint(midPoint, event);
                        mode = ZOOM;
                        isDragging = false;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    isDragging = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG && currentScale > 1.0f) {
                        matrix.set(savedMatrix);
                        float dx = event.getX() - startPoint.x;
                        float dy = event.getY() - startPoint.y;
                        matrix.postTranslate(dx, dy);
                        isDragging = true;
                    } else if (mode == ZOOM) {
                        float newDist = spacing(event);
                        if (newDist > 10f) {
                            matrix.set(savedMatrix);
                            float scale = newDist / oldDist;
                            float newScale = currentScale * scale;

                            if (newScale < minScale) {
                                scale = minScale / currentScale;
                            } else if (newScale > maxScale) {
                                scale = maxScale / currentScale;
                            }

                            matrix.postScale(scale, scale, midPoint.x, midPoint.y);
                            currentScale *= scale;
                            isZoomed = currentScale > 1.05f;
                            isDragging = true;
                        }
                    }
                    break;
            }

            setImageMatrix(matrix);
            invalidate();
            return true;
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (getDrawable() == null) return false;

            float scaleFactor = detector.getScaleFactor();
            float newScale = currentScale * scaleFactor;

            if (newScale < minScale) {
                scaleFactor = minScale / currentScale;
            } else if (newScale > maxScale) {
                scaleFactor = maxScale / currentScale;
            }

            matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            currentScale *= scaleFactor;
            isZoomed = currentScale > 1.05f;
            setImageMatrix(matrix);
            invalidate();
            return true;
        }
    }

    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF point, MotionEvent event) {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }
}