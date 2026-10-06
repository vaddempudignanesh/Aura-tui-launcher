// ═══════════════════════════════════════════════════════════════
// File: AlbumLoader.java
// Single source of truth for album data. No caching layers,
// no SQLite, no global mutable state. Every query is fresh.
// ═══════════════════════════════════════════════════════════════
package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AlbumLoader {

    private final ContentResolver resolver;

    public AlbumLoader(Context context) {
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    // ── Build the album list ───────────────────────────────────────

    public List<AlbumRecord> loadAlbums() {
        Map<String, int[]> counts = new HashMap<>();      // path -> [count]
        Map<String, String> names = new HashMap<>();      // path -> display
        Map<String, String> covers = new HashMap<>();     // path -> cover

        queryAlbums(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, counts, names, covers);
        queryAlbums(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, counts, names, covers);

        List<AlbumRecord> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String albumPath = e.getKey();
            int count = e.getValue()[0];
            if (count <= 0) continue;

            String display = names.get(albumPath);
            if (TextUtils.isEmpty(display)) {
                display = new File(albumPath).getName();
            }
            out.add(new AlbumRecord(albumPath, display, count, covers.get(albumPath)));
        }

        out.addAll(buildWhatsAppAlbums());

        Collections.sort(out, new Comparator<AlbumRecord>() {
            @Override
            public int compare(AlbumRecord a, AlbumRecord b) {
                boolean aw = a.isWhatsApp();
                boolean bw = b.isWhatsApp();
                if (aw != bw) return aw ? -1 : 1;
                String da = a.displayName == null ? "" : a.displayName;
                String db = b.displayName == null ? "" : b.displayName;
                return da.compareToIgnoreCase(db);
            }
        });

        return out;
    }

    private void queryAlbums(android.net.Uri uri,
                             Map<String, int[]> counts,
                             Map<String, String> names,
                             Map<String, String> covers) {
        Cursor c = null;
        try {
            c = resolver.query(uri,
                    new String[]{
                            MediaStore.MediaColumns.DATA,
                            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                    }, null, null, null);
            if (c == null) return;

            int dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);

            while (c.moveToNext()) {
                String path = dataCol >= 0 ? c.getString(dataCol) : null;
                if (TextUtils.isEmpty(path)) continue;
                if (path.contains("/com.whatsapp/")) continue;

                File parent = new File(path).getParentFile();
                if (parent == null) continue;

                String albumPath = parent.getAbsolutePath();

                int[] cnt = counts.get(albumPath);
                if (cnt == null) {
                    cnt = new int[]{0};
                    counts.put(albumPath, cnt);
                    covers.put(albumPath, path);
                }
                cnt[0]++;

                if (!names.containsKey(albumPath)) {
                    String bucket = bucketCol >= 0 ? c.getString(bucketCol) : null;
                    if (TextUtils.isEmpty(bucket)) bucket = parent.getName();
                    names.put(albumPath, bucket);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private List<AlbumRecord> buildWhatsAppAlbums() {
        List<AlbumRecord> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return out;

        File wa = new File(root,
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
        if (wa.isDirectory()) {
            File[] files = wa.listFiles();
            if (files != null) {
                int count = 0;
                String cover = null;
                for (File f : files) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        count++;
                        if (cover == null) cover = f.getAbsolutePath();
                    }
                }
                if (count > 0) {
                    out.add(new AlbumRecord("whatsapp://all", "WhatsApp", count, cover));
                }
            }
        }

        File accounts = new File(root,
                "Android/media/com.whatsapp/WhatsApp/accounts");
        if (accounts.isDirectory()) {
            File[] accs = accounts.listFiles();
            if (accs != null) {
                for (File acc : accs) {
                    if (acc == null || !acc.isDirectory()) continue;
                    File waImg = new File(acc, "Media/WhatsApp Images");
                    if (!waImg.isDirectory()) continue;
                    File[] files = waImg.listFiles();
                    if (files == null) continue;

                    int count = 0;
                    String cover = null;
                    for (File f : files) {
                        if (f.isFile() && !f.getName().startsWith(".")) {
                            count++;
                            if (cover == null) cover = f.getAbsolutePath();
                        }
                    }
                    if (count > 0) {
                        out.add(new AlbumRecord(
                                "whatsapp://" + acc.getName(),
                                "WhatsApp (" + acc.getName() + ")",
                                count, cover));
                    }
                }
            }
        }
        return out;
    }

    // ── Load one album's contents ──────────────────────────────────

    public List<GalleryMediaItem> loadAlbumItems(AlbumRecord album) {
        if (album == null) return Collections.emptyList();

        if (album.isWhatsApp()) {
            return loadWhatsAppItems(album);
        }

        final List<GalleryMediaItem> out = new ArrayList<>();
        final String albumDir = album.path;

        collectItems(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                GalleryMediaItem.TYPE_IMAGE, albumDir, out);
        collectItems(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                GalleryMediaItem.TYPE_VIDEO, albumDir, out);

        Collections.sort(out, LATEST_FIRST);
        return out;
    }

    private void collectItems(android.net.Uri uri, int type,
                              String albumDir, List<GalleryMediaItem> out) {
        Cursor c = null;
        try {
            c = resolver.query(uri,
                    new String[]{
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATE_MODIFIED
                    }, null, null, null);
            if (c == null) return;

            int idCol = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);

            final String prefix = albumDir.endsWith("/") ? albumDir : albumDir + "/";

            while (c.moveToNext()) {
                String path = dataCol >= 0 ? c.getString(dataCol) : null;
                if (TextUtils.isEmpty(path)) continue;

                // Match by raw directory prefix. This is deterministic
                // and never fails on symlinks or canonicalization.
                if (!path.startsWith(prefix)) continue;

                long id = idCol >= 0 ? c.getLong(idCol) : -1L;
                String name = nameCol >= 0 ? c.getString(nameCol) : null;
                if (TextUtils.isEmpty(name)) name = new File(path).getName();
                long modified = modCol >= 0 ? c.getLong(modCol) : 0L;

                File parent = new File(path).getParentFile();
                String albumPath = parent == null ? "" : parent.getAbsolutePath();

                out.add(new GalleryMediaItem(id, path, name, albumPath, modified, type));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private List<GalleryMediaItem> loadWhatsAppItems(AlbumRecord album) {
        List<GalleryMediaItem> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return out;

        File dir;
        if ("whatsapp://all".equals(album.path)) {
            dir = new File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
        } else {
            String accountId = album.path.substring("whatsapp://".length());
            dir = new File(root,
                    "Android/media/com.whatsapp/WhatsApp/accounts/"
                            + accountId + "/Media/WhatsApp Images");
        }

        if (!dir.isDirectory()) return out;

        File[] files = dir.listFiles();
        if (files == null) return out;

        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".")) continue;

            String lower = name.toLowerCase(Locale.ROOT);
            int type;
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                    || lower.endsWith(".avi") || lower.endsWith(".mov")
                    || lower.endsWith(".3gp") || lower.endsWith(".m4v")
                    || lower.endsWith(".flv") || lower.endsWith(".wmv")) {
                type = GalleryMediaItem.TYPE_VIDEO;
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".gif")
                    || lower.endsWith(".bmp") || lower.endsWith(".webp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif")) {
                type = GalleryMediaItem.TYPE_IMAGE;
            } else {
                continue;
            }

            out.add(new GalleryMediaItem(
                    f.getAbsolutePath().hashCode(),
                    f.getAbsolutePath(),
                    name,
                    dir.getAbsolutePath(),
                    f.lastModified() / 1000L,
                    type));
        }

        Collections.sort(out, LATEST_FIRST);
        return out;
    }

    private static final Comparator<GalleryMediaItem> LATEST_FIRST =
            new Comparator<GalleryMediaItem>() {
                @Override
                public int compare(GalleryMediaItem a, GalleryMediaItem b) {
                    int c = Long.compare(b.dateModifiedSeconds, a.dateModifiedSeconds);
                    if (c != 0) return c;
                    int t = Integer.compare(b.type, a.type);
                    if (t != 0) return t;
                    return Long.compare(b.id, a.id);
                }
            };
}