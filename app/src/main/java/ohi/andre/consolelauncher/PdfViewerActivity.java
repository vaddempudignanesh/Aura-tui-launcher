package ohi.andre.consolelauncher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native PDF viewer using Android's built-in {@link PdfRenderer}.
 *
 * No external dependencies required. Works on API 21+.
 *
 * Renders each PDF page to a Bitmap on a background thread and displays
 * them in a vertically scrolling RecyclerView with a black/green theme.
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI  = "pdf_uri";
    public static final String EXTRA_PDF_NAME = "pdf_name";

    // Rendering constants
    private static final int PAGE_WIDTH_PX       = 1240; // ~A4 at 150 DPI
    private static final int PAGE_HEIGHT_PX      = 1754;
    private static final int BITMAP_CACHE_KB     = 32 * 1024; // 32 MB

    // Views
    private RecyclerView recyclerPages;
    private ProgressBar progressBar;
    private TextView tvStatus;

    // Rendering
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pfd;
    private int pageCount = 0;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PdfPageAdapter adapter;

    // Bitmap cache to avoid re-rendering pages when scrolling
    private final LruCache<Integer, Bitmap> bitmapCache =
            new LruCache<Integer, Bitmap>(BITMAP_CACHE_KB) {
                @Override
                protected int sizeOf(@NonNull Integer key, @NonNull Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    // ==================== Lifecycle ====================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.i("PdfViewerActivity.onCreate");

        try {
            buildUi();
        } catch (Exception e) {
            FileLog.e("PdfViewerActivity.buildUi failed", e);
            Toast.makeText(this, "Cannot open PDF viewer", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File pdfFile = resolvePdfFile();
        if (pdfFile == null) {
            FileLog.e("PdfViewerActivity: no PDF file resolved");
            Toast.makeText(this, "PDF file not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FileLog.i("PdfViewerActivity: opening " + pdfFile.getAbsolutePath()
                + " size=" + pdfFile.length()
                + " readable=" + pdfFile.canRead());

        openPdfAsync(pdfFile);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        renderExecutor.shutdownNow();
        closeRenderer();
        bitmapCache.evictAll();
    }

    // ==================== UI Construction ====================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // --- Top bar ---
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
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // --- RecyclerView for pages ---
        recyclerPages = new RecyclerView(this);
        recyclerPages.setLayoutManager(new LinearLayoutManager(this));
        recyclerPages.setBackgroundColor(Color.BLACK);
        recyclerPages.setItemViewCacheSize(3);
        adapter = new PdfPageAdapter();
        recyclerPages.setAdapter(adapter);
        root.addView(recyclerPages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- Progress bar ---
        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        progParams.gravity = Gravity.CENTER;
        root.addView(progressBar, progParams);

        // --- Status text ---
        tvStatus = new TextView(this);
        tvStatus.setText("Loading PDF…");
        tvStatus.setTextColor(0xFF00AA00);
        tvStatus.setTextSize(12);
        tvStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvStatus.setPadding(24, 8, 24, 8);
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // ==================== URI resolution ====================
    @Nullable
    private File resolvePdfFile() {
        // Priority 1: EXTRA_PDF_URI as a raw filesystem path
        String path = getIntent().getStringExtra(EXTRA_PDF_URI);
        if (path != null) {
            FileLog.d("resolvePdfFile: trying EXTRA_PDF_URI=" + path);
            File f = new File(path);
            if (f.exists() && f.canRead()) return f;

            // Maybe it's a URI string
            try {
                Uri u = Uri.parse(path);
                if ("file".equals(u.getScheme())) {
                    File f2 = new File(u.getPath());
                    if (f2.exists() && f2.canRead()) return f2;
                }
                if ("content".equals(u.getScheme())) {
                    // Copy content URI to our cache
                    File cached = copyUriToCache(u);
                    if (cached != null) return cached;
                }
            } catch (Exception e) {
                FileLog.e("resolvePdfFile: parse error", e);
            }
        }

        // Priority 2: intent.getData()
        Uri data = getIntent().getData();
        if (data != null) {
            FileLog.d("resolvePdfFile: trying intent data=" + data);
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

    // ==================== PDF opening ====================
    private void openPdfAsync(File pdfFile) {
        renderExecutor.execute(() -> {
            try {
                FileLog.d("openPdfAsync: opening PFD for " + pdfFile);
                pfd = ParcelFileDescriptor.open(pdfFile,
                        ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(pfd);
                pageCount = pdfRenderer.getPageCount();
                FileLog.i("openPdfAsync: " + pageCount + " pages");

                mainHandler.post(this::onPdfReady);

            } catch (Exception e) {
                FileLog.e("openPdfAsync: failed", e);
                mainHandler.post(() ->
                        showError("Failed to open PDF: " + e.getMessage()));
            }
        });
    }


    @Nullable
    private File copyUriToCache(Uri uri) {
        FileLog.d("copyUriToCache: " + uri);
        try {
            if ("file".equals(uri.getScheme())) {
                File f = new File(uri.getPath());
                if (f.exists() && f.canRead()) return f;
            }

            File outFile = new File(getCacheDir(),
                    "pdf_view_" + System.currentTimeMillis() + ".pdf");
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    FileLog.e("copyUriToCache: openInputStream returned null");
                    return null;
                }
                out = new FileOutputStream(outFile);
                byte[] buf = new byte[64 * 1024];
                int len;
                long total = 0;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                    total += len;
                }
                out.flush();
                FileLog.i("copyUriToCache: copied " + total + " bytes");
                return outFile;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) { }
                try { if (out != null) out.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            FileLog.e("copyUriToCache failed", e);
            return null;
        }
    }

    private void onPdfReady() {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(pageCount + (pageCount == 1 ? " page" : " pages"));
        adapter.notifyDataSetChanged();

        // Kick off rendering of the first page immediately
        renderPageIfNeeded(0);
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ==================== Page rendering ====================

    private void renderPageIfNeeded(int pageIndex) {
        if (bitmapCache.get(pageIndex) != null) return;
        if (pdfRenderer == null) return;
        if (pageIndex < 0 || pageIndex >= pageCount) return;

        renderExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                synchronized (PdfRenderer.class) {
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    bmp = Bitmap.createBitmap(PAGE_WIDTH_PX, PAGE_HEIGHT_PX,
                            Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }

                final Bitmap result = bmp;
                bitmapCache.put(pageIndex, result);
                mainHandler.post(() -> {
                    adapter.notifyItemChanged(pageIndex);
                });
            } catch (Exception e) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
        });
    }

    private void closeRenderer() {
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
                pdfRenderer = null;
            }
        } catch (Exception ignored) { }
        try {
            if (pfd != null) {
                pfd.close();
                pfd = null;
            }
        } catch (Exception ignored) { }
    }

    // ==================== RecyclerView adapter ====================

    private class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout wrapper = new LinearLayout(PdfViewerActivity.this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setBackgroundColor(Color.BLACK);
            wrapper.setPadding(8, 16, 8, 16);

            ImageView iv = new ImageView(PdfViewerActivity.this);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.WHITE);
            wrapper.addView(iv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvPageNum = new TextView(PdfViewerActivity.this);
            tvPageNum.setTextColor(0xFF00AA00);
            tvPageNum.setTextSize(11);
            tvPageNum.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvPageNum.setGravity(Gravity.CENTER);
            tvPageNum.setPadding(0, 8, 0, 0);
            wrapper.addView(tvPageNum, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            return new PageViewHolder(wrapper, iv, tvPageNum);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            holder.tvPageNum.setText("Page " + (position + 1) + " / " + pageCount);

            Bitmap cached = bitmapCache.get(position);
            if (cached != null) {
                holder.imageView.setImageBitmap(cached);
            } else {
                // Placeholder
                holder.imageView.setImageBitmap(null);
                holder.imageView.setMinimumHeight(600);
                renderPageIfNeeded(position);
            }
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            final ImageView imageView;
            final TextView tvPageNum;

            PageViewHolder(@NonNull View itemView, ImageView iv, TextView tv) {
                super(itemView);
                this.imageView = iv;
                this.tvPageNum = tv;
            }
        }
    }
}