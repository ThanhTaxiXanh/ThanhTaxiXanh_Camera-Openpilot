package vn.thanhtaxixanh.camera.ai

import java.util.Locale
import kotlin.math.round

/**
 * Pure rendering labels for GPS and independently verified metric lead data.
 * This never estimates speed, lane geometry, calibration or lead distance.
 * GPS remains informational only and never triggers safety warnings.
 */
object HudDisplayFormat {
    enum class UnitMode(val savedValue: String) {
        METRIC("km/h · m"),
        IMPERIAL("mph · ft");

        companion object {
            fun fromPreference(value: String?): UnitMode =
                entries.firstOrNull { it.savedValue == value } ?: METRIC
        }
    }

    data class Labels(
        val speed: String?,
        val lead: String?,
        val brand: String?
    )

    /**
     * Supplying a hidden logo may NOT suppress the independent GPS/lead HUD.
     * Invalid or not independently verified measurements must be passed as null.
     */
    fun labels(
        speedKmh: Float?,
        verifiedLeadMeters: Float?,
        units: UnitMode,
        showBrand: Boolean,
        roundDistanceToFiveMeters: Boolean = false
    ): Labels {
        val speed = speedKmh?.takeIf { it.isFinite() && it in 0f..240f }
            ?.let {
                val n = if (units == UnitMode.IMPERIAL)
                    it.toDouble() / 1.609344 else it.toDouble()
                String.format(Locale.US, "%.0f %s", n,
                    if (units == UnitMode.IMPERIAL) "mph" else "km/h")
            }
        val distance = verifiedLeadMeters?.takeIf {
            it.isFinite() && it in 1f..200f
        }?.let {
            val measured = it.toDouble()
            if (roundDistanceToFiveMeters && measured >= 20.0)
                round(measured / 5.0) * 5.0
            else measured
        }
        val lead = distance?.let {
            val n = if (units == UnitMode.IMPERIAL)
                it * 3.280839895013123 else it
            String.format(Locale.US, "%.0f %s · CHỈ THAM KHẢO", n,
                if (units == UnitMode.IMPERIAL) "ft" else "m")
        }
        return Labels(speed, lead,
            if (showBrand) "TTX Camera AI · VIDEO / ADAS" else null)
    }
}
