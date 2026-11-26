package com.lumaxr.player.video

import com.lumaxr.player.hardware.BacklightXrState
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.util.Locale

data class MpvTrackInfo(
    val id: Int,
    val type: String,
    val title: String,
    val lang: String,
    val codec: String,
    val selected: Boolean,
)

object MpvShaderOptsFormatter {

    /**
     * Formats libplacebo `glsl-shader-opts` with bare parameter names matching `//!PARAM` declarations.
     * Combined with `//!TYPE DYNAMIC` in the GLSL hooks, this updates push-constant uniforms
     * cleanly at 120 Hz without forcing SPIR-V shader recompilation.
     */
    fun formatInitialOpts(plan: MpvConfigPlan): String {
        return String.format(
            Locale.US,
            "u_tm_mode=%d,u_dst_peak=%.1f",
            plan.hdrToysMapper.id,
            plan.targetPeakNits.toFloat(),
        )
    }

    fun formatLiveOpts(
        backlight: BacklightXrState,
        splitWipe: Float,
        bypassOriginal: Boolean,
        bilateralRadius: Int,
        aspectState: SmartAspectState,
        plan: MpvConfigPlan?,
    ): String {
        val isPqOrHlgOut = plan?.targetTrc == "pq"
        return buildString {
            append(String.format(Locale.US, "u_inv_gain=%.4f,", backlight.inverseShaderGain))
            append(String.format(Locale.US, "u_local_contrast=%.4f,", backlight.localContrastStrength))
            append("u_bilateral_radius=${bilateralRadius.coerceIn(0, 4)},")
            append(String.format(Locale.US, "u_letterbox_top=%.4f,", aspectState.letterboxTopNorm))
            append(String.format(Locale.US, "u_letterbox_bottom=%.4f,", aspectState.letterboxBottomNorm))
            append(String.format(Locale.US, "u_split_wipe=%.4f,", splitWipe.coerceIn(0f, 1f)))
            append("u_bypass_original=${if (bypassOriginal) "1.0" else "0.0"},")
            append("u_is_linear_bypass=${if (isPqOrHlgOut) "1.0" else "0.0"}")
            if (plan != null && plan.hdrToysMapper != HdrToysToneMapper.OFF) {
                append(",u_tm_mode=${plan.hdrToysMapper.id}")
                append(String.format(Locale.US, ",u_dst_peak=%.1f", plan.targetPeakNits.toFloat()))
            }
        }
    }

    /**
     * Detects fatal/error GPU initialization failures logged by libmpv/libplacebo before a frame
     * loads so Adreno 710 can fall back cleanly from Vulkan 1.1 to OpenGL ES 3.2.
     */
    fun shouldTriggerVulkanFallback(level: Int, prefix: String, text: String): Boolean {
        if (level > MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR) return false
        val p = prefix.lowercase(Locale.ROOT)
        val t = text.lowercase(Locale.ROOT)
        val isGpuRelatedPrefix = "vulkan" in p || "gpu-next" in p || "vo" in p || "libplacebo" in p
        val isGpuFailureText = "vulkan" in t ||
            "vk" in t ||
            "gpu context" in t ||
            "failed to initialize" in t ||
            "error opening/initializing" in t ||
            "swapchain" in t
        return isGpuRelatedPrefix || isGpuFailureText
    }

    fun parseTrackList(node: MPVNode?): List<MpvTrackInfo> {
        val items = node?.asArray() ?: return emptyList()
        return items.mapNotNull { entry ->
            val id = entry["id"]?.asInt()?.toInt() ?: return@mapNotNull null
            val type = entry["type"]?.asString().orEmpty()
            if (type.isBlank()) return@mapNotNull null
            MpvTrackInfo(
                id = id,
                type = type,
                title = entry["title"]?.asString().orEmpty(),
                lang = entry["lang"]?.asString().orEmpty(),
                codec = entry["codec"]?.asString().orEmpty(),
                selected = entry["selected"]?.asBoolean() ?: false,
            )
        }
    }
}
