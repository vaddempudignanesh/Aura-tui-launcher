package vaddempudi.gnanesh.syntaxcli.browser;

import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Ultra-lightweight tracking / telemetry filter.
 *
 * Design goals:
 *   • Zero bundled host files — matches against a compiled keyword matrix
 *     so the APK size stays unchanged.
 *   • Never blocks media, streams, blobs, or downloads.
 *   • "Neuters" tracking scripts instead of blocking them: the caller
 *     substitutes a harmless 200 OK text/javascript response so the page's
 *     own JavaScript never throws a ReferenceError.
 *
 * Thread-safety: only reads immutable static fields → safe to call from any
 * thread, including from shouldInterceptRequest (which runs off the UI thread).
 */
public final class TrackingFilter {

    private static final String TAG = "TrackingFilter";

    /** A response with this tag is a neutered empty script body. */
    private static final String NEUTER_TAG = "// tracking disabled";

    /** File extensions / URL fragments that must always bypass the filter. */
    private static final String[] MEDIA_WHITELIST = {
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m3u8", ".ts",
            ".mpd", ".flv", ".wmv", ".mp3", ".m4a", ".aac", ".ogg",
            ".zip", ".rar", ".7z", ".apk", ".torrent",
            "/download/", "blob:", "data:"
    };

    /**
     * Host/subpath keywords that identify trackers.
     * Grouped only for readability — matching is a flat contains().
     *
     * Keep these lowercase. Add more as you discover them.
     */
    private static final Set<String> TRACKER_KEYWORDS = new HashSet<>(256);
    static {
        // Universal telemetry / analytics
        String[] telemetry = {
                "analytics", "telemetry", "metrics", "hotjar", "mixpanel",
                "bugsnag", "sentry", "amplitude", "segment.io", "fullstory",
                "clarity.ms", "newrelic", "datadog", "logrocket"
        };
        // Ad networks / pop engines (mostly redundant with AdBlocker, but
        // kept here for scripts served from innocent-looking CDN paths)
        String[] ads = {
                "doubleclick", "pagead", "googlesyndication", "adservice",
                "adserver", "popads", "popcash", "propellerads",
                "onclickads", "adsterra", "exoclick", "juicyads",
                "revcontent", "outbrain", "taboola"
        };
        // Tracking pixels / fingerprinting / crypto-miners
        String[] trackers = {
                "facebook.net", "connect.facebook", "tiktok.com/api",
                "coinhive", "cryptonight", "miner.js", "fingerprint",
                "bcookie", "criteo", "scorecardresearch", "quantserve",
                "/beacon", "/pixel", "/telemetry", "/collect"
        };

        for (String s : telemetry) TRACKER_KEYWORDS.add(s);
        for (String s : ads)       TRACKER_KEYWORDS.add(s);
        for (String s : trackers)  TRACKER_KEYWORDS.add(s);
    }

    private TrackingFilter() {}

    /**
     * Returns a neutered WebResourceResponse if the URL matches the tracker
     * matrix, or {@code null} to let the request proceed normally.
     *
     * MUST be called from shouldInterceptRequest.
     */
    public static WebResourceResponse maybeNeuter(String url) {
        if (url == null || url.isEmpty()) return null;

        String lower = url.toLowerCase(Locale.US);

        // ── 1. Media / download whitelist always wins ──
        for (String w : MEDIA_WHITELIST) {
            if (lower.contains(w)) return null;
        }

        // ── 2. Fast keyword scan ──
        // We only iterate the set once, using contains() — that's a
        // single pass over the URL per keyword, no allocations.
        for (String kw : TRACKER_KEYWORDS) {
            if (lower.contains(kw)) {
                return neuteredScriptResponse();
            }
        }

        return null;
    }

    /**
     * Builds the harmless 200-OK response used to satisfy a <script src=...>
     * tag. Returning an empty body with the correct MIME type prevents the
     * browser from firing onerror handlers that would otherwise break the
     * site's JS execution chain.
     */
    private static WebResourceResponse neuteredScriptResponse() {
        return new WebResourceResponse(
                "text/javascript",
                "UTF-8",
                200,
                "OK",
                java.util.Collections.singletonMap(
                        "Cache-Control", "no-store"),
                new ByteArrayInputStream(
                        NEUTER_TAG.getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * Diagnostic — returns true if the URL *would* be neutered.
     * Useful for logging; not used in the hot path.
     */
    public static boolean isTracker(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        for (String w : MEDIA_WHITELIST) if (lower.contains(w)) return false;
        for (String kw : TRACKER_KEYWORDS) if (lower.contains(kw)) return true;
        return false;
    }
}