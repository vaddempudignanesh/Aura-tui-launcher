package vaddempudi.gnanesh.syntaxcli.browser;

import android.util.Log;

/**
 * Installs a process-wide uncaught exception handler that swallows
 * exceptions coming from the WebView JNI bridge.
 *
 * The stock Android WebView calls System.exit(1) whenever its Java
 * callbacks throw — including benign things like missing Play Services
 * metadata or a site that uses unsupported DRM. That kills the whole
 * launcher process, taking the user's other tabs with it.
 *
 * We wrap the default handler so:
 *   • Exceptions raised on the "chromium" / WebView native threads are
 *     logged but NOT re-thrown → the process survives.
 *   • Everything else still goes to the default handler so real bugs
 *     don't get hidden.
 */
public final class AuraCrashGuard {

    private static final String TAG = "AuraCrashGuard";
    private static boolean installed = false;

    private AuraCrashGuard() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;

        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String name = thread != null ? thread.getName() : "";

            // Chromium runs its bridge on threads named "chromium" or
            // "CrRendererMain", and native callbacks come from a thread
            // whose name starts with "chromium". Match those.
            boolean fromWebView = name != null
                    && (name.startsWith("chromium")
                    || name.contains("CrRenderer")
                    || name.contains("CrBrowser")
                    || name.contains("JavaBridge")
                    || name.contains("webview"));

            if (fromWebView) {
                Log.e(TAG, "Swallowed WebView exception on '" + name + "'", throwable);
                // Do NOT rethrow, do NOT exit. The process continues.
                return;
            }

            Log.e(TAG, "Uncaught exception on '" + name + "'", throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }
}