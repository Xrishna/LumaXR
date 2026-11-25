package com.lumaxr.player

import android.view.Display
import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.video.DisplayCapabilityGuard
import com.lumaxr.player.video.DisplayColorCapabilities
import com.lumaxr.player.video.EngineSwitchPreference
import com.lumaxr.player.video.HdrFormat
import com.lumaxr.player.video.ProbedMediaInfo
import com.lumaxr.player.video.VideoEngineKind
import org.junit.Test

class DisplayCapabilityGuardTest {

    private val guard = DisplayCapabilityGuard()

    private fun sampleMedia(hdr: HdrFormat) = ProbedMediaInfo(
        uri = "file:///movies/test.mkv",
        title = "Test",
        container = "MKV",
        hdrFormat = hdr,
        width = 3840,
        height = 2160,
        bitDepth = 10,
        bitrateMbps = 45.0,
        fps = 23.976,
        videoCodec = "hevc",
        audioCodec = "eac3",
        durationMs = 7200_000L,
    )

    private val ruanQuirkDisplay = DisplayColorCapabilities(
        isWideColorGamut = false,
        supportedHdrTypes = intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION), // DV (1) without HDR10 (2)
        highestHdrSdrRatio = 2.25f,
    )

    private val trueWcgDisplay = DisplayColorCapabilities(
        isWideColorGamut = true,
        supportedHdrTypes = intArrayOf(
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
            Display.HdrCapabilities.HDR_TYPE_HDR10,
        ),
        highestHdrSdrRatio = 2.25f,
    )

    @Test
    fun blocksMedia3ForDvProfile7EvenOnWcgPanel() {
        val decision = guard.evaluateRouting(
            media = sampleMedia(HdrFormat.DV_P7_MEL_FEL),
            displayCaps = trueWcgDisplay,
            hasMpvFilterOrHdrToysOrXrPreset = false,
            hasLibDovi = true,
            userPreference = EngineSwitchPreference.AUTO,
        )
        assertThat(decision.engine).isEqualTo(VideoEngineKind.LIBMPV_PLACEBO_XR)
        assertThat(decision.blockedMedia3).isTrue()
    }

    @Test
    fun catchesRuanDvWithoutHdr10QuirkAndForcesLibplacebo() {
        val decision = guard.evaluateRouting(
            media = sampleMedia(HdrFormat.DV_P8_1),
            displayCaps = ruanQuirkDisplay,
            hasMpvFilterOrHdrToysOrXrPreset = false,
            hasLibDovi = true,
            userPreference = EngineSwitchPreference.AUTO,
        )
        assertThat(decision.engine).isEqualTo(VideoEngineKind.LIBMPV_PLACEBO_XR)
        assertThat(decision.blockedMedia3).isTrue()
    }

    @Test
    fun allowsMedia3AutoFallbackForDvProfile5WhenLibdoviMissing() {
        val decision = guard.evaluateRouting(
            media = sampleMedia(HdrFormat.DV_P5),
            displayCaps = ruanQuirkDisplay,
            hasMpvFilterOrHdrToysOrXrPreset = true,
            hasLibDovi = false,
            userPreference = EngineSwitchPreference.AUTO,
        )
        assertThat(decision.engine).isEqualTo(VideoEngineKind.MEDIA3_DOLBY_HAL)
        assertThat(decision.blockedMedia3).isFalse()
    }

    @Test
    fun allowsManualOverrideForDolbyHalExceptDvProfile7() {
        val allowed = guard.evaluateRouting(
            media = sampleMedia(HdrFormat.DV_P5),
            displayCaps = ruanQuirkDisplay,
            hasMpvFilterOrHdrToysOrXrPreset = true,
            hasLibDovi = true,
            userPreference = EngineSwitchPreference.FORCE_NATIVE_DOLBY_HAL,
        )
        assertThat(allowed.engine).isEqualTo(VideoEngineKind.MEDIA3_DOLBY_HAL)

        val blockedP7 = guard.evaluateRouting(
            media = sampleMedia(HdrFormat.DV_P7_MEL_FEL),
            displayCaps = trueWcgDisplay,
            hasMpvFilterOrHdrToysOrXrPreset = false,
            hasLibDovi = true,
            userPreference = EngineSwitchPreference.FORCE_NATIVE_DOLBY_HAL,
        )
        assertThat(blockedP7.engine).isEqualTo(VideoEngineKind.LIBMPV_PLACEBO_XR)
        assertThat(blockedP7.blockedMedia3).isTrue()
    }
}
