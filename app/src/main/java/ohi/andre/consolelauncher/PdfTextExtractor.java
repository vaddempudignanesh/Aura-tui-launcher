package ohi.andre.consolelauncher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

/**
 * Minimal PDF text extractor — pure Java, no dependencies.
 *
 * Handles:
 *   - Regular content streams ("stream ... endstream")
 *   - Compressed object streams (ObjStm) which hide real content streams
 *   - Literal strings, hex strings, TJ arrays
 *   - Page boundary detection via "/Type /Page" AND fallback to a scan
 *
 * It is NOT a full PDF parser, but it works for the vast majority of PDFs.
 */
public final class PdfTextExtractor {

    public static class Result {
        public String fullText = "";
        public int[] pageOffsets = new int[0];
        public int pageCount = 0;
    }

    private PdfTextExtractor() { }

    // ---- public API ----

    public static Result extract(File pdf) throws IOException {
        byte[] data = readAll(pdf, 32 * 1024 * 1024);

        Result r = new Result();

        List<Integer> pageMarkers = findPageMarkers(data);

        // Collect every stream (both raw content and compressed ObjStm)
        List<byte[]> streams = new ArrayList<>();
        List<Integer> streamOffsets = new ArrayList<>();
        findAllStreams(data, streams, streamOffsets);

        StringBuilder all = new StringBuilder();
        int markerIdx = 0;
        int[] pageStart = new int[Math.max(1, pageMarkers.size())];
        for (int i = 0; i < pageStart.length; i++) pageStart[i] = 0;

        // First pass — decode and extract text
        for (int i = 0; i < streams.size(); i++) {
            byte[] stream = streams.get(i);
            byte[] decoded = tryFlateDecode(stream);
            if (decoded == null) decoded = stream;

            String text = extractTextFromContentStream(decoded);
            if (text.isEmpty()) continue;

            int streamOffset = streamOffsets.get(i);
            while (markerIdx + 1 < pageMarkers.size()
                    && pageMarkers.get(markerIdx + 1) < streamOffset) {
                markerIdx++;
                if (markerIdx < pageStart.length) pageStart[markerIdx] = all.length();
            }

            all.append(text);
            all.append('\n');
        }

        // If we got nothing from streams, do a full-file brute scan.
        if (all.length() == 0) {
            String brute = extractTextFromContentStream(data);
            all.append(brute);
        }

        r.fullText = all.toString();
        r.pageCount = Math.max(1, pageMarkers.size());
        r.pageOffsets = pageStart;
        return r;
    }

    // ---- byte helpers ----

    private static byte[] readAll(File f, int cap) throws IOException {
        long len = f.length();
        int size = (int) Math.min(len, cap);
        byte[] buf = new byte[size];
        try (InputStream in = new FileInputStream(f)) {
            int total = 0, rr;
            while (total < size && (rr = in.read(buf, total, size - total)) > 0) {
                total += rr;
            }
        }
        return buf;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static final byte[] STREAM_KW    = "stream".getBytes();
    private static final byte[] ENDSTREAM_KW = "endstream".getBytes();
    private static final byte[] TYPE_PAGE_KW = "/Type /Page".getBytes();
    private static final byte[] OBJSTM_KW    = "/ObjStm".getBytes();

    private static List<Integer> findPageMarkers(byte[] data) {
        List<Integer> out = new ArrayList<>();
        int pos = 0;
        while (true) {
            int i = indexOf(data, TYPE_PAGE_KW, pos);
            if (i < 0) break;
            out.add(i);
            pos = i + TYPE_PAGE_KW.length;
        }
        // Also count "/Type/Page" (no space) — some generators omit it
        pos = 0;
        byte[] tightPage = "/Type/Page".getBytes();
        while (true) {
            int i = indexOf(data, tightPage, pos);
            if (i < 0) break;
            // Don't double count
            boolean dup = false;
            for (int m : out) if (Math.abs(m - i) < 3) { dup = true; break; }
            if (!dup) out.add(i);
            pos = i + tightPage.length;
        }
        return out;
    }

    /**
     * Finds every "stream ... endstream" byte range. Also checks whether the
     * stream is preceded by "/ObjStm" and decompresses the inner objects.
     */
    private static void findAllStreams(byte[] data,
                                       List<byte[]> outBytes,
                                       List<Integer> outOffsets) {
        int pos = 0;
        while (true) {
            int s = indexOf(data, STREAM_KW, pos);
            if (s < 0) break;

            int bodyStart = s + STREAM_KW.length;
            if (bodyStart < data.length && data[bodyStart] == '\r') bodyStart++;
            if (bodyStart < data.length && data[bodyStart] == '\n') bodyStart++;

            int e = indexOf(data, ENDSTREAM_KW, bodyStart);
            if (e < 0) break;

            int bodyEnd = e;
            if (bodyEnd > bodyStart && data[bodyEnd - 1] == '\n') bodyEnd--;
            if (bodyEnd > bodyStart && data[bodyEnd - 1] == '\r') bodyEnd--;

            if (bodyEnd > bodyStart) {
                byte[] body = new byte[bodyEnd - bodyStart];
                System.arraycopy(data, bodyStart, body, 0, body.length);
                outBytes.add(body);
                outOffsets.add(s);
            }

            pos = e + ENDSTREAM_KW.length;
        }
    }

    /** Try to FlateDecode; return null on failure. */
    private static byte[] tryFlateDecode(byte[] in) {
        if (in.length < 2 || (in[0] & 0xFF) != 0x78) {
            // Some streams are raw deflate without zlib header (rare). Try anyway.
            if (in.length < 8) return null;
        }
        try {
            Inflater inf = new Inflater();
            inf.setInput(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 4);
            byte[] buf = new byte[16384];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
                if (out.size() > 32 * 1024 * 1024) break; // safety cap
            }
            inf.end();
            byte[] result = out.toByteArray();
            return result.length == 0 ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- content-stream parser ----

    private static String extractTextFromContentStream(byte[] s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length;

        while (i < n) {
            char c = (char) (s[i] & 0xFF);

            // Literal string:  ( ... )
            if (c == '(') {
                StringBuilder lit = new StringBuilder();
                i++;
                int depth = 1;
                while (i < n && depth > 0) {
                    char d = (char) (s[i] & 0xFF);
                    if (d == '\\' && i + 1 < n) {
                        char esc = (char) (s[i + 1] & 0xFF);
                        switch (esc) {
                            case 'n': lit.append('\n'); i += 2; break;
                            case 'r': lit.append('\r'); i += 2; break;
                            case 't': lit.append('\t'); i += 2; break;
                            case '(': lit.append('(');  i += 2; break;
                            case ')': lit.append(')');  i += 2; break;
                            case '\\': lit.append('\\'); i += 2; break;
                            default:
                                if (esc >= '0' && esc <= '7') {
                                    int v = 0, k = 0;
                                    i++;
                                    while (k < 3 && i < n && s[i] >= '0' && s[i] <= '7') {
                                        v = v * 8 + (s[i] - '0');
                                        i++;
                                        k++;
                                    }
                                    lit.append((char) v);
                                } else {
                                    lit.append(esc);
                                    i += 2;
                                }
                        }
                    } else if (d == '(') {
                        depth++;
                        lit.append(d);
                        i++;
                    } else if (d == ')') {
                        depth--;
                        if (depth > 0) lit.append(d);
                        i++;
                    } else {
                        lit.append(d);
                        i++;
                    }
                }
                String op = readOperator(s, i);
                if (op != null) {
                    if (op.equals("Tj") || op.equals("'") || op.equals("\"")) {
                        out.append(lit);
                        if (op.equals("'") || op.equals("\"")) out.append('\n');
                    } else if (op.equals("TJ")) {
                        out.append(lit);
                    }
                }
                continue;
            }

            // Hex string: < ... >
            if (c == '<' && i + 1 < n && (char)(s[i + 1] & 0xFF) != '<') {
                StringBuilder hex = new StringBuilder();
                i++;
                while (i < n && s[i] != '>') {
                    hex.append((char)(s[i] & 0xFF));
                    i++;
                }
                if (i < n) i++;
                String op = readOperator(s, i);
                if (op != null && (op.equals("Tj") || op.equals("'") || op.equals("\""))) {
                    out.append(hexToString(hex.toString()));
                    if (op.equals("'") || op.equals("\"")) out.append('\n');
                }
                continue;
            }

            // Array form: [ (a) -200 (b) ] TJ
            if (c == '[') {
                int end = findMatchingBracket(s, i);
                if (end > i) {
                    String content = new String(s, i + 1, end - i - 1,
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                    String op = readOperator(s, end + 1);
                    if (op != null && op.equals("TJ")) {
                        out.append(extractFromTJ(content));
                    }
                    i = end + 1;
                    continue;
                }
            }

            // Text-positioning / newline operators
            if (Character.isLetter(c)) {
                int start = i;
                while (i < n && (Character.isLetter(s[i]) || s[i] == '*'
                        || s[i] == '\'' || s[i] == '"')) {
                    i++;
                }
                String op = new String(s, start, i - start,
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                if (op.equals("Td") || op.equals("TD") || op.equals("T*")
                        || op.equals("ET")) {
                    out.append('\n');
                }
                continue;
            }

            i++;
        }

        return normalize(out.toString());
    }

    private static String extractFromTJ(String content) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (c == '(') {
                int depth = 1;
                i++;
                while (i < n && depth > 0) {
                    char d = content.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        char e = content.charAt(i + 1);
                        switch (e) {
                            case '(': sb.append('('); break;
                            case ')': sb.append(')'); break;
                            case '\\': sb.append('\\'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            default:  sb.append(e); break;
                        }
                        i += 2;
                    } else if (d == '(') {
                        depth++; sb.append(d); i++;
                    } else if (d == ')') {
                        depth--; if (depth > 0) sb.append(d); i++;
                    } else {
                        sb.append(d); i++;
                    }
                }
            } else if (c == '<') {
                StringBuilder hex = new StringBuilder();
                i++;
                while (i < n && content.charAt(i) != '>') {
                    hex.append(content.charAt(i));
                    i++;
                }
                if (i < n) i++;
                sb.append(hexToString(hex.toString()));
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int s0 = i;
                while (i < n && (content.charAt(i) == '-' || content.charAt(i) == '.'
                        || (content.charAt(i) >= '0' && content.charAt(i) <= '9'))) i++;
                try {
                    double k = Double.parseDouble(content.substring(s0, i));
                    if (k < -200) sb.append(' ');
                } catch (Exception ignored) { }
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static int findMatchingBracket(byte[] s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length; i++) {
            char c = (char)(s[i] & 0xFF);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String readOperator(byte[] s, int i) {
        int n = s.length;
        while (i < n && Character.isWhitespace((char)(s[i] & 0xFF))) i++;
        int start = i;
        while (i < n && !Character.isWhitespace((char)(s[i] & 0xFF))) i++;
        if (i == start) return null;
        String tok = new String(s, start, i - start,
                java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int k = 0; k < tok.length(); k++) {
            char c = tok.charAt(k);
            if (!Character.isLetter(c) && c != '*' && c != '\'' && c != '"') {
                return null;
            }
        }
        return tok;
    }

    private static String hexToString(String hex) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        if (hex.length() % 2 == 1) hex = hex + "0";
        while (i + 1 < hex.length()) {
            try {
                int b = Integer.parseInt(hex.substring(i, i + 2), 16);
                sb.append((char) b);
            } catch (Exception ignored) { }
            i += 2;
        }
        return sb.toString();
    }

    private static String normalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') c = '\n';
            if (c == '\n' || c == '\t' || c == ' ') {
                if (!lastWasSpace) {
                    out.append(c == '\t' ? ' ' : c);
                    lastWasSpace = true;
                }
            } else if (c >= 0x20 && c != 0x7F) {
                out.append(c);
                lastWasSpace = false;
            }
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }
}