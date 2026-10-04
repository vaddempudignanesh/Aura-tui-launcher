package vaddempudi.gnanesh.syntaxcli.mediareader;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vaddempudi.gnanesh.syntaxcli.filemanager.FileLog;
import vaddempudi.gnanesh.syntaxcli.R;

public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI = "pdf_uri";
    public static final String EXTRA_PDF_NAME = "pdf_name";

    private File lastOpenedFile;

    private PdfTextExtractor.Result extractedText;

    private int searchHighlightPage = -1;

    private final java.util.List<Integer> matchPages = new java.util.ArrayList<>();

    private int matchCursor = -1;

    private static final int PAGE_WIDTH_PX = 1240;
    private static final int PAGE_HEIGHT_PX = 1754;

    private RecyclerView recyclerPages;
    private ProgressBar progressBar;
    private TextView tvStatus;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pfd;
    private int pageCount = 0;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService textExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PdfPageAdapter adapter;
    private LinearLayoutManager layoutManager;

    private final SparseArray<Bitmap> bitmapCache = new SparseArray<>();

    private final java.util.Set<Integer> inFlightRenders =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

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
        File pdfFile = resolvePdfFile();
        if (pdfFile == null) {
            FileLog.e("no PDF file resolved");
            Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        lastOpenedFile = pdfFile;
        openPdfAsync(pdfFile);
        loadPdfTextAsync(pdfFile);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        renderExecutor.shutdownNow();
        textExecutor.shutdownNow();
        closeRenderer();
        synchronized (bitmapCache) {
            for (int i = 0; i < bitmapCache.size(); i++) {
                Bitmap b = bitmapCache.valueAt(i);
                if (b != null && !b.isRecycled()) b.recycle();
            }
            bitmapCache.clear();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(0xFF001100);
        topBar.setPadding(16, 16, 16, 16);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setColorFilter(0xFF00FF00);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(48, 48));

        TextView tvTitle = new TextView(this);
        String name = getIntent().getStringExtra(EXTRA_PDF_NAME);
        tvTitle.setText(name != null ? name : "PDF Viewer");
        tvTitle.setTextColor(0xFF00FF00);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvTitle.setPadding(24, 0, 0, 0);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setBackgroundColor(0xFF001100);
        searchBar.setPadding(16, 8, 16, 8);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);

        final EditText searchInput = new EditText(this);
        searchInput.setHint("Type to search…");
        searchInput.setHintTextColor(0xFF00AA00);
        searchInput.setTextColor(0xFF00FF00);
        searchInput.setBackgroundColor(0xFF002200);
        searchInput.setPadding(16, 8, 16, 8);
        searchInput.setSingleLine(true);
        searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnNext = new Button(this);
        btnNext.setText("NEXT");
        btnNext.setTextColor(0xFF00FF00);
        btnNext.setBackgroundColor(0xFF003300);
        btnNext.setTextSize(11);
        btnNext.setTypeface(android.graphics.Typeface.MONOSPACE);
        searchBar.addView(btnNext);

        Button btnClear = new Button(this);
        btnClear.setText("CLEAR");
        btnClear.setTextColor(0xFFFF8888);
        btnClear.setBackgroundColor(0xFF003300);
        btnClear.setTextSize(11);
        btnClear.setTypeface(android.graphics.Typeface.MONOSPACE);
        searchBar.addView(btnClear);

        root.addView(searchBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        recyclerPages = new RecyclerView(this);
        layoutManager = new LinearLayoutManager(this);
        recyclerPages.setLayoutManager(layoutManager);
        recyclerPages.setBackgroundColor(Color.BLACK);
        recyclerPages.setItemViewCacheSize(4);
        recyclerPages.setItemAnimator(null);
        adapter = new PdfPageAdapter();
        recyclerPages.setAdapter(adapter);
        root.addView(recyclerPages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progParams.gravity = Gravity.CENTER;
        root.addView(progressBar, progParams);

        tvStatus = new TextView(this);
        tvStatus.setText("Loading PDF…");
        tvStatus.setTextColor(0xFF00AA00);
        tvStatus.setTextSize(12);
        tvStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvStatus.setPadding(24, 8, 24, 8);
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        final String[] lastQuery = {""};

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim();
                if (q.equals(lastQuery[0])) return;
                lastQuery[0] = q;
                runSearch(q, false);
            }
        });

        btnNext.setOnClickListener(v -> {
            String q = searchInput.getText().toString().trim();
            if (q.isEmpty()) return;
            runSearch(q, true);
        });

        btnClear.setOnClickListener(v -> {
            searchInput.setText("");
            lastQuery[0] = "";
            clearSearchHighlight();
        });
    }

    @Nullable
    private File resolvePdfFile() {
        String path = getIntent().getStringExtra(EXTRA_PDF_URI);
        if (path != null) {
            File f = new File(path);
            if (f.exists() && f.canRead()) return f;
            try {
                Uri u = Uri.parse(path);
                if ("file".equals(u.getScheme())) {
                    File f2 = new File(u.getPath());
                    if (f2.exists() && f2.canRead()) return f2;
                }
                if ("content".equals(u.getScheme())) {
                    File cached = copyUriToCache(u);
                    if (cached != null) return cached;
                }
            } catch (Exception ignored) {}
        }
        Uri data = getIntent().getData();
        if (data != null) {
            if ("file".equals(data.getScheme())) {
                File f = new File(data.getPath());
                if (f.exists() && f.canRead()) return f;
            }
            if ("content".equals(data.getScheme())) {
                return copyUriToCache(data);
            }
        }
        return null;
    }

    @Nullable
    private File copyUriToCache(Uri uri) {
        try {
            File outFile = new File(getCacheDir(), "pdf_view_" + System.currentTimeMillis() + ".pdf");
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) return null;
                out = new FileOutputStream(outFile);
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                out.flush();
                return outFile;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            FileLog.e("copyUriToCache failed: " + e);
            return null;
        }
    }

    private void openPdfAsync(File pdfFile) {
        renderExecutor.execute(() -> {
            try {
                pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(pfd);
                pageCount = pdfRenderer.getPageCount();
                mainHandler.post(this::onPdfReady);
            } catch (Exception e) {
                FileLog.e("openPdfAsync failed: " + e);
                mainHandler.post(() -> showError("Failed to open PDF: " + e.getMessage()));
            }
        });
    }

    private void loadPdfTextAsync(File pdfFile) {
        textExecutor.execute(() -> {
            try {
                PdfTextExtractor.Result r = PdfTextExtractor.extract(pdfFile);
                mainHandler.post(() -> {
                    extractedText = r;
                    FileLog.i("loadPdfTextAsync: " + (r == null ? 0 : r.fullText.length())
                            + " chars, " + (r == null ? 0 : r.pageCount) + " pages");
                });
            } catch (Exception e) {
                FileLog.e("loadPdfTextAsync failed: " + e);
            }
        });
    }

    private void onPdfReady() {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(pageCount + (pageCount == 1 ? " page" : " pages"));
        adapter.notifyDataSetChanged();
        for (int i = 0; i < Math.min(3, pageCount); i++) renderPageIfNeeded(i);
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(msg);
    }

    private void runSearch(String query, boolean advance) {
        if (query.isEmpty()) {
            clearSearchHighlight();
            return;
        }
        if (extractedText == null) {
            final String q = query;
            final boolean adv = advance;
            mainHandler.postDelayed(() -> runSearch(q, adv), 200);
            return;
        }
        if (extractedText.fullText == null || extractedText.fullText.isEmpty()) {
            tvStatus.setText("No searchable text in this PDF");
            return;
        }
        final String needle = query.toLowerCase(java.util.Locale.US);
        final String haystack = extractedText.fullText.toLowerCase(java.util.Locale.US);

        if (matchPages.isEmpty() || !advance) {
            matchPages.clear();
            matchCursor = -1;
            int idx = 0;
            while (true) {
                idx = haystack.indexOf(needle, idx);
                if (idx < 0) break;
                int page = pageForOffset(idx);
                if (matchPages.isEmpty() || matchPages.get(matchPages.size() - 1) != page) {
                    matchPages.add(page);
                }
                idx += Math.max(1, needle.length());
            }
        }

        if (matchPages.isEmpty()) {
            searchHighlightPage = -1;
            adapter.notifyDataSetChanged();
            tvStatus.setText("Not found • " + pageCount + " pages");
            return;
        }

        matchCursor = advance ? (matchCursor + 1) % matchPages.size() : 0;
        int page = matchPages.get(matchCursor);
        searchHighlightPage = page;
        renderPageIfNeeded(page);
        centerPage(page);
        adapter.notifyItemChanged(page);
        tvStatus.setText((matchCursor + 1) + " / " + matchPages.size()
                + "  •  page " + (page + 1) + " / " + pageCount);
    }

    private int pageForOffset(int offset) {
        if (extractedText == null || extractedText.pageOffsets == null
                || extractedText.pageOffsets.length == 0) return 0;
        int bestPage = 0;
        for (int i = 0; i < extractedText.pageOffsets.length; i++) {
            if (extractedText.pageOffsets[i] <= offset) bestPage = i;
            else break;
        }
        return Math.min(bestPage, Math.max(0, pageCount - 1));
    }

    private void centerPage(int pageIndex) {
        if (recyclerPages == null || layoutManager == null) return;
        renderPageIfNeeded(pageIndex);
        recyclerPages.scrollToPosition(pageIndex);
        recyclerPages.post(() -> {
            View v = layoutManager.findViewByPosition(pageIndex);
            if (v == null) return;
            int viewportH = recyclerPages.getHeight();
            int pageTop = v.getTop();
            int pageBottom = v.getBottom();
            int pageCenter = (pageTop + pageBottom) / 2;
            int delta = pageCenter - viewportH / 2;
            recyclerPages.smoothScrollBy(0, delta);
        });
    }

    private void clearSearchHighlight() {
        searchHighlightPage = -1;
        matchCursor = -1;
        matchPages.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tvStatus != null) {
            tvStatus.setText(pageCount + (pageCount == 1 ? " page" : " pages"));
        }
    }

    private void renderPageIfNeeded(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageCount) return;
        if (pdfRenderer == null) return;
        synchronized (bitmapCache) {
            if (bitmapCache.get(pageIndex) != null) return;
        }
        synchronized (inFlightRenders) {
            if (inFlightRenders.contains(pageIndex)) return;
            inFlightRenders.add(pageIndex);
        }
        renderExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                synchronized (PdfRenderer.class) {
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    bmp = Bitmap.createBitmap(PAGE_WIDTH_PX, PAGE_HEIGHT_PX, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
                final Bitmap result = bmp;
                synchronized (bitmapCache) {
                    bitmapCache.put(pageIndex, result);
                }
                mainHandler.post(() -> {
                    if (adapter != null) adapter.notifyItemChanged(pageIndex);
                });
            } catch (Exception e) {
                FileLog.e("renderPageIfNeeded failed page " + pageIndex + ": " + e);
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            } finally {
                synchronized (inFlightRenders) {
                    inFlightRenders.remove(pageIndex);
                }
            }
        });
    }

    private void closeRenderer() {
        try { if (pdfRenderer != null) { pdfRenderer.close(); pdfRenderer = null; } } catch (Exception ignored) {}
        try { if (pfd != null) { pfd.close(); pfd = null; } } catch (Exception ignored) {}
    }

    private class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout wrapper = new LinearLayout(PdfViewerActivity.this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setBackgroundColor(Color.BLACK);
            wrapper.setPadding(8, 16, 8, 16);

            FrameLayout pageContainer = new FrameLayout(PdfViewerActivity.this);
            pageContainer.setBackgroundColor(Color.WHITE);

            ZoomableImageView iv = new ZoomableImageView(PdfViewerActivity.this);
            iv.setBackgroundColor(Color.WHITE);
            iv.setClickable(true);
            iv.setFocusable(true);

            // Fixed height so the ZoomableImageView always has a non-zero
            // measured size (bitmap is always 1240 x 1754). Without this,
            // short pages get a tiny WRAP_CONTENT height and baseScale stays 0,
            // which silently kills all gesture handling.
            int screenW = getResources().getDisplayMetrics().widthPixels - dp(16); // - padding
            int fixedH = (int) (screenW * (PAGE_HEIGHT_PX / (float) PAGE_WIDTH_PX));

            pageContainer.addView(iv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    fixedH));

            View highlightOverlay = new View(PdfViewerActivity.this);
            highlightOverlay.setVisibility(View.GONE);
            GradientDrawable highlightDrawable = new GradientDrawable();
            highlightDrawable.setColor(0x18FFD700);
            highlightDrawable.setStroke(dp(4), 0xFFFFD700);
            highlightOverlay.setBackground(highlightDrawable);
            FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            overlayParams.gravity = Gravity.CENTER;
            pageContainer.addView(highlightOverlay, overlayParams);

            wrapper.addView(pageContainer, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    fixedH));

            TextView tvPageNum = new TextView(PdfViewerActivity.this);
            tvPageNum.setTextColor(0xFF00AA00);
            tvPageNum.setTextSize(11);
            tvPageNum.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvPageNum.setGravity(Gravity.CENTER);
            tvPageNum.setPadding(0, 8, 0, 0);
            wrapper.addView(tvPageNum, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            return new PageViewHolder(wrapper, pageContainer, iv, highlightOverlay, tvPageNum);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            holder.tvPageNum.setText("Page " + (position + 1) + " / " + pageCount);
            boolean highlighted = position == searchHighlightPage;
            if (highlighted) {
                holder.highlightOverlay.setVisibility(View.VISIBLE);
                holder.tvPageNum.setTextColor(0xFFFFD700);
                holder.itemView.setBackgroundColor(0xFF221100);
            } else {
                holder.highlightOverlay.setVisibility(View.GONE);
                holder.tvPageNum.setTextColor(0xFF00AA00);
                holder.itemView.setBackgroundColor(Color.BLACK);
            }
            Bitmap cached;
            synchronized (bitmapCache) {
                cached = bitmapCache.get(position);
            }
            if (cached != null && !cached.isRecycled()) {
                holder.imageView.setImageBitmap(cached);
            } else {
                holder.imageView.setImageBitmap(null);
                renderPageIfNeeded(position);
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            final FrameLayout pageContainer;
            final ZoomableImageView imageView;
            final View highlightOverlay;
            final TextView tvPageNum;

            PageViewHolder(@NonNull View itemView, FrameLayout pageContainer,
                           ZoomableImageView imageView, View highlightOverlay, TextView tvPageNum) {
                super(itemView);
                this.pageContainer = pageContainer;
                this.imageView = imageView;
                this.highlightOverlay = highlightOverlay;
                this.tvPageNum = tvPageNum;
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public static class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {

        private static final float MAX_SCALE = 8.0f;

        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final float[] matrixValues = new float[9];

        private android.view.ScaleGestureDetector scaleDetector;
        private android.view.GestureDetector gestureDetector;

        private float scaleFactor = 1.0f;
        private float baseScale = 1.0f;
        private float lastTouchX, lastTouchY;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private boolean isDragging = false;
        private boolean isZooming = false;

        private android.graphics.drawable.Drawable lastDrawable = null;
        private int lastDrawableWidth = 0;
        private int lastDrawableHeight = 0;

        public ZoomableImageView(android.content.Context context) {
            super(context);
            init(context);
        }

        public ZoomableImageView(android.content.Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
            init(context);
        }

        public ZoomableImageView(android.content.Context context, android.util.AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
            init(context);
        }

        private void init(android.content.Context context) {
            super.setClickable(true);
            super.setLongClickable(true);
            setScaleType(ScaleType.MATRIX);

            scaleDetector = new android.view.ScaleGestureDetector(context,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                            isZooming = true;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }

                        @Override
                        public boolean onScale(android.view.ScaleGestureDetector detector) {
                            if (baseScale <= 0f) return false;
                            float factor = detector.getScaleFactor();
                            float newScale = scaleFactor * factor;
                            newScale = Math.max(baseScale, Math.min(MAX_SCALE, newScale));
                            float applied = newScale / scaleFactor;
                            scaleFactor = newScale;
                            matrix.postScale(applied, applied, detector.getFocusX(), detector.getFocusY());
                            constrain();
                            setImageMatrix(matrix);
                            invalidate();
                            return true;
                        }

                        @Override
                        public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                            isZooming = false;
                            if (scaleFactor <= baseScale + 0.01f) {
                                applyFitMatrix();
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }
                        }
                    });

            gestureDetector = new android.view.GestureDetector(context,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (baseScale <= 0f) return false;
                            if (scaleFactor > baseScale + 0.01f) {
                                scaleFactor = baseScale;
                                applyFitMatrix();
                            } else {
                                float target = Math.min(MAX_SCALE, baseScale * 2.5f);
                                float applied = target / scaleFactor;
                                scaleFactor = target;
                                matrix.postScale(applied, applied, e.getX(), e.getY());
                                constrain();
                                setImageMatrix(matrix);
                                invalidate();
                            }
                            return true;
                        }
                    });
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            post(this::applyFitMatrixIfNeeded);
        }

        @Override
        public void setImageDrawable(android.graphics.drawable.Drawable drawable) {
            super.setImageDrawable(drawable);
            post(this::applyFitMatrixIfNeeded);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            post(this::applyFitMatrixIfNeeded);
        }

        @Override
        public void setImageMatrix(android.graphics.Matrix m) {
            if (m != null && m.isIdentity()) {
                m = null;
            }
            super.setImageMatrix(m);
        }

        private void applyFitMatrixIfNeeded() {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d == null) return;
            int dw = d.getIntrinsicWidth();
            int dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;

            // View not measured yet (short page / WRAP_CONTENT parent). Retry.
            if (getWidth() == 0 || getHeight() == 0) {
                post(this::applyFitMatrixIfNeeded);
                return;
            }

            if (lastDrawable == d && lastDrawableWidth == dw && lastDrawableHeight == dh
                    && baseScale > 0f && scaleFactor > baseScale + 0.001f) {
                return;
            }
            lastDrawable = d;
            lastDrawableWidth = dw;
            lastDrawableHeight = dh;
            applyFitMatrix();
        }

        private void applyFitMatrix() {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || getHeight() == 0) return;

            float vw = getWidth();
            float vh = getHeight();
            float dw = d.getIntrinsicWidth();
            float dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;

            float scale = Math.min(vw / dw, vh / dh);
            baseScale = scale;
            scaleFactor = scale;

            matrix.reset();
            matrix.postScale(scale, scale);
            float dx = (vw - dw * scale) * 0.5f;
            float dy = (vh - dh * scale) * 0.5f;
            matrix.postTranslate(dx, dy);

            setImageMatrix(matrix);
            invalidate();
        }

        private void constrain() {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d == null) return;

            matrix.getValues(matrixValues);

            float transX = matrixValues[android.graphics.Matrix.MTRANS_X];
            float transY = matrixValues[android.graphics.Matrix.MTRANS_Y];
            float scaleX = matrixValues[android.graphics.Matrix.MSCALE_X];
            float scaleY = matrixValues[android.graphics.Matrix.MSCALE_Y];

            float drawableW = d.getIntrinsicWidth() * scaleX;
            float drawableH = d.getIntrinsicHeight() * scaleY;

            float viewW = getWidth();
            float viewH = getHeight();

            float deltaX = 0, deltaY = 0;

            if (drawableW <= viewW) {
                deltaX = (viewW - drawableW) / 2 - transX;
            } else {
                if (transX > 0) deltaX = -transX;
                else if (transX + drawableW < viewW) deltaX = viewW - (transX + drawableW);
            }

            if (drawableH <= viewH) {
                deltaY = (viewH - drawableH) / 2 - transY;
            } else {
                if (transY > 0) deltaY = -transY;
                else if (transY + drawableH < viewH) deltaY = viewH - (transY + drawableH);
            }

            if (deltaX != 0 || deltaY != 0) {
                matrix.postTranslate(deltaX, deltaY);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    activePointerId = event.getPointerId(0);
                    isDragging = false;
                    if (scaleFactor > baseScale + 0.01f) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (event.getPointerCount() > 1) break;
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex < 0) break;

                    float x = event.getX(pointerIndex);
                    float y = event.getY(pointerIndex);
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;

                    if (scaleFactor > baseScale + 0.01f) {
                        matrix.postTranslate(dx, dy);
                        constrain();
                        setImageMatrix(matrix);
                        isDragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    isDragging = false;
                    isZooming = false;
                    if (scaleFactor <= baseScale + 0.01f) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    // Second finger down → this is a pinch. Block RecyclerView
                    // from stealing the gesture, otherwise short pages never zoom.
                    getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    int pointerIndex = event.getActionIndex();
                    int pointerId = event.getPointerId(pointerIndex);
                    if (pointerId == activePointerId) {
                        int newIndex = pointerIndex == 0 ? 1 : 0;
                        lastTouchX = event.getX(newIndex);
                        lastTouchY = event.getY(newIndex);
                        activePointerId = event.getPointerId(newIndex);
                    }
                    break;
                }
            }
            return true;
        }
    }
}