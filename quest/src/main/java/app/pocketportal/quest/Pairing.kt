package app.pocketportal.quest

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom

/**
 * This headset's pairing identity: a random secret made once on first launch.
 * The phone scans it from [pairingCode] and from then on accepts it in place of
 * the PIN, so connecting needs no typing.
 */
object Pairing {
    fun key(context: Context): String {
        val prefs = context.getSharedPreferences("quest", Context.MODE_PRIVATE)
        prefs.getString("key", null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("key", key).apply()
        return key
    }

    /** e.g. "Quest 3". */
    val headsetName: String get() = Build.MODEL.ifBlank { "Quest" }

    /** Read by the phone app's scanner; see MirrorState.pairHeadset there. */
    fun pairingCode(context: Context): String =
        "pocketportal://pair?t=${key(context)}&n=${Uri.encode(headsetName)}"
}

/** A QR code drawn with Compose, dark modules on a white quiet zone. */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(
        modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(14.dp)
            .size(size),
    ) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) {
                // A hair of overlap avoids seams between modules.
                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
