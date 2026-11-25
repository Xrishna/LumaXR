package com.lumaxr.player.hardware

import android.os.Build

/**
 * Verified hardware & OS telemetry profile for Xiaomi Redmi Pad Pro 5G (24074RPD2I / ruan_in).
 */
object RuanHardwareProfile {
    const val MODEL = "24074RPD2I"
    const val CODENAME = "ruan_in"
    const val BOARD_PLATFORM = "parrot" // Qualcomm SM7435
    const val GPU_NAME = "Adreno 710"

    const val VULKAN_API_VERSION_1_1 = 4198400 // 0x00401000
    const val PANEL_WIDTH_PX = 2560
    const val PANEL_HEIGHT_PX = 1600
    const val PANEL_DPI = 320
    const val CORNER_RADIUS_PX = 38
    const val CORNER_RADIUS_DP = 19f
    const val NATIVE_STATIC_CONTRAST = 1200

    val DISCRETE_REFRESH_RATES_HZ = intArrayOf(120, 90, 60, 50, 48, 30)
    const val HAS_ARR_SUPPORT = false

    // Measured backlight-to-nits piecewise spline knots
    const val KNOT_MIN_NORM = 0.001709819f
    const val KNOT_MIN_NITS = 2.0f
    const val KNOT_NORMAL_NORM = 0.49975574f
    const val KNOT_NORMAL_NITS = 500.0f
    const val KNOT_HBM_NORM = 1.0f
    const val KNOT_HBM_NITS = 600.0f

    const val HBM_MIN_LUX = 6001.0f
    const val RAMP_RATE_FAST_PER_SEC = 0.6021006f
    const val RAMP_RATE_SLOW_PER_SEC = 0.4816805f
    const val HIGHEST_HDR_SDR_RATIO = 2.25f

    const val INDOOR_MIN_NITS = 30.0f
    const val INDOOR_MAX_NITS = 245.6f
    const val INDOOR_MID_REFERENCE_NORM = 0.49f
    const val INDOOR_MID_REFERENCE_NITS = 40.71f
    const val INDOOR_HIGH_REFERENCE_NORM = 0.87f
    const val INDOOR_HIGH_REFERENCE_NITS = 245.6f

    // Verified Codec2 components
    const val CODEC_HW_HEVC_10BIT = "c2.qti.hevc.decoder"
    const val CODEC_HW_DV_HEVC = "c2.dolby.decoder.hevc"
    const val CODEC_VENDOR_DOLBY_EAC3 = "c2.dolby.eac3.decoder"
    const val CODEC_VENDOR_DOLBY_AC4 = "c2.dolby.ac4.decoder"
    const val CODEC_SW_AV1_DAV1D = "c2.android.av1-dav1d.decoder"

    // Verified GLES 3.2 extensions
    val VERIFIED_GLES_EXTENSIONS = setOf(
        "GL_EXT_color_buffer_float",
        "GL_EXT_color_buffer_half_float",
        "GL_EXT_texture_norm16",
        "GL_EXT_texture_type_2_10_10_10_REV",
        "GL_EXT_YUV_target",
        "GL_OES_EGL_image_external_essl3",
    )

    fun isRuanDevice(): Boolean {
        val modelMatch = Build.MODEL.equals(MODEL, ignoreCase = true)
        val deviceMatch = Build.DEVICE.contains("ruan", ignoreCase = true)
        return modelMatch || deviceMatch
    }
}
