package app.pocketportal

import android.content.Context
import android.provider.Settings

/** What the phone's own screen does while it's shared to the headset. */
enum class ScreenMode(val label: String) {
    Normal("Normal"),
    Dim("Dim"),
    Off("Off"),
}

/**
 * Darkens the phone's own screen while sharing. "Off" switches the panel off
 * through the phone's own Wireless debugging ([AdbBridge]); if that isn't set up
 * or fails, and for "Dim", brightness drops to the minimum instead (needs the
 * "Modify system settings" permission). Everything is undone by [restore].
 */
object PhoneScreen {
    @Volatile private var panelOff = false
    @Volatile private var dimmed = false

    fun canDim(context: Context) = Settings.System.canWrite(context)

    /** Called when sharing starts. */
    fun apply(context: Context, mode: ScreenMode) {
        val app = context.applicationContext
        when {
            mode == ScreenMode.Off && AdbBridge.paired.value -> {
                panelOff = true
                AdbBridge.setPanel(app, on = false) { ok ->
                    if (!ok) {
                        panelOff = false
                        if (canDim(app)) dim(app)
                    }
                }
            }
            mode != ScreenMode.Normal && canDim(app) -> dim(app)
        }
    }

    /** Called when sharing stops. Safe to call more than once. */
    fun restore(context: Context) {
        val app = context.applicationContext
        if (panelOff) {
            panelOff = false
            AdbBridge.setPanel(app, on = true)
        }
        if (dimmed) undim(app)
    }

    // The user's own brightness is saved in prefs so it comes back even if the app is killed.
    private fun dim(context: Context) {
        val resolver = context.contentResolver
        val prefs = prefs(context)
        if (!prefs.contains("brightness")) {
            prefs.edit()
                .putInt("brightness", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128))
                .putInt("brightness_mode", Settings.System.getInt(
                    resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                ))
                .apply()
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 1)
        dimmed = true
    }

    /** Puts the user's brightness back; also run on app start in case a previous session was killed. */
    fun undim(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains("brightness") || !canDim(context)) return
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, prefs.getInt("brightness", 128))
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.getInt("brightness_mode", 1))
        prefs.edit().remove("brightness").remove("brightness_mode").apply()
        dimmed = false
    }

    private fun prefs(context: Context) = context.getSharedPreferences("screen", Context.MODE_PRIVATE)
}
