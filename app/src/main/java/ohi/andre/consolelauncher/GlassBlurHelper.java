package ohi.andre.consolelauncher;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import androidx.annotation.RequiresApi;

/**
 * Applies a true real-time frosted-glass blur to a View's background content
 * using the native RenderEffect API (Android 12 / API 31+).
 *
 * minSdk is 32, so this is always safe to call — no fallback branch needed,
 * but the version check is kept defensively in case minSdk changes later.
 */
public class GlassBlurHelper {

    // Tune this to match the CSS backdrop-filter: blur(20px) reference
    private static final float BLUR_RADIUS_X = 25f;
    private static final float BLUR_RADIUS_Y = 25f;

    @RequiresApi(Build.VERSION_CODES.S)
    public static void applyBlur(View target) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect blurEffect = RenderEffect.createBlurEffect(
                    BLUR_RADIUS_X,
                    BLUR_RADIUS_Y,
                    Shader.TileMode.CLAMP
            );
            target.setRenderEffect(blurEffect);
        }
    }

    public static void removeBlur(View target) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            target.setRenderEffect(null);
        }
    }
}