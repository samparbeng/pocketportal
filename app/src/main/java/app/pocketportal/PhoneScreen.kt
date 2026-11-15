package app.pocketportal

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/** What the phone's own screen does while it's shared to the headset. */
enum class ScreenMode(val label: String) {
    Normal("Normal"),
    Dim("Dim"),
    Off("Off"),
}

/**
 * Darkens the phone's own screen while sharing. "Off" switches the panel off
 * through [ScreenControlService] in Shizuku's privileged process; when Shizuku
 * isn't running, or for "Dim", brightness drops to the minimum instead (needs
 * the "Modify system settings" permission). Everything is undone by [restore].
 */
object PhoneScreen {
    private const val TAG = "PhoneScreen"
    private const val SHIZUKU_REQUEST = 7

    /** Shizuku is installed, running, and has allowed this app. */
    val shizukuReady = MutableStateFlow(false)
    /** Shizuku is running but hasn't been allowed yet. */
    val shizukuNeedsPermission = MutableStateFlow(false)

    private var control: IScreenControl? = null
    private var panelOff = false
    private var dimmed = false

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ScreenControlService::class.java.name))
            .daemon(false)
            .processNameSuffix("screen")
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            control = IScreenControl.Stub.asInterface(binder)
            if (panelOff) setPanel(on = false)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            control = null
        }
    }

    fun watchShizuku() {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    fun refresh() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        shizukuReady.value = granted
        shizukuNeedsPermission.value = running && !granted
    }

    fun requestShizukuPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(SHIZUKU_REQUEST)
    }

    fun canDim(context: Context) = Settings.System.canWrite(context)

    /** Called when sharing starts. */
    fun apply(context: Context, mode: ScreenMode) {
        refresh()
        when {
            mode == ScreenMode.Off && shizukuReady.value -> {
                panelOff = true
                if (control != null) setPanel(on = false) else runCatching { Shizuku.bindUserService(serviceArgs, connection) }
            }
            mode != ScreenMode.Normal && canDim(context) -> dim(context)
        }
    }

    /** Called when sharing stops. Safe to call more than once. */
    fun restore(context: Context) {
        if (panelOff) {
            panelOff = false
            setPanel(on = true)
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            control = null
        }
        if (dimmed) undim(context)
    }

    private fun setPanel(on: Boolean) {
        val ok = runCatching { control?.setPanelOn(on) }.getOrNull() == true
        if (!ok) Log.w(TAG, "panel ${if (on) "on" else "off"} failed")
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
