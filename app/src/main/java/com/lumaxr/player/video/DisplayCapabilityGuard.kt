package com.lumaxr.player.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

enum class VideoEngineKind {
    LIBMPV_PLACEBO_XR,
    MEDIA3_DOLBY_HAL,
}

enum class EngineSwitchPreference {
    AUTO,
    FORCE_LIBPLACEBO_XR,
    FORCE_NATIVE_DOLBY_HAL,
}

data class DisplayColorCapabilities(
    val isWideColorGamut: Boolean,
    val supportedHdrTypes: IntArray,
    val highestHdrSdrRatio: Float,
) {
    val hasDolbyVision: Boolean get() = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in supportedHdrTypes
    val hasHdr10: Boolean get() = Display.HdrCapabilities.HDR_TYPE_HDR10 in supportedHdrTypes
    val hasBrokenRuanDvWithoutHdr10Quirk: Boolean get() = hasDolbyVision && !hasHdr10

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DisplayColorCapabilities) return false
        return isWideColorGamut == other.isWideColorGamut &&
            supportedHdrTypes.contentEquals(other.supportedHdrTypes) &&
            highestHdrSdrRatio == other.highestHdrSdrRatio
    }

    override fun hashCode(): Int {
        var result = isWideColorGamut.hashCode()
        result = 31 * result + supportedHdrTypes.contentHashCode()
        result = 31 * result + highestHdrSdrRatio.hashCode()
        return result
    }
}

data class RoutingDecision(
    val engine: VideoEngineKind,
    val reason: String,
    val blockedMedia3: Boolean,
)

class DisplayCapabilityGuard {

    fun inspectDisplay(context: Context): DisplayColorCapabilities {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = runCatching { context.display }.getOrNull() ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val supportedTypes = display?.mode?.supportedHdrTypes ?: IntArray(0)
        val ratio = if (display != null && display.isHdrSdrRatioAvailable) {
            display.hdrSdrRatio.takeIf { it.isFinite() }?.coerceAtLeast(1.0f) ?: 1.0f
        } else {
            1.0f
        }
        return DisplayColorCapabilities(
            isWideColorGamut = display?.isWideColorGamut ?: false,
            supportedHdrTypes = supportedTypes,
            highestHdrSdrRatio = ratio,
        )
    }

    fun evaluateRouting(
        media: ProbedMediaInfo,
        displayCaps: DisplayColorCapabilities,
        hasMpvFilterOrHdrToysOrXrPreset: Boolean,
        hasLibDovi: Boolean,
        userPreference: EngineSwitchPreference,
    ): RoutingDecision {
        if (userPreference == EngineSwitchPreference.FORCE_NATIVE_DOLBY_HAL) {
            if (media.hdrFormat == HdrFormat.DV_P7_MEL_FEL) {
                return RoutingDecision(
                    engine = VideoEngineKind.LIBMPV_PLACEBO_XR,
                    reason = "Hard-gate: DV Profile 7 MEL/FEL requires libmpv/libplacebo",
                    blockedMedia3 = true,
                )
            }
            return RoutingDecision(
                engine = VideoEngineKind.MEDIA3_DOLBY_HAL,
                reason = "Manual HUD override: Native Dolby HAL (Media3)",
                blockedMedia3 = false,
            )
        }

        if (userPreference == EngineSwitchPreference.FORCE_LIBPLACEBO_XR) {
            return RoutingDecision(
                engine = VideoEngineKind.LIBMPV_PLACEBO_XR,
                reason = "Manual HUD override: libmpv + libplacebo XR",
                blockedMedia3 = true,
            )
        }

        if (media.hdrFormat == HdrFormat.DV_P7_MEL_FEL) {
            return RoutingDecision(
                engine = VideoEngineKind.LIBMPV_PLACEBO_XR,
                reason = "Blocked Media3: Dolby Vision Profile 7 (UHD Remux MEL/FEL)",
                blockedMedia3 = true,
            )
        }

        // Exception (a): DV Profile 5 without libdovi must use c2.dolby.decoder.hevc to prevent green/magenta IPTPQc2
        if (media.hdrFormat == HdrFormat.DV_P5 && !hasLibDovi) {
            return RoutingDecision(
                engine = VideoEngineKind.MEDIA3_DOLBY_HAL,
                reason = "DV P5 fallback: libdovi absent, using c2.dolby.decoder.hevc",
                blockedMedia3 = false,
            )
        }

        if (!displayCaps.isWideColorGamut || displayCaps.hasBrokenRuanDvWithoutHdr10Quirk) {
            return RoutingDecision(
                engine = VideoEngineKind.LIBMPV_PLACEBO_XR,
                reason = "Blocked Media3: sRGB-only HWC / ruan DV-without-HDR10 quirk",
                blockedMedia3 = true,
            )
        }

        if (hasMpvFilterOrHdrToysOrXrPreset) {
            return RoutingDecision(
                engine = VideoEngineKind.LIBMPV_PLACEBO_XR,
                reason = "Blocked Media3: Cinema Mastering XR / hdr-toys / MPV shader active",
                blockedMedia3 = true,
            )
        }

        return RoutingDecision(
            engine = VideoEngineKind.LIBMPV_PLACEBO_XR,
            reason = "Default primary engine: libmpv + libplacebo XR",
            blockedMedia3 = false,
        )
    }
}
