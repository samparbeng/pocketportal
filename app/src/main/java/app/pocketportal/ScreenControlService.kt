package app.pocketportal

import android.annotation.SuppressLint
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Lives in Shizuku's shell-privileged process, not in the app. It switches the
 * physical panel's power mode, the way scrcpy's "turn screen off" works: the
 * panel goes dark but Android still thinks the display is on, so it keeps
 * drawing and screen capture keeps running.
 *
 * These are hidden platform APIs, reached by reflection. They exist on Android
 * 10 through 17, but a device maker could block them, so failures return false.
 */
class ScreenControlService : IScreenControl.Stub() {

    private companion object {
        const val TAG = "ScreenControl"
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun setPanelOn(on: Boolean): Boolean = try {
        val setMode = Class.forName("android.view.SurfaceControl")
            .getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val tokens = displayTokens()
        tokens.forEach { setMode.invoke(null, it, mode) }
        tokens.isNotEmpty()
    } catch (e: Throwable) {
        Log.w(TAG, "could not change panel power", e)
        false
    }

    /** Android 14 moved physical display tokens into DisplayControl, inside services.jar. */
    // Lint blocks these for apps targeting API 37, but the restriction applies to app
    // processes; this class only runs in Shizuku's shell process, where they are allowed.
    @SuppressLint("BlockedPrivateApi")
    private fun displayTokens(): List<IBinder> = try {
        val create: Method = Class.forName("com.android.internal.os.ClassLoaderFactory").getDeclaredMethod(
            "createClassLoader", String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(
            null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
        ) as ClassLoader
        val displayControl = loader.loadClass("com.android.server.display.DisplayControl")
        Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java).apply {
            isAccessible = true
            invoke(Runtime.getRuntime(), displayControl, "android_servers")
        }
        val ids = displayControl.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
        val token = displayControl.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
        ids.map { token.invoke(null, it) as IBinder }
    } catch (e: Throwable) {
        // Before Android 14 the token came from SurfaceControl itself.
        val sc = Class.forName("android.view.SurfaceControl")
        listOfNotNull(sc.getMethod("getInternalDisplayToken").invoke(null) as IBinder?)
    }
}
