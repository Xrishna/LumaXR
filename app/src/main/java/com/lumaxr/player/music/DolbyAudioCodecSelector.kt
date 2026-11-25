package com.lumaxr.player.music

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lumaxr.player.hardware.RuanHardwareProfile
import java.util.Locale

class DolbyAudioCodecSelector(
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val raw = delegate.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return prioritizeDolbyDecoders(mimeType, raw)
    }

    fun prioritizeDolbyDecoders(
        mimeType: String,
        infos: List<MediaCodecInfo>,
    ): List<MediaCodecInfo> {
        val targetName = preferredCodecForMime(mimeType) ?: return infos
        return infos.sortedWith(
            compareByDescending<MediaCodecInfo> { it.name.equals(targetName, ignoreCase = true) }
                .thenByDescending { it.vendor }
                .thenBy { it.softwareOnly },
        )
    }

    fun preferredCodecForMime(mimeType: String): String? {
        return when (mimeType.lowercase(Locale.ROOT)) {
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC ->
                RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3
            MimeTypes.AUDIO_AC4 -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_AC4
            else -> null
        }
    }
}
