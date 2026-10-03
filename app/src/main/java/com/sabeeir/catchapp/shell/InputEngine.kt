package com.sabeeir.catchapp.shell

import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.OutputStream

/**
 * Touch injection, running inside the Shizuku user service (shell/root identity, no
 * hidden-API restrictions).
 *
 * Primary path (spec 9.1): build a [MotionEvent], attach the *target display id*, and
 * hand it to `InputManager.injectInputEvent`. The guard `displayId <= 0` refuses the
 * default display outright, so nothing can ever land on the user's real screen.
 *
 * Fallback path (spec 8): a persistent `sh` process that receives `input -d <id> ...`
 * lines. One process, not one per click.
 */
internal object InputEngine {

    const val OK = 0
    const val OK_SHELL = 1
    const val ERR_BAD_DISPLAY = -1
    const val ERR_SECURITY = -2
    const val ERR_UNSUPPORTED = -3
    const val ERR_FAILED = -4
    const val ERR_UNEXPECTED = -5

    /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`. */
    private const val MODE_ASYNC = 0

    private const val MAX_LOG = 4096

    data class Probe(
        val hiddenApiBypass: Boolean,
        val inputManagerInstance: Boolean,
        val setDisplayId: Boolean,
        val inject: Boolean,
        val shellFallback: Boolean,
        val error: String? = null,
    )

    private val lock = Any()

    @Volatile private var initAttempted = false
    @Volatile private var hiddenApiOk = false
    @Volatile private var initError: String? = null
    @Volatile var lastError: String? = null
        private set

    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var setDisplayIdMethod: java.lang.reflect.Method? = null
    private var setSourceMethod: java.lang.reflect.Method? = null

    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null
    private val shellLog = StringBuilder()

    private val inputManagerReady: Boolean
        get() = inputManager != null && injectMethod != null && setDisplayIdMethod != null

    // ---------------------------------------------------------------- init

    private fun ensureInit() {
        if (initAttempted) return
        synchronized(lock) {
            if (initAttempted) return
            try {
                HiddenApiBypass.setHiddenApiExemptions("")
                hiddenApiOk = true
            } catch (t: Throwable) {
                initError = "hiddenApiBypass:${t.javaClass.simpleName}"
                // Shell processes are usually unrestricted anyway; keep going.
            }
            try {
                val imClass = Class.forName("android.hardware.input.InputManager")
                inputManager = imClass.getMethod("getInstance").invoke(null)
                injectMethod = imClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Integer.TYPE,
                )
                setDisplayIdMethod = resolveMethod(InputEvent::class.java, "setDisplayId", Integer.TYPE)
                    ?: resolveMethod(MotionEvent::class.java, "setDisplayId", Integer.TYPE)
                setSourceMethod = resolveMethod(InputEvent::class.java, "setSource", Integer.TYPE)
                    ?: resolveMethod(MotionEvent::class.java, "setSource", Integer.TYPE)
                if (setDisplayIdMethod == null) {
                    initError = "setDisplayId unavailable"
                }
            } catch (t: Throwable) {
                initError = "inputManager:${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}"
                inputManager = null
                injectMethod = null
            }
            initAttempted = true
        }
    }

    private fun resolveMethod(cls: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method? =
        try {
            cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }

    fun probe(): Probe {
        ensureInit()
        return Probe(
            hiddenApiBypass = hiddenApiOk,
            inputManagerInstance = inputManager != null && injectMethod != null,
            setDisplayId = setDisplayIdMethod != null,
            inject = injectMethod != null,
            shellFallback = shellAvailable(),
            error = initError,
        )
    }

    private fun shellAvailable(): Boolean =
        File("/system/bin/sh").canExecute() && File("/system/bin/input").canExecute()

    // ---------------------------------------------------------------- injection

    /** @return [OK], [OK_SHELL] or a negative error code. */
    fun tap(x: Int, y: Int, displayId: Int): Int {
        if (displayId <= 0) return ERR_BAD_DISPLAY
        if (x < 0 || y < 0) return ERR_FAILED

        ensureInit()
        if (inputManagerReady) {
            val now = android.os.SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
            val up = MotionEvent.obtain(now + 30, now + 40, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
            try {
                prepare(down, displayId)
                prepare(up, displayId)
                val ok = invokeInject(down) && invokeInject(up)
                if (ok) return OK
                lastError = "inject returned false"
            } catch (se: SecurityException) {
                lastError = "SecurityException"
                return ERR_SECURITY
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                if (cause is SecurityException) {
                    lastError = "SecurityException"
                    return ERR_SECURITY
                }
                lastError = "${t.javaClass.simpleName}:${cause.javaClass.simpleName}"
            } finally {
                down.recycle()
                up.recycle()
            }
        } else {
            lastError = initError ?: "input manager unavailable"
        }

        return shellTap(x, y, displayId)
    }

    /** @return [OK], [OK_SHELL] or a negative error code. */
    @android.annotation.SuppressLint("Recycle") // every event is recycled in the finally block
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int): Int {
        if (displayId <= 0) return ERR_BAD_DISPLAY
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)

        ensureInit()
        if (inputManagerReady) {
            val now = android.os.SystemClock.uptimeMillis()
            val steps = (duration / STEP_MS).coerceIn(2, MAX_STEPS)
            val events = mutableListOf<MotionEvent>()
            try {
                events += MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0,
                )
                for (i in 1 until steps) {
                    val t = i.toFloat() / steps
                    val whenMs = now + (duration * t).toLong()
                    events += MotionEvent.obtain(
                        whenMs, whenMs, MotionEvent.ACTION_MOVE,
                        lerp(x1, x2, t), lerp(y1, y2, t), 0,
                    )
                }
                events += MotionEvent.obtain(
                    now + duration, now + duration, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0,
                )
                for (event in events) prepare(event, displayId)
                for (event in events) {
                    if (!invokeInject(event)) {
                        lastError = "inject returned false"
                        return shellSwipe(x1, y1, x2, y2, duration, displayId)
                    }
                }
                return OK
            } catch (se: SecurityException) {
                lastError = "SecurityException"
                return ERR_SECURITY
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                if (cause is SecurityException) return ERR_SECURITY
                lastError = "${t.javaClass.simpleName}:${cause.javaClass.simpleName}"
            } finally {
                events.forEach { it.recycle() }
            }
        } else {
            lastError = initError ?: "input manager unavailable"
        }

        return shellSwipe(x1, y1, x2, y2, duration, displayId)
    }

    private fun lerp(a: Int, b: Int, t: Float): Float = a + (b - a) * t

    // ---------------------------------------------------------------- gestures

    private val gestureLock = Any()
    private var gestureActive = false
    private var gestureDisplayId = 0
    private var gestureStartX = 0
    private var gestureStartY = 0
    private var gestureStartMs = 0L

    /** Real-time pointer control for mirror drags and the walk joystick (spec 8/M2). */
    fun pointerDown(x: Int, y: Int, displayId: Int): Int {
        if (displayId <= 0) return ERR_BAD_DISPLAY
        if (x < 0 || y < 0) return ERR_FAILED
        ensureInit()
        synchronized(gestureLock) {
            gestureActive = true
            gestureDisplayId = displayId
            gestureStartX = x
            gestureStartY = y
            gestureStartMs = android.os.SystemClock.uptimeMillis()
        }
        if (inputManagerReady) {
            val code = injectSingle(MotionEvent.ACTION_DOWN, x, y, gestureStartTime(), displayId)
            if (code == OK) return OK
            // fall through: replay on pointerUp through the shell
            lastError = lastError ?: "down injection failed"
        }
        return OK_SHELL
    }

    fun pointerMove(x: Int, y: Int, displayId: Int): Int {
        if (displayId <= 0) return ERR_BAD_DISPLAY
        synchronized(gestureLock) {
            if (!gestureActive || gestureDisplayId != displayId) return ERR_FAILED
        }
        if (x < 0 || y < 0) return ERR_FAILED
        if (!inputManagerReady) return OK_SHELL
        val code = injectSingle(MotionEvent.ACTION_MOVE, x, y, android.os.SystemClock.uptimeMillis(), displayId)
        return if (code == OK) OK else OK_SHELL
    }

    fun pointerUp(x: Int, y: Int, displayId: Int): Int {
        if (displayId <= 0) return ERR_BAD_DISPLAY
        val gesture = synchronized(gestureLock) {
            val active = if (gestureActive && gestureDisplayId == displayId) {
                ActiveGesture(gestureStartX, gestureStartY, gestureStartMs)
            } else {
                null
            }
            gestureActive = false
            active
        } ?: return ERR_FAILED

        if (inputManagerReady) {
            val code = injectSingle(MotionEvent.ACTION_UP, x, y, android.os.SystemClock.uptimeMillis(), displayId)
            if (code == OK) return OK
        }
        // Fallback: replay the whole drag as one swipe so a joystick still works.
        val duration = (android.os.SystemClock.uptimeMillis() - gesture.startMs)
            .toInt()
            .coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        return shellSwipe(gesture.startX, gesture.startY, x, y, duration, displayId)
    }

    private fun gestureStartTime(): Long = synchronized(gestureLock) { gestureStartMs }

    private data class ActiveGesture(val startX: Int, val startY: Int, val startMs: Long)

    /** Builds, addresses and injects a single motion event. Returns [OK] or an error code. */
    private fun injectSingle(action: Int, x: Int, y: Int, whenMs: Long, displayId: Int): Int {
        if (!inputManagerReady) return ERR_UNSUPPORTED
        val event = MotionEvent.obtain(whenMs, whenMs, action, x.toFloat(), y.toFloat(), 0)
        try {
            prepare(event, displayId)
            val ok = invokeInject(event)
            return if (ok) OK else ERR_FAILED
        } catch (se: SecurityException) {
            lastError = "SecurityException"
            return ERR_SECURITY
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            lastError = "${t.javaClass.simpleName}:${cause.javaClass.simpleName}"
            return if (cause is SecurityException) ERR_SECURITY else ERR_FAILED
        } finally {
            event.recycle()
        }
    }

    private fun prepare(event: MotionEvent, displayId: Int) {
        setSourceMethod?.invoke(event, InputDevice.SOURCE_TOUCHSCREEN)
        setDisplayIdMethod?.invoke(event, displayId)
        val actual = readDisplayId(event)
        if (actual != null && actual != displayId) {
            error("display id was not applied to the event")
        }
    }

    private fun readDisplayId(event: InputEvent): Int? = try {
        InputEvent::class.java.getMethod("getDisplayId").invoke(event) as Int
    } catch (_: Throwable) {
        null
    }

    private fun invokeInject(event: InputEvent): Boolean =
        injectMethod!!.invoke(inputManager, event, MODE_ASYNC) as Boolean

    // ------------------------------------------------------- shell fallback

    private fun shellTap(x: Int, y: Int, displayId: Int): Int =
        if (writeShell("input -d $displayId tap $x $y")) OK_SHELL else ERR_UNSUPPORTED

    private fun shellSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int): Int =
        if (writeShell("input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs")) OK_SHELL else ERR_UNSUPPORTED

    private fun writeShell(command: String): Boolean = synchronized(lock) {
        try {
            if (shellProcess?.isAlive != true) startShellLocked()
            val stdin = shellStdin ?: return false
            stdin.write((command + "\n").toByteArray(Charsets.UTF_8))
            stdin.flush()
            true
        } catch (t: Throwable) {
            lastError = "shell:${t.javaClass.simpleName}"
            false
        }
    }

    private fun startShellLocked() {
        val process = ProcessBuilder("/system/bin/sh")
            .redirectErrorStream(true)
            .start()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(shellLog) {
                            if (shellLog.length > MAX_LOG) shellLog.setLength(0)
                            shellLog.append(line).append('\n')
                        }
                    }
                }
            } catch (_: Throwable) {
                // process ended; nothing to drain
            }
        }.apply {
            name = "catch-shell-drain"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        reader.start()
        shellProcess = process
        shellStdin = process.outputStream
    }

    /** Last lines from the fallback shell, for diagnostics. */
    fun shellDiagnostics(): String = synchronized(shellLog) { shellLog.toString() }

    fun close() {
        synchronized(lock) {
            try {
                shellStdin?.close()
            } catch (_: Throwable) {
            }
            try {
                shellProcess?.destroy()
            } catch (_: Throwable) {
            }
            shellStdin = null
            shellProcess = null
        }
    }

    /** Used only by tests of the framing logic; never called on device. */
    private const val MIN_SWIPE_MS = 40
    private const val MAX_SWIPE_MS = 5_000
    private const val STEP_MS = 16
    private const val MAX_STEPS = 40
}
