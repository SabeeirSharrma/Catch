package com.sabeeir.catchapp.core

/**
 * The one gate every injected event passes through.
 *
 * Spec 9.1: clicks are tied to the virtual display. If the display is gone, or the id
 * does not match the display we created, or Shizuku died, *nothing* is sent. The
 * clicker can therefore never reach the real screen or another app's window.
 */
class InjectorGuard(
    @Volatile var expectedDisplayId: Int? = null,
    @Volatile var displayAlive: Boolean = false,
    @Volatile var shizukuAlive: Boolean = false,
    @Volatile var stopped: Boolean = true,
) {
    /** Result of a check, so callers can log *why* injection was refused. */
    enum class Refusal { OK, STOPPED, NO_DISPLAY, SHIZUKU_DEAD, DISPLAY_GONE, ID_MISMATCH }

    fun check(requestedDisplayId: Int?): Refusal {
        if (stopped) return Refusal.STOPPED
        if (!shizukuAlive) return Refusal.SHIZUKU_DEAD
        val expected = expectedDisplayId ?: return Refusal.NO_DISPLAY
        if (!displayAlive) return Refusal.DISPLAY_GONE
        if (requestedDisplayId == null || requestedDisplayId != expected) return Refusal.ID_MISMATCH
        return Refusal.OK
    }

    fun canInject(requestedDisplayId: Int?): Boolean =
        check(requestedDisplayId) == Refusal.OK

    fun sessionStarted(displayId: Int) {
        expectedDisplayId = displayId
        displayAlive = true
        stopped = false
    }

    fun displayLost() {
        displayAlive = false
    }

    fun shizukuLost() {
        shizukuAlive = false
    }

    fun shizukuRestored() {
        shizukuAlive = true
    }

    fun sessionStopped() {
        stopped = true
        displayAlive = false
        expectedDisplayId = null
    }
}
