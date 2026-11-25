package com.lumaxr.player.music

import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.ListeningEventEntity
import com.lumaxr.player.data.ListeningStatsDao
import com.lumaxr.player.data.MusicLibraryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

enum class AudioEngineRoute {
    MEDIA3_DOLBY_OR_PCM,
    LIBMPV_STUDIO_FLOAT,
}

data class SignalPathTelemetry(
    val sourceSummary: String = "Idle",
    val engineAndDecoder: String = "Media3",
    val processingStage: String = "Flat Reference",
    val outputPath: String = "Built-in Quad Speakers",
    val lufs: Float = -14.0f,
    val truePeakDbfs: Float = -1.2f,
    val bitPerfectActive: Boolean = false,
    val spatializerActive: Boolean = false,
    val offloadActive: Boolean = false,
)

data class MusicPlaybackState(
    val queue: List<AudioTrackEntity> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: AudioTrackEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeEngine: AudioEngineRoute = AudioEngineRoute.MEDIA3_DOLBY_OR_PCM,
    val audioSessionId: Int = 0,
    val abLoopStartMs: Long? = null,
    val abLoopEndMs: Long? = null,
    val sleepTimerRemainingSec: Int? = null,
    val crossfadeSeconds: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val bitPerfectPreferred: Boolean = false,
    val signalPath: SignalPathTelemetry = SignalPathTelemetry(),
)

class MusicPlaybackCoordinator(
    private val scope: CoroutineScope,
    val media3Engine: Media3MusicEngine,
    val mpvAudioEngine: MpvAudioEngine,
    val dspController: AudioDspController,
    val loudnessManager: LoudnessAndReplayGainManager,
    val usbBitPerfectManager: UsbBitPerfectManager,
    val spatialAudioManager: SpatialAudioManager,
    val deviceRouter: AudioDeviceProfileRouter,
    private val musicDao: MusicLibraryDao,
    private val statsDao: ListeningStatsDao,
    private val onScrobbleTrack: (AudioTrackEntity) -> Unit = {},
) {
    companion object {
        private val MPV_EXCLUSIVE_EXTENSIONS = setOf("dsf", "dff", "thd", "mlp", "dts", "dtshd", "wv", "ape", "mpc", "mka")
    }

    private val _state = MutableStateFlow(MusicPlaybackState())
    val state: StateFlow<MusicPlaybackState> = _state.asStateFlow()

    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private var crossfadeJob: Job? = null

    @Volatile
    private var scrobbleDispatchedForCurrent = false

    init {
        mpvAudioEngine.onTrackEnded = {
            scope.launch { nextTrack(autoAdvance = true) }
        }
        media3Engine.onQueueIndexChanged = { idx ->
            val cur = _state.value
            val target = cur.queue.getOrNull(idx)
            if (target != null && idx != cur.currentIndex) {
                if (requiresMpvEngine(target)) {
                    playQueue(cur.queue, idx)
                } else {
                    scrobbleDispatchedForCurrent = false
                    _state.update {
                        it.copy(
                            currentIndex = idx,
                            currentTrack = target,
                            durationMs = target.durationMs,
                            positionMs = 0L,
                            abLoopStartMs = null,
                            abLoopEndMs = null,
                        )
                    }
                    scope.launch {
                        musicDao.recordPlay(target.uri)
                        refreshSignalPath()
                    }
                }
            }
        }
        startTelemetryLoop()
    }

    fun requiresMpvEngine(track: AudioTrackEntity): Boolean {
        val ext = track.uri.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return track.isDsd ||
            track.cueSheetSourceUri != null ||
            ext in MPV_EXCLUSIVE_EXTENSIONS ||
            track.codec.lowercase(Locale.ROOT) in setOf("truehd", "mlp", "dts", "dsd", "wavpack", "ape", "mpc")
    }

    fun shouldApplyCrossfade(from: AudioTrackEntity?, to: AudioTrackEntity?, configuredSec: Int): Boolean {
        if (configuredSec <= 0 || from == null || to == null) return false
        val sameAlbum = from.album.isNotBlank() && from.album.equals(to.album, ignoreCase = true)
        val sequential = to.trackNumber == from.trackNumber + 1 && to.discNumber == from.discNumber
        return !(sameAlbum && sequential)
    }

    fun playQueue(
        tracks: List<AudioTrackEntity>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
    ) {
        if (tracks.isEmpty()) return
        crossfadeJob?.cancel()
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        val track = tracks[safeIdx]
        scrobbleDispatchedForCurrent = false

        val engine = if (requiresMpvEngine(track)) {
            AudioEngineRoute.LIBMPV_STUDIO_FLOAT
        } else {
            AudioEngineRoute.MEDIA3_DOLBY_OR_PCM
        }

        _state.update {
            it.copy(
                queue = tracks,
                currentIndex = safeIdx,
                currentTrack = track,
                activeEngine = engine,
                isPlaying = true,
                positionMs = startPositionMs,
                durationMs = track.durationMs,
                abLoopStartMs = null,
                abLoopEndMs = null,
            )
        }

        if (engine == AudioEngineRoute.LIBMPV_STUDIO_FLOAT) {
            media3Engine.player.pause()
            mpvAudioEngine.playUri(
                uri = track.cueSheetSourceUri ?: track.uri,
                startMs = track.cueStartMs + startPositionMs,
                endMs = track.cueEndMs,
                useSofaHrtf = spatialAudioManager.state.value.sofaHrtfActive,
            )
        } else {
            mpvAudioEngine.stop()
            media3Engine.prepareQueue(
                tracks = tracks,
                startIndex = safeIdx,
                startPosMs = startPositionMs,
                preferBitPerfectUsb = _state.value.bitPerfectPreferred,
                screenOffOffloadAllowed = true,
            )
        }

        scope.launch {
            musicDao.recordPlay(track.uri)
            refreshSignalPath()
        }
    }

    fun togglePlayPause() {
        val cur = _state.value
        val nextPlaying = !cur.isPlaying
        if (cur.activeEngine == AudioEngineRoute.LIBMPV_STUDIO_FLOAT) {
            mpvAudioEngine.setPaused(!nextPlaying)
        } else {
            if (nextPlaying) media3Engine.player.play() else media3Engine.player.pause()
        }
        _state.update { it.copy(isPlaying = nextPlaying) }
    }

    fun seekToMs(ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        if (_state.value.activeEngine == AudioEngineRoute.LIBMPV_STUDIO_FLOAT) {
            val base = _state.value.currentTrack?.cueStartMs ?: 0L
            mpvAudioEngine.seekToMs(base + clamped)
        } else {
            media3Engine.player.seekTo(clamped)
        }
        _state.update { it.copy(positionMs = clamped) }
    }

    fun cycleRepeatMode() {
        _state.update { it.copy(repeatMode = (it.repeatMode + 1) % 3) }
    }

    fun nextTrack(autoAdvance: Boolean = false) {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val nextIdx = when {
            cur.repeatMode == 2 && autoAdvance -> cur.currentIndex
            cur.shuffleEnabled -> cur.queue.indices.random()
            cur.currentIndex + 1 < cur.queue.size -> cur.currentIndex + 1
            cur.repeatMode == 1 -> 0
            else -> return
        }
        val fromTrack = cur.currentTrack
        val toTrack = cur.queue[nextIdx]
        val useFade = shouldApplyCrossfade(fromTrack, toTrack, cur.crossfadeSeconds)
        if (useFade && cur.activeEngine == AudioEngineRoute.MEDIA3_DOLBY_OR_PCM) {
            crossfadeJob?.cancel()
            crossfadeJob = scope.launch {
                val steps = 8
                for (i in steps downTo 1) {
                    media3Engine.setVolume(i / steps.toFloat())
                    delay((cur.crossfadeSeconds * 125L).coerceAtMost(400L))
                }
                media3Engine.setVolume(1.0f)
                playQueue(cur.queue, nextIdx)
            }
        } else {
            playQueue(cur.queue, nextIdx)
        }
    }

    fun prevTrack() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        if (cur.positionMs > 4000L) {
            seekToMs(0L)
            return
        }
        val prevIdx = if (cur.currentIndex - 1 >= 0) cur.currentIndex - 1 else cur.queue.lastIndex
        playQueue(cur.queue, prevIdx)
    }

    fun setAbLoop(startMs: Long?, endMs: Long?) {
        _state.update { it.copy(abLoopStartMs = startMs, abLoopEndMs = endMs) }
    }

    fun setBitPerfectPreferred(enabled: Boolean) {
        _state.update { it.copy(bitPerfectPreferred = enabled) }
        val cur = _state.value
        if (cur.currentTrack != null && cur.activeEngine == AudioEngineRoute.MEDIA3_DOLBY_OR_PCM) {
            playQueue(cur.queue, cur.currentIndex, cur.positionMs)
        }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        _state.update { it.copy(crossfadeSeconds = seconds.coerceIn(0, 12)) }
    }

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingSec = null) }
            return
        }
        sleepJob = scope.launch {
            var remaining = minutes * 60
            while (remaining > 0 && isActive) {
                _state.update { it.copy(sleepTimerRemainingSec = remaining) }
                if (remaining <= 10) {
                    val vol = remaining / 10f
                    media3Engine.setVolume(vol)
                    mpvAudioEngine.setVolume(vol)
                }
                delay(1000L)
                remaining--
            }
            if (isActive) {
                if (_state.value.isPlaying) togglePlayPause()
                media3Engine.setVolume(1f)
                mpvAudioEngine.setVolume(1f)
                _state.update { it.copy(sleepTimerRemainingSec = null) }
            }
        }
    }

    fun refreshSignalPath() {
        val dsp = dspController.state.value
        val usb = usbBitPerfectManager.state.value
        val spat = spatialAudioManager.state.value
        val route = deviceRouter.activeRoute.value
        val m3 = media3Engine.state.value

        _state.update { cur ->
            val track = cur.currentTrack
            val sourceSummary = if (track == null) "Idle" else buildString {
                append(track.codec.uppercase(Locale.US))
                if (track.isDolbyAtmos) append(" Dolby Atmos")
                val srKhz = if (track.sampleRateHz % 1000 == 0) "${track.sampleRateHz / 1000}" else String.format(Locale.US, "%.1f", track.sampleRateHz / 1000f)
                append(" • ${track.bitDepth}-bit / $srKhz kHz")
                if (track.bitrateKbps > 0) append(" • ${track.bitrateKbps} kbps")
            }

            val engineStr = if (cur.activeEngine == AudioEngineRoute.LIBMPV_STUDIO_FLOAT) {
                mpvAudioEngine.state.value.decoderLabel
            } else {
                "Media3 (${m3.activeDecoderName})"
            }

            val procStr = when {
                usb.activeBitPerfect -> "Bit-Perfect Bypass (0 dB OS Mixer)"
                !dsp.enabled -> "DSP Bypassed"
                else -> buildString {
                    append("AutoEQ (${dsp.activePresetName})")
                    if (dsp.crossfeedEnabled) append(" → BS2B Crossfeed")
                    if (dsp.karaokeVocalReduceStrength > 0.05f) append(" → Vocal Reduce")
                    append(" → Brickwall Limiter")
                }
            }

            val outStr = when {
                usb.activeBitPerfect -> "USB-C DAC (${usb.deviceName}) • BIT_PERFECT • ${usb.activeSampleRateHz / 1000} kHz"
                spat.systemSpatializerActive -> "${route.displayName} • Spatializer ON"
                m3.offloadActive -> "${route.displayName} • Audio Offload Active"
                else -> route.displayName
            }

            cur.copy(
                signalPath = SignalPathTelemetry(
                    sourceSummary = sourceSummary,
                    engineAndDecoder = engineStr,
                    processingStage = procStr,
                    outputPath = outStr,
                    lufs = m3.estimatedLufs,
                    truePeakDbfs = m3.truePeakDbfs,
                    bitPerfectActive = usb.activeBitPerfect,
                    spatializerActive = spat.systemSpatializerActive,
                    offloadActive = m3.offloadActive,
                ),
            )
        }
    }

    private fun startTelemetryLoop() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                if (_state.value.activeEngine == AudioEngineRoute.MEDIA3_DOLBY_OR_PCM) {
                    media3Engine.pollPosition()
                    val m3 = media3Engine.state.value
                    _state.update { cur ->
                        cur.copy(
                            isPlaying = m3.isPlaying,
                            positionMs = m3.positionMs,
                            durationMs = if (m3.durationMs > 0) m3.durationMs else cur.durationMs,
                            audioSessionId = m3.audioSessionId,
                        )
                    }
                } else {
                    val mpvSt = mpvAudioEngine.state.value
                    _state.update { cur ->
                        val base = cur.currentTrack?.cueStartMs ?: 0L
                        cur.copy(
                            isPlaying = mpvSt.isPlaying,
                            positionMs = (mpvSt.positionMs - base).coerceAtLeast(0L),
                        )
                    }
                }

                val latest = _state.value
                val a = latest.abLoopStartMs
                val b = latest.abLoopEndMs
                if (a != null && b != null && b > a && latest.positionMs >= b) {
                    seekToMs(a)
                }

                val track = latest.currentTrack
                if (!scrobbleDispatchedForCurrent && track != null && track.durationMs > 30_000L) {
                    val threshold = minOf(track.durationMs / 2L, 240_000L)
                    if (latest.positionMs >= threshold) {
                        scrobbleDispatchedForCurrent = true
                        onScrobbleTrack(track)
                        statsDao.insertEvent(
                            ListeningEventEntity(
                                trackUri = track.uri,
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                codec = track.codec,
                                isDolby = track.isDolbyAtmos || track.isDolbyAc4,
                                isHiRes = track.sampleRateHz > 48000 || track.bitDepth > 16 || track.isDsd,
                                listenedMs = latest.positionMs,
                            ),
                        )
                    }
                }
                delay(180L)
            }
        }
    }

    fun release() {
        tickerJob?.cancel()
        sleepJob?.cancel()
        crossfadeJob?.cancel()
        mpvAudioEngine.onTrackEnded = null
        media3Engine.onQueueIndexChanged = null
        media3Engine.release()
        mpvAudioEngine.release()
    }
}
