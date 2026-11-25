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
    val gpuApi: String,                // "vulkan" or "opengl"
    val gpuContext: String,            // "android"
    val vo: String,                    // "gpu-next"
    val hwdec: String,                 // "mediacodec-copy" or "no"
    val targetPrim: String,            // "bt.709" or "display-p3"
    val targetTrc: String,             // "srgb" or "pq"
    val targetContrast: Int,           // 1200
    val targetPeakNits: Int,           // 203..300
    val targetColorspaceHint: String,  // "no" (default) or "yes" (HBM experimental)
    val desiredHdrHeadroom: Float,     // 1.0f or <= min(2.25f, highestHdrSdrRatio)
    val toneMapping: String,           // "spline", "bt.2446a", etc.
    val hdrComputePeak: Boolean,       // true (disabled if hdr-toys overrides or DV conflict)
    val hdrPeakPercentile: Double,     // 99.8
    val hdrContrastRecovery: Double,   // 0.25..0.30
    val scaler: String,                // "ewa_lanczossharp", "spline36", "bilinear"
    val downscaler: String,            // "mitchell"
    val cscale: String,                // "bilinear"
    val correctDownscaling: Boolean,
    val linearDownscaling: Boolean,
    val sharpen: Float,                // 0f for 4K remux (zero grain destruction)
    val debandEnabled: Boolean,
    val debandIterations: Int,
    val debandThreshold: Int,
    val debandRange: Int,
    val debandGrain: Int,
    val subHdrPeakNits: Int,           // 120..160
    val hdrToysMapper: HdrToysToneMapper,
    val stripDolbyVisionFallback: Boolean,
    val is4kUhdRemuxProfile: Boolean,
    val hbmWarningMessage: String?,
)

/**
 * Source-adaptive Cinema Mastering XR advisor.
 * - 4K UHD Remux / High-Bitrate 10-bit (>= 2160p or > 20 Mbps): sharpen=0, deband=no (zero grain destruction),
 *   dscale=mitchell, spline / bt.2446a, hdr-compute-peak=yes, percentile=99.8, contrast-recovery=0.25-0.30.
 * - 720p/1080p Streaming, 8-bit, or Anime (< 1600p or < 12 Mbps): Auto-ON Smooth Gradation deband + ewa_lanczossharp.
 * - Clean HDR tone-mapping bypass on SDR streams.
 * - ThermalGovernor overrides step down scaler/deband before dropped frames become visible.
 */
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
        val is4kRemux = (media.height >= 2160 || media.width >= 3840 || media.bitrateMbps > 20.0) &&
            media.bitDepth >= 10
        val isLowResOrAnime = !is4kRemux && (media.height < 1600 || media.bitrateMbps < 12.0 || media.bitDepth <= 8)

        val targetPeak = RuanBacklightSpline.computeAmbientTargetPeakNits(
            estimatedDisplayNits = estimatedDisplayNits,
            manualOverrideNits = manualTargetPeakNits,
        )

        // Thermal governor override steps down ewa_lanczossharp -> spline36 -> bilinear
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
            is4kRemux -> 0.0f // Strictly preserve 35mm film grain
            isLowResOrAnime && preset == XrPreset.CINEMA_MASTERING_XR -> 0.08f
            else -> 0.0f
        }

        val toneMapAlgo = when {
            !isHdr -> "clip"
            masteringPeakNits > 2000f -> "bt.2446a"
            else -> "spline"
        }

        // Note: hdr-toys shader pipeline disables conflicting built-in tone-mapping compute peak
        val useBuiltInComputePeak = isHdr && hdrToysMapper == HdrToysToneMapper.OFF &&
            media.hdrFormat != HdrFormat.DV_P5

        val hdrHeadroom = if (experimentalHbmHdrMode) {
            min(RuanHardwareProfile.HIGHEST_HDR_SDR_RATIO, displayHighestHdrSdrRatio.coerceAtLeast(1.0f))
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
