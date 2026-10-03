package com.sabeeir.catchapp.shell

/**
 * Result of running `ShellUserService.selfTest()`, in a stable `key=value` text format
 * that crosses the binder without needing parcelable marshalling.
 */
data class SelfTestReport(
    val version: Int,
    val uid: Int,
    val pid: Int,
    val hiddenApiBypass: Boolean,
    val inputManagerInstance: Boolean,
    val setDisplayId: Boolean,
    val inject: Boolean,
    val shellFallback: Boolean,
    val error: String? = null,
) {
    /** The in-process InputManager path is usable (spec 9.1 delivery mechanism). */
    val inputManagerPath: Boolean
        get() = inputManagerInstance && setDisplayId && inject

    /** At least one injection path is usable. */
    val canInject: Boolean get() = inputManagerPath || shellFallback

    val isShellIdentity: Boolean get() = uid == SHELL_UID

    fun summary(): String = buildString {
        append("uid=").append(uid)
        append(" pid=").append(pid)
        append(" inputManager=").append(if (inputManagerPath) "ready" else "unavailable")
        append(" shellFallback=").append(if (shellFallback) "ready" else "unavailable")
        if (isShellIdentity) append(" (shell identity)")
        if (error != null) append(" error=").append(error)
    }

    companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0

        fun parse(raw: String?): SelfTestReport? {
            if (raw.isNullOrBlank()) return null
            val map = raw.trim().split(Regex("\\s+"))
                .mapNotNull { token ->
                    val idx = token.indexOf('=')
                    if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1)
                }
                .toMap()
            val version = map["v"]?.toIntOrNull() ?: return null
            return SelfTestReport(
                version = version,
                uid = map["uid"]?.toIntOrNull() ?: -1,
                pid = map["pid"]?.toIntOrNull() ?: -1,
                hiddenApiBypass = map["hiddenApi"] == "1",
                inputManagerInstance = map["im"] == "1",
                setDisplayId = map["setDisp"] == "1",
                inject = map["inject"] == "1",
                shellFallback = map["shell"] == "1",
                error = map["error"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
