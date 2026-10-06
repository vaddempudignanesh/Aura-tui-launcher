package vaddempudi.gnanesh.syntaxcli.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class BasicKeyboardView extends View {

    public interface Listener {
        void onKey(int code, String text);
    }

    private static final int COLOR_BACKGROUND = 0xFF000000;
    private static final int COLOR_KEY          = 0xFF000000;
    private static final int COLOR_KEY_PRESSED  = 0xFF1E4D1E;
    private static final int COLOR_TEXT         = 0xFF33FF33;
    private static final int COLOR_BORDER       = 0xFF1F3F1F;

    private static final float GAP_DP                = 3f;
    private static final float KEY_RADIUS_DP         = 6f;
    private static final float TEXT_SIZE_SP          = 22f;
    private static final float SPECIAL_TEXT_SIZE_SP  = 16f;

    private static final float ROW_HEIGHT_DP         = 55f;
    private static final float TOP_PADDING_DP        = 8f;
    private static final float BOTTOM_PADDING_DP     = 8f;

    private static final float ASDF_SIDE_PADDING_DP = 17f;

    private static final long REPEAT_START_MS = 400L;
    private static final long REPEAT_INTERVAL_MS = 60L;

    private boolean shiftLocked = false;

    private final float density;
    private final float scaledDensity;

    private Listener listener;
    private BasicKeyboardLayout layout;
    private boolean shifted = false;
    private boolean symbols = false;
    private boolean symbols2 = false;
    private boolean symbols3 = false;

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF keyRect = new RectF();

    private final List<HitKey> hitKeys = new ArrayList<>();
    private HitKey pressed = null;

    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private HitKey repeatKey = null;
    private Runnable repeatRunnable;

    public BasicKeyboardView(Context context) {
        super(context);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        scaledDensity = dm.scaledDensity;
        layout = new BasicKeyboardLayout();

        keyPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        borderPaint.setColor(COLOR_BORDER);

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_TEXT);

        setBackgroundColor(COLOR_BACKGROUND);
        setFocusable(true);
        setFocusableInTouchMode(true);

        repeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (repeatKey != null && listener != null) {
                    dispatch(repeatKey.key);
                    repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS);
                }
            }
        };
    }


    public boolean isShiftLocked() { return shiftLocked; }
    public void setShiftLocked(boolean s) { shiftLocked = s; invalidate(); }



    private static final long SHIFT_DOUBLE_TAP_MS = 300L;
    private long lastShiftTapMs = 0L;


    public boolean registerShiftTap() {
        long now = System.currentTimeMillis();
        boolean second = (now - lastShiftTapMs) <= SHIFT_DOUBLE_TAP_MS;
        lastShiftTapMs = now;
        return second;
    }

    public void clearManualShift() {
        shifted = false;
        invalidate();
    }

    public void clearShiftLock() {
        shiftLocked = false;
        shifted = false;
        invalidate();
    }


    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isShifted() { return shifted; }
    public void setShifted(boolean s) { shifted = s; invalidate(); }

    public boolean isSymbols() { return symbols; }
    public boolean isSymbols2() { return symbols2; }
    public boolean isSymbols3() { return symbols3; }

    public void setPage(int page) {
        symbols = false;
        symbols2 = false;
        symbols3 = false;
        switch (page) {
            case 1: symbols = true; break;
            case 2: symbols2 = true; break;
            case 3: symbols3 = true; break;
            default: break;
        }
        shifted = false;
        shiftLocked = false;
        rebuildHitKeys();
        requestLayout();
        invalidate();
    }

    private List<BasicKey[]> rows() {
        if (symbols3) return layout.symbolPage3;
        if (symbols2) return layout.symbolPage2;
        if (symbols)  return layout.symbolPage1;
        return layout.letterRows;
    }

    private int rowCount() {
        return rows().size();
    }

    private int desiredHeightPx() {
        int rows = rowCount();
        float rowsHeight = rows * ROW_HEIGHT_DP * density;
        float top = TOP_PADDING_DP * density;
        float bottom = BOTTOM_PADDING_DP * density;
        return (int) (rowsHeight + top + bottom);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = desiredHeightPx();
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildHitKeys();
    }

    private void rebuildHitKeys() {
        hitKeys.clear();
        int rows = rowCount();
        if (rows == 0) return;

        float gap = GAP_DP * density;
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float top = TOP_PADDING_DP * density;
        float bottom = BOTTOM_PADDING_DP * density;
        float usableHeight = height - top - bottom;

        float rowHeight = (usableHeight - gap * (rows - 1)) / rows;

        for (int r = 0; r < rows; r++) {
            BasicKey[] row = rows().get(r);

            float sideInset = 0f;
            if (isAsdfRow(row)) {
                sideInset = ASDF_SIDE_PADDING_DP * density;
            }

            float totalWeight = 0f;
            for (BasicKey k : row) totalWeight += k.weight;

            float rowTop = top + r * (rowHeight + gap);
            float rowBottom = rowTop + rowHeight;

            float usableWidth = width - sideInset * 2 - gap * (row.length - 1);
            float x = sideInset;

            for (BasicKey k : row) {
                float keyWidth = usableWidth * (k.weight / totalWeight);
                RectF rect = new RectF(x, rowTop, x + keyWidth, rowBottom);
                hitKeys.add(new HitKey(k, rect));
                x += keyWidth + gap;
            }
        }
    }

    private boolean isAsdfRow(BasicKey[] row) {
        if (row == null || row.length != 9) return false;
        if (!"a".equals(row[0].lower)) return false;
        if (!"s".equals(row[1].lower)) return false;
        if (!"d".equals(row[2].lower)) return false;
        if (!"f".equals(row[3].lower)) return false;
        if (!"g".equals(row[4].lower)) return false;
        if (!"h".equals(row[5].lower)) return false;
        if (!"j".equals(row[6].lower)) return false;
        if (!"k".equals(row[7].lower)) return false;
        if (!"l".equals(row[8].lower)) return false;
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = KEY_RADIUS_DP * density;

        for (HitKey hk : hitKeys) {
            if (hk == pressed) {
                keyPaint.setColor(COLOR_KEY_PRESSED);
            } else {
                keyPaint.setColor(COLOR_KEY);
            }

            keyRect.set(hk.rect);
            canvas.drawRoundRect(keyRect, radius, radius, keyPaint);
            canvas.drawRoundRect(keyRect, radius, radius, borderPaint);

            String label;
            if (hk.key.code == BasicKey.CODE_SHIFT && shiftLocked) {
                label = "\u21EA";
            } else {
                label = hk.key.displayLabel(shifted || shiftLocked);
            }            if (label == null) label = "";

            boolean isSpecial = hk.key.code != BasicKey.CODE_NONE;
            textPaint.setTextSize((isSpecial ? SPECIAL_TEXT_SIZE_SP : TEXT_SIZE_SP) * scaledDensity);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float baseline = hk.rect.centerY() - (fm.ascent + textHeight / 2f);

            canvas.drawText(label, hk.rect.centerX(), baseline, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                HitKey hk = findKey(event.getX(), event.getY());
                if (hk != null) {
                    pressed = hk;
                    invalidate();
                    if (hk.key.code == BasicKey.CODE_DELETE) {
                        startRepeat(hk);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                HitKey hk = findKey(event.getX(), event.getY());
                if (hk != pressed) {
                    pressed = hk;
                    invalidate();
                    stopRepeat();
                    if (hk != null && hk.key.code == BasicKey.CODE_DELETE) {
                        startRepeat(hk);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                HitKey hk = pressed;
                pressed = null;
                invalidate();
                stopRepeat();
                if (hk != null && listener != null) {
                    dispatch(hk.key);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                pressed = null;
                invalidate();
                stopRepeat();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void startRepeat(HitKey hk) {
        stopRepeat();
        repeatKey = hk;
        repeatHandler.postDelayed(repeatRunnable, REPEAT_START_MS);
    }

    private void stopRepeat() {
        repeatKey = null;
        repeatHandler.removeCallbacks(repeatRunnable);
    }

    private HitKey findKey(float x, float y) {
        for (HitKey hk : hitKeys) {
            if (hk.rect.contains(x, y)) return hk;
        }
        return null;
    }

    private void dispatch(BasicKey key) {
        if (listener == null) return;

        if (key.code == BasicKey.CODE_SHIFT
                || key.code == BasicKey.CODE_DELETE
                || key.code == BasicKey.CODE_ENTER
                || key.code == BasicKey.CODE_SPACE
                || key.code == BasicKey.CODE_SYMBOLS
                || key.code == BasicKey.CODE_SYMBOLS_2
                || key.code == BasicKey.CODE_LEFT
                || key.code == BasicKey.CODE_RIGHT
                || key.code == BasicKey.CODE_SYM_PAGE_1
                || key.code == BasicKey.CODE_SYM_PAGE_2
                || key.code == BasicKey.CODE_SYM_PAGE_3
                || key.code == BasicKey.CODE_SYM_PAGE_4
                || key.code == BasicKey.CODE_ABC) {
            listener.onKey(key.code, null);
            return;
        }

        String text = key.commitText(shifted);
        listener.onKey(key.code, text);
    }

    private static final class HitKey {
        final BasicKey key;
        final RectF rect;
        HitKey(BasicKey key, RectF rect) {
            this.key = key;
            this.rect = rect;
        }
    }
}