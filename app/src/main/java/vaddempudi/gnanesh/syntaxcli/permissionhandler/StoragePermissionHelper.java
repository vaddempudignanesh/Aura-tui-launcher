package vaddempudi.gnanesh.syntaxcli.permissionhandler;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Centralised helper for storage permissions across all Android versions.
 *
 * - API < 23: nothing needed (install-time grant)
 * - API 23–29: READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE (runtime)
 * - API 30+:   MANAGE_EXTERNAL_STORAGE (special "All files access" grant via Settings)
 */
public final class StoragePermissionHelper {

    public static final int REQUEST_CODE_LEGACY_STORAGE = 4001;
    public static final int REQUEST_CODE_MANAGE_STORAGE = 4002;

    private StoragePermissionHelper() { }

    /**
     * @return true if we have full read/write access to external storage.
     */
    public static boolean hasFullStorageAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — need MANAGE_EXTERNAL_STORAGE
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6–10 — need READ + WRITE runtime permissions
            boolean read = ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            boolean write = ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            return read && write;
        }
        // Android 5 and below — install-time grant, always true if declared
        return true;
    }

    /**
     * Show a dialog explaining why we need the permission, then open the
     * appropriate settings screen.
     */
    public static void requestStorageAccess(final Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — jump straight to the "All files access" settings page
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Storage Access Required")
                    .setMessage("To open PDFs, images, and other files from "
                            + "WhatsApp, Gmail, or your file manager, this app needs "
                            + "\"All files access\" permission.\n\n"
                            + "Tap \"Open Settings\" and enable the toggle for this app.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivityForResult(intent,
                                    REQUEST_CODE_MANAGE_STORAGE);
                        } catch (Exception e) {
                            // Some OEMs don't support the app-specific variant
                            try {
                                Intent intent = new Intent(
                                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                activity.startActivityForResult(intent,
                                        REQUEST_CODE_MANAGE_STORAGE);
                            } catch (Exception e2) {
                                android.widget.Toast.makeText(activity,
                                        "Cannot open storage settings",
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            // Android 6–10 — normal runtime permission request
            ActivityCompat.requestPermissions(activity,
                    new String[]{
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    REQUEST_CODE_LEGACY_STORAGE);
        }
    }

    /**
     * Called from Activity.onActivityResult to check if the user granted
     * the "All files access" permission.
     *
     * @return true if now granted.
     */
    public static boolean handleActivityResult(Context ctx, int requestCode) {
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            return hasFullStorageAccess(ctx);
        }
        return false;
    }

    /**
     * Called from Activity.onRequestPermissionsResult.
     *
     * @return true if now granted.
     */
    public static boolean handleRequestPermissionsResult(Context ctx,
                                                         int requestCode,
                                                         int[] grantResults) {
        if (requestCode == REQUEST_CODE_LEGACY_STORAGE) {
            if (grantResults.length > 0) {
                for (int r : grantResults) {
                    if (r != PackageManager.PERMISSION_GRANTED) return false;
                }
                return true;
            }
        }
        return false;
    }
}