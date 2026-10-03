package com.sabeeir.catchapp.display

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.sabeeir.catchapp.core.SurfaceRouter

/**
 * Creates and owns the hidden virtual display (spec section 7).
 *
 * The display is created through the MediaProjection the user consented to, with
 * `FLAG_PUBLIC` so activities (Roblox) can be launched *onto* it by Shizuku's
 * `am start --display`. If a build rejects that flag we retry with
 * `FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY`; if everything is rejected we surface the
 * exact failure instead of silently mirroring the real screen.
 *
 * The output Surface is never null: while hidden it points at a [MirrorSink], while
 * shown it points at the overlay's SurfaceView (spec 7).
 */
class VirtualDisplayManager(
    private val displayManager: DisplayManager,
    private val onProjectionStopped: () -> Unit = {},
) {

    private val router = SurfaceRouter<Surface>()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var displayId: Int? = null
        private set

    var usedFlags: Int = 0
        private set

    var lastError: String? = null
        private set

    val hasDisplay: Boolean get() = virtualDisplay != null && displayId != null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "media projection stopped by the system/user")
            release(keepError = true)
            onProjectionStopped()
        }
    }

    /**
     * @param surface first surface to route the display to; must not be null
     * @return the display id, or null on failure with [lastError] set
     */
    fun create(projection: MediaProjection, config: DisplayConfig, surface: Surface): Int? {
        release(keepError = false)
        this.projection = projection
        try {
            projection.registerCallback(projectionCallback, mainHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "registerCallback failed: $t")
        }

        var failure: String? = null
        for (strategy in FLAG_STRATEGIES) {
            try {
                val display = projection.createVirtualDisplay(
                    NAME,
                    config.width,
                    config.height,
                    config.dpi,
                    strategy.flags,
                    surface,
                    null,
                    null,
                )
                if (display != null) {
                    virtualDisplay = display
                    usedFlags = strategy.flags
                    displayId = display.display.displayId
                    router.swapTo(surface)
                    lastError = null
                    Log.i(TAG, "created display ${displayId} (${strategy.name})")
                    return displayId
                }
                failure = "${strategy.name}: createVirtualDisplay returned null"
            } catch (t: Throwable) {
                failure = "${strategy.name}: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "create failed for ${strategy.name}: $t")
            }
        }
        lastError = failure ?: "display creation rejected"
        release(keepError = true)
        return null
    }

    /**
     * Swaps the display output between mirror and sink. [surface] must never be null:
     * a null surface pauses the display and therefore Roblox.
     */
    fun routeTo(surface: Surface) {
        val target = virtualDisplay ?: run {
            lastError = "no display to route"
            return
        }
        router.swapTo(surface)
        try {
            target.setSurface(surface)
        } catch (t: Throwable) {
            lastError = "setSurface failed: ${t.message}"
            Log.w(TAG, "setSurface failed: $t")
        }
    }

    /** True while the OS still knows about the display we created. */
    fun isDisplayAlive(): Boolean {
        val id = displayId ?: return false
        if (virtualDisplay == null) return false
        return displayManager.getDisplay(id) != null
    }

    fun release(keepError: Boolean = false) {
        val hadDisplay = virtualDisplay != null
        virtualDisplay?.let { display ->
            runCatching { display.setSurface(null) } // only ever called during teardown
            runCatching { display.release() }
        }
        virtualDisplay = null
        projection?.let { runCatching { it.unregisterCallback(projectionCallback) } }
        if (!keepError) lastError = null
        displayId = null
        usedFlags = 0
        if (hadDisplay) Log.i(TAG, "display released")
    }

    private data class FlagStrategy(val name: String, val flags: Int)

    companion object {
        private const val TAG = "CatchDisplay"
        const val NAME = "catch-hidden"

        private val FLAG_STRATEGIES = listOf(
            FlagStrategy(
                "public",
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            ),
            FlagStrategy(
                "public+own-content",
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ),
        )
    }
}
