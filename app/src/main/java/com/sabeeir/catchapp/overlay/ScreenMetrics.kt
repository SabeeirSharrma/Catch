package com.sabeeir.catchapp.overlay

/**
 * Window geometry for the bubble, with the platform's refusal paths handled.
 *
 * Catch builds the overlay from a Service, and a Service is not a UiContext:
 * [android.view.WindowManager.getCurrentWindowMetrics] is documented to throw
 * `UnsupportedOperationException` on a non-UiContext, and the `WindowInsets` it hands back are
 * not guaranteed to exist. Those calls used to run in BubbleOverlay's eager property
 * initialisers, where a refusal killed the whole foreground service - bubble, hidden display
 * and all - with nothing left but Android's "keeps stopping" dialog.
 *
 * The bubble only needs a sane starting position, so every value here has a fallback instead
 * of a crash.
 */
object ScreenMetrics {

    /** Overlay placement bounds. Width and height are always >= 1. */
    data class Bounds(val width: Int, val height: Int)

    /**
     * @param measured what `currentWindowMetrics` reported, or null when it refused or threw.
     * @param fallbackWidth display width from `resources.displayMetrics`, which any context can
     *   read without asking the window manager.
     * @param fallbackHeight display height, same source.
     */
    fun bounds(measured: Bounds?, fallbackWidth: Int, fallbackHeight: Int): Bounds =
        measured?.takeIf { it.width > 0 && it.height > 0 }
            ?: Bounds(fallbackWidth.coerceAtLeast(1), fallbackHeight.coerceAtLeast(1))

    /**
     * @param measured system-bar inset from `windowInsets`, or null when unavailable. A zero
     *   inset only ever costs the bubble a little vertical breathing room, so it is the safe
     *   default.
     */
    fun topInset(measured: Int?): Int = measured?.coerceAtLeast(0) ?: 0
}
