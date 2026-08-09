package ohi.andre.consolelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ThumbnailCache {
    private static ThumbnailCache instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final File cacheDir;
    private final int cacheSize;

    private ThumbnailCache(Context context) {
        // Memory cache - 1/8 of available memory
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cacheSize = maxMemory / 8;

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        // Disk cache - /storage/emulated/0/Download/.thumbnails/
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        cacheDir = new File(downloadDir, ".thumbnails");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public static synchronized ThumbnailCache getInstance(Context context) {
        if (instance == null) {
            instance = new ThumbnailCache(context.getApplicationContext());
        }
        return instance;
    }

    public Bitmap getThumbnail(String path, int type) {
        String key = generateKey(path, type);

        // Check memory cache first
        Bitmap cached = memoryCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        // Check disk cache
        File cacheFile = new File(cacheDir, key + ".jpg");
        if (cacheFile.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
            if (bitmap != null) {
                memoryCache.put(key, bitmap);
                return bitmap;
            }
        }

        // Generate thumbnail
        Bitmap thumbnail = generateThumbnail(path, type);
        if (thumbnail != null) {
            // Save to memory cache
            memoryCache.put(key, thumbnail);
            // Save to disk cache
            saveToDisk(cacheFile, thumbnail);
        }
        return thumbnail;
    }

    private Bitmap generateThumbnail(String path, int type) {
        try {
            if (type == GalleryActivity.MediaItem.TYPE_IMAGE) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                return BitmapFactory.decodeFile(path, options);
            } else {
                return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToDisk(File file, Bitmap bitmap) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.flush();
            fos.close();
        } catch (IOException e) {
            // Silently fail - will regenerate next time
        }
    }

    private String generateKey(String path, int type) {
        String input = path + "_" + type;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    public void clearCache() {
        memoryCache.evictAll();
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
        }
    }

    public long getCacheSize() {
        long size = 0;
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    size += f.length();
                }
            }
        }
        return size;
    }
}