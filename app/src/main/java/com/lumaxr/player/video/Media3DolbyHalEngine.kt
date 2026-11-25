package com.lumaxr.player.video

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.lumaxr.player.hardware.RuanHardwareProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Media3VideoTelemetry(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val decoderName: String = RuanHardwareProfile.CODEC_HW_DV_HEVC,
)

/**
 * Lean Native Dolby HAL Fallback Engine (Media3 1.11.1).
 */
class Media3DolbyHalEngine(private val context: Context) {

    private var player: ExoPlayer? = null
    private val _telemetry = MutableStateFlow(Media3VideoTelemetry())
    val telemetry: StateFlow<Media3VideoTelemetry> = _telemetry.asStateFlow()

    fun attachAndStart(surfaceView: SurfaceView, uri: String) {
        destroy()
        val selector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            reorderDolbyPreferred(mimeType, defaults)
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector)

        val trackSelector = DefaultTrackSelector(context)
        val exo = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _telemetry.value = _telemetry.value.copy(
                    isPlaying = isPlaying,
                    durationMs = exo.duration.coerceAtLeast(0L),
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _telemetry.value = _telemetry.value.copy(
                        durationMs = exo.duration.coerceAtLeast(0L),
                    )
                }
            }
        })

        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                _telemetry.value = _telemetry.value.copy(decoderName = decoderName)
            }
        })

        exo.setVideoSurfaceView(surfaceView)
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    fun refreshPosition() {
        val p = player ?: return
        _telemetry.value = _telemetry.value.copy(
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = p.duration.coerceAtLeast(0L),
            isPlaying = p.isPlaying,
        )
    }

    fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    fun seekToMs(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4.0f))
    }

    fun destroy() {
        player?.release()
        player = null
        _telemetry.value = Media3VideoTelemetry()
    }

    private fun reorderDolbyPreferred(
        mimeType: String,
        infos: List<MediaCodecInfo>,
    ): List<MediaCodecInfo> {
        val preferred = when (mimeType.lowercase()) {
            "video/dolby-vision" -> RuanHardwareProfile.CODEC_HW_DV_HEVC
            "video/hevc" -> RuanHardwareProfile.CODEC_HW_HEVC_10BIT
            "audio/eac3", "audio/eac3-joc", "audio/ac3" -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3
            "audio/ac4" -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_AC4
            else -> null
        } ?: return infos
        return infos.sortedByDescending { it.name.equals(preferred, ignoreCase = true) }
    }
}
