package com.lumaxr.player.hardware

import kotlin.math.roundToInt

/**
 * Exact piecewise linear backlight-to-nits spline calibrated for Xiaomi Redmi Pad Pro 5G (ruan):
 * - Segment 1: [0.001709819, 0.49975574] <-> [2.0, 500.0] nits (Normal indoor/outdoor ceiling)
 * - Segment 2: [0.49975574, 1.0]         <-> [500.0, 600.0] nits (Sunlight-gated HBM: minLux=6001)
 */
object RuanBacklightSpline {

    private const val X0 = RuanHardwareProfile.KNOT_MIN_NORM
    private const val Y0 = RuanHardwareProfile.KNOT_MIN_NITS
    private const val X1 = RuanHardwareProfile.KNOT_NORMAL_NORM
    private const val Y1 = RuanHardwareProfile.KNOT_NORMAL_NITS
    private const val X2 = RuanHardwareProfile.KNOT_HBM_NORM
    private const val Y2 = RuanHardwareProfile.KNOT_HBM_NITS

    const val TARGET_PEAK_MIN_CLAMP_NITS = 203f
    const val TARGET_PEAK_MAX_CLAMP_NITS = 300f
    const val TARGET_PEAK_DEFAULT_NITS = 240f

    /**
     * Converts normalized Android WindowManager screenBrightness [0..1] to physical panel nits [2..600].
     */
    fun brightnessToNits(normalizedBrightness: Float): Float {
        val x = normalizedBrightness.coerceIn(0f, 1f)
        if (x <= X0) return Y0
        return if (x <= X1) {
            val t = (x - X0) / (X1 - X0)
            Y0 + t * (Y1 - Y0)
        } else {
            val t = (x - X1) / (X2 - X1)
            Y1 + t * (Y2 - Y1)
        }
    }

    /**
     * Converts desired panel nits [2..600] to normalized Android screenBrightness [0.001709819..1.0].
     */
    fun nitsToBrightness(nits: Float): Float {
        val y = nits.coerceIn(Y0, Y2)
        return if (y <= Y1) {
            val t = (y - Y0) / (Y1 - Y0)
            X0 + t * (X1 - X0)
        } else {
            val t = (y - Y1) / (Y2 - Y1)
            X1 + t * (X2 - X1)
        }
    }

    /**
     * Ambient/window-brightness-linked target-peak auto-exposure curve for IPS LCDs.
     * Maps real indoor viewing luminance (~30..245 nits) smoothly into 180..280 nits
     * and clamps to [203, 300] nits so dark rooms don't look murky and highlights stay punchy.
     */
    fun computeAmbientTargetPeakNits(
        estimatedDisplayNits: Float,
        manualOverrideNits: Float? = null,
    ): Int {
        if (manualOverrideNits != null) {
            return manualOverrideNits.coerceIn(TARGET_PEAK_MIN_CLAMP_NITS, TARGET_PEAK_MAX_CLAMP_NITS).roundToInt()
        }
        val indoorFraction = ((estimatedDisplayNits - RuanHardwareProfile.INDOOR_MIN_NITS) /
            (RuanHardwareProfile.INDOOR_MAX_NITS - RuanHardwareProfile.INDOOR_MIN_NITS)).coerceIn(0f, 1f)
        // Smooth S-curve across indoor range mapping 180..280 nits, then clamped [203, 300]
        val smooth = indoorFraction * indoorFraction * (3f - 2f * indoorFraction)
        val rawTarget = 180f + smooth * (280f - 180f)
        return rawTarget.coerceIn(TARGET_PEAK_MIN_CLAMP_NITS, TARGET_PEAK_MAX_CLAMP_NITS).roundToInt()
    }
}
