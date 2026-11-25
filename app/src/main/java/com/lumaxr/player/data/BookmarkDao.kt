package com.lumaxr.player.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "video_bookmarks")
data class VideoBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUri: String,
    val title: String,
    val positionMs: Long,
    val note: String = "",
    val snapshotPath: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "audio_tracks")
data class AudioTrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val composer: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
    val folderPath: String,
    val codec: String,
    val container: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val bitrateKbps: Int,
    val isDolbyAtmos: Boolean,
    val isDolbyAc4: Boolean,
    val isDsd: Boolean,
    val isLossless: Boolean,
    val replayGainTrackDb: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainPeak: Float? = null,
    val artworkUri: String? = null,
    val animatedCoverPath: String? = null,
    val cueSheetSourceUri: String? = null,
    val cueStartMs: Long = 0L,
    val cueEndMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val dateAddedSec: Long = 0L,
    val isFavorite: Boolean = false,
)

@Serializable
@Entity(tableName = "waveform_cache")
data class WaveformCacheEntity(
    @PrimaryKey val trackUri: String,
    val peaksCsv: String, // Normalized [0..1] 120-bin amplitudes
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "device_eq_profiles")
data class DeviceEqProfileEntity(
    @PrimaryKey val deviceKey: String, // e.g., "SPEAKER_QUAD", "USB:Fiio_KA13", "BT:00:11:22"
    val displayName: String,
    val autoEqName: String = "Flat",
    val bandGainsDbCsv: String = "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", // 15-band PEQ
    val preampDb: Float = 0f,
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = 0.35f,
    val bitPerfectPreferred: Boolean = false,
    val monoEnabled: Boolean = false,
    val balanceLR: Float = 0f,
)

@Serializable
@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val trackKey: String, // normalized "artist|title|durationSec"
    val format: String, // "TTML", "ELRC", "LRC", "PLAIN"
    val rawPayload: String,
    val provider: String,
    val offsetMs: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "music_playlists")
data class MusicPlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val trackUrisJson: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "scrobble_queue")
data class ScrobbleQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val artist: String,
    val track: String,
    val album: String,
    val durationSec: Int,
    val timestampEpochSec: Long,
    val sentLastFm: Boolean = false,
    val sentListenBrainz: Boolean = false,
)

@Serializable
@Entity(tableName = "listening_events")
data class ListeningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val codec: String,
    val isDolby: Boolean,
    val isHiRes: Boolean,
    val listenedMs: Long,
    val timestampMs: Long = System.currentTimeMillis(),
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM video_bookmarks WHERE mediaUri = :uri ORDER BY positionMs ASC")
    fun observeForMedia(uri: String): Flow<List<VideoBookmarkEntity>>

    @Query("SELECT * FROM video_bookmarks ORDER BY createdAtMs DESC")
    suspend fun getAllBookmarks(): List<VideoBookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: VideoBookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<VideoBookmarkEntity>)

    @Query("DELETE FROM video_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}

@Dao
interface MusicLibraryDao {
    @Query("SELECT * FROM audio_tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAllTracks(): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks WHERE isDolbyAtmos = 1 OR isDolbyAc4 = 1 OR sampleRateHz > 48000 OR bitDepth > 16 OR isDsd = 1 ORDER BY sampleRateHz DESC")
    fun observeHiResAndDolby(): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks ORDER BY playCount DESC, lastPlayedMs DESC LIMIT 50")
    fun observeMostPlayed(): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks ORDER BY dateAddedSec DESC LIMIT 50")
    fun observeRecentlyAdded(): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks")
    suspend fun getAllTracks(): List<AudioTrackEntity>

    @Query("SELECT * FROM audio_tracks WHERE uri = :uri LIMIT 1")
    suspend fun getTrack(uri: String): AudioTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<AudioTrackEntity>)

    @Query("UPDATE audio_tracks SET playCount = playCount + 1, lastPlayedMs = :nowMs WHERE uri = :uri")
    suspend fun recordPlay(uri: String, nowMs: Long = System.currentTimeMillis())

    @Query("UPDATE audio_tracks SET isFavorite = :fav WHERE uri = :uri")
    suspend fun setFavorite(uri: String, fav: Boolean)

    @Query("SELECT * FROM waveform_cache WHERE trackUri = :uri LIMIT 1")
    suspend fun getWaveform(uri: String): WaveformCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWaveform(entity: WaveformCacheEntity)

    @Query("SELECT * FROM device_eq_profiles WHERE deviceKey = :key LIMIT 1")
    suspend fun getDeviceEqProfile(key: String): DeviceEqProfileEntity?

    @Query("SELECT * FROM device_eq_profiles")
    suspend fun getAllDeviceEqProfiles(): List<DeviceEqProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeviceEqProfile(profile: DeviceEqProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllDeviceEqProfiles(profiles: List<DeviceEqProfileEntity>)

    @Query("SELECT * FROM music_playlists ORDER BY createdAtMs DESC")
    fun observePlaylists(): Flow<List<MusicPlaylistEntity>>

    @Query("SELECT * FROM music_playlists")
    suspend fun getAllPlaylists(): List<MusicPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: MusicPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllPlaylists(playlists: List<MusicPlaylistEntity>)
}

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackKey = :key LIMIT 1")
    suspend fun get(key: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsCacheEntity)

    @Query("UPDATE lyrics_cache SET offsetMs = :offsetMs WHERE trackKey = :key")
    suspend fun updateOffset(key: String, offsetMs: Int)
}

@Dao
interface ScrobbleQueueDao {
    @Query("SELECT * FROM scrobble_queue WHERE sentLastFm = 0 ORDER BY timestampEpochSec ASC LIMIT 50")
    suspend fun pendingLastFm(): List<ScrobbleQueueEntity>

    @Query("SELECT * FROM scrobble_queue WHERE sentListenBrainz = 0 ORDER BY timestampEpochSec ASC LIMIT 100")
    suspend fun pendingListenBrainz(): List<ScrobbleQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: ScrobbleQueueEntity)

    @Query("UPDATE scrobble_queue SET sentLastFm = 1 WHERE id IN (:ids)")
    suspend fun markLastFmSent(ids: List<Long>)

    @Query("UPDATE scrobble_queue SET sentListenBrainz = 1 WHERE id IN (:ids)")
    suspend fun markListenBrainzSent(ids: List<Long>)
}

@Dao
interface ListeningStatsDao {
    @Insert
    suspend fun insertEvent(event: ListeningEventEntity)

    @Insert
    suspend fun insertAll(events: List<ListeningEventEntity>)

    @Query("SELECT * FROM listening_events ORDER BY timestampMs DESC LIMIT 500")
    suspend fun getRecentEvents(): List<ListeningEventEntity>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM listening_events")
    fun observeTotalListenedMs(): Flow<Long>

    @Query("SELECT * FROM listening_events ORDER BY timestampMs DESC LIMIT 200")
    fun observeRecent(): Flow<List<ListeningEventEntity>>
}
