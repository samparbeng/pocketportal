package app.pocketportal

import android.annotation.SuppressLint
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.system.exitProcess

/**
 * A small command-line program inside the APK, started over the phone's own ADB
 * connection ([AdbBridge]) as `CLASSPATH=<apk> app_process / app.pocketportal.InputInjector`.
 * It stays running while sharing and turns lines on its stdin into touch input:
 *
 *     tap X Y
 *     press X Y MS
 *     swipe MS X1 Y1 X2 Y2 ...
 *     key KEYCODE
 *     display ID        (where the following input goes; 0 is the phone's screen)
 *
 * Coordinates are screen pixels. Input injected by the shell user reaches every
 * screen, including Google sign-in and Android permission dialogs, which ignore
 * touches from accessibility services that aren't accessibility tools (that's
 * Android's protection, and the reason remote touch can't press them).
 */
object InputInjector {
    private lateinit var inject: (InputEvent) -> Unit
    private var displayId = 0
    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            inject = injector()
        } catch (e: Throwable) {
            System.err.println("input injector failed: $e")
            println("failed")
            exitProcess(1)
        }
        println("ready")
        System.out.flush()
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            runCatching { handle(line.trim().split(' ')) }.onFailure { System.err.println("bad input '$line': $it") }
        }
        exitProcess(0)
    }

    private fun handle(parts: List<String>) {
        when (parts.firstOrNull()) {
            "tap" -> press(parts[1].toFloat(), parts[2].toFloat(), 40)
            "press" -> press(parts[1].toFloat(), parts[2].toFloat(), parts[3].toLong())
            "swipe" -> {
                val ms = parts[1].toLong()
                val pts = parts.drop(2).map(String::toFloat).chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
                if (pts.isNotEmpty()) swipe(pts, ms)
            }
            "key" -> key(parts[1].toInt())
            "display" -> displayId = parts[1].toInt()
            "mouse" -> mouse(parts.drop(1))
        }
    }

    // ---- Mouse (DeX) -------------------------------------------------------------------------
    //
    //     mouse hover X Y           pointer moves with no button held
    //     mouse down X Y            left button goes down (start of a drag)
    //     mouse move X Y            pointer moves with the left button held
    //     mouse up X Y              left button comes up
    //     mouse click X Y BUTTON    a full click; BUTTON is primary or secondary
    //     mouse scroll X Y V        wheel; V > 0 scrolls up, < 0 down
    //
    // DeX treats these like a real mouse: hover effects, right-click menus, wheel scrolling
    // and click-drag selection. Injected events don't move the system pointer sprite, so
    // the headset page draws its own.

    private var mouseDownTime = 0L
    private var mouseButtons = 0
    private val setActionButton by lazy {
        runCatching { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }.getOrNull()
    }

    private fun mouse(args: List<String>) {
        val x = args[1].toFloat()
        val y = args[2].toFloat()
        when (args[0]) {
            "hover" -> if (mouseButtons == 0) mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, SystemClock.uptimeMillis(), 0)
            "down" -> buttonDown(x, y, MotionEvent.BUTTON_PRIMARY)
            "move" -> if (mouseButtons != 0) mouseEvent(MotionEvent.ACTION_MOVE, x, y, mouseDownTime, mouseButtons)
            "up" -> buttonUp(x, y)
            "click" -> {
                val button = if (args.getOrNull(3) == "secondary") MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
                if (mouseButtons != 0) buttonUp(x, y)
                mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, SystemClock.uptimeMillis(), 0)
                buttonDown(x, y, button)
                Thread.sleep(30)
                buttonUp(x, y)
            }
            "scroll" -> {
                val v = args[3].toFloat().coerceIn(-10f, 10f)
                mouseEvent(MotionEvent.ACTION_SCROLL, x, y, SystemClock.uptimeMillis(), 0, vscroll = v)
            }
        }
    }

    private fun buttonDown(x: Float, y: Float, button: Int) {
        mouseDownTime = SystemClock.uptimeMillis()
        mouseButtons = button
        mouseEvent(MotionEvent.ACTION_DOWN, x, y, mouseDownTime, button)
        mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, x, y, mouseDownTime, button, actionButton = button)
    }

    private fun buttonUp(x: Float, y: Float) {
        val button = mouseButtons.takeIf { it != 0 } ?: return
        mouseButtons = 0
        mouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, x, y, mouseDownTime, 0, actionButton = button)
        mouseEvent(MotionEvent.ACTION_UP, x, y, mouseDownTime, 0)
        mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, SystemClock.uptimeMillis(), 0)
    }

    private fun mouseEvent(
        action: Int, x: Float, y: Float, downTime: Long, buttons: Int,
        actionButton: Int = 0, vscroll: Float = 0f,
    ) {
        val isButtonEvent = action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE
        val setter = setActionButton
        if (isButtonEvent && setter == null) return // can't say which button; DOWN/UP still click
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            if (vscroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        if (isButtonEvent) setter?.invoke(event, actionButton)
        if (displayId != 0) setDisplayId.invoke(event, displayId)
        inject(event)
        event.recycle()
    }

    private fun press(x: Float, y: Float, ms: Long) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        Thread.sleep(ms)
        touch(MotionEvent.ACTION_UP, down, x, y)
    }

    /** Moves along the points at an even speed over [ms], one event every ~8 ms. */
    private fun swipe(pts: List<Pair<Float, Float>>, ms: Long) {
        val lengths = pts.zipWithNext { a, b -> Math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
        val total = lengths.sum().takeIf { it > 0 } ?: 1.0
        fun at(fraction: Double): Pair<Float, Float> {
            var left = fraction * total
            lengths.forEachIndexed { i, len ->
                if (left <= len || i == lengths.lastIndex) {
                    val t = if (len > 0) (left / len).coerceIn(0.0, 1.0) else 1.0
                    val (a, b) = pts[i] to pts[i + 1]
                    return (a.first + (b.first - a.first) * t).toFloat() to (a.second + (b.second - a.second) * t).toFloat()
                }
                left -= len
            }
            return pts.last()
        }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, pts[0].first, pts[0].second)
        val steps = maxOf(1, (ms / 8).toInt())
        for (i in 1..steps) {
            Thread.sleep(ms / steps)
            val (x, y) = if (pts.size == 1) pts[0] else at(i.toDouble() / steps)
            touch(MotionEvent.ACTION_MOVE, down, x, y)
        }
        val (x, y) = pts.last()
        touch(MotionEvent.ACTION_UP, down, x, y)
    }

    private fun touch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        if (displayId != 0) setDisplayId.invoke(event, displayId)
        inject(event)
        event.recycle()
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, code, 0, 0, KeyCharacterMapVirtual, 0, 0, InputDevice.SOURCE_KEYBOARD)
            if (displayId != 0) setDisplayId.invoke(event, displayId)
            inject(event)
        }
    }

    private const val KeyCharacterMapVirtual = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD

    /** InputManagerGlobal (Android 14+) or InputManager: injectInputEvent(event, mode async). Hidden APIs, allowed for the shell user. */
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    private fun injector(): (InputEvent) -> Unit {
        val (target, method) = try {
            val c = Class.forName("android.hardware.input.InputManagerGlobal")
            c.getMethod("getInstance").invoke(null) to
                c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Throwable) {
            val c = Class.forName("android.hardware.input.InputManager")
            c.getDeclaredMethod("getInstance").invoke(null) to
                c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }
        return { event -> method.invoke(target, event, 0) }
    }
}
