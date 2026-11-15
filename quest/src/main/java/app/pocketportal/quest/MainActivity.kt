package app.pocketportal.quest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import java.security.MessageDigest
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val Accent = Color(0xFF4FA3FF)
private val Muted = Color(0xFFA9B4C0)
private val Panel = Color(0xFF1C232B)
private const val DEFAULT_PORT = 8443
private const val LEGACY_PORT = 8080

class MainActivity : ComponentActivity() {

    private lateinit var finder: PhoneFinder
    /** Counts each time the panel comes back (e.g. the headset wakes), to reopen the last phone. */
    private val starts = MutableStateFlow(0)

    private val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        finder.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finder = PhoneFinder(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color.Black,
                    background = Color(0xFF101418),
                    surface = Color(0xFF101418),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) { App(finder, starts) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        starts.value++
        // Android 17+ asks before an app may talk to devices on the local network.
        val needsPermission = Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_LOCAL_NETWORK
        ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) else finder.start()
    }

    override fun onStop() {
        finder.stop()
        super.onStop()
    }
}

@Composable
private fun App(finder: PhoneFinder, starts: StateFlow<Int>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quest", Context.MODE_PRIVATE) }
    var phone by remember { mutableStateOf<Phone?>(null) }
    var pin by remember { mutableStateOf<String?>(null) }
    // The phone whose PIN we're asking for, and why we're asking again.
    var asking by remember { mutableStateOf<Phone?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    // Reopen the last phone by itself when it shows up, until the user backs out; again each time the panel returns.
    var autoOpen by remember { mutableStateOf(true) }
    val started by starts.collectAsStateWithLifecycle()
    // ...except right after the user closed the wide DeX window.
    LaunchedEffect(started) { autoOpen = !WideViewerActivity.consumeUserClosed() }

    fun pinKey(p: Phone) = "pin:${p.host}"
    fun certKey(p: Phone) = "cert:${p.host}"
    fun connect(p: Phone) {
        val saved = prefs.getString(pinKey(p), null)
        if (saved != null) {
            prefs.edit().putString("last", p.host).apply()
            phone = p
            pin = saved
        } else {
            pinError = null
            asking = p
        }
    }

    val current = phone
    val currentPin = pin
    if (current != null && currentPin != null) {
        key(current, currentPin) {
            Viewer(
                phone = current,
                pin = currentPin,
                // A fingerprint advertised over mDNS wins (the phone renews its certificate yearly);
                // otherwise use the one remembered from the first connection.
                trustedFingerprint = current.fingerprint ?: prefs.getString(certKey(current), null),
                onTrust = { prefs.edit().putString(certKey(current), it).apply() },
                onClose = { phone = null; pin = null; autoOpen = false },
                // DeX is landscape: carry on in the wide window.
                onLandscape = { wide ->
                    if (wide) {
                        val fingerprint = current.fingerprint ?: prefs.getString(certKey(current), null)
                        WideViewerActivity.open(context, current, currentPin, fingerprint)
                        phone = null; pin = null; autoOpen = false
                        (context as? android.app.Activity)?.finish()
                    }
                },
                onWrongPin = {
                    autoOpen = false
                    prefs.edit().remove(pinKey(current)).apply()
                    pin = null
                    pinError = "That PIN didn't work. Check the PIN shown in Pocket Portal on the phone."
                    asking = current
                },
            )
        }
    } else {
        val phones by finder.phones.collectAsStateWithLifecycle()
        // Only a phone this headset has already connected to with a saved PIN.
        LaunchedEffect(phones, autoOpen) {
            if (!autoOpen) return@LaunchedEffect
            val last = prefs.getString("last", null) ?: return@LaunchedEffect
            phones.firstOrNull { it.host == last && prefs.getString(pinKey(it), null) != null }?.let(::connect)
        }
        PhoneList(finder, onOpen = { autoOpen = false; connect(it) })
    }

    asking?.let { target ->
        PinDialog(
            phone = target,
            error = pinError,
            onDismiss = { asking = null; phone = null },
            onSubmit = { code ->
                prefs.edit().putString(pinKey(target), code).putString("last", target.host).apply()
                asking = null
                phone = target
                pin = code
            },
        )
    }
}

@Composable
private fun PinDialog(phone: Phone, error: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        if (code.length == 6) onSubmit(code)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${phone.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the 6-digit PIN shown in Pocket Portal on the phone.", color = Muted)
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("••••••") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
            }
        },
        confirmButton = { Button(onClick = { submit() }, enabled = code.length == 6) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PhoneList(finder: PhoneFinder, onOpen: (Phone) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quest", Context.MODE_PRIVATE) }
    val phones by finder.phones.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(prefs.getString("address", "").orEmpty()) }
    var addressError by remember { mutableStateOf<String?>(null) }

    fun open(phone: Phone) {
        prefs.edit().putString("address", if (phone.port == DEFAULT_PORT) phone.host else "${phone.host}:${phone.port}").apply()
        onOpen(phone)
    }
    fun openTyped() {
        val phone = parseAddress(address)
        if (phone == null) addressError = "Enter the address shown on the phone, like 192.168.1.20"
        else { addressError = null; open(phone) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Pocket Portal", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(
            "Open Pocket Portal on your phone and tap Start sharing. Phones on this Wi-Fi show up here.",
            color = Muted, fontSize = 18.sp,
        )

        Text("Phones nearby", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (phones.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text("Looking for phones…", color = Muted, fontSize = 18.sp)
            }
        }
        phones.forEach { phone ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { open(phone) },
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(Color(0xFF4CD787), RoundedCornerShape(7.dp))
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(phone.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(phone.host, color = Muted, fontFamily = FontFamily.Monospace)
                    }
                    Text("Connect", color = Accent, fontSize = 18.sp)
                }
            }
        }

        Text("Or type the address", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; addressError = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("192.168.1.20") },
            isError = addressError != null,
            supportingText = addressError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { openTyped() }),
        )
        Button(onClick = { openTyped() }) { Text("Connect", fontSize = 18.sp) }
        Text(
            "Not listed? Make sure the phone and this headset are on the same Wi-Fi " +
                "and that sharing is on.",
            color = Muted,
        )
    }
}

/** Accepts "192.168.1.20", "192.168.1.20:8443" or a full https:// address. */
private fun parseAddress(text: String): Phone? {
    val trimmed = text.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore('#')
    if (trimmed.isEmpty()) return null
    val host = trimmed.substringBefore(':')
    // 8080 was the unencrypted port of earlier versions; the phone now listens on 8443.
    val port = trimmed.substringAfter(':', "").ifEmpty { "$DEFAULT_PORT" }.toIntOrNull()
        ?.let { if (it == LEGACY_PORT) DEFAULT_PORT else it } ?: return null
    val valid = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.matches(Regex("""[A-Za-z0-9.-]+"""))
    return if (valid && port in 1..65535) Phone(host, host, port) else null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun Viewer(
    phone: Phone,
    pin: String,
    trustedFingerprint: String?,
    onTrust: (String) -> Unit,
    onClose: () -> Unit,
    onWrongPin: () -> Unit,
    /** The phone says whether it's showing DeX (landscape) or its own screen. */
    onLandscape: (Boolean) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // A certificate that doesn't match the one this phone used before.
    var unexpectedCert by remember { mutableStateOf<String?>(null) }
    val wrongPin by rememberUpdatedState(onWrongPin)
    val trust by rememberUpdatedState(onTrust)
    val trusted by rememberUpdatedState(trustedFingerprint)
    // The viewer page calls this instead of showing its own PIN box when it runs in this app.
    val landscape by rememberUpdatedState(onLandscape)
    val bridge = remember { QuestBridge(onWrongPin = { wrongPin() }, onShape = { landscape(it) }) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹ Phones", fontSize = 18.sp) }
            Text(phone.name, Modifier.weight(1f), color = Muted, fontSize = 16.sp)
            TextButton(onClick = { error = null; webView?.reload() }) { Text("Reload", fontSize = 18.sp) }
        }
        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // Without explicit MATCH_PARENT the WebView sizes its viewport to the
                        // page's content, so the page's height:100% layout collapses to zero.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The phone's sound should start as soon as the viewer connects.
                        settings.mediaPlaybackRequiresUserGesture = false
                        // The viewer page hides its own full-screen button inside the app.
                        settings.userAgentString = settings.userAgentString + " PocketPortalQuest/1"
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (request.isForMainFrame) error = err.description.toString()
                            }

                            // The phone signs its own certificate, so the WebView always flags it.
                            // Accept it only when it matches the fingerprint the phone advertised
                            // or the one it used the first time (trust on first use).
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, err: SslError) {
                                val seen = err.certificate.x509Certificate?.let { sha256Hex(it.encoded) }
                                val expected = trusted
                                when {
                                    seen == null -> handler.cancel()
                                    expected == null -> { trust(seen); handler.proceed() }
                                    seen.equals(expected, ignoreCase = true) -> handler.proceed()
                                    else -> { handler.cancel(); unexpectedCert = seen }
                                }
                            }
                        }
                        addJavascriptInterface(bridge, "PocketPortalApp")
                        // The page reads the PIN from the #fragment, which never leaves the headset.
                        loadUrl("${phone.url}#$pin")
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            unexpectedCert?.let { seen ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101418))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Security code changed", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This device's security code doesn't match the one ${phone.name} used before. " +
                            "That happens after reinstalling Pocket Portal on the phone, but it could also mean " +
                            "another device is pretending to be your phone.\n\nCompare with the security code " +
                            "shown in Pocket Portal on the phone:",
                        color = Muted,
                    )
                    Text(
                        seen.take(16).chunked(4).joinToString(" "),
                        fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                    )
                    Button(onClick = { trust(seen); unexpectedCert = null; webView?.reload() }) {
                        Text("They match, connect", fontSize = 18.sp)
                    }
                    TextButton(onClick = onClose) { Text("Back to phones", fontSize = 18.sp) }
                }
            }
            error?.takeIf { unexpectedCert == null }?.let { message ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101418))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Can't reach ${phone.name}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Check that sharing is on in Pocket Portal on the phone and that both are on the same Wi-Fi.\n($message)",
                        color = Muted,
                    )
                    Button(onClick = { error = null; webView?.reload() }) { Text("Try again", fontSize = 18.sp) }
                    TextButton(onClick = onClose) { Text("Back to phones", fontSize = 18.sp) }
                }
            }
        }
    }
}

/** Exposed to the viewer page as `window.PocketPortalApp`. */
private class QuestBridge(private val onWrongPin: () -> Unit, private val onShape: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun wrongPin() {
        main.post(onWrongPin)
    }

    /** Called with true when the phone streams DeX, false for its own screen. */
    @JavascriptInterface
    fun shape(landscape: Boolean) {
        main.post { onShape(landscape) }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
