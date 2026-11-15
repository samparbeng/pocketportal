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
        status.value = "In Wireless debugging, tap \"Pair device with pairing code\", then type the code in the notification."
        pairingPort = -1
        pairingMdns?.stop()
        pairingMdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (port > 0) {
                pairingHost = host
                pairingPort = port
            }
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
                if (pairingPort <= 0) error("pairing port not found; keep the pairing dialog open")
                val host = pairingHost?.hostAddress ?: "127.0.0.1"
                status.value = "Pairing…"
                if (!manager(app).pair(host, pairingPort, code)) error("wrong code")
                pairingMdns?.stop()
                pairingMdns = null
                status.value = "Paired. Connecting…"
                if (!connect(app)) error("paired, but couldn't connect")
                // Lets the app switch Wireless debugging back on after a restart.
                shell(app, "pm grant ${app.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
                prefs(app).edit().putBoolean("paired", true).apply()
                paired.value = true
                status.value = "Screen-off is set up."
                notifyDone(app, "Screen-off is set up. You can close Wireless debugging.")
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

    private fun shell(context: Context, command: String): String {
        val stream = manager(context).openStream("shell:$command")
        return stream.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }.also { stream.close() }
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
