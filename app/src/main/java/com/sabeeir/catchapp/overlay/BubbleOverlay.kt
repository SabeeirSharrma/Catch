package com.sabeeir.catchapp.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sabeeir.catchapp.R
import com.sabeeir.catchapp.core.BubbleState
import com.sabeeir.catchapp.core.CoordinateMapper
import com.sabeeir.catchapp.core.EdgeSnapper
import com.sabeeir.catchapp.core.settings.SettingsStore

/** What the gesture turned into, in virtual display coordinates. */
enum class GestureAction { DOWN, MOVE, UP }

/** Buttons in the expanded bubble menu (spec section 5). */
enum class MenuAction { TOGGLE_MIRROR, HIDE, TOGGLE_LOOP, STOP }

/**
 * The floating bubble + expanded mirror window.
 *
 * Rules from spec section 5:
 *  - the window is TYPE_APPLICATION_OVERLAY and FLAG_NOT_FOCUSABLE;
 *  - it only captures touches inside its own bounds (children return false where there
 *    is nothing to touch, so taps fall through to the apps below);
 *  - opacity never exceeds 0.8;
 *  - it snaps to an edge and remembers the position.
 */
class BubbleOverlay(
    private val context: Context,
    private val settings: SettingsStore,
    private val listener: Listener,
) {

    interface Listener {
        /** Virtual display geometry, used to translate mirror touches. */
        fun displaySize(): Pair<Int, Int>

        /** True when mirror taps are captured for calibration instead of injected. */
        fun calibrateMode(): Boolean

        fun onMirrorGesture(action: GestureAction, x: Int, y: Int, isTap: Boolean)

        fun onMirrorTouchStart()

        fun onMirrorTouchEnd()

        /** Null means the mirror surface is gone; the display must be re-routed. */
        fun onMirrorSurface(surface: Surface?)

        fun onMenuAction(action: MenuAction)
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val bubbleSizePx = dp(BUBBLE_DP)
    private val edgeMarginPx = dp(4f)

    // A Service is not a UiContext: getCurrentWindowMetrics() is documented to throw
    // UnsupportedOperationException there, and the WindowInsets it returns are not guaranteed
    // to exist. These run in eager property initialisers, so an unguarded refusal would kill
    // the whole foreground service. ScreenMetrics gives every value a fallback instead.
    private val screenBounds = ScreenMetrics.bounds(
        measured = runCatching {
            windowManager.currentWindowMetrics.bounds
                .let { ScreenMetrics.Bounds(it.width(), it.height()) }
        }.getOrNull(),
        fallbackWidth = context.resources.displayMetrics.widthPixels,
        fallbackHeight = context.resources.displayMetrics.heightPixels,
    )
    private val topInset = ScreenMetrics.topInset(
        runCatching {
            windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).top
        }.getOrNull(),
    )

    private val root = FrameLayout(context)

    private val bubbleView = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
        background = bubbleDrawable(colorFor(BubbleState.IDLE))
        contentDescription = "Catch bubble"
    }

    private val surfaceView = SurfaceView(context)

    private val statusText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(dp(8f), dp(6f), dp(8f), dp(2f))
    }

    private val loopButton = actionButton("Loop")
    private val hideButton = actionButton("Hide")
    private val stopButton = actionButton("Stop")
    private val openButton = actionButton("Mirror")

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.argb(204, 24, 26, 30)) // 0.8 max opacity
        addView(statusText)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(openButton)
                addView(loopButton)
                addView(hideButton)
                addView(stopButton)
            },
        )
        addView(
            surfaceView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    private val windowParams = WindowManager.LayoutParams(
        bubbleSizePx,
        bubbleSizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        x = screenBounds.width - bubbleSizePx - edgeMarginPx
        y = topInset + edgeMarginPx
    }

    private var expanded = false
    private var attached = false

    /** Why [show] was refused, so the caller can report it instead of guessing. */
    var lastFailure: Throwable? = null
        private set

    /** Movement below this is a tap, above it is a drag. */
    private var downRawX = 0f
    private var downRawY = 0f
    private var draggingBubble = false
    private var lastGestureMoveAt = 0L
    private var gestureDownAt = 0L

    init {
        restorePosition()
        root.addView(bubbleView)
        root.addView(panel)
        panel.visibility = View.GONE

        bubbleView.setOnTouchListener { view, event -> handleBubbleTouch(view, event) }
        surfaceView.setOnTouchListener { view, event -> handleMirrorTouch(view, event) }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                listener.onMirrorSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                listener.onMirrorSurface(null)
            }
        })

        openButton.setOnClickListener { listener.onMenuAction(MenuAction.TOGGLE_MIRROR) }
        hideButton.setOnClickListener { listener.onMenuAction(MenuAction.HIDE) }
        loopButton.setOnClickListener { listener.onMenuAction(MenuAction.TOGGLE_LOOP) }
        stopButton.setOnClickListener { listener.onMenuAction(MenuAction.STOP) }
    }

    // ------------------------------------------------------------------ window

    /** @return true when the overlay window is attached to the screen. */
    fun show(): Boolean {
        if (attached) return true
        return runCatching {
            windowManager.addView(root, windowParams)
            attached = true
            lastFailure = null
        }.onFailure {
            // Almost always a revoked SYSTEM_ALERT_WINDOW permission; report it verbatim.
            attached = false
            lastFailure = it
        }.isSuccess
    }

    fun close() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeViewImmediate(root) }
    }

    /** Expands to the mirror window or collapses back to the bubble. */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (value) {
            val (dw, dh) = listener.displaySize()
            val panelWidth = (screenBounds.width * 85 / 100).coerceAtMost(screenBounds.width - dp(16f))
            val surfaceHeight = if (dh > 0) (panelWidth.toFloat() / (dw.toFloat() / dh)).toInt() else panelWidth * 9 / 16
            bubbleView.visibility = View.GONE
            panel.visibility = View.VISIBLE
            windowParams.width = panelWidth
            windowParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            surfaceView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                surfaceHeight,
            )
            statusText.visibility = View.VISIBLE
        } else {
            panel.visibility = View.GONE
            bubbleView.visibility = View.VISIBLE
            windowParams.width = bubbleSizePx
            windowParams.height = bubbleSizePx
            statusText.visibility = View.GONE
        }
        relayout()
    }

    fun updateState(state: BubbleState, loopActive: Boolean) {
        bubbleView.background = bubbleDrawable(colorFor(state))
        statusText.text = buildString {
            append(statusOf(state))
            if (loopActive) append(" · loop on")
        }
        loopButton.text = if (loopActive) "Loop ●" else "Loop ○"
    }

    private fun relayout() {
        if (!attached) return
        runCatching { windowManager.updateViewLayout(root, windowParams) }
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    private fun handleBubbleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                draggingBubble = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!draggingBubble && (dx * dx + dy * dy) > touchSlopSq()) {
                    draggingBubble = true
                }
                if (draggingBubble) {
                    val rawX = windowParams.x + dx
                    val rawY = windowParams.y + dy
                    windowParams.x = rawX.toInt()
                    windowParams.y = rawY.toInt()
                    relayout()
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (draggingBubble) {
                    settleBubble()
                } else {
                    setExpanded(true)
                }
                view.performClick()
                return true
            }
            else -> return false
        }
    }

    private fun settleBubble() {
        val (x, y) = EdgeSnapper.settle(
            rawX = windowParams.x.toFloat(),
            rawY = windowParams.y.toFloat(),
            bubbleW = bubbleSizePx,
            bubbleH = bubbleSizePx,
            boundsW = screenBounds.width,
            boundsH = screenBounds.height,
            insets = EdgeSnapper.Insets(top = topInset),
        )
        windowParams.x = x.toInt()
        windowParams.y = y.toInt()
        relayout()
        settings.bubbleX = windowParams.x
        settings.bubbleY = windowParams.y
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleMirrorTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                gestureDownAt = System.currentTimeMillis()
                lastGestureMoveAt = 0L
                listener.onMirrorTouchStart()
                if (!listener.calibrateMode()) {
                    mappedDisplayCoordinates(view, event.x, event.y)?.let { (x, y) ->
                        listener.onMirrorGesture(GestureAction.DOWN, x, y, false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (listener.calibrateMode()) return true
                val now = System.currentTimeMillis()
                if (now - lastGestureMoveAt < MOVE_INTERVAL_MS) return true
                lastGestureMoveAt = now
                mappedDisplayCoordinates(view, event.x, event.y)?.let { (x, y) ->
                    listener.onMirrorGesture(GestureAction.MOVE, x, y, false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                listener.onMirrorTouchEnd()
                val moved = distanceSq(downRawX, downRawY, event.rawX, event.rawY) > touchSlopSq()
                val isTap = !moved && (System.currentTimeMillis() - gestureDownAt) < TAP_MAX_MS
                mappedDisplayCoordinates(view, event.x, event.y)?.let { (x, y) ->
                    listener.onMirrorGesture(GestureAction.UP, x, y, isTap)
                }
                view.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                listener.onMirrorTouchEnd()
                return true
            }
            else -> return false
        }
    }

    private fun mappedDisplayCoordinates(view: View, x: Float, y: Float): Pair<Int, Int>? {
        val (displayW, displayH) = listener.displaySize()
        val rect = CoordinateMapper.fitRect(view.width, view.height, displayW, displayH)
        return CoordinateMapper.toDisplay(x, y, rect, displayW, displayH)
    }

    private fun restorePosition() {
        val x = settings.bubbleX
        val y = settings.bubbleY
        if (x >= 0 && y >= 0) {
            windowParams.x = x.coerceAtMost(screenBounds.width - bubbleSizePx)
            windowParams.y = y.coerceIn(topInset, screenBounds.height - bubbleSizePx)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun actionButton(label: String): Button = Button(context).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun bubbleDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            alpha = 217 // ~0.85 of the colour itself, window alpha keeps us under 0.8
        }

    private fun statusOf(state: BubbleState): String = when (state) {
        BubbleState.SHIZUKU_DOWN -> "Shizuku down"
        BubbleState.ROBLOX_DEAD -> "Roblox dead"
        BubbleState.DISPLAY_LOST -> "Display lost"
        BubbleState.LOOP_ON -> "Loop running"
        BubbleState.RUNNING -> "Running"
        BubbleState.IDLE -> "Hidden"
    }

    private fun colorFor(state: BubbleState): Int = when (state) {
        BubbleState.SHIZUKU_DOWN -> color(R.color.state_shizuku_down)
        BubbleState.ROBLOX_DEAD -> color(R.color.state_roblox_dead)
        BubbleState.DISPLAY_LOST -> color(R.color.state_display_lost)
        BubbleState.LOOP_ON -> color(R.color.state_loop)
        BubbleState.RUNNING -> color(R.color.state_running)
        BubbleState.IDLE -> color(R.color.state_idle)
    }

    private fun color(id: Int): Int = context.getColor(id)

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun touchSlopSq(): Float {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        return slop * slop
    }

    private fun distanceSq(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return dx * dx + dy * dy
    }

    companion object {
        private const val BUBBLE_DP = 52
        private const val MOVE_INTERVAL_MS = 32L
        private const val TAP_MAX_MS = 400L
    }
}
