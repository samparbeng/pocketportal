package app.pocketportal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface

enum class CheckState { Ok, Info, Warn, Bad }

/** One line of the troubleshooter: what was checked, how it looks, and a fix when there is one. */
data class Check(
    val title: String,
    val state: CheckState,
    val detail: String,
    val fixLabel: String? = null,
    val fix: ((Context) -> Unit)? = null,
)

/**
 * Looks at the things that most often make Pocket Portal slow or stop working:
 * the Wi-Fi band and signal, the delay the headset measures, permissions, the
 * Wireless debugging setup, notification access, battery saver and heat.
 */
object Troubleshooter {

    fun run(context: Context): List<Check> = listOfNotNull(
        wifi(context),
        headset(context),
        touch(),
        permissions(context),
        setup(context),
        notifications(context),
        power(context),
    )

    // ---- Wi-Fi ------------------------------------------------------------------------------

    private fun wifi(context: Context): Check {
        val names = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp }.getOrDefault(false) && it.inetAddresses.toList().any { a -> a is Inet4Address && a.isSiteLocalAddress } }
            .map { it.name }
        val hotspot = names.any { it.startsWith("swlan") || it.startsWith("ap") }
        @Suppress("DEPRECATION")
        val info = context.getSystemService(WifiManager::class.java)?.connectionInfo
        val frequency = info?.frequency ?: -1
        if (frequency <= 0) {
            return if (hotspot) Check(
                "Using this phone's hotspot", CheckState.Ok,
                "The headset connects straight to the phone, which avoids a busy router. For the best picture, " +
                    "set the hotspot to 5 GHz in its settings.",
            ) else Check(
                "Not on Wi-Fi", CheckState.Bad,
                "Connect this phone to the same Wi-Fi network as the headset, or turn on this phone's hotspot and " +
                    "join it from the headset.",
                "Wi-Fi settings",
            ) { it.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val band = when {
            frequency < 3000 -> "2.4 GHz"
            frequency < 5925 -> "5 GHz"
            else -> "6 GHz"
        }
        val rssi = info?.rssi ?: -127
        val link = info?.linkSpeed ?: -1
        val problems = buildList {
            if (frequency < 3000) add(
                "2.4 GHz Wi-Fi is slow and crowded, so the picture lags and softens. Join your router's 5 GHz or " +
                    "6 GHz network (often named with \"5G\") on both the phone and the headset."
            )
            if (rssi < -70) add("The signal is weak ($rssi dBm). Move closer to the router, or use this phone's hotspot.")
            if (link in 1 until 150) add("The phone's Wi-Fi link is only $link Mbps; that usually means distance or interference.")
        }
        val summary = buildString {
            append("$band, ")
            append(if (rssi >= -60) "strong signal" else if (rssi >= -70) "good signal" else "weak signal")
            if (link > 0) append(", $link Mbps link")
        }
        return if (problems.isEmpty()) {
            Check("Wi-Fi looks good", CheckState.Ok, "$summary. Keep the headset on the same network.")
        } else {
            Check(
                "Wi-Fi could be faster", if (frequency < 3000 && rssi < -70) CheckState.Bad else CheckState.Warn,
                "$summary. " + problems.joinToString(" "),
                "Wi-Fi settings",
            ) { it.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ---- The headset link -------------------------------------------------------------------

    private fun headset(context: Context): Check {
        if (!MirrorState.running.value) return Check(
            "Headset connection", CheckState.Info,
            "Tap Connect and open Pocket Portal on the headset to measure the delay and picture rate here.",
        )
        if (MirrorState.viewers.value == 0) return Check(
            "Waiting for the headset", CheckState.Warn,
            "Open Pocket Portal on the Quest and pick this phone. If it isn't listed, check both are on the same " +
                "Wi-Fi network. Guest networks and some mesh routers keep devices apart; this phone's hotspot " +
                "avoids that." + (localIpAddress()?.let { " You can also open https://$it:${MirrorState.PORT} in the headset's browser." } ?: ""),
        )
        val stats = MirrorState.headsetStats.value ?: return Check(
            "Measuring the headset connection…", CheckState.Info, "The headset reports every couple of seconds.",
        )
        val latency = stats.latencyMs
        val ceiling = MirrorState.quality.value.bitrate
        val now = MirrorState.bitrateNow.value
        val squeezed = now in 1 until ceiling / 2
        val parts = buildList {
            if (stats.codec.isNotBlank()) add(stats.codec)
            add("${stats.fps} fps")
            add("${"%.1f".format(stats.mbps)} Mbps")
            if (latency >= 0) add("$latency ms delay")
        }.joinToString(" · ")
        val advice = buildList {
            if (latency > 70) add(
                "The delay is high. 5 GHz Wi-Fi, being closer to the router, or the Fast picture quality (in More " +
                    "options, after disconnecting) bring it down."
            )
            if (squeezed) add(
                "The Wi-Fi is only carrying ${now / 1_000_000} of ${ceiling / 1_000_000} Mbps, so the picture is " +
                    "softer than it could be."
            )
            if (latency < 0) add("This phone's clock can't be compared with the headset's, so the delay isn't shown.")
        }
        val state = when {
            latency > 130 -> CheckState.Bad
            latency > 70 || squeezed -> CheckState.Warn
            else -> CheckState.Ok
        }
        val title = when (state) {
            CheckState.Ok -> "Headset connection is smooth"
            CheckState.Bad -> "Headset connection is slow"
            else -> "Headset connection could be better"
        }
        val tip = if (stats.fps < 10 && state == CheckState.Ok) " A low frame rate is normal while the screen is still." else ""
        return Check(title, state, parts + ". " + advice.joinToString(" ") + tip)
    }

    // ---- Touch and permissions --------------------------------------------------------------

    private fun touch(): Check = when {
        AdbBridge.injectorReady.value -> Check("Remote touch is on", CheckState.Ok, "Touches go in through Wireless debugging, so sign-in and permission screens work too.")
        TouchService.instance != null -> Check("Remote touch is on", CheckState.Ok, "You can tap, swipe and type from the headset.")
        AdbBridge.paired.value -> Check("Remote touch is ready", CheckState.Ok, "It turns on through Wireless debugging when sharing starts.")
        else -> Check(
            "Remote touch is off", CheckState.Warn,
            "The headset can only watch. Use \"Turn on remote touch\" above.",
        )
    }

    private fun permissions(context: Context): Check {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 37 && !granted(Manifest.permission.ACCESS_LOCAL_NETWORK))
                add("Nearby devices / local network (the headset can't connect without it)")
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                add("Notifications (for the sharing notification and the Wireless debugging setup)")
            if (MirrorState.sound.value && !granted(Manifest.permission.RECORD_AUDIO))
                add("Audio (to stream sound; the microphone isn't used)")
        }
        if (missing.isEmpty()) return Check("Permissions are in place", CheckState.Ok, "Everything Pocket Portal asks for is allowed.")
        val blocking = missing.first().startsWith("Nearby")
        return Check(
            "Permissions missing", if (blocking) CheckState.Bad else CheckState.Warn,
            "Allow in App info → Permissions: " + missing.joinToString("; ") + ".",
            "Open App info",
        ) { it.startActivity(appInfo(it)) }
    }

    private fun setup(context: Context): Check? {
        val wanted = MirrorState.screenMode.value == ScreenMode.Off || MirrorState.dex.value
        if (!wanted) return null
        if (AdbBridge.paired.value) return Check(
            "Wireless debugging setup is done", CheckState.Ok,
            "Screen-off, DeX and shell touch work. Leave Developer options on; Pocket Portal turns Wireless debugging back on by itself.",
        )
        val notificationsOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return Check(
            "Wireless debugging setup needed", CheckState.Warn,
            (if (MirrorState.dex.value) "DeX" else "Turning the screen off") + " needs a one-time setup through " +
                "Wireless debugging in Developer options. Until then the headset shows the phone screen and it only dims.",
            if (notificationsOk) "Start setup" else "Allow notifications first",
        ) { if (notificationsOk) AdbBridge.startPairing(it) else it.startActivity(appInfo(it)) }
    }

    private fun notifications(context: Context): Check? {
        if (!MirrorState.notifications.value) return null
        if (HeadsetNotifications.hasAccess(context)) return Check(
            "Headset notifications are on", CheckState.Ok, "Notifications and battery warnings show in the headset.",
        )
        return Check(
            "Notification access is off", CheckState.Warn,
            "The headset can't show your notifications until Pocket Portal is allowed to read them.",
            "Allow access",
        ) { ctx ->
            if (AdbBridge.paired.value) {
                AdbBridge.allowNotificationAccess(ctx) { ok ->
                    if (!ok) ctx.mainExecutor.execute { ctx.startActivity(HeadsetNotifications.accessSettingsIntent(ctx)) }
                }
            } else {
                ctx.startActivity(HeadsetNotifications.accessSettingsIntent(ctx))
            }
        }
    }

    // ---- Power and heat ---------------------------------------------------------------------

    private fun power(context: Context): Check {
        val pm = context.getSystemService(PowerManager::class.java)
        val saver = pm.isPowerSaveMode
        val hot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        return when {
            saver -> Check(
                "Battery saver is on", CheckState.Warn,
                "It slows the phone and its Wi-Fi, so the picture can stutter. Turn it off while sharing" +
                    (if (hot) ". The phone is also running hot." else "."),
                "Battery saver",
            ) { it.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            hot -> Check(
                "Phone is running hot", CheckState.Warn,
                "Android slows a hot phone down, which shows as stutter. Take it out of its case, keep it off the " +
                    "charger for a while, or pick Balanced or Fast picture quality.",
            )
            else -> Check("Power and temperature are fine", CheckState.Ok, "Battery saver is off and the phone isn't overheating.")
        }
    }

    private fun appInfo(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** The troubleshooter's list, refreshed every couple of seconds while it's open. */
@Composable
fun TroubleshootPanel() {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(Troubleshooter.run(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            checks = Troubleshooter.run(context)
        }
    }
    val issues = checks.count { it.state == CheckState.Warn || it.state == CheckState.Bad }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (issues == 0) "Everything checks out." else if (issues == 1) "1 thing to look at." else "$issues things to look at.",
            color = if (issues == 0) Live else Warn,
        )
        checks.sortedByDescending { it.state.ordinal }.forEach { CheckRow(it) }
    }
}

@Composable
private fun CheckRow(check: Check) {
    val context = LocalContext.current
    val color = when (check.state) {
        CheckState.Ok -> Live
        CheckState.Info -> Accent
        CheckState.Warn -> Warn
        CheckState.Bad -> Danger
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(check.title, fontWeight = FontWeight.SemiBold)
            Text(check.detail, color = Muted, fontSize = 13.sp)
            if (check.fix != null && check.fixLabel != null) {
                OutlinedButton(onClick = { check.fix.invoke(context) }) { Text(check.fixLabel) }
            }
        }
    }
}
