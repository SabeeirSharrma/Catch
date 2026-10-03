package com.sabeeir.catchapp.core

/**
 * Maps touches in the mirror view to virtual display coordinates.
 *
 * The mirror shows the whole display image (aspect *fit*), so the content may be
 * letterboxed inside the view. Touches in the letterbox belong to nothing and are
 * rejected instead of being silently clamped onto a random display pixel.
 */
object CoordinateMapper {

    /** Letterboxed rectangle of display content inside the view, in view pixels. */
    data class ContentRect(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x < right && y >= top && y < bottom
    }

    /** Returns null when either size is degenerate. */
    fun fitRect(viewWidth: Int, viewHeight: Int, displayWidth: Int, displayHeight: Int): ContentRect? {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return null
        val scale = minOf(
            viewWidth.toFloat() / displayWidth,
            viewHeight.toFloat() / displayHeight,
        )
        val width = displayWidth * scale
        val height = displayHeight * scale
        return ContentRect(
            left = (viewWidth - width) / 2f,
            top = (viewHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    /**
     * View pixel -> display pixel, or null when the touch is outside the content area
     * or the geometry is unusable.
     */
    fun toDisplay(
        viewX: Float,
        viewY: Float,
        rect: ContentRect?,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int>? {
        if (rect == null || displayWidth <= 0 || displayHeight <= 0) return null
        if (!rect.contains(viewX, viewY)) return null
        val fx = ((viewX - rect.left) / rect.width).coerceIn(0f, 1f)
        val fy = ((viewY - rect.top) / rect.height).coerceIn(0f, 1f)
        val x = (fx * displayWidth).toInt().coerceIn(0, displayWidth - 1)
        val y = (fy * displayHeight).toInt().coerceIn(0, displayHeight - 1)
        return x to y
    }
}
