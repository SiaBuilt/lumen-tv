package app.orchard.tv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Panel brightness. TVs keep it in different places:
 *
 * - Xiaomi (MediaTek, China firmware): Settings.Global "picture_backlight", 0–100, applied by the
 *   firmware's TV menu service as soon as it changes. Writing it needs WRITE_SECURE_SETTINGS.
 * - TCL 65V8L: the standard screen brightness drives the backlight (TCL MT5879 ignored it).
 * - Sony BRAVIA: the screen brightness setting changes but the backlight does not (no backlight in
 *   the display config); Control Center opens Sony's picture settings, which DISPLAY_SETTINGS
 *   resolves to. Reported on a BRAVIA VH1, Android 12 (GitHub issue #2).
 * - Xiaomi without "picture_backlight" (MiTV-ASTP0 Android 9, Mi TV 4A, recent Redmi, issue #23):
 *   the screen brightness setting changes but the backlight does not; Control Center opens the
 *   TV's settings instead.
 * - Others: the standard Settings.System screen brightness, 0–255. Writing it needs the "modify
 *   system settings" app-op. Both are granted by tools/install.sh; without them Control Center
 *   opens the TV's own picture settings instead.
 */
object Brightness {
    private const val XIAOMI_BACKLIGHT = "picture_backlight"

    private fun usesXiaomiBacklight(context: Context) =
        Settings.Global.getString(context.contentResolver, XIAOMI_BACKLIGHT) != null

    private fun isXiaomi(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.xiaomi.mitv.settings", 0) }.isSuccess

    private fun isSony(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.sony.dtv.configsettings", 0) }.isSuccess

    /** Current level as 0..1, or null when Aerial cannot change it on this TV. */
    fun level(context: Context): Float? {
        val r = context.contentResolver
        return if (usesXiaomiBacklight(context)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return null
            Settings.Global.getInt(r, XIAOMI_BACKLIGHT, 50) / 100f
        } else {
            // TCL and Sony drive the backlight through their own TV services and ignore the system
            // setting (TCL MT5879, Sony BRAVIA VH1, Xiaomi without picture_backlight); Control
            // Center opens their picture panels.
            // Before Android 6.0 WRITE_SETTINGS is granted at install.
            // TCL 65V8L shows a brightness tile in its own quick settings over this same setting,
            // so Lumen offers the slider there too (MT5879 ignored it; see above).
            if (isSony(context) || isXiaomi(context) || Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context)) return null
            Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }
    }

    /** Steps brightness one notch up or down and returns the new level. */
    fun adjust(context: Context, direction: Int): Float? {
        val current = level(context) ?: return null
        val next = (current + direction * 0.05f).coerceIn(0f, 1f)
        val r = context.contentResolver
        runCatching {
            if (usesXiaomiBacklight(context)) {
                Settings.Global.putInt(r, XIAOMI_BACKLIGHT, Math.round(next * 100))
            } else {
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, Math.round(next * 255))
            }
        }
        return level(context)
    }
}
