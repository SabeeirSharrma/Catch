package com.sabeeir.catchapp.core

/**
 * Keeps track of which surface the virtual display is drawing into.
 *
 * The invariant from spec section 7: the display must never be given a null surface,
 * because a null surface pauses the display and therefore Roblox. The type system does
 * most of the work ([active] is non-null and the router only ever accepts non-null
 * values); this class makes the swap order testable.
 *
 * @param T the surface holder type, e.g. an Android `Surface`.
 */
class SurfaceRouter<T : Any> {

    private var current: T? = null

    val active: T
        get() = current ?: error("no surface routed yet")

    val hasSurface: Boolean get() = current != null

    /** Routes [next] and returns the surface that was active before (null on first swap). */
    fun swapTo(next: T): T? {
        val previous = current
        current = next
        return previous
    }

    /** For tests and diagnostics. */
    fun activeOrNull(): T? = current
}
