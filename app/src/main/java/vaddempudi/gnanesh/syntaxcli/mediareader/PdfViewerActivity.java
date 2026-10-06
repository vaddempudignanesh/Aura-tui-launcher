package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vaddempudi.gnanesh.syntaxcli.filemanager.FileLog;

public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI = "pdf_uri";
    public static final String EXTRA_PDF_NAME = "pdf_name";

    private static final int PAGE_WIDTH_PX = 1240;
    private static final int PAGE_HEIGHT_PX = 1754;
    private static final int CACHE_BYTES = 24 * 1024 * 1024;  // 24 MB

    private RecyclerView recyclerPages;
    private ProgressBar progressBar;
    private TextView tvStatus;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pfd;
    private int pageCount = 0;

    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PdfPageAdapter adapter;

    private final LruCache<Integer, Bitmap> bitmapCache =
            new LruCache<Integer, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };

    private final Set<Integer> inFlightRenders =
            java.util.Collections.synchronizedSet(new HashSet<Integer>());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.i("PdfViewerActivity.onCreate");
        try {
            buildUi();
        } catch (Exception e) {
            FileLog.e("buildUi failed: " + e);
            Toast.makeText(this, "Cannot open PDF viewer", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        backgroundExecutor.execute(() -> {
            File pdfFile = resolvePdfFile();
            if (pdfFile == null) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            openPdfAsync(pdfFile);
            loadPdfTextAsync(pdfFile);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdownNow();
        closeRenderer();
        bitmapCache.evictAll();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        ZoomableFrameLayout zoomContainer = new ZoomableFrameLayout(this);

        recyclerPages = new RecyclerView(this);
        recyclerPages.setLayoutManager(new LinearLayoutManager(this));
        recyclerPages.setItemViewCacheSize(2);
        recyclerPages.setItemAnimator(null);
        recyclerPages.setHasFixedSize(true);
        adapter = new PdfPageAdapter();
        recyclerPages.setAdapter(adapter);

        zoomContainer.addView(recyclerPages, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(zoomContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.gravity = android.view.Gravity.CENTER;
        root.addView(progressBar, pp);

        tvStatus = new TextView(this);
        tvStatus.setText("Loading…");
        tvStatus.setTextColor(0xFF00AA00);
        tvStatus.setGravity(android.view.Gravity.CENTER);
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Nullable
    private File resolvePdfFile() {
        String path = getIntent().getStringExtra(EXTRA_PDF_URI);
        if (path != null) {
            File f = new File(path);
            if (f.exists() && f.canRead()) return f;
            try {
                Uri u = Uri.parse(path);
                if ("content".equals(u.getScheme())) return copyUriToCache(u);
            } catch (Exception ignored) {}
        }
        Uri data = getIntent().getData();
        if (data != null && "content".equals(data.getScheme())) {
            return copyUriToCache(data);
        }
        return null;
    }

    @Nullable
    private File copyUriToCache(Uri uri) {
        try {
            File outFile = new File(getCacheDir(),
                    "pdf_view_" + System.currentTimeMillis() + ".pdf");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                out.flush();
                return outFile;
            }
        } catch (Exception e) {
            FileLog.e("copyUriToCache failed: " + e);
            return null;
        }
    }

    private void openPdfAsync(File pdfFile) {
        try {
            pfd = ParcelFileDescriptor.open(pdfFile,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(pfd);
            pageCount = pdfRenderer.getPageCount();
            mainHandler.post(this::onPdfReady);
        } catch (Exception e) {
            FileLog.e("openPdfAsync failed: " + e);
            mainHandler.post(() -> showError("Failed to open PDF"));
        }
    }

    private void loadPdfTextAsync(File pdfFile) {
        try {
            PdfTextExtractor.Result r = PdfTextExtractor.extract(pdfFile);
            mainHandler.post(() -> FileLog.i("Text loaded: "
                    + (r != null ? r.pageCount : 0) + " pages"));
        } catch (Exception e) {
            FileLog.e("loadPdfTextAsync failed: " + e);
        }
    }

    private void onPdfReady() {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(pageCount + " pages");
        adapter.notifyDataSetChanged();
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(msg);
    }

    private void renderPageIfNeeded(final int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount || pdfRenderer == null) return;
        if (bitmapCache.get(pageIndex) != null) return;

        synchronized (inFlightRenders) {
            if (inFlightRenders.contains(pageIndex)) return;
            inFlightRenders.add(pageIndex);
        }

        backgroundExecutor.execute(() -> {
            Bitmap bmp = null;
            boolean ok = false;
            try {
                synchronized (PdfRenderer.class) {
                    if (pdfRenderer == null) return;
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    bmp = Bitmap.createBitmap(PAGE_WIDTH_PX, PAGE_HEIGHT_PX,
                            Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
                bitmapCache.put(pageIndex, bmp);
                ok = true;
                final int idx = pageIndex;
                mainHandler.post(() -> adapter.notifyItemChanged(idx));
            } catch (Exception e) {
                FileLog.e("Render failed for page " + pageIndex);
            } finally {
                if (!ok && bmp != null && !bmp.isRecycled()) {
                    bmp.recycle();
                }
                synchronized (inFlightRenders) {
                    inFlightRenders.remove(pageIndex);
                }
            }
        });
    }

    private void closeRenderer() {
        try { if (pdfRenderer != null) { pdfRenderer.close(); pdfRenderer = null; } }
        catch (Exception ignored) {}
        try { if (pfd != null) { pfd.close(); pfd = null; } }
        catch (Exception ignored) {}
    }

    private class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {
        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();

            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setBackgroundColor(Color.parseColor("#2C2C2C"));

            LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            int marginPx = (int) (12 * context.getResources().getDisplayMetrics().density);
            wrapperParams.setMargins(0, marginPx, 0, marginPx);
            wrapper.setLayoutParams(wrapperParams);

            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int calculatedHeight = (int) (screenWidth * ((float) PAGE_HEIGHT_PX / PAGE_WIDTH_PX));

            ImageView iv = new ImageView(context);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.WHITE);

            wrapper.addView(iv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, calculatedHeight));

            return new PageViewHolder(wrapper, iv);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            Bitmap cached = bitmapCache.get(position);
            if (cached != null && !cached.isRecycled()) {
                holder.imageView.setImageBitmap(cached);
            } else {
                holder.imageView.setImageDrawable(null);
                renderPageIfNeeded(position);
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            final ImageView imageView;
            PageViewHolder(@NonNull View itemView, ImageView imageView) {
                super(itemView);
                this.imageView = imageView;
            }
        }
    }

    /**
     * Zoom container.
     *  - transform applied at DRAW time only (Matrix + dispatchDraw)
     *  - no setScaleX/Y or setTranslationX/Y — those force re-layout
     *  - hardware layer set once, never toggled
     */
    private static class ZoomableFrameLayout extends FrameLayout {
        private static final float MAX_SCALE = 5.0f;

        private float scale = 1.0f;
        private float translationX = 0f;
        private float translationY = 0f;

        // Reused every frame — zero allocations.
        private final android.graphics.Matrix drawMatrix = new android.graphics.Matrix();

        private float lastTouchX, lastTouchY;
        private boolean isPanning = false;
        private boolean intercepting = false;

        private final ScaleGestureDetector scaleDetector;

        public ZoomableFrameLayout(@NonNull Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    intercepting = false;
                    lastTouchX = ev.getX();
                    lastTouchY = ev.getY();
                    return false;

                case MotionEvent.ACTION_POINTER_DOWN:
                    intercepting = true;
                    isPanning = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (scale > 1.01f && ev.getPointerCount() == 1) {
                        intercepting = true;
                        return true;
                    }
                    return intercepting;
            }
            return intercepting;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    isPanning = scale > 1.01f;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isPanning
                            && !scaleDetector.isInProgress()
                            && scale > 1.01f
                            && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        translationX += dx;
                        translationY += dy;
                        updateMatrix();
                        invalidate();     // draw only, no layout
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP: {
                    int idx = event.getActionIndex();
                    int remaining = (idx == 0) ? 1 : 0;
                    if (remaining < event.getPointerCount()) {
                        lastTouchX = event.getX(remaining);
                        lastTouchY = event.getY(remaining);
                        isPanning = scale > 1.01f;
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isPanning = false;
                    intercepting = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
            }
            return true;
        }

        private void updateMatrix() {
            drawMatrix.reset();
            drawMatrix.postScale(scale, scale);
            drawMatrix.postTranslate(translationX, translationY);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            if (scale == 1f && translationX == 0f && translationY == 0f) {
                super.dispatchDraw(canvas);
                return;
            }
            int save = canvas.save();
            canvas.concat(drawMatrix);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                isPanning = false;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float prevScale = scale;
                float newScale = prevScale * detector.getScaleFactor();
                newScale = Math.max(1.0f, Math.min(newScale, MAX_SCALE));

                if (newScale == prevScale) return false;

                float focalX = detector.getFocusX();
                float focalY = detector.getFocusY();

                translationX = focalX - (focalX - translationX) * (newScale / prevScale);
                translationY = focalY - (focalY - translationY) * (newScale / prevScale);

                scale = newScale;

                if (scale <= 1.0f) {
                    scale = 1.0f;
                    translationX = 0f;
                    translationY = 0f;
                }

                updateMatrix();
                invalidate();     // draw only — no measure, no layout
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                isPanning = scale > 1.01f;
            }
        }
    }
}