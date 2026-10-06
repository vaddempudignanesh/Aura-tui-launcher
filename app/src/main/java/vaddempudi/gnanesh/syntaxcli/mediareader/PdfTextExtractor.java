package vaddempudi.gnanesh.syntaxcli.mediareader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public final class PdfTextExtractor {

    public static class Result {
        public String fullText = "";
        public int[] pageOffsets = new int[0];
        public int pageCount = 0;
    }

    private PdfTextExtractor() { }

    public static Result extract(File pdf) throws IOException {
        byte[] data = readAll(pdf, 16 * 1024 * 1024); // Reduced cap to 16MB for mobile memory safety[cite: 4]

        Result r = new Result();
        List<Integer> pageMarkers = findPageMarkers(data);

        List<byte[]> streams = new ArrayList<>();
        List<Integer> streamOffsets = new ArrayList<>();
        findAllStreams(data, streams, streamOffsets);

        StringBuilder all = new StringBuilder();
        int markerIdx = 0;
        int[] pageStart = new int[Math.max(1, pageMarkers.size())];
        for (int i = 0; i < pageStart.length; i++) pageStart[i] = 0;

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

            all.append(text).append('\n');
        }

        if (all.length() == 0) {
            all.append(extractTextFromContentStream(data));
        }

        r.fullText = all.toString();
        r.pageCount = Math.max(1, pageMarkers.size());
        r.pageOffsets = pageStart;
        return r;
    }

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

    private static List<Integer> findPageMarkers(byte[] data) {
        List<Integer> out = new ArrayList<>();
        int pos = 0;
        while (true) {
            int i = indexOf(data, TYPE_PAGE_KW, pos);
            if (i < 0) break;
            out.add(i);
            pos = i + TYPE_PAGE_KW.length;
        }
        return out;
    }

    private static void findAllStreams(byte[] data, List<byte[]> outBytes, List<Integer> outOffsets) {
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

    private static byte[] tryFlateDecode(byte[] in) {
        if (in.length < 2) return null;
        Inflater inf = new Inflater();
        try {
            inf.setInput(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 2);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
                if (out.size() > 16 * 1024 * 1024) break;
            }
            byte[] result = out.toByteArray();
            return result.length == 0 ? null : result;
        } catch (Exception e) {
            return null;
        } finally {
            inf.end(); // Guaranteed native memory cleanup
        }
    }

    private static String extractTextFromContentStream(byte[] s) {
        // Simplified text token collection to lower memory churn
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length;
        while (i < n) {
            char c = (char) (s[i] & 0xFF);
            if (c == '(') {
                i++;
                StringBuilder lit = new StringBuilder();
                while (i < n && s[i] != ')') {
                    lit.append((char)(s[i] & 0xFF));
                    i++;
                }
                out.append(lit).append(" ");
            }
            i++;
        }
        return out.toString().trim();
    }
}

