package com.lumaxr.player.ui

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumaxr.player.LumaXrApplication
import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.FolderVideoConfigEntity
import com.lumaxr.player.data.VideoBookmarkEntity
import com.lumaxr.player.hardware.RefreshRatePolicy
import com.lumaxr.player.lyrics.LoadedLyricsState
import com.lumaxr.player.lyrics.LyricStyleConfig
import com.lumaxr.player.pro.NetworkServerConfig
import com.lumaxr.player.pro.RemoteMediaEntry
import com.lumaxr.player.video.EngineSwitchPreference
import com.lumaxr.player.video.HdrToysToneMapper
import com.lumaxr.player.video.XrPreset
import com.lumaxr.player.visuals.GlowConfigState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class VideoLibraryItem(
    val uri: String,
    val title: String,
    val folderPath: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val container = (application as LumaXrApplication).container
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _videos = MutableStateFlow<List<VideoLibraryItem>>(emptyList())
    val videos: StateFlow<List<VideoLibraryItem>> = _videos.asStateFlow()

    val audioTracks: StateFlow<List<AudioTrackEntity>> = container.database.musicLibraryDao()
        .observeAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiResDolbyTracks: StateFlow<List<AudioTrackEntity>> = container.database.musicLibraryDao()
        .observeHiResAndDolby()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists = container.database.musicLibraryDao()
        .observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val listeningEvents = container.database.listeningStatsDao()
        .observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalListenedMs = container.database.listeningStatsDao()
        .observeTotalListenedMs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Video Player Settings State
    private val _xrPreset = MutableStateFlow(XrPreset.CINEMA_MASTERING_XR)
    val xrPreset: StateFlow<XrPreset> = _xrPreset.asStateFlow()

    private val _refreshPolicy = MutableStateFlow(RefreshRatePolicy.AUTO_CINEMA_120HZ)
    val refreshPolicy: StateFlow<RefreshRatePolicy> = _refreshPolicy.asStateFlow()

    private val _enginePreference = MutableStateFlow(EngineSwitchPreference.AUTO)
    val enginePreference: StateFlow<EngineSwitchPreference> = _enginePreference.asStateFlow()

    private val _hdrToysMapper = MutableStateFlow(HdrToysToneMapper.OFF)
    val hdrToysMapper: StateFlow<HdrToysToneMapper> = _hdrToysMapper.asStateFlow()

    private val _experimentalHbm = MutableStateFlow(false)
    val experimentalHbm: StateFlow<Boolean> = _experimentalHbm.asStateFlow()

    private val _subtitlePeakNits = MutableStateFlow(140)
    val subtitlePeakNits: StateFlow<Int> = _subtitlePeakNits.asStateFlow()

    private val _showTelemetryPill = MutableStateFlow(true)
    val showTelemetryPill: StateFlow<Boolean> = _showTelemetryPill.asStateFlow()

    private val _backStyle = MutableStateFlow(PredictiveBackStyle.PREDICTIVE_DETACH_SHARED)
    val backStyle: StateFlow<PredictiveBackStyle> = _backStyle.asStateFlow()

    // Phase D Visual Studio & AM Lyrics
    private val _glowConfig = MutableStateFlow(loadSavedGlowConfig())
    val glowConfig: StateFlow<GlowConfigState> = _glowConfig.asStateFlow()

    private val _lyricStyle = MutableStateFlow(LyricStyleConfig())
    val lyricStyle: StateFlow<LyricStyleConfig> = _lyricStyle.asStateFlow()

    private val _lyricsState = MutableStateFlow(LoadedLyricsState())
    val lyricsState: StateFlow<LoadedLyricsState> = _lyricsState.asStateFlow()

    private val _waveformPeaks = MutableStateFlow<List<Float>>(List(120) { 0.3f })
    val waveformPeaks: StateFlow<List<Float>> = _waveformPeaks.asStateFlow()

    private val _networkServers = MutableStateFlow<List<NetworkServerConfig>>(emptyList())
    val networkServers: StateFlow<List<NetworkServerConfig>> = _networkServers.asStateFlow()

    private val _remoteEntriesByServer = MutableStateFlow<Map<String, List<RemoteMediaEntry>>>(emptyMap())
    val remoteEntriesByServer: StateFlow<Map<String, List<RemoteMediaEntry>>> = _remoteEntriesByServer.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    init {
        refreshMediaLibraries()
        viewModelScope.launch {
            container.musicCoordinator.state
                .map { it.audioSessionId }
                .filter { it > 0 }
                .distinctUntilChanged()
                .collect { sessionId ->
                    container.audioFftAnalyzer.bindToSession(sessionId)
                }
        }
        viewModelScope.launch {
            container.musicCoordinator.state
                .map { it.currentTrack }
                .distinctUntilChanged { old, new -> old?.uri == new?.uri }
                .collectLatest { track ->
                    if (track != null) {
                        launch {
                            _lyricsState.value = container.lyricsRepository.loadLyricsForTrack(track)
                        }
                        launch {
                            _waveformPeaks.value = container.waveformExtractor.getOrExtractPeaks(track.uri)
                        }
                    } else {
                        _lyricsState.value = LoadedLyricsState()
                    }
                }
        }
    }

    override fun onCleared() {
        container.audioFftAnalyzer.release()
        super.onCleared()
    }

    fun refreshMediaLibraries() {
        viewModelScope.launch(Dispatchers.IO) {
            scanVideosFromMediaStore()
            container.musicScanner.scanLibrary(
                listOf(File("/storage/emulated/0/Music"), File("/storage/emulated/0/Download")),
            )
        }
    }

    private fun scanVideosFromMediaStore() {
        val ctx = getApplication<Application>()
        val list = mutableListOf<VideoLibraryItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
        )
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
            )?.use { c ->
                val colId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val colData = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val colTitle = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val colDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val colW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val colH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val colSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(colId)
                    val rawPath = if (colData >= 0) c.getString(colData) else null
                    val resolvedUri = if (!rawPath.isNullOrBlank()) {
                        rawPath
                    } else {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                    }
                    val fallbackTitle = if (!rawPath.isNullOrBlank()) {
                        File(rawPath).nameWithoutExtension
                    } else {
                        "Video $id"
                    }
                    list += VideoLibraryItem(
                        uri = resolvedUri,
                        title = c.getString(colTitle)?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                        folderPath = if (!rawPath.isNullOrBlank()) File(rawPath).parent.orEmpty() else "",
                        durationMs = c.getLong(colDur),
                        width = c.getInt(colW),
                        height = c.getInt(colH),
                        sizeBytes = c.getLong(colSize),
                    )
                }
            }
        }
        _videos.value = list
    }

    fun updateXrPreset(p: XrPreset) { _xrPreset.value = p }
    fun updateRefreshPolicy(p: RefreshRatePolicy) { _refreshPolicy.value = p }
    fun updateEnginePreference(p: EngineSwitchPreference) { _enginePreference.value = p }
    fun updateHdrToysMapper(m: HdrToysToneMapper) { _hdrToysMapper.value = m }
    fun setExperimentalHbm(enabled: Boolean) { _experimentalHbm.value = enabled }
    fun setSubtitlePeakNits(nits: Int) { _subtitlePeakNits.value = nits.coerceIn(80, 260) }
    fun toggleTelemetryPill() { _showTelemetryPill.value = !_showTelemetryPill.value }
    fun setBackStyle(style: PredictiveBackStyle) { _backStyle.value = style }

    fun updateGlowConfig(cfg: GlowConfigState) {
        val clamped = cfg.clamped()
        _glowConfig.value = clamped
        getApplication<Application>()
            .getSharedPreferences("lumaxr_visual_studio", Context.MODE_PRIVATE)
            .edit()
            .putString("glow_config_json", json.encodeToString(clamped))
            .apply()
    }

    fun updateLyricStyle(style: LyricStyleConfig) {
        _lyricStyle.value = style
    }

    fun updateLyricsOffset(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-3000, 3000)
        val cur = _lyricsState.value
        _lyricsState.value = cur.copy(offsetMs = clamped)
        if (cur.trackKey.isNotBlank()) {
            viewModelScope.launch { container.lyricsRepository.saveOffset(cur.trackKey, clamped) }
        }
    }

    fun searchLyricsManual(query: String) {
        val key = _lyricsState.value.trackKey.ifBlank { return }
        viewModelScope.launch {
            val found = container.lyricsRepository.searchManual(key, query)
            if (found.document.lines.isNotEmpty()) _lyricsState.value = found
        }
    }

    fun saveFolderConfig(folderPath: String, preset: XrPreset) {
        viewModelScope.launch {
            val base = container.trackLanguageRuleEngine.defaultRuleForFolder(folderPath)
            container.database.folderConfigDao().upsert(
                base.copy(xrPresetName = preset.name, subtitlePeakNits = _subtitlePeakNits.value),
            )
        }
    }

    fun addBookmark(mediaUri: String, title: String, posMs: Long, note: String) {
        viewModelScope.launch {
            container.database.bookmarkDao().insertBookmark(
                VideoBookmarkEntity(mediaUri = mediaUri, title = title, positionMs = posMs, note = note),
            )
        }
    }

    fun addNetworkServer(server: NetworkServerConfig) {
        _networkServers.value = _networkServers.value + server
    }

    fun browseNetworkServer(server: NetworkServerConfig, path: String = "") {
        viewModelScope.launch {
            val entries = container.networkMediaRepo.listDirectory(server, path)
            _remoteEntriesByServer.value = _remoteEntriesByServer.value + (server.id to entries)
        }
    }

    fun clearBackupStatus() {
        _backupStatus.value = null
    }

    fun exportBackup(uri: Uri?) {
        viewModelScope.launch {
            _backupStatus.value = runCatching {
                val file = container.backupManager.exportBackupZip(uri)
                if (uri != null) "LumaXR backup exported cleanly" else "Backup saved: ${file.name}"
            }.getOrElse { err ->
                "Backup export failed: ${err.message ?: "unknown error"}"
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            val res = container.backupManager.importBackup(uri)
            _backupStatus.value = res.fold(
                onSuccess = { payload ->
                    "Imported ${payload.folderVideoConfigs.size} video configs & ${payload.playlists.size} playlists"
                },
                onFailure = { err ->
                    "Backup import failed: ${err.message ?: "invalid archive"}"
                },
            )
        }
    }

    private fun loadSavedGlowConfig(): GlowConfigState {
        val raw = getApplication<Application>()
            .getSharedPreferences("lumaxr_visual_studio", Context.MODE_PRIVATE)
            .getString("glow_config_json", null) ?: return GlowConfigState.default()
        return runCatching { json.decodeFromString<GlowConfigState>(raw).clamped() }
            .getOrDefault(GlowConfigState.default())
    }
}
