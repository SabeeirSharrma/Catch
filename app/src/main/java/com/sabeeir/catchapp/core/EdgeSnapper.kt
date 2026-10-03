package com.sabeeir.catchapp.core

/**
 * Drag handling for the bubble: clamp to the usable screen area, then snap to the
 * nearest horizontal edge. Position memory is the caller's job (it just feeds the
 * last snapped position back in as the drag start).
 */
object EdgeSnapper {

    data class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    /** Distance kept from the physical screen edge. */
    const val EDGE_MARGIN_PX = 4f

    /**
     * @param rawX raw left coordinate of the bubble under the finger (finger offset is
     *   already applied by the caller)
     * @param rawY raw top coordinate of the bubble
     */
    fun clamp(rawX: Float, rawY: Float, bubbleW: Int, bubbleH: Int, boundsW: Int, boundsH: Int, insets: Insets = Insets()): Pair<Float, Float> {
        val minX = insets.left + EDGE_MARGIN_PX
        val maxX = (boundsW - insets.right - bubbleW - EDGE_MARGIN_PX).coerceAtLeast(minX)
        val minY = insets.top + EDGE_MARGIN_PX
        val maxY = (boundsH - insets.bottom - bubbleH - EDGE_MARGIN_PX).coerceAtLeast(minY)
        return rawX.coerceIn(minX, maxX) to rawY.coerceIn(minY, maxY)
    }

    /** Snaps an already clamped position to the closer of the left/right edges. */
    fun snapToEdge(
        x: Float,
        y: Float,
        bubbleW: Int,
        boundsW: Int,
        insets: Insets = Insets(),
    ): Pair<Float, Float> {
        val minX = insets.left + EDGE_MARGIN_PX
        val maxX = (boundsW - insets.right - bubbleW - EDGE_MARGIN_PX).coerceAtLeast(minX)
        val toLeft = x - minX
        val toRight = maxX - x
        return (if (toLeft <= toRight) minX else maxX) to y
    }

    /** Full drop handling: clamp then snap. */
    fun settle(
        rawX: Float,
        rawY: Float,
        bubbleW: Int,
        bubbleH: Int,
        boundsW: Int,
        boundsH: Int,
        insets: Insets = Insets(),
    ): Pair<Float, Float> {
        val (cx, cy) = clamp(rawX, rawY, bubbleW, bubbleH, boundsW, boundsH, insets)
        return snapToEdge(cx, cy, bubbleW, boundsW, insets)
    }
}
