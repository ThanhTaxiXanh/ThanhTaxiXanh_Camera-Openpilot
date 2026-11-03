package vn.thanhtaxixanh.camera.ai

import android.content.Context
import android.content.SharedPreferences

/** Offline configuration. Never equate saved warning thresholds with validated ADAS. */
object AdasPrefs {
    private const val NAME = "ttx_adas_preferences"
    const val LANE_AREA = "lane_area"
    const val LANE_LINES = "lane_lines"
    const val SHOW_LOGO = "show_logo"
    const val SHOW_LEAD = "show_lead"
    const val LEAD_ARROW = "lead_arrow"
    const val ROUND_DISTANCE = "round_distance"
    const val HIDE_STATUS_BAR = "hide_status_bar"
    const val SPEED_UNIT = "speed_unit"
    const val START_SPEED = "start_speed_kmh"
    const val ALERT_MODE = "alert_mode"
    const val HEADWAY_TENTHS = "headway_tenths"
    const val CAMERA_HEIGHT_CM = "camera_height_cm"
    const val GPS_OFFSET = "gps_offset_kmh"
    const val NIGHT_MINUTES = "night_minutes"
    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
