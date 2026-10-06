package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

public class ThumbnailCache {

    private static ThumbnailCache instance;

    private static final int TARGET_PX = 360;
    private static final long MAX_DISK_BYTES = 64L * 1024L * 1024L;

    private final File cacheDir;
    private final LruCache<String, Bitmap> imageMemory;
    private final LruCache<String, Bitmap> videoMemory;

    private final Object diskLock = new Object();

    private ThumbnailCache(Context context) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int imageKb = Math.max(1024, maxKb / 10);
        int videoKb = Math.max(1024, maxKb / 12);

        imageMemory = new LruCache<String, Bitmap>(imageKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        videoMemory = new LruCache<String, Bitmap>(videoKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        cacheDir = new File(context.getCacheDir(), "thumbnails_v2");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public static synchronized ThumbnailCache getInstance(Context context) {
        if (instance == null) {
            instance = new ThumbnailCache(context.getApplicationContext());
        }
        return instance;
    }

    public Bitmap getThumbnail(String path, int type) {
        LruCache<String, Bitmap> pool = pool(type);
        Bitmap cached = pool.get(path);
        if (cached != null && !cached.isRecycled()) return cached;

        File source = new File(path);
        if (!source.exists()) return null;

        long sourceMtime = source.lastModified();
        if (sourceMtime <= 0L) sourceMtime = source.length();

        String key = hash(path + "_" + type);
        File diskImage = new File(cacheDir, key + ".jpg");
        File diskMeta = new File(cacheDir, key + ".meta");

        Long cachedMtime = readMtime(diskMeta);
        if (diskImage.exists() && cachedMtime != null && cachedMtime == sourceMtime) {
            Bitmap bmp = BitmapFactory.decodeFile(diskImage.getAbsolutePath());
            if (bmp != null) {
                pool.put(path, bmp);
                diskImage.setLastModified(System.currentTimeMillis());
                return bmp;
            }
        }

        Bitmap decoded = decode(source.getAbsolutePath(), type);
        if (decoded == null) return null;

        pool.put(path, decoded);

        synchronized (diskLock) {
            File tmp = new File(cacheDir, key + ".tmp");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tmp);
                decoded.compress(Bitmap.CompressFormat.JPEG, 82, fos);
                fos.flush();
            } catch (IOException ignored) {
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ignored) {}
            }
            if (tmp.exists()) {
                if (tmp.renameTo(diskImage)) {
                    writeMtime(diskMeta, sourceMtime);
                    diskImage.setLastModified(System.currentTimeMillis());
                    enforceDiskLimit();
                } else {
                    tmp.delete();
                }
            }
        }

        return decoded;
    }

    private LruCache<String, Bitmap> pool(int type) {
        return type == GalleryMediaItem.TYPE_VIDEO ? videoMemory : imageMemory;
    }

    private Bitmap decode(String path, int type) {
        try {
            if (type == GalleryMediaItem.TYPE_VIDEO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return ThumbnailUtils.createVideoThumbnail(
                            new File(path),
                            new android.util.Size(TARGET_PX, TARGET_PX),
                            null);
                }
                return ThumbnailUtils.createVideoThumbnail(
                        path, MediaStore.Video.Thumbnails.MINI_KIND);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int minSide = Math.min(bounds.outWidth, bounds.outHeight);
            while (minSide / (sample * 2) >= TARGET_PX) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private Long readMtime(File f) {
        if (!f.exists()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return Long.parseLong(r.readLine());
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) {}
        }
    }

    private void writeMtime(File f, long v) {
        FileWriter w = null;
        try {
            w = new FileWriter(f, false);
            w.write(Long.toString(v));
        } catch (IOException ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) {}
        }
    }

    private void enforceDiskLimit() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        long total = 0;
        ArrayList<File> jpgs = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".jpg")) {
                total += f.length();
                jpgs.add(f);
            }
        }
        if (total <= MAX_DISK_BYTES) return;
        Collections.sort(jpgs, Comparator.comparingLong(File::lastModified));
        for (File f : jpgs) {
            if (total <= MAX_DISK_BYTES) break;
            long len = f.length();
            f.delete();
            new File(cacheDir, f.getName().replace(".jpg", ".meta")).delete();
            total -= len;
        }
    }

    private String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    public void clearCache() {
        imageMemory.evictAll();
        videoMemory.evictAll();
        File[] files = cacheDir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}