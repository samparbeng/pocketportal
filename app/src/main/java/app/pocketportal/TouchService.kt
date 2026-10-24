package app.pocketportal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Replays the taps, swipes and keys made in the headset viewer on the phone.
 * Android only lets an accessibility service inject gestures, so remote touch
 * works only while the user has switched this service on in Settings.
 *
 * Coordinates arrive normalized to 0..1 across the mirrored frame, which always
 * covers the whole screen, so they map straight onto the real screen size.
 */
class TouchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TouchService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        MirrorState.touchEnabled.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        MirrorState.touchEnabled.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        MirrorState.touchEnabled.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Called from a server thread with one message from the viewer page. */
    fun handle(message: JSONObject) {
        main.post {
            when (message.optString("t")) {
                "tap" -> stroke(listOf(point(message)), 40)
                "press" -> stroke(listOf(point(message)), message.optLong("ms", 700))
                "swipe" -> {
                    val pts = message.getJSONArray("pts")
                    val points = (0 until pts.length()).map { i ->
                        pts.getJSONArray(i).let { toScreen(it.getDouble(0), it.getDouble(1)) }
                    }
                    if (points.isNotEmpty()) stroke(points, message.optLong("ms", 300))
                }
                "key" -> globalKey(message.optString("k"))
                "text" -> editFocused { it + message.optString("s") }
                "backspace" -> editFocused { it.dropLast(1) }
                "enter" -> focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
    }

    private fun point(message: JSONObject) = toScreen(message.getDouble("x"), message.getDouble("y"))

    private fun toScreen(x: Double, y: Double): Pair<Float, Float> {
        val metrics = realScreenMetrics(this)
        val sx = (x.coerceIn(0.0, 1.0) * (metrics.widthPixels - 1)).toFloat()
        val sy = (y.coerceIn(0.0, 1.0) * (metrics.heightPixels - 1)).toFloat()
        return sx to sy
    }

    private fun stroke(points: List<Pair<Float, Float>>, durationMs: Long) {
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val duration = durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun globalKey(key: String) {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return
        }
        performGlobalAction(action)
    }

    private fun focusedInput(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** Edits the focused text field directly, since accessibility services can't send key presses. */
    private fun editFocused(change: (String) -> String) {
        val node = focusedInput() ?: return
        if (!node.isEditable) return
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val updated = change(current)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, updated.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, updated.length)
        })
    }
}
