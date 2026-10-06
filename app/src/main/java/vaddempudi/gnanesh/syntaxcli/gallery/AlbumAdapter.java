// ═══════════════════════════════════════════════════════════════
// File: AlbumAdapter.java
// ═══════════════════════════════════════════════════════════════
package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.VH> {

    public interface OnAlbumClick {
        void onClick(AlbumRecord album);
    }

    private final Context context;
    private final OnAlbumClick listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AsyncListDiffer<AlbumRecord> differ;

    private static final LruCache<String, Bitmap> COVERS =
            new LruCache<String, Bitmap>(48) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount() / 1024;
                }
            };

    private static final DiffUtil.ItemCallback<AlbumRecord> DIFF =
            new DiffUtil.ItemCallback<AlbumRecord>() {
                @Override public boolean areItemsTheSame(@NonNull AlbumRecord a,
                                                         @NonNull AlbumRecord b) {
                    return a.stableKey().equals(b.stableKey());
                }
                @Override public boolean areContentsTheSame(@NonNull AlbumRecord a,
                                                            @NonNull AlbumRecord b) {
                    return a.count == b.count
                            && safeEq(a.displayName, b.displayName)
                            && safeEq(a.coverPath, b.coverPath);
                }
                private boolean safeEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    public AlbumAdapter(Context context, OnAlbumClick listener) {
        this.context = context;
        this.listener = listener;
        this.differ = new AsyncListDiffer<>(this, DIFF);
    }

    public void submit(List<AlbumRecord> albums) {
        differ.submitList(albums == null
                ? Collections.<AlbumRecord>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(albums)));
    }

    @Override public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_album, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AlbumRecord album = differ.getCurrentList().get(position);

        h.name.setText(album.displayName);
        h.cover.setTag(album.path);

        Bitmap cached = COVERS.get(album.path);
        if (cached != null && !cached.isRecycled()) {
            h.cover.setImageBitmap(cached);
            return;
        }

        h.cover.setImageResource(android.R.drawable.ic_menu_gallery);

        if (album.coverPath != null) {
            final String coverPath = album.coverPath;
            final String albumPath = album.path;
            executor.execute(() -> {
                Bitmap bmp = decode(coverPath);
                if (bmp != null) {
                    COVERS.put(albumPath, bmp);
                    main.post(() -> {
                        Object tag = h.cover.getTag();
                        if (albumPath.equals(tag)) h.cover.setImageBitmap(bmp);
                    });
                }
            });
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(album);
        });
    }

    private Bitmap decode(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) > 256
                    && bounds.outHeight / (sample * 2) > 256) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name;

        VH(View v) {
            super(v);
            cover = v.findViewById(R.id.album_cover);
            name = v.findViewById(R.id.album_name);
        }
    }
}