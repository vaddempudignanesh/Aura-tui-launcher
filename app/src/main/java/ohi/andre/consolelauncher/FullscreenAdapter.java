package ohi.andre.consolelauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.VideoView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FullscreenAdapter extends RecyclerView.Adapter<FullscreenAdapter.ViewHolder> {

    private List<String> paths;
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    public FullscreenAdapter(List<String> paths) {
        this.paths = paths;
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
            return;
        }

        String extension = path.substring(path.lastIndexOf(".") + 1).toLowerCase();
        boolean isVideo = extension.matches("mp4|avi|mkv|mov|wmv|flv|3gp|webm|m4v");

        if (isVideo) {
            holder.imageView.setVisibility(View.GONE);
            holder.videoView.setVisibility(View.VISIBLE);
            holder.progressBar.setVisibility(View.VISIBLE);
            holder.videoView.setVideoPath(path);
            holder.videoView.setOnPreparedListener(mp -> {
                holder.progressBar.setVisibility(View.GONE);
                mp.start();
            });
            holder.videoView.setOnCompletionListener(mp -> {
                mp.start();
            });
        } else {
            holder.videoView.setVisibility(View.GONE);
            holder.imageView.setVisibility(View.VISIBLE);
            holder.progressBar.setVisibility(View.VISIBLE);

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

    @Override
    public int getItemCount() {
        return paths != null ? paths.size() : 0;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        executor.shutdown();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        VideoView videoView;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.fullscreen_image);
            videoView = itemView.findViewById(R.id.fullscreen_video);
            progressBar = itemView.findViewById(R.id.fullscreen_progress);
        }
    }
}