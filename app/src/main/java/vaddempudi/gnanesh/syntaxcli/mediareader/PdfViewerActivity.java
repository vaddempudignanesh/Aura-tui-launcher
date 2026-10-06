package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
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
        root.setBackgroundColor(Color.parseColor("#121212")); // Dark background for contrast

        recyclerPages = new RecyclerView(this);
        recyclerPages.setLayoutManager(new LinearLayoutManager(this));
        recyclerPages.setItemViewCacheSize(2);
        recyclerPages.setItemAnimator(null);
        recyclerPages.setHasFixedSize(true);
        adapter = new PdfPageAdapter();
        recyclerPages.setAdapter(adapter);
        root.addView(recyclerPages, new LinearLayout.LayoutParams(
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

            // Outer wrapper with top/bottom margins to create space & border separation between pages
            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setBackgroundColor(Color.parseColor("#2C2C2C")); // Border/margin gap color

            LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            int marginPx = (int) (12 * context.getResources().getDisplayMetrics().density); // ~12dp spacing
            wrapperParams.setMargins(0, marginPx, 0, marginPx);
            wrapper.setLayoutParams(wrapperParams);

            // Calculate dynamic height based on screen width and PDF aspect ratio (1754/1240)
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int calculatedHeight = (int) (screenWidth * ((float) PAGE_HEIGHT_PX / PAGE_WIDTH_PX));

            ZoomableImageView iv = new ZoomableImageView(context);
            iv.setScaleType(ImageView.ScaleType.MATRIX);
            iv.setBackgroundColor(Color.WHITE);

            wrapper.addView(iv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, calculatedHeight));

            return new PageViewHolder(wrapper, iv);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            Bitmap cached = bitmapCache.get(position);
            holder.imageView.resetZoom();
            if (cached != null && !cached.isRecycled()) {
                holder.imageView.setImageBitmap(cached);
                holder.imageView.setupMatrix();
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
            final ZoomableImageView imageView;
            PageViewHolder(@NonNull View itemView, ZoomableImageView imageView) {
                super(itemView);
                this.imageView = imageView;
            }
        }
    }

    /**
     * Custom ImageView supporting pinch-to-zoom and pan gestures per page.
     */
    private static class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {
        private final Matrix matrix = new Matrix();
        private float scale = 1f;
        private static final float MIN_SCALE = 1f;
        private static final float MAX_SCALE = 5f;

        private final ScaleGestureDetector scaleDetector;
        private float lastX, lastY;
        private boolean isDragging = false;

        public ZoomableImageView(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        }

        public void resetZoom() {
            scale = 1f;
            matrix.reset();
            setImageMatrix(matrix);
        }

        public void setupMatrix() {
            if (getDrawable() == null) return;
            // Fit center initialization inside matrix
            float dWidth = getDrawable().getIntrinsicWidth();
            float dHeight = getDrawable().getIntrinsicHeight();
            float vWidth = getWidth();
            float vHeight = getHeight();

            if (vWidth <= 0 || vHeight <= 0) return;

            float scaleX = vWidth / dWidth;
            float scaleY = vHeight / dHeight;
            float initialScale = Math.min(scaleX, scaleY);

            matrix.reset();
            matrix.setScale(initialScale, initialScale);

            // Center the bitmap
            float redundantX = (vWidth - (dWidth * initialScale)) / 2f;
            float redundantY = (vHeight - (dHeight * initialScale)) / 2f;
            matrix.postTranslate(redundantX, redundantY);

            setImageMatrix(matrix);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            setupMatrix();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    isDragging = true;
                    getParent().requestDisallowInterceptTouchEvent(scale > 1f);
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (isDragging && scale > 1f) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        setImageMatrix(matrix);
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return true;
        }

        private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                float prevScale = scale;
                scale *= scaleFactor;

                if (scale < MIN_SCALE) {
                    scale = MIN_SCALE;
                    scaleFactor = scale / prevScale;
                } else if (scale > MAX_SCALE) {
                    scale = MAX_SCALE;
                    scaleFactor = scale / prevScale;
                }

                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                setImageMatrix(matrix);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
    }
}