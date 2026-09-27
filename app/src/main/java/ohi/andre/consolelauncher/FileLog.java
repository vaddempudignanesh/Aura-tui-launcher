package ohi.andre.consolelauncher;

import android.util.Log;

/**
 * Central debug logger for everything file-manager related.
 * Use: adb logcat -s filemanager
 */
public final class FileLog {
    public static final String TAG = "filemanager";
    private static boolean enabled = true;

    private FileLog() { }

    public static void setEnabled(boolean e) { enabled = e; }

    public static void d(String msg) {
        if (enabled) Log.d(TAG, msg);
    }

    public static void d(String msg, Throwable t) {
        if (enabled) Log.d(TAG, msg, t);
    }

    public static void i(String msg) {
        if (enabled) Log.i(TAG, msg);
    }

    public static void w(String msg) {
        if (enabled) Log.w(TAG, msg);
    }

    public static void w(String msg, Throwable t) {
        if (enabled) Log.w(TAG, msg, t);
    }

    public static void e(String msg) {
        if (enabled) Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        if (enabled) Log.e(TAG, msg, t);
    }
}