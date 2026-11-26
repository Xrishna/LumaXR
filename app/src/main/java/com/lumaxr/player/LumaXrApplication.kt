package com.lumaxr.player

import android.app.Application
import android.content.Context
import com.lumaxr.player.data.ConfigBackupManager
import com.lumaxr.player.data.LumaDatabase
import com.lumaxr.player.hardware.DisplayRefreshRateManager
import com.lumaxr.player.hardware.RuanBacklightController
import com.lumaxr.player.hardware.ThermalGovernor
import com.lumaxr.player.lyrics.LyricsRepository
import com.lumaxr.player.music.AudioDeviceProfileRouter
import com.lumaxr.player.music.AudioDspController
import com.lumaxr.player.music.DolbyAudioCodecSelector
import com.lumaxr.player.music.LoudnessAndReplayGainManager
import com.lumaxr.player.music.LumaHaptics
import com.lumaxr.player.music.Media3MusicEngine
import com.lumaxr.player.music.MpvAudioEngine
import com.lumaxr.player.music.MusicPlaybackCoordinator
import com.lumaxr.player.music.MusicScannerRepository
import com.lumaxr.player.music.PlaylistExporter
import com.lumaxr.player.music.ScrobbleManager
import com.lumaxr.player.music.SpatialAudioManager
import com.lumaxr.player.music.TagEditorManager
import com.lumaxr.player.music.UsbBitPerfectManager
import com.lumaxr.player.music.WaveformExtractor
import com.lumaxr.player.pro.ChapterAndIntroSkipper
import com.lumaxr.player.pro.NetworkMediaRepository
import com.lumaxr.player.pro.RangeChunkCache
import com.lumaxr.player.pro.SubtitleDownloader
import com.lumaxr.player.pro.TrackLanguageRuleEngine
import com.lumaxr.player.pro.VideoToAudioHandoff
import com.lumaxr.player.video.DisplayCapabilityGuard
import com.lumaxr.player.video.DualEngineCoordinator
import com.lumaxr.player.video.MediaFormatProber
import com.lumaxr.player.video.PlaybackConfigAdvisor
import com.lumaxr.player.video.SmartAspectManager
import com.lumaxr.player.visuals.AudioFftAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database = LumaDatabase.getInstance(context)
    val backupManager = ConfigBackupManager(context, database)
    val haptics = LumaHaptics(context)

    // Hardware & XR Physics
    val backlightController = RuanBacklightController()
    val refreshRateManager = DisplayRefreshRateManager()
    val thermalGovernor = ThermalGovernor(context)

    // Video Core
    val prober = MediaFormatProber(context)
    val capabilityGuard = DisplayCapabilityGuard()
    val configAdvisor = PlaybackConfigAdvisor()
    val smartAspectManager = SmartAspectManager()

    // Visuals & Lyrics
    val audioFftAnalyzer = AudioFftAnalyzer()
    val lyricsRepository = LyricsRepository(database.lyricsCacheDao())

    // Audiophile Music Core
    val dolbyCodecSelector = DolbyAudioCodecSelector()
    val dspController = AudioDspController()
    val loudnessManager = LoudnessAndReplayGainManager()
    val usbBitPerfectManager = UsbBitPerfectManager(context)
    val spatialAudioManager = SpatialAudioManager(context)
    val deviceProfileRouter = AudioDeviceProfileRouter(
        context = context,
        scope = appScope,
        musicDao = database.musicLibraryDao(),
        dspController = dspController,
    )
    val scrobbleManager = ScrobbleManager(database.scrobbleQueueDao())
    val media3MusicEngine = Media3MusicEngine(
        context = context,
        codecSelector = dolbyCodecSelector,
        dspController = dspController,
        loudnessManager = loudnessManager,
        usbBitPerfectManager = usbBitPerfectManager,
        spatialAudioManager = spatialAudioManager,
        fftAnalyzer = audioFftAnalyzer,
    )
    val mpvAudioEngine = MpvAudioEngine(context, dspController)
    val musicCoordinator = MusicPlaybackCoordinator(
        context = context,
        scope = appScope,
        media3Engine = media3MusicEngine,
        mpvAudioEngine = mpvAudioEngine,
        dspController = dspController,
        loudnessManager = loudnessManager,
        usbBitPerfectManager = usbBitPerfectManager,
        spatialAudioManager = spatialAudioManager,
        deviceRouter = deviceProfileRouter,
        musicDao = database.musicLibraryDao(),
        statsDao = database.listeningStatsDao(),
        onScrobbleTrack = { track ->
            appScope.launch { scrobbleManager.enqueueAndFlush(track) }
        },
    )

    val musicScanner = MusicScannerRepository(context, database.musicLibraryDao())
    val tagEditor = TagEditorManager(database.musicLibraryDao())
    val waveformExtractor = WaveformExtractor(database.musicLibraryDao())
    val playlistExporter = PlaylistExporter(database.musicLibraryDao())

    // Pro Video & Network
    val rangeChunkCache = RangeChunkCache(File(context.cacheDir, "range_chunks"), appScope)
    val networkMediaRepo = NetworkMediaRepository(rangeChunkCache)
    val chapterAndIntroSkipper = ChapterAndIntroSkipper()
    val trackLanguageRuleEngine = TrackLanguageRuleEngine()
    val subtitleDownloader = SubtitleDownloader()

    val videoCoordinator = DualEngineCoordinator(
        context = context,
        scope = appScope,
        prober = prober,
        guard = capabilityGuard,
        advisor = configAdvisor,
        backlightController = backlightController,
        refreshRateManager = refreshRateManager,
        thermalGovernor = thermalGovernor,
        smartAspectManager = smartAspectManager,
    )

    val videoToAudioHandoff = VideoToAudioHandoff(videoCoordinator, musicCoordinator)

    init {
        usbBitPerfectManager.start()
        spatialAudioManager.start()
        deviceProfileRouter.start()
    }
}

class LumaXrApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
