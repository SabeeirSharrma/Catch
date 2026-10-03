package com.sabeeir.catchapp.display

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * The sink the virtual display is pointed at while the mirror is hidden (spec 7).
 *
 * It consumes frames at a low frame rate so Roblox keeps rendering instead of stalling
 * on a null surface. Every acquired image is closed immediately: we only need the
 * buffer flow, not the pixels.
 */
class MirrorSink(config: DisplayConfig, maxImages: Int = DEFAULT_MAX_IMAGES) : AutoCloseable {

    private val thread = HandlerThread("catch-sink").apply { start() }
    private val handler = Handler(thread.looper)

    private val reader: ImageReader =
        ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, maxImages)

    @Volatile
    var framesReceived: Long = 0
        private set

    @Volatile
    var closed: Boolean = false
        private set

    val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            }
            if (image != null) {
                framesReceived++
                image.close()
            }
        }, handler)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { reader.close() }
        runCatching { thread.quitSafely() }
    }

    companion object {
        /** Two buffers: the display always has somewhere to draw. */
        const val DEFAULT_MAX_IMAGES = 2
    }
}
