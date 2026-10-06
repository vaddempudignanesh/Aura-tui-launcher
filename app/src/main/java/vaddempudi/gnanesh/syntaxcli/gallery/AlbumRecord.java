// ═══════════════════════════════════════════════════════════════
// File: AlbumRecord.java
// ═══════════════════════════════════════════════════════════════
package vaddempudi.gnanesh.syntaxcli.gallery;

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
        return path == null ? "" : path;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlbumRecord)) return false;
        AlbumRecord other = (AlbumRecord) o;
        return stableKey().equals(other.stableKey());
    }

    @Override public int hashCode() {
        return stableKey().hashCode();
    }
}