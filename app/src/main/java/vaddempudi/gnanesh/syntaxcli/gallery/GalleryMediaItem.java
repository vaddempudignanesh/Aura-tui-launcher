package vaddempudi.gnanesh.syntaxcli.gallery;

import java.io.File;

/**
 * Immutable media model. Replaces {@code GalleryActivity.MediaItem}.
 *
 * Kept backward-compatible with the old field names via public aliases
 * so existing call-sites keep compiling during the migration.
 */
public final class GalleryMediaItem {

    public static final int TYPE_IMAGE = 0;
    public static final int TYPE_VIDEO = 1;

    public final long id;              // MediaStore _ID (or synthetic for injected items)
    public final String path;
    public final String displayName;
    public final String albumPath;     // parent directory
    public final long dateModifiedSeconds;
    public final int type;             // TYPE_IMAGE or TYPE_VIDEO

    // Mutable per-item state that the UI toggles — kept on the object so
    // the adapter doesn't have to keep two parallel lists.
    public boolean isFavorite;
    public boolean isTrashed;



    public final long lastModifiedMillis;

    public GalleryMediaItem(long id,
                            String path,
                            String displayName,
                            String albumPath,
                            long dateModifiedSeconds,
                            int type) {
        this.id = id;
        this.path = path;
        this.displayName = displayName;
        this.albumPath = albumPath == null ? "" : albumPath;
        this.dateModifiedSeconds = dateModifiedSeconds;
        this.type = type;
        this.lastModifiedMillis =
                (path == null || path.isEmpty()) ? 0L : new File(path).lastModified();
    }

    /** Compatibility accessors for legacy code. */
    public String name() { return displayName; }
    public long dateModified() { return dateModifiedSeconds; }
    public String album() { return albumPath; }

    public String stableKey() {
        return type + ":" + id + ":" + path;
    }

    public boolean isVideo() { return type == TYPE_VIDEO; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GalleryMediaItem)) return false;
        GalleryMediaItem other = (GalleryMediaItem) o;
        return stableKey().equals(other.stableKey());
    }

    @Override
    public int hashCode() {
        return stableKey().hashCode();
    }
}