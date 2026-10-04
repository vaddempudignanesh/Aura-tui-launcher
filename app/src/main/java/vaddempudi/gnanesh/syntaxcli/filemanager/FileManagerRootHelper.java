package vaddempudi.gnanesh.syntaxcli.filemanager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around `su -c "<cmd>"` for file-manager operations that
 * need to touch /system, /data, /vendor, etc.
 *
 *  - isRootAvailable() : does `su` exist on the device?
 *  - hasRootGranted()  : has the user approved the su prompt for our app?
 *  - list(File)        : returns the contents of a directory as File[]
 *  - run(String...)    : runs a shell command, returns stdout lines
 */
final class FileManagerRootHelper {

    private static Boolean suExists = null;
    private static Boolean granted = null;

    private FileManagerRootHelper() { }

    /** True if the `su` binary is present somewhere on the system. */
    /**
     * Passive check — only stats the well-known su binary paths.
     * Never runs a command, so it can't trigger the prompt.
     */
    static synchronized boolean isRootAvailable() {
        if (suExists != null) return suExists;
        suExists = false;
        String[] paths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/su/bin/su",
                "/system/sbin/su",
                "/vendor/bin/su",
                "/debug_ramdisk/su"
        };
        for (String p : paths) {
            if (new File(p).exists()) { suExists = true; break; }
        }
        return suExists;
    }

    /** True if the user has already approved our su request. */


    /** Request root by running a no-op command. Blocks until the user answers. */
    static boolean requestRoot() {
        if (!isRootAvailable()) return false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo ok"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String out = br.readLine();
            int rc = p.waitFor();
            granted = (rc == 0 && "ok".equals(out));
            return granted;
        } catch (Exception e) {
            granted = false;
            return false;
        }
    }

    /** Run `su -c <cmd>` and return stdout as lines. Returns null on error. */
    static List<String> run(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = br.readLine()) != null) lines.add(l);
            p.waitFor();
            return lines;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /**
     * List the contents of a directory via root. Returns File objects whose
     * name is the full path, since we cannot stat them otherwise.
     *  - Returns null on failure.
     *  - Uses `ls -a` and filters "." and "..".
     */
    static File[] list(File dir) {
        String path = shellEscape(dir.getAbsolutePath());
        // -1: one entry per line; -a: include hidden; -p: append / for dirs.
        List<String> lines = run("ls -1ap " + path);
        if (lines == null) return null;
        List<File> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty() || ".".equals(line) || "..".equals(line)) continue;
            // ls -p appends '/' to directories. Strip it for the File name,
            // but remember the original so the caller can tell it was a dir.
            String clean = line.endsWith("/") ? line.substring(0, line.length() - 1) : line;
            if (clean.isEmpty()) continue;
            out.add(new File(dir, clean));
        }
        return out.toArray(new File[0]);
    }

    /** True if the given absolute path lies inside a root-only area. */
    static boolean needsRoot(String absolutePath) {
        if (absolutePath == null) return false;
        if (absolutePath.equals("/")) return true;
        // Anything under /storage, /mnt, /sdcard is normal user storage.
        if (absolutePath.startsWith("/storage")) return false;
        if (absolutePath.startsWith("/sdcard")) return false;
        if (absolutePath.startsWith("/mnt")) return false;
        // Everything else under / and /system /data /vendor /product etc.
        return true;
    }
    /**
     * Passive check — never spawns `su`, so it never triggers the prompt.
     * Returns true only if `requestRoot()` has already succeeded in this
     * process. On a fresh app launch this is false, and root will be
     * requested lazily the first time the user opens a root-only path.
     */
    static synchronized boolean hasRootGranted() {
        return granted != null && granted;
    }

    /** Quotes a path for `sh`. */
    static String shellEscape(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}