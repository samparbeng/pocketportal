package app.pocketportal

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MirrorState.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color.Black,
                    background = Color(0xFF101418),
                    surface = Color(0xFF101418),
                    surfaceVariant = Color(0xFF1C232B),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) { MirrorScreen() }
            }
        }
    }
}

@Composable
private fun MirrorScreen() {
    val context = LocalContext.current
    val running by MirrorState.running.collectAsStateWithLifecycle()
    val viewers by MirrorState.viewers.collectAsStateWithLifecycle()
    val touch by MirrorState.touchEnabled.collectAsStateWithLifecycle()
    val pin by MirrorState.pin.collectAsStateWithLifecycle()
    val quality by MirrorState.quality.collectAsStateWithLifecycle()
    val sound by MirrorState.sound.collectAsStateWithLifecycle()
    val error by MirrorState.error.collectAsStateWithLifecycle()
    var showDisclosure by remember { mutableStateOf(false) }

    // The Wi-Fi address can change while the app is open, so keep checking it.
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
    fun start() {
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pocket Portal", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("See and use this phone inside your VR headset over Wi-Fi.", color = Muted)

        Step("1. Remote touch", if (touch) "On" else "Off", touch) {
            Text(
                if (touch) "The headset can tap, swipe and type on this phone."
                else "Switch on \"Pocket Portal remote touch\" so the headset can control the phone. " +
                    "Without it, the headset can only watch.",
                color = Muted,
            )
            if (!touch) {
                Button(onClick = { showDisclosure = true }) {
                    Text("Turn on remote touch")
                }
                Text(
                    "On Samsung: Accessibility → Installed apps → Pocket Portal remote touch. " +
                        "If the switch is greyed out (\"Restricted setting\"), open App info, tap ⋮ " +
                        "and choose \"Allow restricted settings\", then try again.",
                    color = Muted, fontSize = 13.sp,
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Open App info") }
            }
        }

        Step("2. Share screen", if (running) "Live" else "Stopped", running) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stream sound")
                    Text(
                        "Play this phone's media and game sound in the headset. Android asks for audio " +
                            "permission once; the microphone isn't used.",
                        color = Muted, fontSize = 13.sp,
                    )
                }
                Switch(
                    checked = sound,
                    enabled = !running,
                    onCheckedChange = { MirrorState.setSound(context, it) },
                )
            }
            if (running) {
                OutlinedButton(onClick = { MirrorService.stop(context) }) { Text("Stop sharing") }
            } else {
                Button(onClick = { start() }, enabled = address != null) { Text("Start sharing") }
                Text("When Android asks, choose to share the entire screen.", color = Muted, fontSize = 13.sp)
            }
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
        }

        Step("3. Open in the headset", if (viewers > 0) "$viewers connected" else "", viewers > 0) {
            if (address == null) {
                Text("Connect this phone to the same Wi-Fi as your headset.", color = Color(0xFFFF8A80))
            } else {
                Text("Open Pocket Portal on the headset and pick this phone, or go to this address in the headset's browser:", color = Muted)
                Text(
                    "http://$address:${MirrorState.PORT}",
                    fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = Accent,
                )
                Text("PIN", color = Muted)
                Text(pin, fontSize = 32.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { MirrorState.regeneratePin(context) }, enabled = !running) {
                    Text("New PIN")
                }
            }
        }

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
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDecline = { showDisclosure = false },
        )
    }
}

/**
 * Google Play requires this prominent disclosure, with an explicit choice,
 * before an app that isn't an accessibility tool sends the user to switch on
 * its accessibility service.
 */
@Composable
private fun AccessibilityDisclosure(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Allow remote touch?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pocket Portal uses Android's Accessibility Service API so you can control this phone " +
                        "from your VR headset."
                )
                Text("When it's on, Pocket Portal will:")
                Text("• Perform the taps, swipes and Back / Home / Recents presses you make in the headset viewer.")
                Text("• Read the text box you're typing into, only so it can add the text you type in the headset.")
                Text(
                    "It doesn't collect, store or share this information. Your screen and input travel only " +
                        "between this phone and your own headset on your Wi-Fi, protected by the PIN. " +
                        "You can switch remote touch off at any time in Accessibility settings."
                )
                Text(
                    "Next, Android opens Accessibility settings. Choose Pocket Portal remote touch and switch it on.",
                    color = Muted,
                )
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Agree and continue") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } },
    )
}

@Composable
private fun Step(title: String, status: String, active: Boolean, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = if (active) Accent else Muted)
            }
            content()
        }
    }
}
