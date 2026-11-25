package com.lumaxr.player.music

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lumaxr.player.hardware.RuanHardwareProfile

/**
 * Custom Media3 MediaCodecSelector prioritizing verified Xiaomi Redmi Pad Pro 5G vendor Dolby HALs:
 * - c2.dolby.eac3.decoder (AC-3, E-AC-3, E-AC-3 JOC Atmos up to 16ch)
 * - c2.dolby.ac4.decoder  (Dolby AC-4 up to 16ch)
 */
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
        return when (mimeType.lowercase()) {
            "audio/ac3", "audio/eac3", "audio/eac3-joc" -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3
            "audio/ac4" -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_AC4
            else -> null
        }
    }
}
