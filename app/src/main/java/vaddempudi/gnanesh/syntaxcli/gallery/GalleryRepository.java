package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


public final class GalleryRepository {

    public interface Listener {
        void onAlbumItemsChanged(
                String albumPath,
                List<GalleryMediaItem> items);

        void onMediaChanged(List<GalleryMediaItem> items, boolean endReached);
        void onBinChanged(List<GalleryMediaItem> binItems);
        void onError(Throwable error);
    }

    private static final String KEY_MEDIA_WATERMARK = "media_watermark";

    private final ExecutorService ioPool = Executors.newFixedThreadPool(4);
    private final ExecutorService binPool = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final GalleryIndexCache indexCache;
    private final MediaStorePager pager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final ArrayList<GalleryMediaItem> allItems = new ArrayList<>();
    private final AtomicBoolean mediaLoading = new AtomicBoolean(false);
    private final AtomicBoolean binLoading = new AtomicBoolean(false);

    private volatile int nextOffset;
    private volatile boolean mediaEnded;
    private volatile boolean albumsLoadedOnce;

    public GalleryRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.indexCache = new GalleryIndexCache(appContext);
        this.pager = new MediaStorePager(appContext.getContentResolver(), ioPool);
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ── Cached first page ──────────────────────────────────────────

    public void loadCachedFirstPage() {
        ioPool.execute(() -> {
            final List<GalleryMediaItem> cached = indexCache.readMediaPage(60);
            Collections.sort(cached, LATEST_FIRST);
            synchronized (allItems) {
                allItems.clear();
                allItems.addAll(cached);
            }
            publishMedia(false);
        });
    }



    private void collectAlbumItems(android.database.Cursor cursor, int type,
                                   String albumPath, String canonicalTarget,
                                   List<GalleryMediaItem> out) {
        if (cursor == null) return;
        int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
        int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
        int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
        int modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
        while (cursor.moveToNext()) {
            String path = dataCol >= 0 ? cursor.getString(dataCol) : null;
            if (path == null) continue;

            java.io.File parent = new java.io.File(path).getParentFile();
            if (parent == null) continue;

            // Canonical parent — same form the pager stores.
            String parentCanon;
            try { parentCanon = parent.getCanonicalPath(); }
            catch (Exception e) { parentCanon = parent.getAbsolutePath(); }

            // Match if canonical matches the target canonical, OR the raw
            // path starts with the album directory (covers any residual
            // non-canonical albumPath values stored elsewhere).
            boolean matches = parentCanon.equals(canonicalTarget)
                    || path.startsWith(albumPath + "/");
            if (!matches) continue;

            long id = idCol >= 0 ? cursor.getLong(idCol) : -1L;
            String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
            if (name == null) name = new java.io.File(path).getName();
            long modified = modCol >= 0 ? cursor.getLong(modCol) : 0L;

            out.add(new GalleryMediaItem(id, path, name, parentCanon, modified, type));
        }
    }
    public void replaceItemPath(String oldPath, GalleryMediaItem replacement) {
        if (oldPath == null || replacement == null) return;
        synchronized (allItems) {
            for (int i = 0; i < allItems.size(); i++) {
                GalleryMediaItem it = allItems.get(i);
                if (it.path != null && it.path.equals(oldPath)) {
                    allItems.set(i, replacement);
                    publishMedia(mediaEnded);
                    return;
                }
            }
            allItems.add(0, replacement);
            Collections.sort(allItems, LATEST_FIRST);
        }
        publishMedia(mediaEnded);
    }

    public void refreshFirstPage() {
        nextOffset = 0;
        mediaEnded = false;
        mediaLoading.set(false);
        pager.reset();
        loadNextPage();
    }

    public void loadNextPage() {
        if (mediaEnded) return;
        if (!mediaLoading.compareAndSet(false, true)) return;

        final int offset = nextOffset;

        pager.loadPage(offset, MediaStorePager.DEFAULT_PAGE_SIZE,
                new MediaStorePager.PageCallback() {
                    @Override
                    public void onPage(List<GalleryMediaItem> page, boolean end) {
                        mediaLoading.set(false);

                        synchronized (allItems) {
                            if (offset == 0) allItems.clear();
                            mergeByStableKey(allItems, page);
                            Collections.sort(allItems, LATEST_FIRST);
                        }

                        indexCache.putMediaPage(page);
                        if (!page.isEmpty()) {
                            indexCache.putMetadata(KEY_MEDIA_WATERMARK,
                                    String.valueOf(page.get(0).dateModifiedSeconds));
                        }
                        nextOffset = offset + page.size();
                        mediaEnded = end;

                        publishMedia(end);

                        // Keep loading pages until we hit the end, so the UI
                        // never has to drive pagination by scrolling.
                        if (!end) loadNextPage();
                    }

                    @Override
                    public void onError(Throwable error) {
                        mediaLoading.set(false);
                        for (Listener l : listeners) l.onError(error);
                    }
                });
    }

    private void mergeByStableKey(ArrayList<GalleryMediaItem> target,
                                  List<GalleryMediaItem> incoming) {
        HashMap<String, Integer> positions = new HashMap<>(target.size());
        for (int i = 0; i < target.size(); i++) {
            positions.put(target.get(i).stableKey(), i);
        }
        for (GalleryMediaItem item : incoming) {
            Integer pos = positions.get(item.stableKey());
            if (pos == null) {
                target.add(item);
            } else {
                GalleryMediaItem old = target.get(pos);
                item.isFavorite = old.isFavorite;
                item.isTrashed = old.isTrashed;
                target.set(pos, item);
            }
        }
    }

    private void publishMedia(boolean endReached) {
        final List<GalleryMediaItem> snapshot;
        synchronized (allItems) {
            snapshot = new ArrayList<>(allItems);
        }
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onMediaChanged(snapshot, endReached);
        });
    }

    // ── Albums ─────────────────────────────────────────────────────



    private List<GalleryIndexCache.AlbumRecord> buildAlbumIndex() {
        HashMap<String, int[]> counts = new HashMap<>();   // albumPath -> [count, coverType]
        HashMap<String, String> covers = new HashMap<>();
        HashMap<String, String> names = new HashMap<>();

        try {
            android.database.Cursor c = appContext.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Images.Media.DATA,
                            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                    }, null, null, null);
            collectAlbums(c, counts, covers, names, true);
        } catch (Exception ignored) {}

        try {
            android.database.Cursor c = appContext.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Video.Media.DATA,
                            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                    }, null, null, null);
            collectAlbums(c, counts, covers, names, false);
        } catch (Exception ignored) {}

        List<GalleryIndexCache.AlbumRecord> out = new ArrayList<>(counts.size());
        for (String albumPath : counts.keySet()) {
            int count = counts.get(albumPath)[0];
            if (count <= 0) continue;
            String display = names.get(albumPath);
            if (display == null || display.isEmpty()) {
                display = new java.io.File(albumPath).getName();
            }
            out.add(new GalleryIndexCache.AlbumRecord(
                    albumPath, display, count, covers.get(albumPath)));
        }
        return out;
    }

    private void collectAlbums(android.database.Cursor cursor,
                               HashMap<String, int[]> counts,
                               HashMap<String, String> covers,
                               HashMap<String, String> names,
                               boolean isImage) {
        if (cursor == null) return;
        try {
            int dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int bucketIdx = cursor.getColumnIndex(
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String path = dataIdx >= 0 ? cursor.getString(dataIdx) : null;
                if (path == null) continue;
                if (path.contains("/com.whatsapp/")) continue;

                java.io.File parent = new java.io.File(path).getParentFile();
                if (parent == null) continue;
                String albumPath;
                try { albumPath = parent.getCanonicalPath(); }
                catch (Exception e) { albumPath = parent.getAbsolutePath(); }

                int[] cnt = counts.get(albumPath);
                if (cnt == null) {
                    cnt = new int[]{0};
                    counts.put(albumPath, cnt);
                    covers.put(albumPath, path);
                }
                cnt[0]++;

                if (!names.containsKey(albumPath)) {
                    String bucket = bucketIdx >= 0
                            ? cursor.getString(bucketIdx) : null;
                    if (bucket == null || bucket.isEmpty()) bucket = parent.getName();
                    names.put(albumPath, bucket);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private List<GalleryIndexCache.AlbumRecord> buildWhatsAppVirtualAlbums() {
        List<GalleryIndexCache.AlbumRecord> out = new ArrayList<>();
        java.io.File root = android.os.Environment.getExternalStorageDirectory();
        if (root == null) return out;

        java.io.File wa = new java.io.File(root,
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images");
        if (wa.exists() && wa.isDirectory()) {
            java.io.File[] top = wa.listFiles();
            if (top != null) {
                int count = 0;
                String cover = null;
                for (java.io.File f : top) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        count++;
                        if (cover == null) cover = f.getAbsolutePath();
                    }
                }
                if (count > 0) {
                    out.add(new GalleryIndexCache.AlbumRecord(
                            "whatsapp://all", "WhatsApp", count, cover));
                }
            }
        }

        java.io.File accounts = new java.io.File(root,
                "Android/media/com.whatsapp/WhatsApp/accounts");
        if (accounts.exists() && accounts.isDirectory()) {
            java.io.File[] accs = accounts.listFiles();
            if (accs != null) {
                for (java.io.File acc : accs) {
                    if (acc == null || !acc.isDirectory()) continue;
                    java.io.File waImg = new java.io.File(acc, "Media/WhatsApp Images");
                    if (!waImg.exists() || !waImg.isDirectory()) continue;
                    java.io.File[] top = waImg.listFiles();
                    if (top == null) continue;
                    int count = 0;
                    String cover = null;
                    for (java.io.File f : top) {
                        if (f.isFile() && !f.getName().startsWith(".")) {
                            count++;
                            if (cover == null) cover = f.getAbsolutePath();
                        }
                    }
                    if (count > 0) {
                        out.add(new GalleryIndexCache.AlbumRecord(
                                "whatsapp://" + acc.getName(),
                                "WhatsApp (" + acc.getName() + ")",
                                count, cover));
                    }
                }
            }
        }
        return out;
    }

    // ── Bin ────────────────────────────────────────────────────────



    private void scanForTrashed(java.io.File dir, List<GalleryMediaItem> out,
                                int depth, int maxDepth) {
        if (depth > maxDepth || dir == null || !dir.isDirectory()) return;
        String name = dir.getName();
        if (name.startsWith(".")) return;
        String lower = name.toLowerCase();
        if (lower.equals("android") || lower.equals("system")
                || lower.equals("obb") || lower.equals("data")) return;

        java.io.File[] files;
        try { files = dir.listFiles(); }
        catch (Exception e) { return; }
        if (files == null) return;

        for (java.io.File f : files) {
            if (f.isDirectory()) {
                scanForTrashed(f, out, depth + 1, maxDepth);
            } else {
                String fn = f.getName();
                if (!fn.contains(".trashed.")) continue;
                String fl = fn.toLowerCase();
                int type;
                if (fl.endsWith(".mp4") || fl.endsWith(".mkv") || fl.endsWith(".webm")
                        || fl.endsWith(".avi") || fl.endsWith(".mov")
                        || fl.endsWith(".3gp") || fl.endsWith(".m4v")
                        || fl.endsWith(".flv") || fl.endsWith(".wmv")) {
                    type = GalleryMediaItem.TYPE_VIDEO;
                } else if (fl.endsWith(".jpg") || fl.endsWith(".jpeg")
                        || fl.endsWith(".png") || fl.endsWith(".gif")
                        || fl.endsWith(".bmp") || fl.endsWith(".webp")
                        || fl.endsWith(".heic") || fl.endsWith(".heif")) {
                    type = GalleryMediaItem.TYPE_IMAGE;
                } else continue;

                String path;
                try { path = f.getCanonicalPath(); }
                catch (Exception e) { path = f.getAbsolutePath(); }

                out.add(new GalleryMediaItem(
                        path.hashCode(),
                        path,
                        fn,
                        f.getParent() == null ? "" : f.getParent(),
                        f.lastModified() / 1000L,
                        type));
            }
        }
    }

    private static final java.util.Comparator<GalleryMediaItem> LATEST_FIRST =
            (a, b) -> {
                int c = Long.compare(b.lastModifiedMillis, a.lastModifiedMillis);
                if (c != 0) return c;
                String pa = a.path == null ? "" : a.path;
                String pb = b.path == null ? "" : b.path;
                return pb.compareTo(pa);
            };

    public void shutdown() {
        ioPool.shutdown();
        binPool.shutdown();
        indexCache.close();
    }
}