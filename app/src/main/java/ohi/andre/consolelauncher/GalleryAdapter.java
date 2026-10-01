package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {

    private Context context;
    private List<GalleryActivity.MediaItem> mediaItems;
    private List<String> selectedItems;
    private OnItemClickListener listener;
    private ExecutorService executor = Executors.newFixedThreadPool(4);
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ThumbnailCache thumbnailCache;

    public interface OnItemClickListener {
        void onImageClick(String path);
        void onVideoClick(String path);
        void onFavoriteToggle(GalleryActivity.MediaItem item);
        void onDelete(GalleryActivity.MediaItem item);
        void onItemClick(String path);
        void onItemLongPress(String path);
        boolean isSelectionMode();
        void onRestore(GalleryActivity.MediaItem item);
    }

    public GalleryAdapter(Context context, List<GalleryActivity.MediaItem> mediaItems,
                          List<String> selectedItems, OnItemClickListener listener) {
        this.context = context;
        this.mediaItems = mediaItems;
        this.selectedItems = selectedItems;
        this.listener = listener;
        this.thumbnailCache = ThumbnailCache.getInstance(context);
        setHasStableIds(true);
    }

    public void updateItems(List<GalleryActivity.MediaItem> newItems) {
        this.mediaItems = newItems;
        notifyDataSetChanged();
    }

    public void updateSelectedItems(List<String> newSelectedItems) {
        this.selectedItems = newSelectedItems;
        for (int i = 0; i < mediaItems.size(); i++) {
            GalleryActivity.MediaItem item = mediaItems.get(i);
            boolean isSelected = newSelectedItems != null && newSelectedItems.contains(item.path);
            notifyItemChanged(i, isSelected);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_gallery, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position, java.util.List<Object> payloads) {
        if (payloads != null && !payloads.isEmpty()) {
            GalleryActivity.MediaItem item = mediaItems.get(position);
            boolean isSelected = selectedItems != null && selectedItems.contains(item.path);
            if (listener.isSelectionMode()) {
                holder.checkIcon.setVisibility(View.VISIBLE);
                holder.checkIcon.setImageResource(isSelected
                        ? R.drawable.ic_checkbox_checked
                        : R.drawable.ic_checkbox_empty);
            } else {
                holder.checkIcon.setVisibility(View.GONE);
            }
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        GalleryActivity.MediaItem item = mediaItems.get(position);

        holder.videoIcon.setVisibility(View.GONE);
        holder.checkIcon.setVisibility(View.GONE);
        holder.videoOverlay.setVisibility(View.GONE);

        if (item.isFavorite) {
            holder.favIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_orange_dark));
            holder.favIcon.setVisibility(View.VISIBLE);
        } else {
            holder.favIcon.clearColorFilter();
            holder.favIcon.setVisibility(View.INVISIBLE);
        }

        if (item.isTrashed && item.type == GalleryActivity.MediaItem.TYPE_VIDEO) {
            holder.videoOverlay.setVisibility(View.VISIBLE);
        }

        if (item.type == GalleryActivity.MediaItem.TYPE_VIDEO && !item.isTrashed) {
            holder.videoIcon.setVisibility(View.VISIBLE);
        }

        loadThumbnail(holder, item);

        // ★ Click animation (scale bounce) + click dispatch
        holder.itemView.setOnClickListener(v -> {
            animateClickBounce(v);
            if (item.isTrashed) {
                if (listener.isSelectionMode()) {
                    listener.onItemClick(item.path);
                } else {
                    listener.onRestore(item);
                }
                return;
            }
            if (listener.isSelectionMode()) {
                listener.onItemClick(item.path);
            } else {
                if (item.type == GalleryActivity.MediaItem.TYPE_IMAGE) {
                    listener.onImageClick(item.path);
                } else {
                    listener.onVideoClick(item.path);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            animateClickBounce(v);
            listener.onItemLongPress(item.path);
            return true;
        });

        if (listener.isSelectionMode()) {
            holder.checkIcon.setVisibility(View.VISIBLE);
            if (selectedItems != null && selectedItems.contains(item.path)) {
                holder.checkIcon.setImageResource(R.drawable.ic_checkbox_checked);
            } else {
                holder.checkIcon.setImageResource(R.drawable.ic_checkbox_empty);
            }
        } else {
            holder.checkIcon.setVisibility(View.GONE);
        }

        holder.favIcon.setOnClickListener(v -> {
            animateClickBounce(v);
            listener.onFavoriteToggle(item);
        });
    }

    private void animateClickBounce(View v) {
        if (v == null) return;
        try {
            v.startAnimation(AnimationUtils.loadAnimation(
                    v.getContext(), R.anim.bounce_animation));
        } catch (Exception ignored) {}
    }

    private void loadThumbnail(ViewHolder holder, GalleryActivity.MediaItem item) {
        String path = item.path;

        Object currentTag = holder.imageView.getTag();
        if (currentTag != null && currentTag.equals(path) && holder.imageView.getDrawable() != null) {
            return;
        }

        holder.imageView.setTag(path);

        executor.execute(() -> {
            Bitmap bitmap = thumbnailCache.getThumbnail(path, item.type);

            mainHandler.post(() -> {
                if (holder.imageView.getTag() != null
                        && holder.imageView.getTag().equals(path)) {
                    if (bitmap != null) {
                        holder.imageView.setImageBitmap(bitmap);
                    } else {
                        holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        return mediaItems != null ? mediaItems.size() : 0;
    }

    @Override
    public long getItemId(int position) {
        return mediaItems.get(position).path.hashCode();
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        executor.shutdown();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ImageView videoIcon;
        ImageView favIcon;
        ImageView checkIcon;
        RelativeLayout videoOverlay;

        public ViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.gallery_image);
            videoIcon = itemView.findViewById(R.id.video_icon);
            favIcon = itemView.findViewById(R.id.fav_icon);
            checkIcon = itemView.findViewById(R.id.check_icon);
            videoOverlay = itemView.findViewById(R.id.video_overlay);
        }
    }
}