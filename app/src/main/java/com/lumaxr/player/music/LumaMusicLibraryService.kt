package com.lumaxr.player.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lumaxr.player.BuildConfig
import com.lumaxr.player.LumaXrApplication
import com.lumaxr.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

private val Context.resumptionStore by preferencesDataStore("lumaxr_resumption")

class LumaMusicLibraryService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "lumaxr_root"
        const val NODE_RECENT = "lumaxr_recent"
        const val NODE_ALBUMS = "lumaxr_albums"
        const val NODE_ARTISTS = "lumaxr_artists"
        const val NODE_FOLDERS = "lumaxr_folders"
        const val NODE_HIRES_DOLBY = "lumaxr_hires_dolby"
        const val NODE_PLAYLISTS = "lumaxr_playlists"

        val CMD_TOGGLE_FAVORITE = "${BuildConfig.APPLICATION_ID}.CMD_FAVORITE"
        val CMD_CYCLE_REPEAT = "${BuildConfig.APPLICATION_ID}.CMD_REPEAT"
        private const val CHANNEL_ID = "lumaxr_music_playback"
        private val KEY_LAST_URI = stringPreferencesKey("last_uri")
        private val KEY_LAST_TITLE = stringPreferencesKey("last_title")
        private val KEY_LAST_ARTIST = stringPreferencesKey("last_artist")
        private val KEY_LAST_POS_MS = longPreferencesKey("last_pos_ms")
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var lastPersistedWriteMs = 0L

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val coordinator = (application as LumaXrApplication).container.musicCoordinator
                if (coordinator.state.value.isPlaying) {
                    coordinator.togglePlayPause()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val container = (application as LumaXrApplication).container
        val player = container.musicCoordinator.media3Engine.player

        val favButton = CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build()
        val repeatButton = CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setMediaButtonPreferences(ImmutableList.of(favButton, repeatButton))
            .build()

        serviceScope.launch {
            container.musicCoordinator.state
                .distinctUntilChangedBy { Triple(it.currentTrack?.uri, it.isPlaying, it.positionMs / 5000L) }
                .collect { st ->
                    val t = st.currentTrack ?: return@collect
                    val now = System.currentTimeMillis()
                    if (!st.isPlaying || now - lastPersistedWriteMs >= 4500L) {
                        lastPersistedWriteMs = now
                        resumptionStore.edit { prefs ->
                            prefs[KEY_LAST_URI] = t.uri
                            prefs[KEY_LAST_TITLE] = t.title
                            prefs[KEY_LAST_ARTIST] = t.artist
                            prefs[KEY_LAST_POS_MS] = st.positionMs
                        }
                    }
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        serviceScope.cancel()
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_music),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCmds)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val container = (application as LumaXrApplication).container
            when (customCommand.customAction) {
                CMD_TOGGLE_FAVORITE -> {
                    val track = container.musicCoordinator.state.value.currentTrack
                    if (track != null) {
                        val nextFav = !track.isFavorite
                        serviceScope.launch {
                            container.database.musicLibraryDao().setFavorite(track.uri, nextFav)
                        }
                        val favIcon = if (nextFav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
                        val favBtn = CommandButton.Builder(favIcon)
                            .setDisplayName("Favorite")
                            .setSessionCommand(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                            .build()
                        val repBtn = CommandButton.Builder(CommandButton.ICON_REPEAT_ALL)
                            .setDisplayName("Repeat")
                            .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                            .build()
                        mediaLibrarySession?.setMediaButtonPreferences(ImmutableList.of(favBtn, repBtn))
                    }
                }
                CMD_CYCLE_REPEAT -> {
                    container.musicCoordinator.cycleRepeatMode()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("LumaXR Audiophile Library")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future(Dispatchers.IO) {
            val track = (application as LumaXrApplication).container.database.musicLibraryDao().getTrack(mediaId)
            if (track != null) LibraryResult.ofItem(toPlayableItem(track), null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future(Dispatchers.IO) {
            val dao = (application as LumaXrApplication).container.database.musicLibraryDao()
            val all = dao.getAllTracks()
            val items: List<MediaItem> = when {
                parentId == ROOT_ID -> listOf(
                    browsableFolder(NODE_RECENT, "Recently Played"),
                    browsableFolder(NODE_ALBUMS, "Albums"),
                    browsableFolder(NODE_ARTISTS, "Artists"),
                    browsableFolder(NODE_FOLDERS, "Folders"),
                    browsableFolder(NODE_HIRES_DOLBY, "Hi-Res & Dolby"),
                    browsableFolder(NODE_PLAYLISTS, "Playlists"),
                )
                parentId == NODE_HIRES_DOLBY -> all
                    .filter { it.isDolbyAtmos || it.isDolbyAc4 || it.sampleRateHz > 48000 || it.bitDepth > 16 || it.isDsd }
                    .map { toPlayableItem(it) }
                parentId == NODE_RECENT -> all.sortedByDescending { it.lastPlayedMs }.take(30).map { toPlayableItem(it) }
                parentId == NODE_ALBUMS -> all.map { it.album }.distinct().map { browsableFolder("album:$it", it) }
                parentId == NODE_ARTISTS -> all.map { it.artist }.distinct().map { browsableFolder("artist:$it", it) }
                parentId == NODE_FOLDERS -> all.map { it.folderPath }.distinct().map { browsableFolder("folder:$it", it.substringAfterLast('/')) }
                parentId == NODE_PLAYLISTS -> dao.getAllPlaylists().map { browsableFolder("playlist:${it.playlistId}", it.name) }
                parentId.startsWith("album:") -> all.filter { it.album == parentId.removePrefix("album:") }.map { toPlayableItem(it) }
                parentId.startsWith("artist:") -> all.filter { it.artist == parentId.removePrefix("artist:") }.map { toPlayableItem(it) }
                parentId.startsWith("folder:") -> all.filter { it.folderPath == parentId.removePrefix("folder:") }.map { toPlayableItem(it) }
                else -> all.map { toPlayableItem(it) }
            }
            val safeSize = pageSize.coerceAtLeast(1)
            val from = (page * safeSize).coerceAtMost(items.size)
            val to = (from + safeSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.IO) {
            val prefs = resumptionStore.data.first()
            val uri = prefs[KEY_LAST_URI]
            if (uri.isNullOrBlank()) {
                return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
            val item = MediaItem.Builder()
                .setMediaId(uri)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(prefs[KEY_LAST_TITLE] ?: "Resumed Track")
                        .setArtist(prefs[KEY_LAST_ARTIST] ?: "Unknown Artist")
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
            val pos = if (isForPlayback) (prefs[KEY_LAST_POS_MS] ?: 0L) else 0L
            MediaSession.MediaItemsWithStartPosition(listOf(item), 0, pos)
        }

        private fun browsableFolder(id: String, title: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()

        private fun toPlayableItem(t: com.lumaxr.player.data.AudioTrackEntity): MediaItem =
            MediaItem.Builder()
                .setMediaId(t.uri)
                .setUri(t.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
    }
}
