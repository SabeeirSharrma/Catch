package com.sabeeir.catchapp

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The last error that would otherwise be invisible.
 *
 * Release builds are not debuggable: there is no `run-as`, and logcat is gone the moment the
 * device reboots. Without this, an uncaught exception leaves only Android's "Catch keeps
 * stopping" dialog and no way to work out what happened.
 *
 * Both uncaught exceptions and errors contained at a boundary (a service command that threw,
 * an overlay that would not attach) are rendered here and written to disk, so Diagnostics can
 * hand the report to the user on a share sheet.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "error.log"

    /** Traces are cut at this many characters, keeping both ends. */
    const val MAX_CHARS = 32_768

    /** Pure: exactly what [record] writes, formatted for a human. */
    fun render(throwable: Throwable, appVersion: String, timestampMillis: Long): String =
        buildString {
            append("Catch ").append(appVersion).append('\n')
            append("recorded at epoch ms ").append(timestampMillis).append('\n')
            append('\n')
            append(clip(throwable.stackTraceToString()))
        }

    /**
     * Keeps the head (the exception and its top frames) and the tail (the cause chain, which
     * is usually where the real fault is) when a trace is longer than [MAX_CHARS].
     */
    fun clip(trace: String): String {
        if (trace.length <= MAX_CHARS) return trace
        val half = MAX_CHARS / 2
        val elided = trace.length - (half * 2)
        return buildString {
            append(trace.take(half))
            append('\n')
            append("… ").append(elided).append(" characters elided …")
            append('\n')
            append(trace.takeLast(half))
        }
    }

    /** @return true when the report reached the disk. Never throws. */
    fun record(context: Context, throwable: Throwable): Boolean = runCatching {
        File(context.filesDir, FILE_NAME).writeText(
            render(throwable, versionOf(context), System.currentTimeMillis()),
        )
        Log.e(TAG, "recorded ${throwable.javaClass.name}", throwable)
        true
    }.getOrElse { failure ->
        Log.w(TAG, "could not persist ${throwable.javaClass.name}", failure)
        false
    }

    /** @return the last recorded report, or null when there is none. Never throws. */
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun versionOf(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown version")
}
