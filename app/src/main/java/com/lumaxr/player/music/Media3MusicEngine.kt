package com.lumaxr.player.music

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.hardware.RuanHardwareProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Media3AudioState(
    val audioSessionId: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeDecoderName: String = "Media3 PCM",
    val offloadActive: Boolean = false,
    val silenceSkipEnabled: Boolean = false,
    val estimatedLufs: Float = -14.0f,
    val truePeakDbfs: Float = -1.2f,
)

/**
 * Engine 1 (Media3 1.11.1): ExoPlayer + Vendor Dolby HAL (c2.dolby.eac3.decoder / c2.dolby.ac4.decoder)
 * + Bit-Perfect USB Mixer + CTA-2075 LoudnessCodecController + Low-Power Audio Offload.
 */
class Media3MusicEngine(
    private val context: Context,
    private val codecSelector: DolbyAudioCodecSelector,
    private val dspController: AudioDspController,
    private val loudnessManager: LoudnessAndReplayGainManager,
    private val usbBitPerfectManager: UsbBitPerfectManager,
    private val spatialAudioManager: SpatialAudioManager,
) {
    private val _state = MutableStateFlow(Media3AudioState())
    val state: StateFlow<Media3AudioState> = _state.asStateFlow()

    val player: ExoPlayer

    init {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setMediaCodecSelector(codecSelector)

        val trackSelector = DefaultTrackSelector(context)
        player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    _state.value = _state.value.copy(audioSessionId = audioSessionId)
                    dspController.attachToSession(audioSessionId)
                    loudnessManager.attachToSession(audioSessionId)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(
                    isPlaying = isPlaying,
                    durationMs = player.duration.coerceAtLeast(0L),
                )
                if (!isPlaying) {
                    usbBitPerfectManager.clearBitPerfect()
                }
            }
        })
    }

    fun prepareQueue(
        tracks: List<AudioTrackEntity>,
        startIndex: Int,
        startPosMs: Long = 0L,
        preferBitPerfectUsb: Boolean,
        screenOffOffloadAllowed: Boolean,
    ) {
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setMediaId(t.uri)
                .setUri(t.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setAlbumArtist(t.albumArtist)
                        .build(),
                )
                .build()
        }

        val currentTrack = tracks.getOrNull(startIndex)
        val platformAttrs = spatialAudioManager.buildAudioAttributes(
            isPreRenderedBinaural = false,
            bypassSystemSpatializer = preferBitPerfectUsb,
        )
        val bpActive = if (currentTrack != null && preferBitPerfectUsb) {
            usbBitPerfectManager.engageForTrack(
                audioAttributes = platformAttrs,
                sampleRateHz = currentTrack.sampleRateHz,
                bitDepth = currentTrack.bitDepth,
                userEnabled = true,
            )
        } else {
            usbBitPerfectManager.clearBitPerfect()
            false
        }

        dspController.setBitPerfectBypass(bpActive)

        val dspActive = dspController.state.value.enabled && !bpActive
        val offloadMode = if (screenOffOffloadAllowed && !dspActive) {
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
        } else {
            AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(offloadMode)
                    .setIsGaplessSupportRequired(false)
                    .setIsSpeedChangeSupportRequired(false)
                    .build(),
            )
            .build()

        val decoderLabel = when {
            currentTrack?.isDolbyAc4 == true -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_AC4
            currentTrack?.isDolbyAtmos == true -> RuanHardwareProfile.CODEC_VENDOR_DOLBY_EAC3
            else -> "Media3 AudioTrack"
        }
        _state.value = _state.value.copy(
            activeDecoderName = decoderLabel,
            offloadActive = offloadMode == AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
        )

        player.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startPosMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun setSkipSilence(enabled: Boolean) {
        player.skipSilenceEnabled = enabled
        _state.value = _state.value.copy(silenceSkipEnabled = enabled)
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 2.0f), pitch.coerceIn(0.5f, 2.0f))
    }

    fun setVolume(vol: Float) {
        player.volume = vol.coerceIn(0f, 1f)
    }

    fun pollPosition() {
        _state.value = _state.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
        )
    }

    fun release() {
        usbBitPerfectManager.clearBitPerfect()
        player.release()
    }
}
