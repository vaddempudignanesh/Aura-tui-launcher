package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
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

    public MediaStorePager(ContentResolver resolver, Executor executor) {
        this.resolver = resolver;
        this.executor = executor;
    }

    public void loadPage(final int offset,
                         final int pageSize,
                         final PageCallback callback) {
        executor.execute(() -> {
            try {
                final List<GalleryMediaItem> merged = new ArrayList<>();
                boolean imagesEnded = queryType(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        GalleryMediaItem.TYPE_IMAGE, merged);
                boolean videosEnded = queryType(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        GalleryMediaItem.TYPE_VIDEO, merged);

                Collections.sort(merged, (a, b) -> {
                    int c = Long.compare(b.dateModifiedSeconds, a.dateModifiedSeconds);
                    if (c != 0) return c;
                    int t = Integer.compare(b.type, a.type);
                    if (t != 0) return t;
                    return Long.compare(b.id, a.id);
                });

                int from = Math.min(offset, merged.size());
                int to = Math.min(offset + pageSize, merged.size());
                final List<GalleryMediaItem> page =
                        Collections.unmodifiableList(new ArrayList<>(merged.subList(from, to)));
                final boolean end = (to >= merged.size()) && imagesEnded && videosEnded;

                mainHandler.post(() -> callback.onPage(page, end));
            } catch (Throwable t) {
                mainHandler.post(() -> callback.onError(t));
            }
        });
    }

    private boolean queryType(Uri uri, int type, List<GalleryMediaItem> out) {
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
            if (cursor == null) return true;

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
                    catch (Exception ignored) { /* keep as-is */ }
                }

                out.add(new GalleryMediaItem(
                        id, path, name,
                        parent == null ? "" : parent,
                        modified, type));
            }
            return true;
        } catch (Exception e) {
            return true;
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}