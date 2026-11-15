package app.pocketportal.quest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The viewer in a wide, landscape panel, for Samsung DeX. Horizon OS sizes a
 * panel from its activity's manifest <layout> when it opens, so DeX gets its own
 * activity (in its own task) rather than resizing the tall phone panel.
 *
 * Opened by [MainActivity] when the phone says it's streaming DeX; when DeX ends
 * it hands back to [MainActivity], which reopens the phone in the tall panel.
 */
class WideViewerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_FP = "fp"
        private const val EXTRA_PIN = "pin"

        @Volatile private var userClosed = false

        /** True once after the user closed this window, so the tall panel doesn't jump straight back in. */
        fun consumeUserClosed(): Boolean = userClosed.also { userClosed = false }

        fun open(context: Context, phone: Phone, pin: String, fingerprint: String?) {
            context.startActivity(
                Intent(context, WideViewerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_NAME, phone.name)
                    .putExtra(EXTRA_HOST, phone.host)
                    .putExtra(EXTRA_PORT, phone.port)
                    .putExtra(EXTRA_FP, fingerprint)
                    .putExtra(EXTRA_PIN, pin)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = Phone(
            name = intent.getStringExtra(EXTRA_NAME) ?: "Phone",
            host = intent.getStringExtra(EXTRA_HOST) ?: return finish(),
            port = intent.getIntExtra(EXTRA_PORT, 8443),
            fingerprint = intent.getStringExtra(EXTRA_FP),
        )
        val pin = intent.getStringExtra(EXTRA_PIN) ?: return finish()
        val prefs = getSharedPreferences("quest", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FA3FF),
                    onPrimary = Color.Black,
                    background = Color(0xFF101418),
                    surface = Color(0xFF101418),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Viewer(
                        phone = phone,
                        pin = pin,
                        trustedFingerprint = phone.fingerprint ?: prefs.getString("cert:${phone.host}", null),
                        onTrust = { prefs.edit().putString("cert:${phone.host}", it).apply() },
                        onClose = { backToPhones(userClosed = true) },
                        onWrongPin = { backToPhones(userClosed = false) },
                        // DeX ended: the phone's own screen goes back in the tall panel.
                        onLandscape = { wide -> if (!wide) backToPhones(userClosed = false) },
                    )
                }
            }
        }
    }

    private fun backToPhones(userClosed: Boolean) {
        Companion.userClosed = userClosed
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
