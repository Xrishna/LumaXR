package com.lumaxr.player.hardware

import kotlin.math.roundToInt

/**
 * Exact piecewise linear backlight-to-nits spline calibrated for Xiaomi Redmi Pad Pro 5G (ruan):
 * - Segment 1: [0.001709819, 0.49975574] <-> [2.0, 500.0] nits
 * - Segment 2: [0.49975574, 1.0]         <-> [500.0, 600.0] nits (HBM minLux=6001)
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

    fun brightnessToNits(normalizedBrightness: Float): Float {
        if (!normalizedBrightness.isFinite()) return Y0
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

    fun nitsToBrightness(nits: Float): Float {
        if (!nits.isFinite()) return X0
        val y = nits.coerceIn(Y0, Y2)
        return if (y <= Y1) {
            val t = (y - Y0) / (Y1 - Y0)
            X0 + t * (X1 - X0)
        } else {
            val t = (y - Y1) / (Y2 - Y1)
            X1 + t * (X2 - X1)
        }
    }

    fun computeAmbientTargetPeakNits(
        estimatedDisplayNits: Float,
        manualOverrideNits: Float? = null,
    ): Int {
        if (manualOverrideNits != null && manualOverrideNits.isFinite()) {
            return manualOverrideNits.coerceIn(TARGET_PEAK_MIN_CLAMP_NITS, TARGET_PEAK_MAX_CLAMP_NITS).roundToInt()
        }
        val safeNits = if (estimatedDisplayNits.isFinite()) estimatedDisplayNits else TARGET_PEAK_DEFAULT_NITS
        val indoorFraction = ((safeNits - RuanHardwareProfile.INDOOR_MIN_NITS) /
            (RuanHardwareProfile.INDOOR_MAX_NITS - RuanHardwareProfile.INDOOR_MIN_NITS)).coerceIn(0f, 1f)
        val smooth = indoorFraction * indoorFraction * (3f - 2f * indoorFraction)
        val rawTarget = TARGET_PEAK_MIN_CLAMP_NITS + smooth * (280f - TARGET_PEAK_MIN_CLAMP_NITS)
        return rawTarget.coerceIn(TARGET_PEAK_MIN_CLAMP_NITS, TARGET_PEAK_MAX_CLAMP_NITS).roundToInt()
    }
}
