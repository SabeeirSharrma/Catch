package com.sabeeir.catchapp.display

/**
 * Geometry of the hidden virtual display (spec section 7): low resolution, low DPI so
 * Roblox renders cheaply while nobody is looking at it.
 */
data class DisplayConfig(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val dpi: Int = DEFAULT_DPI,
) {
    init {
        require(width in MIN_SIZE..MAX_SIZE) { "width out of range" }
        require(height in MIN_SIZE..MAX_SIZE) { "height out of range" }
        require(dpi in MIN_DPI..MAX_DPI) { "dpi out of range" }
    }

    val aspect: Float get() = width.toFloat() / height

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_DPI = 160

        const val MIN_SIZE = 320
        const val MAX_SIZE = 2560
        const val MIN_DPI = 80
        const val MAX_DPI = 320

        /** Never throws: bad user input is clamped into a sane band. */
        fun sanitized(width: Int, height: Int, dpi: Int): DisplayConfig = DisplayConfig(
            width = width.coerceIn(MIN_SIZE, MAX_SIZE),
            height = height.coerceIn(MIN_SIZE, MAX_SIZE),
            dpi = dpi.coerceIn(MIN_DPI, MAX_DPI),
        )
    }
}
