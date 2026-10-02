package ohi.andre.consolelauncher.mediareader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Very lightweight legacy .doc (OLE2) text extractor.
 *
 * This is NOT a full DOC parser. It scans the binary stream and pulls out
 * printable characters while collapsing runs of null bytes and binary
 * control characters into whitespace. It gives a readable text dump of
 * most Word documents, which is enough for viewing.
 *
 * For perfect formatting you'd need Apache POI or a server-side converter,
 * both of which are too heavy for this launcher.
 */
public final class DocReader {

    private DocReader() { }

    public static String readDoc(File doc) throws IOException {
        byte[] bytes;
        try (InputStream in = new FileInputStream(doc)) {
            // .doc files can be several MB; cap at 10 MB to avoid OOM
            long len = doc.length();
            int cap = (int) Math.min(len, 10 * 1024 * 1024);
            bytes = new byte[cap];
            int read = 0;
            int total = 0;
            while (total < cap && (read = in.read(bytes, total, cap - total)) > 0) {
                total += read;
            }
        }

        // Try UTF-16LE scan (Word stores text in UTF-16LE in the WordDocument stream)
        String utf16 = extractUtf16Le(bytes);
        // Try 8-bit scan as fallback
        String ascii = extractAscii(bytes);

        // Prefer whichever yields more meaningful characters
        return utf16.length() >= ascii.length() ? utf16 : ascii;
    }

    private static String extractUtf16Le(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length / 2);
        int runStart = -1;
        int i = 0;

        while (i + 1 < bytes.length) {
            int lo = bytes[i] & 0xFF;
            int hi = bytes[i + 1] & 0xFF;
            int cp = lo | (hi << 8);

            if (isPrintableUtf16(cp)) {
                if (runStart < 0) runStart = i;
                sb.append((char) cp);
            } else {
                if (runStart >= 0) {
                    // End of run — add a separator if the run was substantial
                    if (sb.length() > 0) sb.append(' ');
                    runStart = -1;
                }
            }
            i += 2;
        }
        return cleanup(sb.toString());
    }

    private static String extractAscii(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        int runStart = -1;
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isPrintableAscii(c)) {
                if (runStart < 0) runStart = 0;
                sb.append((char) c);
            } else {
                if (runStart >= 0 && sb.length() > 0) sb.append(' ');
                runStart = -1;
            }
        }
        return cleanup(sb.toString());
    }

    private static boolean isPrintableUtf16(int cp) {
        if (cp == '\n' || cp == '\r' || cp == '\t') return true;
        if (cp < 0x20) return false;
        if (cp >= 0xE000 && cp <= 0xF8FF) return false; // private use
        if (cp >= 0xFFF0) return false;                 // specials
        return true;
    }

    private static boolean isPrintableAscii(int c) {
        if (c == '\n' || c == '\r' || c == '\t') return true;
        if (c < 0x20) return false;
        if (c == 0x7F) return false;
        return true;
    }

    /**
     * Collapse whitespace, drop long runs of garbage, tighten spacing.
     */
    private static String cleanup(String raw) {
        // Collapse multiple spaces into one
        String s = raw.replaceAll("[ \\t]+", " ");
        // Collapse 3+ newlines into 2
        s = s.replaceAll("\\n{3,}", "\n\n");
        // Trim lines
        String[] lines = s.split("\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.length() >= 3) {   // skip single-char garbage lines
                out.append(t).append('\n');
            }
        }
        return out.toString();
    }
}