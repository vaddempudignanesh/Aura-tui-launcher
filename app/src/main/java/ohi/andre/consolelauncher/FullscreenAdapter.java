package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FullscreenAdapter extends RecyclerView.Adapter<FullscreenAdapter.ViewHolder> {

    private static final String LOG_TAG = "gallery-tui";
    private final String idHash = Integer.toHexString(System.identityHashCode(this));
    private String src() { return "[FullscreenAdapter:" + idHash + "]"; }

    public interface TapCallback { void onTap(); }
    public interface PageTypeCallback {
        void onImageVisible(int position);
        void onVideoVisible(CustomVideoView videoView, int position);
    }

    private final List<String> paths;
    private final Context context;
    private TapCallback tapCallback;
    private PageTypeCallback pageTypeCallback;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public FullscreenAdapter(List<String> paths, Context context) {
        this.paths = paths;
        this.context = context;
        Log.d(LOG_TAG, src() + " constructor called, context=" + context.getClass().getName());
    }

    public void setTapCallback(TapCallback cb) {
        this.tapCallback = cb;
    }

    public void setPageTypeCallback(PageTypeCallback cb) {
        this.pageTypeCallback = cb;
    }

    private void log(String msg) { Log.d(LOG_TAG, src() + " " + msg); }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ProgressBar progressBar;
        public final ZoomableImageView imageView;
        public final CustomVideoView videoView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            progressBar = itemView.findViewById(R.id.fullscreen_progress);
            imageView = itemView.findViewById(R.id.fullscreen_image);
            videoView = itemView.findViewById(R.id.fullscreen_video);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_fullscreen_media, parent, false);
        log("onCreateViewHolder");
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final int boundPosition = position;
        final String path = paths.get(position);

        log("onBind pos=" + boundPosition + " path=" + path);

        // ★ Get the current path this video view holds BEFORE we touch it.
        String previousVideoPath = null;
        try { previousVideoPath = holder.videoView.getVideoPath(); } catch (Exception ignored) {}

        // Always reset visibility
        holder.videoView.setVisibility(View.GONE);
        holder.imageView.setVisibility(View.GONE);
        holder.progressBar.setVisibility(View.VISIBLE);

        if (isVideoPath(path)) {
            log("  → VIDEO page (previous=" + previousVideoPath + ")");
            holder.videoView.setVisibility(View.VISIBLE);

            // ★ Only stop playback if the view is being reused for a different path
            boolean pathChanged = previousVideoPath == null
                    || !previousVideoPath.equals(path);
            if (pathChanged) {
                log("  path changed → stopping old playback");
                try { holder.videoView.stopPlayback(); } catch (Exception ignored) {}
            } else {
                log("  same path → keeping playback state");
            }

            // Clear old listener before setting new one
            holder.videoView.setOnPreparedListener(null);

            holder.videoView.setOnPreparedListener(mp -> {
                int adapterPosition = holder.getBindingAdapterPosition();
                log("  adapter.onPrepared pos=" + boundPosition
                        + " currentAdapterPos=" + adapterPosition);
                if (adapterPosition != boundPosition
                        || boundPosition < 0 || boundPosition >= paths.size()
                        || !path.equals(paths.get(boundPosition))) {
                    log("  → rejected (stale)");
                    return;
                }

                holder.progressBar.setVisibility(View.GONE);

                if (pageTypeCallback != null) {
                    log("  → dispatching onVideoVisible pos=" + boundPosition);
                    pageTypeCallback.onVideoVisible(holder.videoView, boundPosition);
                }
            });

            // Forward taps from the video surface to the activity
            holder.videoView.setOnTapListener(() -> {
                if (tapCallback != null) tapCallback.onTap();
            });

            holder.videoView.setOnErrorListener((mp, what, extra) -> {
                log("  adapter.onError what=" + what + " extra=" + extra);
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition == boundPosition) holder.progressBar.setVisibility(View.GONE);
                return true;
            });

            // setVideoPath is idempotent now — it skips if path is unchanged
            try {
                log("  calling videoView.setVideoPath");
                holder.videoView.setVideoPath(path);
            } catch (Exception e) {
                Log.e(LOG_TAG, src() + " setVideoPath threw", e);
                holder.progressBar.setVisibility(View.GONE);
            }
            return;
        }

        // IMAGE
        log("  → IMAGE page");

        // If this view was previously a video, stop it
        if (previousVideoPath != null) {
            log("  recycled video view → stopping");
            try { holder.videoView.stopPlayback(); } catch (Exception ignored) {}
            holder.videoView.setOnPreparedListener(null);
            holder.videoView.setOnTapListener(null);
        }

        holder.imageView.setImageDrawable(null);
        holder.imageView.setTag(path);

        executor.execute(() -> {
            Bitmap bmp = decodeSampled(path);
            holder.itemView.post(() -> {
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition != boundPosition
                        || boundPosition < 0 || boundPosition >= paths.size()
                        || !path.equals(paths.get(boundPosition))) {
                    log("  image decode stale for pos=" + boundPosition);
                    return;
                }

                holder.progressBar.setVisibility(View.GONE);
                holder.imageView.setVisibility(View.VISIBLE);

                if (bmp != null) {
                    holder.imageView.setImageBitmap(bmp);
                } else {
                    holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                }

                holder.imageView.setOnTapListener(() -> {
                    if (tapCallback != null) tapCallback.onTap();
                });

                if (pageTypeCallback != null) {
                    log("  → dispatching onImageVisible pos=" + boundPosition);
                    pageTypeCallback.onImageVisible(boundPosition);
                }
            });
        });
    }

    private static boolean isVideoPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    private Bitmap decodeSampled(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int reqW = 1440, reqH = 2560, scale = 1;
            while ((o.outWidth / scale) > reqW * 2 && (o.outHeight / scale) > reqH * 2) scale *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Exception e) { return null; }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        log("onViewRecycled");
        try { holder.videoView.stopPlayback(); } catch (Exception ignored) {}
        holder.videoView.setVisibility(View.GONE);
        holder.videoView.setOnPreparedListener(null);
        holder.videoView.setOnTapListener(null);
        holder.imageView.setVisibility(View.GONE);
        holder.imageView.setImageDrawable(null);
        holder.progressBar.setVisibility(View.GONE);
        super.onViewRecycled(holder);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        log("onDetachedFromRecyclerView — shutting down executor");
        executor.shutdownNow();
    }

    @Override
    public int getItemCount() { return paths != null ? paths.size() : 0; }
}