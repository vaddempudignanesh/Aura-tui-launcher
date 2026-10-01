package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.ViewHolder> {

    private Context context;
    private List<String> albumNames;
    private List<String> albumPaths;   // parallel list: canonical folder path per album
    private OnAlbumClickListener listener;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // ★ In-memory LRU cover cache so re-opening the album grid is instant
    private static final LruCache<String, Bitmap> COVER_CACHE =
            new LruCache<String, Bitmap>(32) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value != null ? value.getByteCount() : 0;
                }
            };

    public interface OnAlbumClickListener {
        void onAlbumClick(String albumName);
    }

    public AlbumAdapter(Context context, List<String> albumNames,
                        List<String> albumPaths, OnAlbumClickListener listener) {
        this.context = context;
        this.albumNames = albumNames;
        this.albumPaths = albumPaths;
        this.listener = listener;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_album, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        String albumName = albumNames.get(position);
        holder.albumName.setText(albumName);

        String folderPath = (albumPaths != null && position < albumPaths.size())
                ? albumPaths.get(position) : null;

        // ── Instant cover load ──
        //  1) If we have a cached bitmap → set immediately (no async delay)
        //  2) Otherwise, async scan for a cover and cache it
        Bitmap cached = COVER_CACHE.get(albumName);
        if (cached != null) {
            holder.albumCover.setImageBitmap(cached);
            holder.albumCover.setTag(albumName);
        } else {
            holder.albumCover.setImageResource(android.R.drawable.ic_menu_gallery);
            holder.albumCover.setTag(albumName);

            final String key = albumName;
            executor.execute(() -> {
                Bitmap cover = findAlbumCover(folderPath, albumName);
                if (cover != null) {
                    COVER_CACHE.put(key, cover);
                    mainHandler.post(() -> {
                        Object tag = holder.albumCover.getTag();
                        if (tag != null && tag.equals(key)) {
                            holder.albumCover.setImageBitmap(cover);
                        }
                    });
                }
            });
        }

        // ★ Click bounce + dispatch
        holder.itemView.setOnClickListener(v -> {
            try {
                v.startAnimation(AnimationUtils.loadAnimation(
                        v.getContext(), R.anim.bounce_animation));
            } catch (Exception ignored) {}
            if (listener != null) listener.onAlbumClick(albumName);
        });
    }

    /**
     * Find a cover bitmap for the album.
     *
     * First tries the folder path directly (fast, no query), then falls back
     * to a MediaStore query for the newest image in the album.
     */
    private Bitmap findAlbumCover(String folderPath, String albumName) {
        // ── Path-based lookup (fastest) ──
        if (folderPath != null) {
            File folder = new File(folderPath);
            if (folder.exists() && folder.isDirectory()) {
                File[] children = folder.listFiles();
                if (children != null) {
                    for (File f : children) {
                        if (f.isFile()) {
                            String n = f.getName().toLowerCase();
                            if (n.endsWith(".jpg") || n.endsWith(".jpeg")
                                    || n.endsWith(".png") || n.endsWith(".webp")
                                    || n.endsWith(".bmp") || n.endsWith(".gif")) {
                                return decodeThumb(f.getAbsolutePath());
                            }
                        }
                    }
                }
            }
        }

        // ── MediaStore fallback (only if path lookup failed) ──
        if (albumName == null) return null;

        String[] projection = {MediaStore.Images.Media.DATA};
        String selection = MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " = ?";
        String[] selectionArgs = {albumName};

        try {
            android.database.Cursor cursor = context.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1");

            if (cursor != null && cursor.moveToFirst()) {
                int dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                if (dataIndex >= 0) {
                    String path = cursor.getString(dataIndex);
                    if (path != null && new File(path).exists()) {
                        cursor.close();
                        return decodeThumb(path);
                    }
                }
                cursor.close();
            }
        } catch (Exception ignored) {}

        return null;
    }

    private Bitmap decodeThumb(String path) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);

            int target = 256;   // px (square target for cover)
            int scale = 1;
            int w = opts.outWidth, h = opts.outHeight;
            while (w / scale > target * 2 && h / scale > target * 2) scale *= 2;

            BitmapFactory.Options opts2 = new BitmapFactory.Options();
            opts2.inSampleSize = scale;
            opts2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts2);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return albumNames != null ? albumNames.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView albumCover;
        TextView albumName;

        public ViewHolder(View itemView) {
            super(itemView);
            albumCover = itemView.findViewById(R.id.album_cover);
            albumName = itemView.findViewById(R.id.album_name);
        }
    }
}