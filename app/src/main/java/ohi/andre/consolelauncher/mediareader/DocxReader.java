package ohi.andre.consolelauncher.mediareader;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight DOCX text extractor using only Android SDK classes.
 *
 * A .docx file is a ZIP archive containing word/document.xml.
 * We unzip in-memory, parse XML with XmlPullParser (native, fast),
 * and build a formatted string with basic bold/italic preserved as
 * HTML-like markers.
 *
 * No external dependencies. APK impact: ~4 KB.
 */
public final class DocxReader {

    private DocxReader() { }

    /** Result: extracted text, possibly with [b]...[/b] and [i]...[/i] markers. */
    public static String readDocx(File docx) throws Exception {
        StringBuilder out = new StringBuilder();
        boolean foundDocument = false;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    foundDocument = true;
                    parseDocumentXml(zis, out);
                    break;
                }
            }
        }

        if (!foundDocument) {
            throw new Exception("Not a valid DOCX: missing word/document.xml");
        }
        return out.toString();
    }

    /**
     * Streams the document.xml, walking the tree and collecting:
     *   - <w:t>...</w:t>  → text content
     *   - <w:p ...>       → paragraph breaks
     *   - <w:b/>          → bold toggle
     *   - <w:i/>          → italic toggle
     *   - <w:tab/>        → tab
     *   - <w:br/>         → line break
     */
    private static void parseDocumentXml(InputStream xmlIn, StringBuilder out)
            throws Exception {

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(xmlIn, null);

        boolean bold = false;
        boolean italic = false;
        boolean inText = false;
        boolean inParagraph = false;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();

            switch (event) {
                case XmlPullParser.START_TAG:
                    if (name == null) break;

                    switch (name) {
                        case "t":              // <w:t>
                            inText = true;
                            if (bold)   out.append("[b]");
                            if (italic) out.append("[i]");
                            break;
                        case "b":              // <w:b/>  (bold on)
                            bold = true;
                            break;
                        case "i":              // <w:i/>  (italic on)
                            italic = true;
                            break;
                        case "p":              // <w:p>   paragraph start
                            inParagraph = true;
                            break;
                        case "tab":            // <w:tab/>
                            out.append('\t');
                            break;
                        case "br":             // <w:br/>
                            out.append('\n');
                            break;
                        case "drawing":        // skip drawing/image children
                        case "pict":
                            // We don't extract images; skip text within drawings
                            break;
                    }
                    break;

                case XmlPullParser.TEXT:
                    if (inText) {
                        String text = parser.getText();
                        if (text != null) out.append(text);
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if (name == null) break;

                    switch (name) {
                        case "t":
                            if (bold)   out.append("[/b]");
                            if (italic) out.append("[/i]");
                            inText = false;
                            break;
                        case "b":
                            bold = false;
                            break;
                        case "i":
                            italic = false;
                            break;
                        case "p":
                            // Close paragraph with a blank line
                            out.append("\n\n");
                            inParagraph = false;
                            break;
                    }
                    break;
            }

            event = parser.next();
        }
    }
}