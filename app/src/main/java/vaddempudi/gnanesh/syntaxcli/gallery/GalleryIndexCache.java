package vaddempudi.gnanesh.syntaxcli.gallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed persistent index.
 *
 * Stores:
 *   - the last N media rows seen (so the first page can render before
 *     MediaStore answers)
 *   - the album list with counts and cover paths
 *   - small key/value metadata (watermarks, last build time)
 */
public final class GalleryIndexCache extends SQLiteOpenHelper {

    private static final String DB_NAME = "gallery_index.db";
    private static final int DB_VERSION = 1;

    public static final class AlbumRecord {
        public final String path;
        public final String displayName;
        public final int count;
        public final String coverPath;

        public AlbumRecord(String path, String displayName, int count, String coverPath) {
            this.path = path;
            this.displayName = displayName;
            this.count = count;
            this.coverPath = coverPath;
        }
    }

    public GalleryIndexCache(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media ("
                + "stable_key TEXT PRIMARY KEY,"
                + "media_id INTEGER NOT NULL,"
                + "path TEXT NOT NULL,"
                + "name TEXT,"
                + "album_path TEXT,"
                + "modified INTEGER NOT NULL,"
                + "type INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX media_modified ON media(modified DESC)");
        db.execSQL("CREATE INDEX media_album ON media(album_path)");

        db.execSQL("CREATE TABLE albums ("
                + "album_path TEXT PRIMARY KEY,"
                + "display_name TEXT,"
                + "count INTEGER NOT NULL,"
                + "cover_path TEXT)");

        db.execSQL("CREATE TABLE metadata ("
                + "key TEXT PRIMARY KEY,"
                + "value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 — no migrations yet.
    }

    public void putMediaPage(List<GalleryMediaItem> items) {
        if (items == null || items.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (GalleryMediaItem item : items) {
                ContentValues v = new ContentValues();
                v.put("stable_key", item.stableKey());
                v.put("media_id", item.id);
                v.put("path", item.path);
                v.put("name", item.displayName);
                v.put("album_path", item.albumPath);
                v.put("modified", item.dateModifiedSeconds);
                v.put("type", item.type);
                db.insertWithOnConflict("media", null, v,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<GalleryMediaItem> readMediaPage(int limit) {
        ArrayList<GalleryMediaItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("media",
                new String[]{"media_id", "path", "name", "album_path", "modified", "type"},
                null, null, null, null,
                "modified DESC, media_id DESC",
                String.valueOf(limit));
        try {
            while (cursor.moveToNext()) {
                result.add(new GalleryMediaItem(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                        cursor.getInt(5)));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public void replaceAlbums(List<AlbumRecord> albums) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("albums", null, null);
            for (AlbumRecord a : albums) {
                ContentValues v = new ContentValues();
                v.put("album_path", a.path);
                v.put("display_name", a.displayName);
                v.put("count", a.count);
                v.put("cover_path", a.coverPath);
                db.insert("albums", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<AlbumRecord> readAlbums() {
        ArrayList<AlbumRecord> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("albums",
                new String[]{"album_path", "display_name", "count", "cover_path"},
                null, null, null, null,
                "display_name COLLATE NOCASE ASC");
        try {
            while (cursor.moveToNext()) {
                result.add(new AlbumRecord(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getInt(2),
                        cursor.getString(3)));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public void putMetadata(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        getWritableDatabase().insertWithOnConflict("metadata", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getMetadata(String key) {
        Cursor cursor = getReadableDatabase().query("metadata",
                new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null, "1");
        try {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } finally {
            cursor.close();
        }
    }
}