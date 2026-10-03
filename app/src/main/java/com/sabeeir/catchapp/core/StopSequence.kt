package com.sabeeir.catchapp.core

/**
 * The shutdown order from spec section 6:
 *
 *  1. halt the auto-clicker and anti-idle,
 *  2. force-stop Roblox,
 *  3. release the virtual display,
 *  4. stop the service and exit.
 *
 * A failure in one step must not leave the others untried (otherwise a dead Shizuku
 * would leak the display forever), so every step is attempted and failures are reported
 * back to the caller.
 */
class StopSequence(private val executor: suspend (Step) -> Unit) {

    enum class Step { HALT_TOOLS, FORCE_STOP_ROBLOX, RELEASE_DISPLAY, STOP_SERVICE }

    data class Failure(val step: Step, val message: String)

    companion object {
        val ORDER: List<Step> = listOf(
            Step.HALT_TOOLS,
            Step.FORCE_STOP_ROBLOX,
            Step.RELEASE_DISPLAY,
            Step.STOP_SERVICE,
        )
    }

    suspend fun run(): List<Failure> {
        val failures = mutableListOf<Failure>()
        for (step in ORDER) {
            try {
                executor(step)
            } catch (t: Throwable) {
                failures += Failure(step, t.message ?: t::class.java.simpleName)
            }
        }
        return failures
    }
}
