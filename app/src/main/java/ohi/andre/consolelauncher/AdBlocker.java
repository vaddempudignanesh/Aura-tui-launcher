package ohi.andre.consolelauncher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight host-based ad blocker.
 * Loads a hosts file into a HashSet → O(1) lookups, tiny memory footprint.
 */
public final class AdBlocker {

    private static final String TAG = "AdBlocker";

    private static final HashSet<String> AD_HOSTS = new HashSet<>(65536);
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    private AdBlocker() {}

    /** Call from any background thread once per process. */
    public static void init(Context context) {
        if (LOADED.get()) return;
        if (!LOADED.compareAndSet(false, true)) return;

        int resId = context.getResources().getIdentifier(
                "ad_hosts", "raw", context.getPackageName());
        if (resId == 0) {
            Log.w(TAG, "res/raw/ad_hosts.txt not found — ad blocker disabled");
            LOADED.set(false);
            return;
        }

        long start = System.currentTimeMillis();
        int count = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getResources().openRawResource(resId)))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                // Handle both "domain.com" and "127.0.0.1 domain.com" formats
                String host;
                if (trimmed.indexOf(' ') >= 0 || trimmed.indexOf('\t') >= 0) {
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length < 2) continue;
                    host = parts[1];
                } else {
                    host = trimmed;
                }

                host = host.toLowerCase(Locale.US);

                // Skip localhost / loopback junk
                if (host.equals("localhost") || host.equals("127.0.0.1")
                        || host.equals("0.0.0.0") || host.equals("::1")) {
                    continue;
                }

                if (AD_HOSTS.add(host)) count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ad hosts", e);
            LOADED.set(false);
            return;
        }

        Log.i(TAG, "Loaded " + count + " ad hosts in "
                + (System.currentTimeMillis() - start) + " ms");
    }

    /** O(1) domain check with subdomain walk-up. */
    public static boolean isAd(String urlStr) {
        if (urlStr == null || urlStr.isEmpty() || AD_HOSTS.isEmpty()) return false;

        String host;
        try {
            // Fast path — avoid URL() allocation for common cases
            int schemeEnd = urlStr.indexOf("://");
            int hostStart = schemeEnd >= 0 ? schemeEnd + 3 : 0;
            int hostEnd = urlStr.length();
            for (int i = hostStart; i < urlStr.length(); i++) {
                char c = urlStr.charAt(i);
                if (c == '/' || c == '?' || c == '#') { hostEnd = i; break; }
            }
            host = urlStr.substring(hostStart, hostEnd).toLowerCase(Locale.US);

            // Strip user:pass@ and :port
            int at = host.indexOf('@');
            if (at >= 0) host = host.substring(at + 1);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);

        } catch (Exception e) {
            // Fallback for weird URLs
            try {
                host = new URL(urlStr).getHost().toLowerCase(Locale.US);
            } catch (Exception e2) {
                return false;
            }
        }

        if (host.isEmpty()) return false;

        // Walk up the domain tree: a.b.example.com → b.example.com → example.com
        String tempHost = host;
        while (tempHost.indexOf('.') >= 0) {
            if (AD_HOSTS.contains(tempHost)) return true;
            tempHost = tempHost.substring(tempHost.indexOf('.') + 1);
        }
        return AD_HOSTS.contains(tempHost);
    }
}