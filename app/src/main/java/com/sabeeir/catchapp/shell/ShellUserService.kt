package com.sabeeir.catchapp.shell

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import androidx.annotation.Keep
import com.sabeeir.catchapp.core.ExecResult
import java.util.concurrent.TimeUnit

/**
 * Runs with shell (uid 2000) or root (uid 0) identity inside a process started by the
 * Shizuku server. There are no hidden-API restrictions here, which is what makes
 * `InputManager` injection with an explicit display id possible without root.
 *
 * The class must keep a public no-arg constructor (and optionally a `Context`
 * constructor, tried first by Shizuku v13) and must never be renamed by R8.
 */
class ShellUserService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IShellService.Stub() {

    override fun ping(): Int = AndroidProcess.myPid()

    override fun selfTest(): String = SelfTest.build()

    override fun exec(command: String?, timeoutMs: Long): String? {
        if (command.isNullOrBlank()) {
            return ExecResult.encode(ERROR_CODE, "", "empty command")
        }
        val (code, stdout, stderr) = runShell(command, timeoutMs)
        return ExecResult.encode(code, stdout, stderr)
    }

    override fun tap(x: Int, y: Int, displayId: Int): Int {
        val result = InputEngine.tap(x, y, displayId)
        if (result < 0) Log.w(TAG, "tap refused: code=$result error=${InputEngine.lastError}")
        return result
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int): Int {
        val result = InputEngine.swipe(x1, y1, x2, y2, durationMs, displayId)
        if (result < 0) Log.w(TAG, "swipe refused: code=$result error=${InputEngine.lastError}")
        return result
    }

    override fun closeFallbackShell(): Int {
        InputEngine.close()
        return 0
    }

    override fun pointerDown(x: Int, y: Int, displayId: Int): Int =
        InputEngine.pointerDown(x, y, displayId)

    override fun pointerMove(x: Int, y: Int, displayId: Int): Int =
        InputEngine.pointerMove(x, y, displayId)

    override fun pointerUp(x: Int, y: Int, displayId: Int): Int =
        InputEngine.pointerUp(x, y, displayId)

    /** Reserved teardown hook: the Shizuku server calls this before replacing us. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        InputEngine.close()
        System.exit(0)
    }

    // ------------------------------------------------------------------ exec

    private data class ShellRun(val code: Int, val stdout: String, val stderr: String)

    private fun runShell(command: String, timeoutMs: Long): ShellRun {
        val timeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = drain(process.inputStream, stdout)
            val errThread = drain(process.errorStream, stderr)
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                outThread.join(DRAIN_JOIN_MS)
                errThread.join(DRAIN_JOIN_MS)
                ShellRun(TIMEOUT_CODE, stdout.toString(), "timeout after ${timeout}ms")
            } else {
                outThread.join(DRAIN_JOIN_MS)
                errThread.join(DRAIN_JOIN_MS)
                ShellRun(process.exitValue(), stdout.toString(), stderr.toString())
            }
        } catch (t: Throwable) {
            ShellRun(ERROR_CODE, "", t.toString())
        }
    }

    private fun drain(stream: java.io.InputStream, target: StringBuilder): Thread = Thread {
        try {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    synchronized(target) {
                        if (target.length > MAX_OUTPUT_CHARS) {
                            target.setLength(0)
                            target.append("…truncated…\n")
                        }
                        target.append(line).append('\n')
                    }
                }
            }
        } catch (_: Throwable) {
            // stream closed, nothing more to read
        }
    }.apply {
        name = "catch-exec-drain"
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }.also { it.start() }

    companion object {
        private const val TAG = "CatchShellService"

        /** Distinctive codes so the app side can tell timeouts from crashes. */
        const val TIMEOUT_CODE = -9998
        const val ERROR_CODE = -9999

        private const val MIN_TIMEOUT_MS = 100L
        private const val MAX_TIMEOUT_MS = 60_000L
        private const val DRAIN_JOIN_MS = 500L
        private const val MAX_OUTPUT_CHARS = 256 * 1024
    }
}
