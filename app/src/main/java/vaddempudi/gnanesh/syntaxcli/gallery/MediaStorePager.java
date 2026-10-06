package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public final class MediaStorePager {

    public static final int DEFAULT_PAGE_SIZE = 60;

    public interface PageCallback {
        void onPage(List<GalleryMediaItem> page, boolean endReached);
        void onError(Throwable error);
    }

    private final ContentResolver resolver;
    private final Executor executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Full sorted master list, cached after the first query.
    private final Object lock = new Object();
    private List<GalleryMediaItem> master = null;

    public MediaStorePager(ContentResolver resolver, Executor executor) {
        this.resolver = resolver;
        this.executor = executor;
    }

    /** Clears the cached master list. Next loadPage() will re-query. */
    public void reset() {
        synchronized (lock) {
            master = null;
        }
    }

    public void loadPage(final int offset,
                         final int pageSize,
                         final PageCallback callback) {
        executor.execute(() -> {
            try {
                List<GalleryMediaItem> full;
                synchronized (lock) {
                    if (master == null) {
                        ArrayList<GalleryMediaItem> merged = new ArrayList<>();
                        queryType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                GalleryMediaItem.TYPE_IMAGE, merged);
                        queryType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                GalleryMediaItem.TYPE_VIDEO, merged);
                        Collections.sort(merged, (a, b) -> {
                            int c = Long.compare(b.lastModifiedMillis, a.lastModifiedMillis);
                            if (c != 0) return c;
                            String pa = a.path == null ? "" : a.path;
                            String pb = b.path == null ? "" : b.path;
                            return pb.compareTo(pa);
                        });
                        master = merged;
                    }
                    full = master;
                }

                final int total = full.size();
                int from = Math.min(offset, total);
                int to = Math.min(offset + pageSize, total);

                final List<GalleryMediaItem> page;
                if (from >= to) {
                    page = Collections.emptyList();
                } else {
                    page = Collections.unmodifiableList(
                            new ArrayList<>(full.subList(from, to)));
                }
                final boolean end = (to >= total);

                mainHandler.post(() -> callback.onPage(page, end));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));
            }
        });
    }

    private void queryType(Uri uri, int type, List<GalleryMediaItem> out) {
        String[] projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        String sort = MediaStore.MediaColumns.DATE_MODIFIED + " DESC, "
                + MediaStore.MediaColumns._ID + " DESC";

        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, projection, null, null, sort);
            if (cursor == null) return;

            int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int modifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);

            while (cursor.moveToNext()) {
                long id = idCol >= 0 ? cursor.getLong(idCol) : -1L;
                String path = dataCol >= 0 ? cursor.getString(dataCol) : null;
                if (TextUtils.isEmpty(path)) continue;

                String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
                if (TextUtils.isEmpty(name)) name = new File(path).getName();

                long modified = modifiedCol >= 0 ? cursor.getLong(modifiedCol) : 0L;
                File file = new File(path);
                String parent = file.getParent();
                if (parent != null) {
                    try { parent = new File(parent).getCanonicalPath(); }
                    catch (Exception ignored) { }
                }

                out.add(new GalleryMediaItem(
                        id, path, name,
                        parent == null ? "" : parent,
                        modified, type));
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}