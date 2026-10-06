package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vaddempudi.gnanesh.syntaxcli.R;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.ViewHolder> {

    public interface OnAlbumClickListener {
        void onAlbumClick(GalleryIndexCache.AlbumRecord album);
    }

    private final Context context;
    private final OnAlbumClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final AsyncListDiffer<GalleryIndexCache.AlbumRecord> differ;

    private static final LruCache<String, Bitmap> COVER_CACHE =
            new LruCache<String, Bitmap>(64) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private static final DiffUtil.ItemCallback<GalleryIndexCache.AlbumRecord> DIFF =
            new DiffUtil.ItemCallback<GalleryIndexCache.AlbumRecord>() {
                @Override
                public boolean areItemsTheSame(@NonNull GalleryIndexCache.AlbumRecord a,
                                               @NonNull GalleryIndexCache.AlbumRecord b) {
                    return a.path.equals(b.path);
                }

                @Override
                public boolean areContentsTheSame(@NonNull GalleryIndexCache.AlbumRecord a,
                                                  @NonNull GalleryIndexCache.AlbumRecord b) {
                    return a.count == b.count
                            && (a.displayName == null ? b.displayName == null
                            : a.displayName.equals(b.displayName))
                            && (a.coverPath == null ? b.coverPath == null
                            : a.coverPath.equals(b.coverPath));
                }
            };

    public AlbumAdapter(Context context, OnAlbumClickListener listener) {
        this.context = context;
        this.listener = listener;
        // ★ FIX: use the RecyclerView.Adapter overload. The old code
        //   passed `(ListUpdateCallback) this` and crashed at runtime
        //   because AlbumAdapter isn't a ListUpdateCallback.
        this.differ = new AsyncListDiffer<>(this, DIFF);
    }

    public void submitList(List<GalleryIndexCache.AlbumRecord> items) {
        differ.submitList(items == null
                ? Collections.<GalleryIndexCache.AlbumRecord>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items)));
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_album, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GalleryIndexCache.AlbumRecord album = differ.getCurrentList().get(position);

        holder.albumName.setText(album.displayName);
        holder.albumCover.setTag(album.path);

        Bitmap cached = COVER_CACHE.get(album.path);
        if (cached != null) {
            holder.albumCover.setImageBitmap(cached);
            return;
        }

        if (album.coverPath != null && !album.coverPath.isEmpty()) {
            holder.albumCover.setImageResource(android.R.drawable.ic_menu_gallery);
            final String coverPath = album.coverPath;
            final String albumPath = album.path;
            executor.execute(() -> {
                Bitmap bmp = decodeThumb(coverPath);
                if (bmp != null) {
                    COVER_CACHE.put(albumPath, bmp);
                    mainHandler.post(() -> {
                        Object tag = holder.albumCover.getTag();
                        if (albumPath.equals(tag)) {
                            holder.albumCover.setImageBitmap(bmp);
                        }
                    });
                }
            });
        } else {
            holder.albumCover.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        holder.itemView.setOnClickListener(v -> {
            try {
                v.startAnimation(AnimationUtils.loadAnimation(
                        v.getContext(), R.anim.bounce_animation));
            } catch (Exception ignored) {}
            if (listener != null) listener.onAlbumClick(album);
        });
    }

    private Bitmap decodeThumb(String path) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, b);
            if (b.outWidth <= 0 || b.outHeight <= 0) return null;

            int target = 256;
            int sample = 1;
            while (b.outWidth / sample > target * 2
                    && b.outHeight / sample > target * 2) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception e) {
            return null;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView albumCover;
        TextView albumName;

        ViewHolder(View itemView) {
            super(itemView);
            albumCover = itemView.findViewById(R.id.album_cover);
            albumName = itemView.findViewById(R.id.album_name);
        }
    }
}