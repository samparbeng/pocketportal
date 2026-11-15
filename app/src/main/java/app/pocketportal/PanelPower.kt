package app.pocketportal

import android.annotation.SuppressLint
import android.os.IBinder
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * A tiny command-line program inside the APK, started over the phone's own ADB
 * connection ([AdbBridge]) as
 * `CLASSPATH=<apk> app_process / app.pocketportal.PanelPower <0|2>`.
 *
 * It switches the physical panel's power mode the way scrcpy's "turn screen
 * off" does: the panel goes dark but Android still treats the display as on,
 * so it keeps drawing and screen capture keeps running. Only the shell user may
 * do this, which is why it can't run inside the app itself.
 *
 * These are hidden platform APIs, reached by reflection. They exist on Android
 * 10 through 17, but a device maker could block them; failure exits with 1.
 */
object PanelPower {
    const val MODE_OFF = 0
    const val MODE_NORMAL = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull()?.toIntOrNull() ?: MODE_NORMAL
        val ok = try {
            val setMode = Class.forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            val tokens = displayTokens()
            tokens.forEach { setMode.invoke(null, it, mode) }
            tokens.isNotEmpty()
        } catch (e: Throwable) {
            System.err.println("panel power failed: $e")
            false
        }
        println(if (ok) "ok" else "failed")
        exitProcess(if (ok) 0 else 1)
    }

    /** Android 14 moved physical display tokens into DisplayControl, inside services.jar. */
    // Lint blocks these for apps targeting API 37, but the restriction applies to app
    // processes; this program only runs as the shell user, where they are allowed.
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
