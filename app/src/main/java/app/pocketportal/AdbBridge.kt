package app.pocketportal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Gives the app shell-level access by connecting to the phone's own Wireless
 * debugging, the way Shizuku and LADB do. It's used for one thing: running
 * [PanelPower] to switch the phone's screen off and on while sharing.
 *
 * Setup is once: the user pairs with the code from Settings → Wireless
 * debugging, typed into a notification ([PairingReceiver]). On that first
 * connection the app grants itself WRITE_SECURE_SETTINGS, so afterwards it can
 * switch Wireless debugging back on by itself, even after a restart.
 */
object AdbBridge {
    private const val TAG = "AdbBridge"
    private const val CHANNEL = "setup"
    private const val NOTIFICATION_ID = 2
    private const val KEY_ALIAS = "pocketportal-adb-key-wrap"
    const val ACTION_PAIR = "app.pocketportal.PAIR"
    const val EXTRA_CODE = "code"

    /** Paired with Wireless debugging at least once, so screen-off can work. */
    val paired = MutableStateFlow(false)
    /** Human-readable progress of pairing, shown in the app and the notification. */
    val status = MutableStateFlow<String?>(null)

    // libadb's calls block, so they all run here, one at a time.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "pocketportal-adb") }
    private var manager: Manager? = null
    private var pairingMdns: AdbMdns? = null
    @Volatile private var pairingHost: InetAddress? = null
    @Volatile private var pairingPort = -1

    fun init(context: Context) {
        paired.value = prefs(context).getBoolean("paired", false)
    }

    // ---- Pairing -------------------------------------------------------------------------

    /**
     * Opens Wireless debugging and posts a notification with a reply box for the
     * pairing code, so the user can type it while Settings stays open (leaving
     * Settings would close the pairing dialog). Listens for the pairing port meanwhile.
     */
    fun startPairing(context: Context) {
        val app = context.applicationContext
        // If Wireless debugging already trusts this app (paired earlier), no code is needed.
        if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1 || canWriteSecureSettings(app)) {
            status.value = "Connecting…"
            worker.execute {
                if (finishSetup(app)) return@execute
                main.post { askForCode(app) }
            }
            return
        }
        askForCode(app)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Connects, grants WRITE_SECURE_SETTINGS (so Wireless debugging can be switched back
     * on after a restart) and records that screen-off works. Runs on the worker thread.
     */
    private fun finishSetup(app: Context): Boolean {
        if (!connect(app)) return false
        val result = shell(app, "pm grant ${app.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
        Log.i(TAG, "grant: ${result.trim().ifEmpty { "ok" }}")
        prefs(app).edit().putBoolean("paired", true).apply()
        pairingMdns?.stop()
        pairingMdns = null
        paired.value = true
        status.value = "Screen-off is set up."
        notifyDone(app, "Screen-off is set up. You can close Wireless debugging.")
        return true
    }

    private fun askForCode(app: Context) {
        status.value = "In Wireless debugging, tap \"Pair device with pairing code\", then type the code in the notification."
        pairingPort = -1
        pairingMdns?.stop()
        // Each time the pairing dialog opens it advertises a new port, and the old one
        // closes; a port of 0 or less means the advertisement went away.
        pairingMdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            pairingHost = host
            pairingPort = port
            Log.i(TAG, "pairing port ${if (port > 0) "advertised: $port" else "gone"}")
        }.also { it.start() }
        notifyPairing(app, "Type the 6-digit pairing code shown in Wireless debugging.")
        openWirelessDebugging(app)
    }

    fun openWirelessDebugging(context: Context) {
        // Deep link to the Wireless debugging row in Developer options, where supported.
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    internal fun pair(context: Context, code: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            try {
                // The pairing port is only advertised while the pairing dialog is open.
                for (i in 0 until 50) {
                    if (pairingPort > 0) break
                    Thread.sleep(100)
                }
                if (pairingPort <= 0) error("the pairing dialog isn't open; keep it on screen while you type the code")
                status.value = "Pairing…"
                // adbd listens on the phone itself, so try the loopback address first (as Shizuku
                // does), then the Wi-Fi address it advertised.
                val hosts = listOfNotNull("127.0.0.1", pairingHost?.hostAddress).distinct()
                var lastError: Exception? = null
                var ok = false
                for (host in hosts) {
                    try {
                        ok = manager(app).pair(host, pairingPort, code)
                        Log.i(TAG, "pair via $host:$pairingPort -> $ok")
                        lastError = null
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "pair via $host:$pairingPort failed: ${e.message}")
                        lastError = e
                    }
                }
                lastError?.let { throw it }
                if (!ok) error("wrong code")
                pairingMdns?.stop()
                pairingMdns = null
                status.value = "Paired. Connecting…"
                if (!finishSetup(app)) error("paired, but couldn't connect")
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                status.value = "Pairing failed: ${e.message}. Try again with a new code."
                notifyPairing(app, "That didn't work (${e.message}). Tap \"Pair device with pairing code\" again and enter the new code.")
            } finally {
                onDone()
            }
        }
    }

    // ---- Screen control ------------------------------------------------------------------

    /** Switches the panel off or on; [onResult] runs on the worker thread. */
    fun setPanel(context: Context, on: Boolean, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            val ok = runCatching {
                if (!connect(app)) return@runCatching false
                val mode = if (on) PanelPower.MODE_NORMAL else PanelPower.MODE_OFF
                val apk = app.applicationInfo.sourceDir
                shell(app, "CLASSPATH=$apk app_process / ${PanelPower::class.java.name} $mode").trim().endsWith("ok")
            }.onFailure { Log.w(TAG, "panel ${if (on) "on" else "off"} failed", it) }.getOrDefault(false)
            onResult(ok)
        }
    }

    /**
     * Gives Pocket Portal notification access itself, which the shell may do, so the
     * user needn't find it in Settings. [onResult] runs on the worker thread.
     */
    fun allowNotificationAccess(context: Context, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val ok = runCatching {
                if (!connect(app)) return@runCatching false
                shell(app, "cmd notification allow_listener ${HeadsetNotifications.component(app).flattenToString()}")
                HeadsetNotifications.hasAccess(app)
            }.onFailure { Log.w(TAG, "allow notification access failed", it) }.getOrDefault(false)
            onResult(ok)
        }
    }

    /**
     * Runs a program from this APK as the shell user with app_process and hands its
     * stream (stdin/stdout) to [onOpen] on the worker thread, or null if that failed.
     */
    fun startProgram(context: Context, mainClass: Class<*>, args: String, onOpen: (io.github.muntashirakon.adb.AdbStream?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val stream = runCatching {
                if (!connect(app)) return@runCatching null
                val apk = app.applicationInfo.sourceDir
                manager(app).openStream("shell:CLASSPATH=$apk app_process / ${mainClass.name} $args 2>/dev/null")
            }.onFailure { Log.w(TAG, "couldn't start ${mainClass.simpleName}", it) }.getOrNull()
            onOpen(stream)
        }
    }

    // ---- Touch input through the shell ---------------------------------------------------

    /** [InputInjector] is running, so headset touches can go through it. */
    val injectorReady = MutableStateFlow(false)
    @Volatile private var injector: io.github.muntashirakon.adb.AdbStream? = null
    @Volatile private var injectorOut: java.io.OutputStream? = null
    // Writes go on their own thread so a slow panel switch never delays a tap.
    private val input = Executors.newSingleThreadExecutor { Thread(it, "pocketportal-input") }

    /** Starts [InputInjector] for this sharing session, if screen-off (the ADB connection) is set up. */
    fun startInjector(context: Context) {
        if (!paired.value) return
        val app = context.applicationContext
        worker.execute {
            runCatching {
                if (injector != null || !connect(app)) return@runCatching
                val apk = app.applicationInfo.sourceDir
                val stream = manager(app).openStream("shell:CLASSPATH=$apk app_process / ${InputInjector::class.java.name}")
                // Wait for it to say it's ready (or that it failed).
                val reply = StringBuilder()
                val source = stream.openInputStream()
                val buffer = ByteArray(256)
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && "ready" !in reply && "failed" !in reply) {
                    val n = try { source.read(buffer) } catch (e: java.io.IOException) { -1 }
                    if (n < 0) break
                    reply.append(String(buffer, 0, n))
                }
                if ("ready" !in reply) {
                    Log.w(TAG, "input injector didn't start: $reply")
                    runCatching { stream.close() }
                    return@runCatching
                }
                injector = stream
                injectorOut = stream.openOutputStream()
                injectorReady.value = true
                Log.i(TAG, "input injector ready")
            }.onFailure { Log.w(TAG, "input injector failed", it) }
        }
    }

    fun stopInjector() {
        injectorReady.value = false
        val stream = injector
        injector = null
        injectorOut = null
        if (stream != null) input.execute { runCatching { stream.close() } }
    }

    /** Sends one [InputInjector] command; false if it isn't running. */
    fun sendInput(line: String): Boolean {
        val out = injectorOut ?: return false
        input.execute {
            try {
                out.write("$line\n".toByteArray())
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "input injector gone", e)
                if (injectorOut === out) stopInjector()
            }
        }
        return true
    }

    // ---- Connection ----------------------------------------------------------------------

    private fun canWriteSecureSettings(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun connect(context: Context): Boolean {
        val m = manager(context)
        if (m.isConnected) return true
        // After a restart Wireless debugging is off; the permission granted at pairing turns it back on.
        if (canWriteSecureSettings(context) &&
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 0
        ) {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        }
        return runCatching { m.connectTls(context, 10_000) }
            .onFailure { Log.w(TAG, "connect failed", it) }
            .getOrDefault(false)
    }

    /**
     * Runs a shell command and returns its output. libadb signals the normal end of
     * the output by throwing "Stream closed" instead of returning -1, so that ends
     * the read rather than failing it.
     */
    private fun shell(context: Context, command: String): String {
        val stream = manager(context).openStream("shell:$command")
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            val input = stream.openInputStream()
            while (true) {
                val n = try {
                    input.read(buffer)
                } catch (e: java.io.IOException) {
                    if (stream.isClosed) -1 else throw e
                }
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    @Synchronized
    private fun manager(context: Context): Manager =
        manager ?: Manager(context.applicationContext).also { manager = it }

    /** libadb's connection manager with this app's own ADB identity. */
    private class Manager(context: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val prefs = prefs(context)
            val storedKey = prefs.getString("key", null)
            val storedCert = prefs.getString("cert", null)
            if (storedKey != null && storedCert != null) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(unwrap(storedKey)))
                cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(Base64.decode(storedCert, Base64.NO_WRAP)))
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                key = pair.private
                cert = selfSigned(pair.public, pair.private)
                prefs.edit()
                    .putString("key", wrap(key.encoded))
                    .putString("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                    .apply()
            }
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Pocket Portal"

        private fun selfSigned(public: java.security.PublicKey, private: PrivateKey): Certificate {
            val algorithm = "SHA512withRSA"
            val name = X500Name("CN=Pocket Portal")
            val now = Date()
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber((System.currentTimeMillis() and 0x7fffffff).toInt()))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                set("subject", CertificateSubjectName(name))
                set("key", CertificateX509Key(public))
                // adbd doesn't check validity; this only has to be well-formed.
                set("validity", CertificateValidity(now, Date(now.time + TimeUnit.DAYS.toMillis(365L * 30))))
                set("issuer", CertificateIssuerName(name))
                set("extensions", CertificateExtensions())
            }
            return X509CertImpl(info).apply { sign(private, algorithm) }
        }
    }

    // ---- Key storage: the ADB private key is encrypted with a keystore-held AES key ------

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data, 0, 12))
        }
        return cipher.doFinal(data, 12, data.size - 12)
    }

    // ---- Notifications -------------------------------------------------------------------

    private fun notifyPairing(context: Context, text: String) {
        val reply = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, PairingReceiver::class.java).setAction(ACTION_PAIR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(0, "Enter pairing code", reply)
            .addRemoteInput(RemoteInput.Builder(EXTRA_CODE).setLabel("Pairing code").build())
            .build()
        post(context, NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Set up screen-off")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(action))
    }

    private fun notifyDone(context: Context, text: String) {
        post(context, NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Pocket Portal")
            .setContentText(text)
            .setTimeoutAfter(10_000))
    }

    private fun post(context: Context, builder: NotificationCompat.Builder) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        nm.notify(NOTIFICATION_ID, builder.setSmallIcon(R.drawable.ic_launcher_monochrome).setOnlyAlertOnce(true).build())
    }

    private fun prefs(context: Context) = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
}

/** Receives the pairing code typed into the setup notification. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbBridge.EXTRA_CODE)
            ?.toString()?.filter(Char::isDigit) ?: return
        // Keep the process alive while pairing runs on the worker thread.
        val pending = goAsync()
        AdbBridge.pair(context, code) { pending.finish() }
    }
}
