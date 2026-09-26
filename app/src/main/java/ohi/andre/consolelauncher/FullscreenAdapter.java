package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FullscreenAdapter — pages through a list of media paths inside a ViewPager2.
 *
 * Exposes:
 *   - ViewHolder with public imageView + videoView
 *   - global scale mode shared by all pages
 *   - zoom toggle helpers used by FullscreenViewerActivity
 *   - a tap callback so the parent activity can toggle its chrome
 */
public class FullscreenAdapter extends RecyclerView.Adapter<FullscreenAdapter.ViewHolder> {

    // ── Scale modes ────────────────────────────────────────────
    public static final int SCALE_FILL        = 0;
    public static final int SCALE_FIT         = 1;
    public static final int SCALE_CENTER      = 2;
    public static final int SCALE_FIT_WIDTH   = 3;
    public static final int SCALE_FIT_HEIGHT  = 4;

    // ── Tap callback ───────────────────────────────────────────
    public interface TapCallback {
        void onTap();
    }

    // ── Fields ─────────────────────────────────────────────────
    private final List<String> paths;
    private final Context context;
    private int scaleMode = SCALE_FIT;
    private TapCallback tapCallback;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public FullscreenAdapter(List<String> paths, Context context) {
        this.paths = paths;
        this.context = context;
    }

    /** Called by the activity to register a single-tap handler. */
    public void setTapCallback(TapCallback cb) {
        this.tapCallback = cb;
    }

    // ── Scale mode ─────────────────────────────────────────────
    public int getScaleMode() {
        return scaleMode;
    }

    public void setScaleMode(int mode) {
        this.scaleMode = mode;
        notifyDataSetChanged();
    }

    // ── ViewHolder ─────────────────────────────────────────────
    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ProgressBar progressBar;
        public final ZoomableImageView imageView;
        public final CustomVideoView videoView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            progressBar = itemView.findViewById(R.id.fullscreen_progress);
            imageView   = itemView.findViewById(R.id.fullscreen_image);
            videoView   = itemView.findViewById(R.id.fullscreen_video);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_fullscreen_media, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String path = paths.get(position);

        // Stop any old playback
        try { holder.videoView.stopPlayback(); } catch (Exception ignored) {}
        holder.videoView.setVisibility(View.GONE);
        holder.imageView.setVisibility(View.GONE);
        holder.progressBar.setVisibility(View.VISIBLE);

        boolean isVideo = isVideoPath(path);

        if (isVideo) {
            holder.progressBar.setVisibility(View.GONE);
            holder.videoView.setVisibility(View.VISIBLE);
            try {
                holder.videoView.setVideoPath(path);
                holder.videoView.seekTo(1);
                holder.videoView.setOnPreparedListener(mp -> {
                    if (context instanceof FullscreenViewerActivity) {
                        ((FullscreenViewerActivity) context)
                                .setCurrentVideoView(holder.videoView);
                    }
                });
                // Tapping the video also toggles chrome (via the callback)
                holder.videoView.setOnClickListener(v -> {
                    if (tapCallback != null) tapCallback.onTap();
                });
            } catch (Exception e) {
                holder.progressBar.setVisibility(View.VISIBLE);
            }
            return;
        }

        // Image path — decode in background
        final int pos = position;
        executor.execute(() -> {
            Bitmap bmp = decodeSampled(path);
            holder.itemView.post(() -> {
                if (holder.getAdapterPosition() != pos) return;
                holder.progressBar.setVisibility(View.GONE);
                holder.imageView.setVisibility(View.VISIBLE);
                if (bmp != null) {
                    holder.imageView.setImageBitmap(bmp);
                } else {
                    holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                }

                // Wire the tap callback on the zoomable image
                holder.imageView.setOnTapListener(() -> {
                    if (tapCallback != null) tapCallback.onTap();
                });
            });
        });
    }





    private static boolean isVideoPath(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp")
                || lower.endsWith(".m4v") || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    private Bitmap decodeSampled(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);

            int reqW = 1440, reqH = 2560, scale = 1;
            while ((o.outWidth / scale) > reqW * 2 && (o.outHeight / scale) > reqH * 2) {
                scale *= 2;
            }

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Exception e) {
            return null;
        }
    }


    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        try { holder.videoView.stopPlayback(); } catch (Exception ignored) {}
        holder.imageView.setImageDrawable(null);
    }

    @Override
    public int getItemCount() {
        return paths != null ? paths.size() : 0;
    }
}