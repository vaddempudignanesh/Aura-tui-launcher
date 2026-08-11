package ohi.andre.consolelauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FullscreenAdapter extends RecyclerView.Adapter<FullscreenAdapter.ViewHolder> {

    // Video scale modes
    public static final int SCALE_FILL = 0;
    public static final int SCALE_FIT = 1;
    public static final int SCALE_CENTER = 2;
    public static final int SCALE_FIT_WIDTH = 3;
    public static final int SCALE_FIT_HEIGHT = 4;

    private List<String> paths;
    private FullscreenViewerActivity activity;
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private int currentScaleMode = SCALE_FILL; // Default to FILL for no gaps

    public FullscreenAdapter(List<String> paths, FullscreenViewerActivity activity) {
        this.paths = paths;
        this.activity = activity;
    }

    public void setScaleMode(int mode) {
        this.currentScaleMode = mode;
        notifyDataSetChanged();
    }

    public int getScaleMode() {
        return currentScaleMode;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fullscreen_media, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String path = paths.get(position);
        File file = new File(path);

        if (!file.exists()) {
            holder.progressBar.setVisibility(View.GONE);
            holder.imageView.setVisibility(View.VISIBLE);
            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            return;
        }

        String extension = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
        boolean isVideo = extension.matches("mp4|avi|mkv|mov|wmv|flv|3gp|webm|m4v");

        // Reset views
        holder.imageView.setVisibility(View.GONE);
        holder.videoView.setVisibility(View.GONE);
        holder.progressBar.setVisibility(View.VISIBLE);

        if (isVideo) {
            holder.imageView.setVisibility(View.GONE);
            holder.videoView.setVisibility(View.VISIBLE);
            holder.videoView.setVideoPath(path);

            // Remove previous listeners
            holder.videoView.setOnPreparedListener(null);
            holder.videoView.setOnCompletionListener(null);
            holder.videoView.setOnErrorListener(null);

            holder.videoView.setOnPreparedListener(mp -> {
                holder.progressBar.setVisibility(View.GONE);

                int videoWidth = mp.getVideoWidth();
                int videoHeight = mp.getVideoHeight();

                if (videoWidth == 0 || videoHeight == 0) {
                    return;
                }

                // Use the item's own current width/height (already correct for orientation)
                int screenWidth = holder.itemView.getWidth();
                int screenHeight = holder.itemView.getHeight();

                if (screenWidth == 0 || screenHeight == 0) {
                    // View not laid out yet — wait for next layout pass then apply
                    holder.itemView.post(() -> {
                        int w = holder.itemView.getWidth();
                        int h = holder.itemView.getHeight();
                        if (w == 0 || h == 0) return;
                        FrameLayout.LayoutParams p = calculateVideoLayoutParams(
                                videoWidth, videoHeight, w, h, currentScaleMode);
                        // Gravity and margins are fully handled inside calculateVideoLayoutParams
                        holder.videoView.setLayoutParams(p);
                    });
                    return;
                }

                // Calculate dimensions based on current scale mode
                FrameLayout.LayoutParams params = calculateVideoLayoutParams(
                        videoWidth, videoHeight, screenWidth, screenHeight, currentScaleMode);
                // Gravity and margins are fully handled inside calculateVideoLayoutParams
                holder.videoView.setLayoutParams(params);

                // Set looping
                mp.setLooping(true);

                // Register video view with activity and auto-play
                if (activity != null) {
                    activity.setCurrentVideoView(holder.videoView);
                }
            });

            holder.videoView.setOnCompletionListener(mp -> {
                mp.seekTo(0);
                mp.start();
            });

            holder.videoView.setOnErrorListener((mp, what, extra) -> {
                holder.progressBar.setVisibility(View.GONE);
                return false;
            });

            // For the current item, ensure it's set
            if (activity != null && position == activity.getCurrentPosition()) {
                holder.videoView.post(() -> {
                    if (activity != null && activity.isActivityAlive()) {
                        activity.setCurrentVideoView(holder.videoView);
                    }
                });
            }
        } else {
            holder.videoView.setVisibility(View.GONE);
            holder.imageView.setVisibility(View.VISIBLE);
            holder.imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);

            executor.execute(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                Bitmap bitmap = BitmapFactory.decodeFile(path, options);

                holder.imageView.post(() -> {
                    if (bitmap != null) {
                        holder.imageView.setImageBitmap(bitmap);
                    }
                    holder.progressBar.setVisibility(View.GONE);
                });
            });
        }
    }

    private FrameLayout.LayoutParams calculateVideoLayoutParams(
            int videoWidth, int videoHeight, int screenWidth, int screenHeight, int scaleMode) {

        float videoAspect = (float) videoWidth / videoHeight;
        float screenAspect = (float) screenWidth / screenHeight;

        int newWidth, newHeight;

        switch (scaleMode) {
            case SCALE_FILL:
                if (videoAspect > screenAspect) {
                    newHeight = screenHeight;
                    newWidth = (int) (screenHeight * videoAspect);
                } else {
                    newWidth = screenWidth;
                    newHeight = (int) (screenWidth / videoAspect);
                }

                if (newWidth < screenWidth) {
                    newWidth = screenWidth;
                    newHeight = (int) (screenWidth / videoAspect);
                }
                if (newHeight < screenHeight) {
                    newHeight = screenHeight;
                    newWidth = (int) (screenHeight * videoAspect);
                }
                break;

            case SCALE_FIT:
                if (videoAspect > screenAspect) {
                    newWidth = screenWidth;
                    newHeight = (int) (screenWidth / videoAspect);
                } else {
                    newHeight = screenHeight;
                    newWidth = (int) (screenHeight * videoAspect);
                }
                break;

            case SCALE_CENTER:
                float scaleX = (float) screenWidth / videoWidth;
                float scaleY = (float) screenHeight / videoHeight;
                float scale = Math.min(scaleX, scaleY);
                newWidth = (int) (videoWidth * scale);
                newHeight = (int) (videoHeight * scale);
                break;

            case SCALE_FIT_WIDTH:
                newWidth = screenWidth;
                newHeight = (int) (screenWidth / videoAspect);
                break;

            case SCALE_FIT_HEIGHT:
                newHeight = screenHeight;
                newWidth = (int) (screenHeight * videoAspect);
                break;

            default:
                if (videoAspect > screenAspect) {
                    newHeight = screenHeight;
                    newWidth = (int) (screenHeight * videoAspect);
                } else {
                    newWidth = screenWidth;
                    newHeight = (int) (screenWidth / videoAspect);
                }
                if (newWidth < screenWidth) {
                    newWidth = screenWidth;
                    newHeight = (int) (screenWidth / videoAspect);
                }
                if (newHeight < screenHeight) {
                    newHeight = screenHeight;
                    newWidth = (int) (screenHeight * videoAspect);
                }
                break;
        }

        // Ensure minimum dimensions
        newWidth = Math.max(newWidth, screenWidth);
        newHeight = Math.max(newHeight, screenHeight);

        // Expand width by 100px and push left to cover punch-hole gap
        newWidth += 100;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(newWidth, newHeight);
        params.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        params.leftMargin = -100;
        return params;
    }

    @Override
    public int getItemCount() {
        return paths != null ? paths.size() : 0;
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder.videoView != null) {
            try {
                holder.videoView.stopPlayback();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        executor.shutdown();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        CustomVideoView videoView; // Updated to CustomVideoView
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.fullscreen_image);
            videoView = itemView.findViewById(R.id.fullscreen_video);
            progressBar = itemView.findViewById(R.id.fullscreen_progress);
        }
    }
}