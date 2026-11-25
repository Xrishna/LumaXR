package com.lumaxr.player

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.hardware.RuanHardwareProfile
import com.lumaxr.player.music.DolbyAudioCodecSelector
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DolbyAudioCodecSelectorTest {

    private val selector = DolbyAudioCodecSelector()

    @Test
    fun prioritizesVerifiedRuanDolbyCodecs() {
        assertThat(selector.preferredCodecForMime("audio/eac3-joc")).isEqualTo(RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3)
        assertThat(selector.preferredCodecForMime("audio/eac3")).isEqualTo(RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3)
        assertThat(selector.preferredCodecForMime("audio/ac4")).isEqualTo(RuanHardwareProfile.CODEC_VENDOR_DOLBY_AC4)

        val generic = MediaCodecInfo.newInstance("c2.android.eac3.decoder", "audio/eac3-joc", "audio/eac3-joc", null, false, true, false, false, false)
        val vendor = MediaCodecInfo.newInstance(RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3, "audio/eac3-joc", "audio/eac3-joc", null, false, false, true, false, false)

        val sorted = selector.prioritizeDolbyDecoders("audio/eac3-joc", listOf(generic, vendor))
        assertThat(sorted.first().name).isEqualTo(RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3)
    }
}
