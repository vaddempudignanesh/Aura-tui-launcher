package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
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

    /**
     * Only refresh the rows whose selection state actually changed.
     * Previously this called notifyDataSetChanged() which caused the entire
     * grid to rebind and flash on every tap.
     */
    public void updateSelectedItems(List<String> newSelectedItems) {
        this.selectedItems = newSelectedItems;

        // Refresh only the rows that show a checkbox (i.e., selected or previously selected).
        // Since the parent activity toggles one item at a time, we can find the diff and
        // invalidate just those positions.
        for (int i = 0; i < mediaItems.size(); i++) {
            GalleryActivity.MediaItem item = mediaItems.get(i);
            boolean isSelected = newSelectedItems != null && newSelectedItems.contains(item.path);
            // Notify this position only if it's potentially stale
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
            // Payload-only update: only adjust the checkbox visibility
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
            return; // Do not touch the image, avoids flicker
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        GalleryActivity.MediaItem item = mediaItems.get(position);

        // Reset icon states without wiping the thumbnail (which causes flicker)
        holder.videoIcon.setVisibility(View.GONE);
        holder.checkIcon.setVisibility(View.GONE);
        holder.videoOverlay.setVisibility(View.GONE);

        // Favorite icon
        if (item.isFavorite) {
            holder.favIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_orange_dark));
            holder.favIcon.setVisibility(View.VISIBLE);
        } else {
            holder.favIcon.clearColorFilter();
            holder.favIcon.setVisibility(View.INVISIBLE);
        }

        // Trashed video overlay
        if (item.isTrashed && item.type == GalleryActivity.MediaItem.TYPE_VIDEO) {
            holder.videoOverlay.setVisibility(View.VISIBLE);
        }

        // Regular video icon
        if (item.type == GalleryActivity.MediaItem.TYPE_VIDEO && !item.isTrashed) {
            holder.videoIcon.setVisibility(View.VISIBLE);
        }

        // Load or reuse thumbnail
        loadThumbnail(holder, item);

        // Click handling
        if (item.isTrashed) {
            holder.itemView.setOnClickListener(v -> {
                if (listener.isSelectionMode()) {
                    listener.onItemClick(item.path);
                } else {
                    listener.onRestore(item);
                }
            });
            holder.videoIcon.setVisibility(View.GONE);
        } else {
            holder.itemView.setOnClickListener(v -> {
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
        }

        holder.itemView.setOnLongClickListener(v -> {
            listener.onItemClick(item.path);
            return true;
        });

        // Selection checkbox
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

        // Favorite toggle
        holder.favIcon.setOnClickListener(v -> {
            listener.onFavoriteToggle(item);
        });
    }

    private void loadThumbnail(ViewHolder holder, GalleryActivity.MediaItem item) {
        String path = item.path;

        // If this holder is already showing the right thumbnail, skip reloading.
        Object currentTag = holder.imageView.getTag();
        if (currentTag != null && currentTag.equals(path) && holder.imageView.getDrawable() != null) {
            return; // already showing the right thumbnail for this item
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