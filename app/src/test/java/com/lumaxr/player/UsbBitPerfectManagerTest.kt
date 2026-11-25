package com.lumaxr.player

import android.media.AudioFormat
import android.media.AudioMixerAttributes
import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.music.UsbBitPerfectManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsbBitPerfectManagerTest {

    @Test
    fun returnsNullWhenDacHasNoBitPerfectAttributes() {
        val mgr = UsbBitPerfectManager(RuntimeEnvironment.getApplication())
        val defaultAttr = AudioMixerAttributes.Builder(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT).build()

        val chosen = mgr.selectBestBitPerfectAttributes(listOf(defaultAttr), 192000, 24)
        assertThat(chosen).isNull()
    }

    @Test
    fun selectsExactSampleRateAndBitDepthWhenBitPerfectSupported() {
        val mgr = UsbBitPerfectManager(RuntimeEnvironment.getApplication())
        val bp48 = AudioMixerAttributes.Builder(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

        val bp192 = AudioMixerAttributes.Builder(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                .setSampleRate(192000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

        val chosen = mgr.selectBestBitPerfectAttributes(listOf(bp48, bp192), 192000, 24)
        assertThat(chosen).isNotNull()
        assertThat(chosen!!.format.sampleRate).isEqualTo(192000)
        assertThat(chosen.mixerBehavior).isEqualTo(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
    }
}
