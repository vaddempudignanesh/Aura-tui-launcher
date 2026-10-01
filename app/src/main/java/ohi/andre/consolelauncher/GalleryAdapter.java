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
import java.util.concurrent.atomic.AtomicLong;

public class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.ViewHolder> {

    private Context context;
    private List<GalleryActivity.MediaItem> mediaItems;
    private List<String> selectedItems;
    private OnItemClickListener listener;
    private ExecutorService executor = Executors.newFixedThreadPool(4);
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ThumbnailCache thumbnailCache;

    // ★ Per-holder generation counter so stale async loads are dropped.
    private final AtomicLong generation = new AtomicLong(0);

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
        // ★ Take a private copy. The activity may continue to mutate its
        //   `displayedItems` list from background callbacks; the adapter must
        //   never see those mutations mid-layout.
        this.mediaItems = (newItems == null)
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(newItems);
        notifyDataSetChanged();
    }

    public void updateSelectedItems(List<String> newSelectedItems) {
        this.selectedItems = newSelectedItems;
        for (int i = 0; i < mediaItems.size(); i++) {
            GalleryActivity.MediaItem item = mediaItems.get(i);
            boolean isSelected = newSelectedItems != null && newSelectedItems.contains(item.path);
            notifyItemChanged(i, isSelected ? "selection-on" : "selection-off");
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_gallery, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position, java.util.List<Object> payloads) {
        // ★ Payload updates: only touch the checkbox, never the thumbnail.
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

    /**
     * Loads the thumbnail for the given item.
     *
     * ★ FIX: Before kicking off the async load, the ImageView is cleared
     * and tagged with the new path. When the async callback fires, it checks
     * both the current tag AND a per-holder generation counter — if either
     * has moved on, the bitmap is discarded instead of being shown under a
     * different image (the "top image shows in bottom slot" flicker).
     */
    private void loadThumbnail(ViewHolder holder, GalleryActivity.MediaItem item) {
        final String path = item.path;

        // ── 1. If the holder is already showing this path, do nothing. ──
        Object currentTag = holder.imageView.getTag();
        if (currentTag != null && currentTag.equals(path)
                && holder.imageView.getDrawable() != null) {
            return;
        }

        // ── 2. Otherwise: clear + tag so a stale bitmap can't leak through. ──
        holder.imageView.setImageDrawable(null);   // ★ prevents flash of old image
        holder.imageView.setTag(path);

        // ── 3. Bump the generation for this holder. ──
        final long myGen = holder.bindGeneration.incrementAndGet();

        executor.execute(() -> {
            Bitmap bitmap = thumbnailCache.getThumbnail(path, item.type);

            mainHandler.post(() -> {
                // ★ Drop the result if the holder was rebound since we
                //    started, OR if the tag was changed.
                if (holder.bindGeneration.get() != myGen) return;
                Object tag = holder.imageView.getTag();
                if (tag == null || !tag.equals(path)) return;

                if (bitmap != null) {
                    holder.imageView.setImageBitmap(bitmap);
                } else {
                    holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
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

        // ★ Per-holder generation counter — bumped on every bind.
        final AtomicLong bindGeneration = new AtomicLong(0);

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