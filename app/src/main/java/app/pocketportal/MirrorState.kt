package app.pocketportal

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** How hard the phone works to encode frames, traded against Wi-Fi bandwidth. */
// fps and jpegQuality apply to the JPEG fallback; bitrate and videoFps to H.265/H.264.
// bitrate is the ceiling: the stream backs off below it when Wi-Fi can't keep up.
// 120 fps matches the Quest 3's fastest refresh; the phone only sends frames when
// its screen changes, so a still screen costs nothing.
enum class Quality(
    val label: String,
    val maxDimension: Int,
    val jpegQuality: Int,
    val fps: Int,
    val bitrate: Int,
    val videoFps: Int,
) {
    Sharp("Sharp", 1920, 80, 30, 16_000_000, 120),
    Balanced("Balanced", 1440, 70, 30, 10_000_000, 120),
    Fast("Fast", 1024, 60, 30, 4_000_000, 60),
}

/** Process-wide state shared by the UI, the capture service and the touch service. */
object MirrorState {
    /** Encrypted viewer (HTTPS + secure WebSocket). */
    const val PORT = 8443
    /** Plain HTTP, only to redirect old http:// bookmarks to [PORT]. */
    const val REDIRECT_PORT = 8080
    /** mDNS type the Quest app browses for; keep in sync with the quest module. */
    const val SERVICE_TYPE = "_pocketportal._tcp"

    val running = MutableStateFlow(false)
    val viewers = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val touchEnabled = MutableStateFlow(false)
    val pin = MutableStateFlow("")
    val quality = MutableStateFlow(Quality.Balanced)
    /** Stream what the phone is playing to the headset. */
    val sound = MutableStateFlow(true)
    /** What the phone's own screen does while sharing. */
    val screenMode = MutableStateFlow(ScreenMode.Normal)
    /**
     * Headsets paired by scanning the QR code they show: secret key → headset name.
     * A paired headset connects with its key instead of the PIN.
     */
    val headsets = MutableStateFlow<Map<String, String>>(emptyMap())

    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        headsets.value = runCatching {
            val json = JSONObject(prefs.getString("headsets", "{}")!!)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        pin.value = prefs.getString("pin", null) ?: newPin().also { prefs.edit().putString("pin", it).apply() }
        quality.value = runCatching { Quality.valueOf(prefs.getString("quality", "")!!) }.getOrDefault(Quality.Balanced)
        sound.value = prefs.getBoolean("sound", true)
        screenMode.value = runCatching { ScreenMode.valueOf(prefs.getString("screen", "")!!) }.getOrDefault(ScreenMode.Normal)
    }

    fun regeneratePin(context: Context) {
        pin.value = newPin()
        prefs(context).edit().putString("pin", pin.value).apply()
    }

    fun setQuality(context: Context, value: Quality) {
        quality.value = value
        prefs(context).edit().putString("quality", value.name).apply()
    }

    fun setScreenMode(context: Context, value: ScreenMode) {
        screenMode.value = value
        prefs(context).edit().putString("screen", value.name).apply()
    }

    fun setSound(context: Context, value: Boolean) {
        sound.value = value
        prefs(context).edit().putBoolean("sound", value).apply()
    }

    /**
     * Reads a headset's pairing code, `pocketportal://pair?t=<key>&n=<name>`, and
     * remembers it. Returns the headset's name, or null if it isn't a Pocket Portal code.
     */
    fun pairHeadset(context: Context, code: String): String? {
        val uri = runCatching { Uri.parse(code.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "pocketportal" || uri.host != "pair") return null
        val key = uri.getQueryParameter("t")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
        val name = uri.getQueryParameter("n")?.take(40)?.ifBlank { null } ?: "Headset"
        // One entry per headset name, so re-pairing replaces the old key.
        val updated = headsets.value.filterValues { it != name } + (key to name)
        saveHeadsets(context, updated)
        return name
    }

    fun forgetHeadsets(context: Context) = saveHeadsets(context, emptyMap())

    private fun saveHeadsets(context: Context, value: Map<String, String>) {
        headsets.value = value
        prefs(context).edit().putString("headsets", JSONObject(value).toString()).apply()
    }

    /** True for the PIN or a paired headset's key; compared in constant time. */
    fun isAuthorized(key: String): Boolean {
        val given = key.toByteArray()
        var ok = MessageDigest.isEqual(given, pin.value.toByteArray())
        headsets.value.keys.forEach { ok = MessageDigest.isEqual(given, it.toByteArray()) || ok }
        return ok
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
