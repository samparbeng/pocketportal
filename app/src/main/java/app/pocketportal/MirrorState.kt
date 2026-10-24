package app.pocketportal

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** How hard the phone works to encode frames, traded against Wi-Fi bandwidth. */
enum class Quality(val label: String, val maxDimension: Int, val jpegQuality: Int, val fps: Int) {
    Sharp("Sharp", 1920, 80, 30),
    Balanced("Balanced", 1440, 70, 30),
    Fast("Fast", 1024, 60, 30),
}

/** Process-wide state shared by the UI, the capture service and the touch service. */
object MirrorState {
    const val PORT = 8080
    /** mDNS type the Quest app browses for; keep in sync with the quest module. */
    const val SERVICE_TYPE = "_pocketportal._tcp"

    val running = MutableStateFlow(false)
    val viewers = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val touchEnabled = MutableStateFlow(false)
    val pin = MutableStateFlow("")
    val quality = MutableStateFlow(Quality.Balanced)

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        pin.value = prefs.getString("pin", null) ?: newPin().also { prefs.edit().putString("pin", it).apply() }
        quality.value = runCatching { Quality.valueOf(prefs.getString("quality", "")!!) }.getOrDefault(Quality.Balanced)
    }

    fun regeneratePin(context: Context) {
        pin.value = newPin()
        prefs(context).edit().putString("pin", pin.value).apply()
    }

    fun setQuality(context: Context, value: Quality) {
        quality.value = value
        prefs(context).edit().putString("quality", value.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("pocketportal", Context.MODE_PRIVATE)

    private fun newPin(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()
}

/** The phone's full-screen size in pixels, including system bars, in its current rotation. */
@Suppress("DEPRECATION")
fun realScreenMetrics(context: Context): DisplayMetrics {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    return DisplayMetrics().also { display.getRealMetrics(it) }
}

/**
 * The phone's IPv4 address on Wi-Fi (or on its own hotspot), which is what the
 * headset types into its browser. Falls back to any private address.
 */
fun localIpAddress(): String? {
    val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, address) -> address.isSiteLocalAddress }
    val wifi = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") }
    return (wifi ?: candidates.firstOrNull())?.second?.hostAddress
}
