package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.hardware.ThermalTier
import com.lumaxr.player.video.HdrFormat
import com.lumaxr.player.video.HdrToysToneMapper
import com.lumaxr.player.video.PlaybackConfigAdvisor
import com.lumaxr.player.video.ProbedMediaInfo
import com.lumaxr.player.video.XrPreset
import org.junit.Test

class PlaybackConfigAdvisorTest {

    private val advisor = PlaybackConfigAdvisor()

    @Test
    fun uhdRemuxProtects35mmFilmGrainWithZeroSharpenAndNoDeband() {
        val remux = ProbedMediaInfo(
            uri = "file:///movies/BladeRunner.2160p.Remux.mkv",
            title = "Blade Runner",
            container = "MKV",
            hdrFormat = HdrFormat.DV_P7_MEL_FEL,
            width = 3840,
            height = 2160,
            bitDepth = 10,
            bitrateMbps = 68.5,
            fps = 23.976,
            videoCodec = "hevc",
            audioCodec = "truehd",
            durationMs = 7000_000L,
        )
        val plan = advisor.buildConfigPlan(
            media = remux,
            preset = XrPreset.CINEMA_MASTERING_XR,
            useVulkan = true,
            estimatedDisplayNits = 180f,
            manualTargetPeakNits = null,
            experimentalHbmHdrMode = false,
            displayHighestHdrSdrRatio = 2.25f,
            hdrToysMapper = HdrToysToneMapper.OFF,
        )
        assertThat(plan.is4kUhdRemuxProfile).isTrue()
        assertThat(plan.sharpen).isEqualTo(0.0f)
        assertThat(plan.debandEnabled).isFalse()
        assertThat(plan.downscaler).isEqualTo("mitchell")
        assertThat(plan.targetContrast).isEqualTo(1200)
        assertThat(plan.desiredHdrHeadroom).isEqualTo(1.0f)
    }

    @Test
    fun anime1080pEnablesSmoothGradationDebandAndEwaLanczossharp() {
        val anime = ProbedMediaInfo(
            uri = "file:///anime/Frieren.1080p.mkv",
            title = "Frieren",
            container = "MKV",
            hdrFormat = HdrFormat.SDR,
            width = 1920,
            height = 1080,
            bitDepth = 8,
            bitrateMbps = 6.2,
            fps = 23.976,
            videoCodec = "hevc",
            audioCodec = "flac",
            durationMs = 1440_000L,
        )
        val plan = advisor.buildConfigPlan(
            media = anime,
            preset = XrPreset.CINEMA_MASTERING_XR,
            useVulkan = true,
            estimatedDisplayNits = 160f,
            manualTargetPeakNits = null,
            experimentalHbmHdrMode = false,
            displayHighestHdrSdrRatio = 2.25f,
            hdrToysMapper = HdrToysToneMapper.OFF,
        )
        assertThat(plan.debandEnabled).isTrue()
        assertThat(plan.debandIterations).isEqualTo(2)
        assertThat(plan.scaler).isEqualTo("ewa_lanczossharp")
        assertThat(plan.toneMapping).isEqualTo("clip")
    }

    @Test
    fun thermalGovernorStepsDownScalerAndDisablesDebandOn4kAv1Pressure() {
        val anime = ProbedMediaInfo(
            uri = "file:///anime/Test.1080p.av1.mkv",
            title = "AV1",
            container = "MKV",
            hdrFormat = HdrFormat.SDR,
            width = 1920,
            height = 1080,
            bitDepth = 10,
            bitrateMbps = 8.0,
            fps = 24.0,
            videoCodec = "av1",
            audioCodec = "opus",
            durationMs = 1200_000L,
        )
        val throttled = advisor.buildConfigPlan(
            media = anime,
            preset = XrPreset.CINEMA_MASTERING_XR,
            useVulkan = true,
            estimatedDisplayNits = 160f,
            manualTargetPeakNits = null,
            experimentalHbmHdrMode = false,
            displayHighestHdrSdrRatio = 2.25f,
            hdrToysMapper = HdrToysToneMapper.OFF,
            thermalTier = ThermalTier.THROTTLED,
        )
        assertThat(throttled.scaler).isEqualTo("bilinear")
        assertThat(throttled.debandEnabled).isFalse()
    }
}
