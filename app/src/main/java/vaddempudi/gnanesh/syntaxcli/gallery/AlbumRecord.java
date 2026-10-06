// ═══════════════════════════════════════════════════════════════
// File: AlbumRecord.java
// ═══════════════════════════════════════════════════════════════
package vaddempudi.gnanesh.syntaxcli.gallery;

import java.io.File;

/** Immutable album record. */
public final class AlbumRecord {
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

    public boolean isWhatsApp() {
        return path != null && path.startsWith("whatsapp://");
    }

    public String stableKey() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlbumRecord)) return false;
        AlbumRecord other = (AlbumRecord) o;
        return path != null && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return path == null ? 0 : path.hashCode();
    }
}