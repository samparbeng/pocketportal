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
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
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

private val Accent = Color(0xFF4FA3FF)
private val Muted = Color(0xFFA9B4C0)
private val Panel = Color(0xFF1C232B)
private const val DEFAULT_PORT = 8080

class MainActivity : ComponentActivity() {

    private lateinit var finder: PhoneFinder

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
                Surface(Modifier.fillMaxSize()) { App(finder) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
private fun App(finder: PhoneFinder) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quest", Context.MODE_PRIVATE) }
    var phone by remember { mutableStateOf<Phone?>(null) }
    var pin by remember { mutableStateOf<String?>(null) }
    // The phone whose PIN we're asking for, and why we're asking again.
    var asking by remember { mutableStateOf<Phone?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun pinKey(p: Phone) = "pin:${p.host}"
    fun connect(p: Phone) {
        val saved = prefs.getString(pinKey(p), null)
        if (saved != null) {
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
                onClose = { phone = null; pin = null },
                onWrongPin = {
                    prefs.edit().remove(pinKey(current)).apply()
                    pin = null
                    pinError = "That PIN didn't work. Check the PIN shown in Pocket Portal on the phone."
                    asking = current
                },
            )
        }
    } else {
        PhoneList(finder, onOpen = ::connect)
    }

    asking?.let { target ->
        PinDialog(
            phone = target,
            error = pinError,
            onDismiss = { asking = null; phone = null },
            onSubmit = { code ->
                prefs.edit().putString(pinKey(target), code).apply()
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

/** Accepts "192.168.1.20", "192.168.1.20:8080" or a full http:// address. */
private fun parseAddress(text: String): Phone? {
    val trimmed = text.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore('#')
    if (trimmed.isEmpty()) return null
    val host = trimmed.substringBefore(':')
    val port = trimmed.substringAfter(':', "").ifEmpty { "$DEFAULT_PORT" }.toIntOrNull() ?: return null
    val valid = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.matches(Regex("""[A-Za-z0-9.-]+"""))
    return if (valid && port in 1..65535) Phone(host, host, port) else null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Viewer(phone: Phone, pin: String, onClose: () -> Unit, onWrongPin: () -> Unit) {
    BackHandler(onBack = onClose)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val wrongPin by rememberUpdatedState(onWrongPin)
    // The viewer page calls this instead of showing its own PIN box when it runs in this app.
    val bridge = remember { QuestBridge { wrongPin() } }

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
                        // The viewer page hides its own full-screen button inside the app.
                        settings.userAgentString = settings.userAgentString + " PocketPortalQuest/1"
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (request.isForMainFrame) error = err.description.toString()
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
            error?.let { message ->
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
private class QuestBridge(private val onWrongPin: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun wrongPin() {
        main.post(onWrongPin)
    }
}
