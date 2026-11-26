package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.hardware.BacklightXrState
import com.lumaxr.player.hardware.ThermalTier
import com.lumaxr.player.video.HdrFormat
import com.lumaxr.player.video.HdrToysToneMapper
import com.lumaxr.player.video.MpvShaderOptsFormatter
import com.lumaxr.player.video.MpvUriResolver
import com.lumaxr.player.video.PlaybackConfigAdvisor
import com.lumaxr.player.video.ProbedMediaInfo
import com.lumaxr.player.video.SmartAspectState
import com.lumaxr.player.video.XrPreset
import `is`.xyz.mpv.MPV
import org.junit.Test
import java.io.File

class MpvRuntimeFixTest {

    @Test
    fun glslHooksDeclareAllRuntimeParamsAsTypeDynamic() {
        val roots = listOf(File("app/src/main/assets/shaders"), File("src/main/assets/shaders"))
        val shaderDir = roots.firstOrNull { it.isDirectory }
        assertThat(shaderDir).isNotNull()

        listOf("xr_lcd_master.glsl", "hdr_toys_pipeline.glsl").forEach { name ->
            val src = File(shaderDir!!, name).readText()
            val paramLines = src.lines().filter { it.startsWith("//!PARAM ") }
            val dynamicLines = src.lines().filter { it.startsWith("//!TYPE DYNAMIC ") }
            assertThat(paramLines).isNotEmpty()
            assertThat(dynamicLines.size).isEqualTo(paramLines.size)
        }
    }

    @Test
    fun shaderOptsFormatterUsesBareParamNamesWithoutSlashPrefixes() {
        val media = ProbedMediaInfo(
            uri = "file:///movies/Demo.mkv",
            title = "Demo",
            container = "MKV",
            hdrFormat = HdrFormat.HDR10_PQ,
            width = 3840,
            height = 2160,
            bitDepth = 10,
            bitrateMbps = 35.0,
            fps = 24.0,
            videoCodec = "hevc",
            audioCodec = "eac3",
            durationMs = 1200_000L,
        )
        val plan = PlaybackConfigAdvisor().buildConfigPlan(
            media = media,
            preset = XrPreset.CINEMA_MASTERING_XR,
            useVulkan = true,
            estimatedDisplayNits = 180f,
            manualTargetPeakNits = null,
            experimentalHbmHdrMode = false,
            displayHighestHdrSdrRatio = 2.25f,
            hdrToysMapper = HdrToysToneMapper.BT2390,
            thermalTier = ThermalTier.OPTIMAL,
        )

        val initOpts = MpvShaderOptsFormatter.formatInitialOpts(plan)
        val liveOpts = MpvShaderOptsFormatter.formatLiveOpts(
            backlight = BacklightXrState(inverseShaderGain = 1.45f, localContrastStrength = 0.28f),
            splitWipe = 0.75f,
            bypassOriginal = false,
            bilateralRadius = 4,
            aspectState = SmartAspectState(),
            plan = plan,
        )

        assertThat(initOpts).doesNotContain("/")
        assertThat(initOpts).contains("u_tm_mode=2")
        assertThat(liveOpts).doesNotContain("/")
        assertThat(liveOpts).contains("u_inv_gain=1.4500")
        assertThat(liveOpts).contains("u_tm_mode=2")
    }

    @Test
    fun uriResolverMapsContentUrisCleanlyToRealPathOrDetachedFd() {
        var closedFd = -1
        val realPathRes = MpvUriResolver.resolveForMpv(
            rawUri = "content://media/external/video/media/99",
            openDetachedFd = { 17 },
            findRealPath = { "/storage/emulated/0/Movies/HDRDemo.mkv" },
            closeDetachedFd = { closedFd = it },
        )
        assertThat(realPathRes.uriOrPath).isEqualTo("/storage/emulated/0/Movies/HDRDemo.mkv")
        assertThat(realPathRes.usedContentFd).isFalse()
        assertThat(closedFd).isEqualTo(17)

        var leakedClosedFd = -1
        val fdRes = MpvUriResolver.resolveForMpv(
            rawUri = "content://com.android.providers.downloads/document/42",
            openDetachedFd = { 42 },
            findRealPath = { null },
            closeDetachedFd = { leakedClosedFd = it },
        )
        assertThat(fdRes.uriOrPath).isEqualTo("fd://42")
        assertThat(fdRes.usedContentFd).isTrue()
        assertThat(leakedClosedFd).isEqualTo(-1)

        val fileRes = MpvUriResolver.resolveForMpv(
            rawUri = "file:///storage/emulated/0/test.mkv",
            openDetachedFd = { null },
            findRealPath = { null },
            closeDetachedFd = {},
        )
        assertThat(fileRes.uriOrPath).isEqualTo("/storage/emulated/0/test.mkv")
    }

    @Test
    fun vulkanFallbackDetectsGpuNextErrorsAndIgnoresInformationalLogs() {
        assertThat(
            MpvShaderOptsFormatter.shouldTriggerVulkanFallback(
                level = MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR,
                prefix = "vo/gpu-next/vulkan",
                text = "vkCreateSwapchainKHR failed",
            ),
        ).isTrue()
        assertThat(
            MpvShaderOptsFormatter.shouldTriggerVulkanFallback(
                level = MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN,
                prefix = "vo/gpu-next/vulkan",
                text = "benign warning",
            ),
        ).isFalse()
        assertThat(
            MpvShaderOptsFormatter.shouldTriggerVulkanFallback(
                level = MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR,
                prefix = "demuxer",
                text = "minor metadata gap",
            ),
        ).isFalse()
    }
}
