package com.sabeeir.catchapp.shell

import com.sabeeir.catchapp.core.ExecResult

/**
 * Applies the keep-alive tuning from spec section 10, one command at a time, and
 * reports per-command success. A device that rejects a command (OEM builds differ) must
 * not abort the rest of the tuning.
 */
class KeepAliveTuner(private val exec: suspend (String) -> ExecResult) {

    data class Result(val command: String, val ok: Boolean, val detail: String) {
        override fun toString(): String = "${if (ok) "OK  " else "FAIL"} $command -> $detail"
    }

    suspend fun apply(): List<Result> = Commands.keepAliveCommands().map { command ->
        val result = try {
            exec(command)
        } catch (t: Throwable) {
            ExecResult.error(command, t.message ?: t::class.java.simpleName)
        }
        Result(command, result.isSuccess && !result.stderr.contains("Exception"), result.summary())
    }
}
