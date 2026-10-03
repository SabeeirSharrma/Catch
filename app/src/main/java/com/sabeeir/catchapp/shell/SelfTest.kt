package com.sabeeir.catchapp.shell

import android.util.Log
import java.io.File

/** Builds the capability report executed inside the shell process. */
internal object SelfTest {

    private const val TAG = "CatchSelfTest"

    fun build(): String = buildString {
        append("v=1")
        append(" uid=").append(android.os.Process.myUid())
        append(" pid=").append(android.os.Process.myPid())
        val probe = InputEngine.probe()
        append(" hiddenApi=").append(flag(probe.hiddenApiBypass))
        append(" im=").append(flag(probe.inputManagerInstance))
        append(" setDisp=").append(flag(probe.setDisplayId))
        append(" inject=").append(flag(probe.inject))
        append(" shell=").append(flag(probe.shellFallback))
        probe.error?.let { append(" error=").append(it.replace(' ', '_')) }
        Log.i(TAG, "self test: $this")
    }.also { Log.i(TAG, "self test result: $it") }

    private fun flag(value: Boolean) = if (value) 1 else 0

    /** Cheap static check: the input tool exists and is executable. */
    fun inputToolAvailable(): Boolean = File("/system/bin/input").canExecute()
}
