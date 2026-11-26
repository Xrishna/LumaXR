package com.lumaxr.player.music

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.hardware.RuanHardwareProfile
import com.lumaxr.player.visuals.AudioFftAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

class Media3MusicEngine(
    context: Context,
    private val codecSelector: DolbyAudioCodecSelector,
    private val dspController: AudioDspController,
    private val loudnessManager: LoudnessAndReplayGainManager,
    private val usbBitPerfectManager: UsbBitPerfectManager,
    private val spatialAudioManager: SpatialAudioManager,
    private val fftAnalyzer: AudioFftAnalyzer? = null,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(Media3AudioState())
    val state: StateFlow<Media3AudioState> = _state.asStateFlow()

    val player: ExoPlayer
    var onQueueIndexChanged: ((Int) -> Unit)? = null
    private var lastTracks: List<AudioTrackEntity> = emptyList()
    private var lastPreferBitPerfect = false

    private val pcmBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        private var sampleRateHz = 48000
        private var channelCount = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private val ring = FloatArray(256)
        private var ringIdx = 0
        private var lastDispatchMs = 0L

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this.sampleRateHz = sampleRateHz.coerceAtLeast(8000)
            this.channelCount = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            ringIdx = 0
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val analyzer = fftAnalyzer ?: return
            val dup = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            if (dup.remaining() < 4) return

            val ch = channelCount.coerceAtLeast(1)
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val frameBytes = ch * 2
                    while (dup.remaining() >= frameBytes) {
                        var sum = 0f
                        for (c in 0 until ch) {
                            sum += dup.short / 32768f
                        }
                        pushSample(sum / ch, analyzer)
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val frameBytes = ch * 4
                    while (dup.remaining() >= frameBytes) {
                        var sum = 0f
                        for (c in 0 until ch) {
                            sum += dup.float
                        }
                        pushSample(sum / ch, analyzer)
                    }
                }
                C.ENCODING_PCM_32BIT -> {
                    val frameBytes = ch * 4
                    while (dup.remaining() >= frameBytes) {
                        var sum = 0f
                        for (c in 0 until ch) {
                            sum += dup.int / 2147483648f
                        }
                        pushSample(sum / ch, analyzer)
                    }
                }
            }
        }

        private fun pushSample(mono: Float, analyzer: AudioFftAnalyzer) {
            ring[ringIdx++] = mono
            if (ringIdx >= ring.size) {
                ringIdx = 0
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastDispatchMs >= 16L) {
                    lastDispatchMs = now
                    analyzer.submitPcmSamples(ring.copyOf(), sampleRateHz)
                }
            }
        }
    }

    init {
        val teeProcessor = TeeAudioProcessor(pcmBufferSink)
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(teeProcessor))
                    .build()
            }
        }
            .setEnableDecoderFallback(true)
            .setEnableAudioOutputPlaybackParameters(true)
            .setMediaCodecSelector(codecSelector)

        val trackSelector = DefaultTrackSelector(appContext)
        player = ExoPlayer.Builder(appContext, renderersFactory)
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                if (idx in lastTracks.indices) {
                    onQueueIndexChanged?.invoke(idx)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(
                    isPlaying = isPlaying,
                    durationMs = player.duration.coerceAtLeast(0L),
                )
                if (!isPlaying) {
                    usbBitPerfectManager.clearBitPerfect()
                } else if (lastPreferBitPerfect) {
                    val track = lastTracks.getOrNull(player.currentMediaItemIndex)
                    if (track != null) {
                        val attrs = spatialAudioManager.buildAudioAttributes(false, true)
                        val bp = usbBitPerfectManager.engageForTrack(attrs, track.sampleRateHz, track.bitDepth, true)
                        dspController.setBitPerfectBypass(bp)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0L))
                }
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                _state.value = _state.value.copy(activeDecoderName = decoderName)
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
        lastTracks = tracks
        lastPreferBitPerfect = preferBitPerfectUsb
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
            isPreRenderedBinaural = spatialAudioManager.state.value.sofaHrtfActive,
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
        // Disable offload while DSP or live FFT visualizer PCM tap is attached so TeeAudioProcessor stays active.
        val offloadMode = if (screenOffOffloadAllowed && !dspActive && fftAnalyzer == null) {
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
