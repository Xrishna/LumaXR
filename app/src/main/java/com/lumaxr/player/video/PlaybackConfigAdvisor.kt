package com.lumaxr.player.video

import com.lumaxr.player.hardware.RuanBacklightSpline
import com.lumaxr.player.hardware.RuanHardwareProfile
import com.lumaxr.player.hardware.ThermalTier
import kotlin.math.min

enum class XrPreset(
    val displayName: String,
    val dimmingFloorDMin: Float,
    val localContrastDefault: Float,
    val defaultCeilingNits: Float,
) {
    CINEMA_MASTERING_XR("Cinema Mastering XR", 0.42f, 0.28f, 210f),
    REFERENCE_CREATOR("Reference Creator", 0.60f, 0.0f, 180f),
    NIGHT_THEATRE("Night Theatre", 0.35f, 0.24f, 185f),
    RAW_BYPASS("Raw / Bypass", 1.0f, 0.0f, 220f),
}

enum class HdrToysToneMapper(val id: Int, val label: String) {
    OFF(0, "libplacebo Native"),
    BT2446A(1, "hdr-toys BT.2446a"),
    BT2390(2, "hdr-toys BT.2390"),
    BT1886(3, "hdr-toys BT.1886"),
    ST2094_40_50(4, "hdr-toys ST.2094-40/50"),
    ASTRA(5, "hdr-toys Astra"),
}

data class MpvConfigPlan(
    val gpuApi: String,
    val gpuContext: String,
    val vo: String,
    val hwdec: String,
    val targetPrim: String,
    val targetTrc: String,
    val targetContrast: Int,
    val targetPeakNits: Int,
    val targetColorspaceHint: String,
    val desiredHdrHeadroom: Float,
    val toneMapping: String,
    val hdrComputePeak: Boolean,
    val hdrPeakPercentile: Double,
    val hdrContrastRecovery: Double,
    val scaler: String,
    val downscaler: String,
    val cscale: String,
    val correctDownscaling: Boolean,
    val linearDownscaling: Boolean,
    val sharpen: Float,
    val debandEnabled: Boolean,
    val debandIterations: Int,
    val debandThreshold: Int,
    val debandRange: Int,
    val debandGrain: Int,
    val subHdrPeakNits: Int,
    val hdrToysMapper: HdrToysToneMapper,
    val stripDolbyVisionFallback: Boolean,
    val is4kUhdRemuxProfile: Boolean,
    val hbmWarningMessage: String?,
)

class PlaybackConfigAdvisor {

    fun buildConfigPlan(
        media: ProbedMediaInfo,
        preset: XrPreset,
        useVulkan: Boolean,
        estimatedDisplayNits: Float,
        manualTargetPeakNits: Float?,
        experimentalHbmHdrMode: Boolean,
        displayHighestHdrSdrRatio: Float,
        hdrToysMapper: HdrToysToneMapper,
        subtitlePeakNits: Int = 140,
        thermalTier: ThermalTier = ThermalTier.OPTIMAL,
        forceP3GamutOverride: Boolean = false,
        masteringPeakNits: Float = 1000f,
    ): MpvConfigPlan {
        val isHdr = media.hdrFormat != HdrFormat.SDR
        val is4k = media.height >= 2160 || media.width >= 3840
        val is4kRemux = (is4k || media.bitrateMbps > 20.0) && media.bitDepth >= 10
        val isLowResOrAnime = !is4k && !is4kRemux &&
            (media.height < 1600 || (media.bitrateMbps in 0.1..12.0) || media.bitDepth <= 8)

        val targetPeak = if (experimentalHbmHdrMode) {
            RuanHardwareProfile.KNOT_HBM_NITS.toInt()
        } else {
            RuanBacklightSpline.computeAmbientTargetPeakNits(
                estimatedDisplayNits = estimatedDisplayNits,
                manualOverrideNits = manualTargetPeakNits,
            )
        }

        val desiredScaler = when {
            is4kRemux -> "spline36"
            isLowResOrAnime && preset != XrPreset.RAW_BYPASS -> "ewa_lanczossharp"
            else -> "spline36"
        }
        val finalScaler = thermalTier.scalerOverride ?: desiredScaler

        val deband = isLowResOrAnime &&
            preset != XrPreset.RAW_BYPASS &&
            preset != XrPreset.REFERENCE_CREATOR &&
            thermalTier.allowDeband

        val sharpen = when {
            is4kRemux -> 0.0f
            isLowResOrAnime && preset == XrPreset.CINEMA_MASTERING_XR -> 0.08f
            else -> 0.0f
        }

        val toneMapAlgo = when {
            !isHdr -> "clip"
            masteringPeakNits > 2000f -> "bt.2446a"
            else -> "spline"
        }

        val useBuiltInComputePeak = isHdr && hdrToysMapper == HdrToysToneMapper.OFF &&
            media.hdrFormat != HdrFormat.DV_P5

        val safeRatio = if (displayHighestHdrSdrRatio.isFinite()) displayHighestHdrSdrRatio.coerceAtLeast(1.0f) else 1.0f
        val hdrHeadroom = if (experimentalHbmHdrMode) {
            min(RuanHardwareProfile.HIGHEST_HDR_SDR_RATIO, safeRatio)
        } else {
            1.0f
        }

        val hbmWarning = if (experimentalHbmHdrMode && RuanHardwareProfile.isRuanDevice()) {
            "Experimental 600-nit HBM is sunlight-gated (minLux 6001.0) on ruan and may trigger HyperOS elevated-black desiredRatio(10.00) quirk."
        } else null

        val targetPrim = if (forceP3GamutOverride) "display-p3" else "bt.709"
        val targetTrc = if (experimentalHbmHdrMode) "pq" else "srgb"

        return MpvConfigPlan(
            gpuApi = if (useVulkan) "vulkan" else "opengl",
            gpuContext = "android",
            vo = "gpu-next",
            hwdec = "mediacodec-copy",
            targetPrim = targetPrim,
            targetTrc = targetTrc,
            targetContrast = RuanHardwareProfile.NATIVE_STATIC_CONTRAST,
            targetPeakNits = targetPeak,
            targetColorspaceHint = if (experimentalHbmHdrMode) "yes" else "no",
            desiredHdrHeadroom = hdrHeadroom,
            toneMapping = toneMapAlgo,
            hdrComputePeak = useBuiltInComputePeak,
            hdrPeakPercentile = 99.8,
            hdrContrastRecovery = if (isHdr && preset != XrPreset.RAW_BYPASS) 0.28 else 0.0,
            scaler = finalScaler,
            downscaler = "mitchell",
            cscale = "bilinear",
            correctDownscaling = true,
            linearDownscaling = false,
            sharpen = sharpen,
            debandEnabled = deband,
            debandIterations = if (deband) 2 else 0,
            debandThreshold = 36,
            debandRange = 16,
            debandGrain = 12,
            subHdrPeakNits = subtitlePeakNits.coerceIn(80, 260),
            hdrToysMapper = hdrToysMapper,
            stripDolbyVisionFallback = false,
            is4kUhdRemuxProfile = is4kRemux,
            hbmWarningMessage = hbmWarning,
        )
    }
}
