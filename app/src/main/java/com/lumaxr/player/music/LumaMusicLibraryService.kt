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
import com.lumaxr.player.LumaXrApplication
import com.lumaxr.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

private val Context.resumptionStore by preferencesDataStore("lumaxr_resumption")

/**
 * Official Media3 1.11.1 MediaLibraryService hosting audiophile playback, Android Auto / Wear OS
 * browsable MediaBrowser tree, custom media button preferences, ACTION_AUDIO_BECOMING_NOISY,
 * and 3-arg onPlaybackResumption(session, controller, isForPlayback) persistence.
 */
class LumaMusicLibraryService : MediaLibraryService() {

    companion object {
        const val ROOT_ID = "lumaxr_root"
        const val NODE_RECENT = "lumaxr_recent"
        const val NODE_ALBUMS = "lumaxr_albums"
        const val NODE_ARTISTS = "lumaxr_artists"
        const val NODE_FOLDERS = "lumaxr_folders"
        const val NODE_HIRES_DOLBY = "lumaxr_hires_dolby"
        const val NODE_PLAYLISTS = "lumaxr_playlists"

        const val CMD_TOGGLE_FAVORITE = "com.lumaxr.player.CMD_FAVORITE"
        const val CMD_CYCLE_REPEAT = "com.lumaxr.player.CMD_REPEAT"
        private const val CHANNEL_ID = "lumaxr_music_playback"
        private val KEY_LAST_URI = stringPreferencesKey("last_uri")
        private val KEY_LAST_TITLE = stringPreferencesKey("last_title")
        private val KEY_LAST_ARTIST = stringPreferencesKey("last_artist")
        private val KEY_LAST_POS_MS = longPreferencesKey("last_pos_ms")
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaLibrarySession: MediaLibrarySession? = null

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

        // Persist active playback cursor for onPlaybackResumption()
        serviceScope.launch {
            container.musicCoordinator.state.collect { st ->
                val t = st.currentTrack ?: return@collect
                resumptionStore.edit { prefs ->
                    prefs[KEY_LAST_URI] = t.uri
                    prefs[KEY_LAST_TITLE] = t.title
                    prefs[KEY_LAST_ARTIST] = t.artist
                    prefs[KEY_LAST_POS_MS] = st.positionMs
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
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

        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val sessionCmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCmds)
                    .build(),
            )
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
                        serviceScope.launch {
                            container.database.musicLibraryDao().setFavorite(track.uri, !track.isFavorite)
                        }
                    }
                }
                CMD_CYCLE_REPEAT -> {
                    // Handled by coordinator
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

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future(Dispatchers.IO) {
            val dao = (application as LumaXrApplication).container.database.musicLibraryDao()
            val items: List<MediaItem> = when (parentId) {
                ROOT_ID -> listOf(
                    browsableFolder(NODE_RECENT, "Recently Played"),
                    browsableFolder(NODE_ALBUMS, "Albums"),
                    browsableFolder(NODE_ARTISTS, "Artists"),
                    browsableFolder(NODE_FOLDERS, "Folders"),
                    browsableFolder(NODE_HIRES_DOLBY, "Hi-Res & Dolby"),
                    browsableFolder(NODE_PLAYLISTS, "Playlists"),
                )
                NODE_HIRES_DOLBY -> dao.getAllTracks()
                    .filter { it.isDolbyAtmos || it.isDolbyAc4 || it.sampleRateHz > 48000 || it.bitDepth > 16 || it.isDsd }
                    .map { toPlayableItem(it) }
                NODE_RECENT -> dao.getAllTracks()
                    .sortedByDescending { it.lastPlayedMs }
                    .take(30)
                    .map { toPlayableItem(it) }
                else -> dao.getAllTracks().map { toPlayableItem(it) }
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
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
