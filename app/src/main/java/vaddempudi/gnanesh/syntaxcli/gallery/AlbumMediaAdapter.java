package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import vaddempudi.gnanesh.syntaxcli.R;

/**
 * Adapter used ONLY for the contents of a single opened album.
 *
 * Design contract:
 *  - One instance per album open. When the user opens a different album,
 *    the controller sets a NEW instance on the RecyclerView. This forces a
 *    synchronous detach of every ViewHolder bound to the old album, so no
 *    stale frame can survive into the new album's first draw.
 *  - This adapter is NEVER shared with the main gallery grid.
 */
public class AlbumMediaAdapter extends RecyclerView.Adapter<AlbumMediaAdapter.VH> {

    public interface Listener {
        void onImageClick(String path);
        void onVideoClick(String path);
    }

    private final Context context;
    private final Listener listener;
    private final ThumbnailCache thumbnailCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<GalleryMediaItem> items = Collections.emptyList();

    public AlbumMediaAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.thumbnailCache = ThumbnailCache.getInstance(context);
    }

    /** Replace the entire list. Must be called on the main thread. */
    public void setItems(List<GalleryMediaItem> newItems) {
        this.items = newItems == null
                ? Collections.<GalleryMediaItem>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(newItems));
        notifyDataSetChanged();
    }

    public void clear() {
        this.items = Collections.emptyList();
        notifyDataSetChanged();
    }

    public List<GalleryMediaItem> getItems() {
        return items;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_gallery, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        GalleryMediaItem item = items.get(position);

        h.videoIcon.setVisibility(
                item.type == GalleryMediaItem.TYPE_VIDEO ? View.VISIBLE : View.GONE);
        h.videoOverlay.setVisibility(View.GONE);

        if (item.isFavorite) {
            h.favIcon.setColorFilter(Color.parseColor("#FFD700"));
            h.favIcon.setVisibility(View.VISIBLE);
        } else {
            h.favIcon.clearColorFilter();
            h.favIcon.setVisibility(View.INVISIBLE);
        }

        loadThumbnail(h, item);

        h.itemView.setOnClickListener(v -> {
            if (listener == null) return;
            if (item.type == GalleryMediaItem.TYPE_IMAGE) {
                listener.onImageClick(item.path);
            } else {
                listener.onVideoClick(item.path);
            }
        });
    }

    private void loadThumbnail(VH holder, GalleryMediaItem item) {
        final String path = item.path;

        Object tag = holder.imageView.getTag();
        if (tag != null && tag.equals(path) && holder.imageView.getDrawable() != null) {
            return;
        }

        holder.imageView.setImageDrawable(null);
        holder.imageView.setTag(path);

        final long myGen = holder.bindGeneration.incrementAndGet();

        executor.execute(() -> {
            Bitmap bmp = thumbnailCache.getThumbnail(path, item.type);
            mainHandler.post(() -> {
                if (holder.bindGeneration.get() != myGen) return;
                Object t = holder.imageView.getTag();
                if (t == null || !t.equals(path)) return;
                if (bmp != null) holder.imageView.setImageBitmap(bmp);
                else holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
            });
        });
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final ImageView videoIcon;
        final ImageView favIcon;
        final RelativeLayout videoOverlay;
        final AtomicLong bindGeneration = new AtomicLong(0);

        VH(View v) {
            super(v);
            imageView = v.findViewById(R.id.gallery_image);
            videoIcon = v.findViewById(R.id.video_icon);
            favIcon   = v.findViewById(R.id.fav_icon);
            videoOverlay = v.findViewById(R.id.video_overlay);
        }
    }
}