package com.sabeeir.catchapp.core.settings

import android.content.Context
import com.sabeeir.catchapp.core.AntiIdleConfig
import com.sabeeir.catchapp.core.ClickPoint
import com.sabeeir.catchapp.core.ClickerConfig

/**
 * Durable user settings. Plain SharedPreferences: small, synchronous, and testable
 * through a fake [android.content.SharedPreferences] if needed.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var cps: Float
        get() = prefs.getFloat(KEY_CPS, ClickerConfig.DEFAULT_CPS)
            .let { ClickerConfig.clampCps(it) }
        set(value) = prefs.edit().putFloat(KEY_CPS, ClickerConfig.clampCps(value)).apply()

    var antiIdleMinutes: Int
        get() = AntiIdleConfig.clampMinutes(prefs.getInt(KEY_ANTI_IDLE, AntiIdleConfig.DEFAULT_MINUTES))
        set(value) = prefs.edit().putInt(KEY_ANTI_IDLE, AntiIdleConfig.clampMinutes(value)).apply()

    var maxClicks: Int
        get() = prefs.getInt(KEY_MAX_CLICKS, ClickerConfig.UNLIMITED)
        set(value) = prefs.edit().putInt(KEY_MAX_CLICKS, value.coerceAtLeast(0)).apply()

    var maxMinutes: Int
        get() = prefs.getInt(KEY_MAX_MINUTES, ClickerConfig.UNLIMITED)
        set(value) = prefs.edit().putInt(KEY_MAX_MINUTES, value.coerceAtLeast(0)).apply()

    /** Saved calibration point in virtual display coordinates, or null. */
    var calibrationPoint: ClickPoint?
        get() {
            val id = prefs.getString(KEY_POINT_ID, null) ?: return null
            val label = prefs.getString(KEY_POINT_LABEL, null) ?: return null
            if (!prefs.contains(KEY_POINT_X) || !prefs.contains(KEY_POINT_Y)) return null
            return ClickPoint(
                id = id,
                label = label,
                x = prefs.getInt(KEY_POINT_X, 0),
                y = prefs.getInt(KEY_POINT_Y, 0),
            )
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_POINT_ID)
                    .remove(KEY_POINT_LABEL)
                    .remove(KEY_POINT_X)
                    .remove(KEY_POINT_Y)
            } else {
                editor.putString(KEY_POINT_ID, value.id)
                    .putString(KEY_POINT_LABEL, value.label)
                    .putInt(KEY_POINT_X, value.x)
                    .putInt(KEY_POINT_Y, value.y)
            }
            editor.apply()
        }

    /** Bubble position; -1 means "not placed yet, snap to the right edge". */
    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    var checklistDone: Boolean
        get() = prefs.getBoolean(KEY_CHECKLIST, false)
        set(value) = prefs.edit().putBoolean(KEY_CHECKLIST, value).apply()

    companion object {
        private const val NAME = "catch_settings"
        private const val KEY_CPS = "cps"
        private const val KEY_ANTI_IDLE = "anti_idle_minutes"
        private const val KEY_MAX_CLICKS = "max_clicks"
        private const val KEY_MAX_MINUTES = "max_minutes"
        private const val KEY_POINT_ID = "point_id"
        private const val KEY_POINT_LABEL = "point_label"
        private const val KEY_POINT_X = "point_x"
        private const val KEY_POINT_Y = "point_y"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_CHECKLIST = "checklist_done"
    }
}
