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
 *
 * Coordinates are screen pixels. Input injected by the shell user reaches every
 * screen, including Google sign-in and Android permission dialogs, which ignore
 * touches from accessibility services that aren't accessibility tools (that's
 * Android's protection, and the reason remote touch can't press them).
 */
object InputInjector {
    private lateinit var inject: (InputEvent) -> Unit

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
        }
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
        inject(event)
        event.recycle()
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, code, 0, 0, KeyCharacterMapVirtual, 0, 0, InputDevice.SOURCE_KEYBOARD)
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
