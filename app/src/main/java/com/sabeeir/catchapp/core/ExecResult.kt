package com.sabeeir.catchapp.core

/**
 * Result of a shell command executed through the Shizuku user service.
 *
 * The AIDL boundary carries a single framed string; encoding/parsing lives here so it can
 * be unit tested. Framing: `CATCH1|<exitCode>|<base64 stdout>|<base64 stderr>`.
 */
data class ExecResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** Single line summary used in the diagnostics panel. */
    fun summary(maxLen: Int = 240): String {
        val body = (if (stdout.isNotBlank()) stdout else stderr).trim().lines().firstOrNull().orEmpty()
        val clipped = if (body.length > maxLen) body.take(maxLen) + "…" else body
        return "exit=$exitCode${if (clipped.isEmpty()) "" else " $clipped"}"
    }

    companion object {
        const val PREFIX = "CATCH1"

        fun error(command: String, message: String): ExecResult =
            ExecResult(command, Int.MIN_VALUE, "", message)

        fun encode(exitCode: Int, stdout: String, stderr: String): String =
            listOf(PREFIX, exitCode.toString(), b64(stdout), b64(stderr)).joinToString("|")

        fun decode(raw: String?, command: String): ExecResult {
            if (raw.isNullOrBlank()) return error(command, "empty response from shell service")
            val parts = raw.split("|")
            if (parts.size != 4 || parts[0] != PREFIX) {
                return error(command, "malformed shell response: ${raw.take(80)}")
            }
            val code = parts[1].toIntOrNull() ?: return error(command, "malformed exit code")
            return ExecResult(command, code, unb64(parts[2]), unb64(parts[3]))
        }

        private fun b64(value: String): String =
            java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun unb64(value: String): String = try {
            String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            ""
        }
    }
}
