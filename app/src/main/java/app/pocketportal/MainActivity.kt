package app.pocketportal

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Accent = Color(0xFF4FA3FF)
private val Muted = Color(0xFFA9B4C0)
private val Live = Color(0xFF4CD787)
private val Warn = Color(0xFFFFB74D)
private val Danger = Color(0xFFFF8A80)
private val Panel = Color(0xFF1C232B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MirrorState.init(this)
        AdbBridge.init(this)
        // If a previous session was killed while dimmed, give the user their brightness back.
        if (!MirrorState.running.value) PhoneScreen.undim(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color.Black,
                    background = Color(0xFF101418),
                    surface = Color(0xFF101418),
                    surfaceVariant = Panel,
                ),
            ) {
                Surface(Modifier.fillMaxSize()) { MirrorScreen() }
            }
        }
    }
}

/**
 * One screen: a status line, one big Connect button, and the two things that
 * might need doing once (pair the headset, switch on remote touch). Everything
 * else is under "More options".
 */
@Composable
private fun MirrorScreen() {
    val context = LocalContext.current
    val running by MirrorState.running.collectAsStateWithLifecycle()
    val viewers by MirrorState.viewers.collectAsStateWithLifecycle()
    val touch by MirrorState.touchEnabled.collectAsStateWithLifecycle()
    val pin by MirrorState.pin.collectAsStateWithLifecycle()
    val sound by MirrorState.sound.collectAsStateWithLifecycle()
    val error by MirrorState.error.collectAsStateWithLifecycle()
    var showDisclosure by remember { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }

    // The Wi-Fi address can change while the app is open, so keep checking.
    var address by remember { mutableStateOf(localIpAddress()) }
    LaunchedEffect(Unit) {
        while (true) {
            address = localIpAddress()
            delay(3000)
        }
    }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MirrorService.start(context, result.resultCode, data)
        }
    }
    fun requestCapture() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        // Ask for the whole screen: touch coordinates assume the frame covers it.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        capture.launch(intent)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_LOCAL_NETWORK] == false) {
            MirrorState.error.value = "Pocket Portal needs the \"Nearby devices\" / local network permission, " +
                "or the headset can't connect. Allow it in App info → Permissions."
        } else {
            // Without the audio permission, sharing still works, just silently.
            if (result[Manifest.permission.RECORD_AUDIO] == false) MirrorState.setSound(context, false)
            MirrorState.error.value = null
            requestCapture()
        }
    }
    fun connect() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (sound) add(Manifest.permission.RECORD_AUDIO)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else requestCapture()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Pocket Portal", fontSize = 30.sp, fontWeight = FontWeight.Bold)

        // ---- Status and the one big button -----------------------------------------------
        val (statusColor, statusText) = when {
            address == null -> Danger to "Connect this phone to the same Wi-Fi as your headset."
            !running -> Muted to "Ready. Put on your headset, then tap Connect."
            viewers > 0 -> Live to if (viewers == 1) "Connected to your headset" else "Connected to $viewers viewers"
            else -> Warn to "Waiting for the headset… Open Pocket Portal on it."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(statusText, color = if (statusColor == Muted) Muted else Color.White, fontSize = 17.sp)
        }

        if (running) {
            OutlinedButton(
                onClick = { MirrorService.stop(context) },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(20.dp),
            ) { Text("Disconnect", fontSize = 22.sp) }
        } else {
            Button(
                onClick = { connect() },
                enabled = address != null,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("Connect", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            Text("Android asks to share your screen: choose \"Entire screen\".", color = Muted, fontSize = 13.sp)
        }
        error?.let { Text(it, color = Danger) }

        // The headset app asks for this once per phone and remembers it.
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PIN for the headset", color = Muted)
                Text(pin, fontSize = 36.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    "Open Pocket Portal on the Quest and pick this phone. It asks for the PIN the first time only.",
                    color = Muted, fontSize = 13.sp,
                )
            }
        }

        // ---- One-time setup, shown only while something is missing ------------------------
        if (!touch) {
            SetupCard(
                title = "Turn on remote touch",
                body = "Lets you tap, swipe and type on this phone from the headset. Without it the headset can only watch.",
                action = "Turn on",
                onAction = { showDisclosure = true },
            )
        }

        // ---- Everything else ---------------------------------------------------------------
        HorizontalDivider(color = Color(0xFF26303B))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showOptions = !showOptions }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("More options", Modifier.weight(1f), fontSize = 17.sp)
            Text(if (showOptions) "▴" else "▾", color = Muted, fontSize = 17.sp)
        }
        if (showOptions) MoreOptions(address, running)

        val privacyUrl = stringResource(R.string.privacy_policy_url)
        if (privacyUrl.isNotBlank()) {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl))) }) {
                Text("Privacy policy", color = Muted)
            }
        }
    }

    if (showDisclosure) {
        AccessibilityDisclosure(
            onAccept = {
                showDisclosure = false
                openTouchSettings(context)
            },
            onDecline = { showDisclosure = false },
        )
    }
}

/** Opens Accessibility settings with Pocket Portal's switch highlighted where the phone supports it. */
private fun openTouchSettings(context: android.content.Context) {
    val component = ComponentName(context, TouchService::class.java).flattenToString()
    val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", component)
        .putExtra(":settings:show_fragment_args", args)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Muted)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun MoreOptions(address: String?, running: Boolean) {
    val context = LocalContext.current
    val quality by MirrorState.quality.collectAsStateWithLifecycle()
    val sound by MirrorState.sound.collectAsStateWithLifecycle()
    val screenMode by MirrorState.screenMode.collectAsStateWithLifecycle()
    val pin by MirrorState.pin.collectAsStateWithLifecycle()
    val paired by AdbBridge.paired.collectAsStateWithLifecycle()
    val pairingStatus by AdbBridge.status.collectAsStateWithLifecycle()
    var canDim by remember { mutableStateOf(PhoneScreen.canDim(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            canDim = PhoneScreen.canDim(context)
            delay(3000)
        }
    }
    // The pairing code is typed into a notification, so ask for that permission first.
    val setupNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        AdbBridge.startPairing(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (running) Text("Stop sharing to change these.", color = Muted, fontSize = 13.sp)

        Text("Picture quality", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q ->
                FilterChip(
                    selected = q == quality,
                    enabled = !running,
                    onClick = { MirrorState.setQuality(context, q) },
                    label = { Text(q.label) },
                )
            }
        }
        Text(
            "Uses H.265 when the headset supports it, otherwise H.264, at up to " +
                "${quality.videoFps} fps. Quality drops by itself if the Wi-Fi can't keep up.",
            color = Muted, fontSize = 13.sp,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Stream sound")
                Text(
                    "Play this phone's media and game sound in the headset, in stereo. Android asks for audio " +
                        "permission once; the microphone isn't used. Phone calls can't be streamed.",
                    color = Muted, fontSize = 13.sp,
                )
            }
            Switch(checked = sound, enabled = !running, onCheckedChange = { MirrorState.setSound(context, it) })
        }

        NotificationsOption(paired)

        Text("Phone screen while sharing", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScreenMode.entries.forEach { m ->
                FilterChip(
                    selected = m == screenMode,
                    enabled = !running,
                    onClick = { MirrorState.setScreenMode(context, m) },
                    label = { Text(m.label) },
                )
            }
        }
        if (screenMode == ScreenMode.Off) {
            if (paired) {
                Text(
                    "The phone's screen turns off while sharing; the headset still shows everything. " +
                        "Press the power button twice to see the phone's screen again.",
                    color = Muted, fontSize = 13.sp,
                )
            } else {
                Text(
                    "Switching the screen off needs a one-time setup through Wireless debugging " +
                        "(in Developer options). After that it works on its own, even after a restart. " +
                        "Until then the screen dims instead.",
                    color = Muted, fontSize = 13.sp,
                )
                OutlinedButton(onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (Build.VERSION.SDK_INT >= 33 && !granted) setupNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else AdbBridge.startPairing(context)
                }) { Text("Set up screen-off") }
            }
            pairingStatus?.takeIf { !paired || it.startsWith("Screen-off") }?.let {
                Text(it, color = Accent, fontSize = 13.sp)
            }
        }
        val needsDim = screenMode == ScreenMode.Dim || (screenMode == ScreenMode.Off && !paired)
        if (needsDim && !canDim) {
            Text("To dim the screen, allow Pocket Portal to change the brightness.", color = Muted, fontSize = 13.sp)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Allow brightness control") }
        }

        HorizontalDivider(color = Color(0xFF26303B))
        val dex by MirrorState.dex.collectAsStateWithLifecycle()
        Text("The headset shows", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !dex, enabled = !running, onClick = { MirrorState.setDex(context, false) }, label = { Text("Phone screen") })
            FilterChip(selected = dex, enabled = !running, onClick = { MirrorState.setDex(context, true) }, label = { Text("DeX desktop") })
        }
        if (dex) Text(
            if (paired) "A Samsung DeX desktop opens in the headset, separate from the phone's screen. " +
                "Apps you open there move back to the phone when you disconnect."
            else "DeX needs the one-time Wireless debugging setup (Phone screen while sharing → Off → " +
                "Set up screen-off). Until then the headset shows the phone screen.",
            color = Muted, fontSize = 13.sp,
        )

        HorizontalDivider(color = Color(0xFF26303B))
        Text("Use a web browser instead", color = Muted)
        if (address != null) {
            Text(
                "In the headset's browser, go to this address and enter the PIN. The browser warns that the " +
                    "connection isn't private because the phone signs its own certificate: choose Advanced → Proceed.",
                color = Muted, fontSize = 13.sp,
            )
            Text("https://$address:${MirrorState.PORT}", fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Accent)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PIN  $pin", color = Muted, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { MirrorState.regeneratePin(context) }, enabled = !running) { Text("New PIN") }
        }
        val fingerprint = remember(running) { CertStore.fingerprint() }
        fingerprint?.let {
            Text(
                "Security code: " + it.take(16).chunked(4).joinToString(" "),
                color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            )
        }

        HorizontalDivider(color = Color(0xFF26303B))
        Text("Remote touch switch greyed out?", color = Muted)
        Text(
            "Android blocks it for apps installed outside the Play Store. Open App info, tap ⋮, choose " +
                "\"Allow restricted settings\", then turn remote touch on again.",
            color = Muted, fontSize = 13.sp,
        )
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("Open App info") }
    }
}

/**
 * "Notifications in the headset": toasts with quick reply, plus battery warnings.
 * Can change while sharing. Needs notification access, which Pocket Portal grants
 * itself when the Wireless debugging setup is done, else the user allows it in Settings.
 */
@Composable
private fun NotificationsOption(paired: Boolean) {
    val context = LocalContext.current
    val on by MirrorState.notifications.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(HeadsetNotifications.hasAccess(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            access = HeadsetNotifications.hasAccess(context)
            delay(2000)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Notifications in the headset")
            Text(
                "Your phone's notifications pop up in the headset, where you can reply to messages, open " +
                    "them, or dismiss them. Low battery and charger changes show too. They're only sent " +
                    "to a headset you've connected with your PIN.",
                color = Muted, fontSize = 13.sp,
            )
        }
        Switch(checked = on, onCheckedChange = { MirrorState.setNotifications(context, it) })
    }
    if (on && !access) {
        Text(
            "Pocket Portal needs notification access to show them. Battery warnings work without it.",
            color = Muted, fontSize = 13.sp,
        )
        OutlinedButton(onClick = {
            if (paired) {
                status = "Allowing…"
                AdbBridge.allowNotificationAccess(context) { ok ->
                    status = if (ok) null else "Couldn't allow it automatically; opening Settings instead."
                    if (!ok) context.mainExecutor.execute { context.startActivity(HeadsetNotifications.accessSettingsIntent(context)) }
                }
            } else {
                context.startActivity(HeadsetNotifications.accessSettingsIntent(context))
            }
        }) { Text("Allow notification access") }
        if (!paired) Text(
            "If the switch in Settings is greyed out, use \"Allow restricted settings\" in App info (below), then try again.",
            color = Muted, fontSize = 13.sp,
        )
        status?.let { Text(it, color = Accent, fontSize = 13.sp) }
    }
}

/**
 * Google Play requires this prominent disclosure, with an explicit choice,
 * before an app that isn't an accessibility tool sends the user to switch on
 * its accessibility service. It also shows, as a short looping animation, the
 * three taps the user is about to make in Settings.
 */
@Composable
private fun AccessibilityDisclosure(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Allow remote touch?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pocket Portal uses Android's Accessibility Service API so you can control this phone " +
                        "from your VR headset. When it's on, Pocket Portal will:"
                )
                Text("• Perform the taps, swipes and Back / Home / Recents presses you make in the headset.")
                Text("• Read the text box you're typing into, only so it can add the text you type in the headset.")
                Text(
                    "It doesn't collect, store or share this information. Your screen and input travel only " +
                        "between this phone and your own headset on your local network. You can switch remote " +
                        "touch off at any time in Accessibility settings."
                )
                Text("Next, in Settings:", color = Muted)
                SettingsWalkthrough()
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Agree and continue") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } },
    )
}

/** The three taps in Accessibility settings, lighting up one after another on a loop. */
@Composable
private fun SettingsWalkthrough() {
    val steps = listOf("Installed apps", "Pocket Portal remote touch", "Switch on  ●")
    var current by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1100)
            current = (current + 1) % (steps.size + 1) // a beat of rest after the last step
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, label ->
            val lit = i == current
            val border by animateColorAsState(if (lit) Accent else Color(0xFF26303B), label = "step")
            val text by animateColorAsState(if (lit) Color.White else Muted, label = "stepText")
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = border, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(label, color = text)
                Spacer(Modifier.weight(1f))
                if (lit) Text("tap", color = Accent, fontSize = 13.sp)
            }
        }
    }
}
