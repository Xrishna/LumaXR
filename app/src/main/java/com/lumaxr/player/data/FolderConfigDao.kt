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
@Entity(tableName = "folder_video_configs")
data class FolderVideoConfigEntity(
    @PrimaryKey val scopeKey: String, // either file hash or canonical parent folder path
    val isFolderRule: Boolean,
    val xrPresetName: String = "CINEMA_MASTERING_XR",
    val hdrToysMapperName: String = "OFF",
    val debandOverride: Boolean? = null,
    val subtitlePeakNits: Int = 140,
    val aspectModeName: String = "ORIGINAL",
    val subtitlePlacementName: String = "ABOVE_LETTERBOX",
    val audioDelayMs: Int = 0,
    val preferredAudioLang: String = "eng",
    val preferredSubtitleLang: String = "eng",
    val preferAssSignsSongs: Boolean = false,
    val autoSkipIntro: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

@Dao
interface FolderConfigDao {
    @Query("SELECT * FROM folder_video_configs WHERE scopeKey = :key LIMIT 1")
    suspend fun getByKey(key: String): FolderVideoConfigEntity?

    @Query("SELECT * FROM folder_video_configs ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<FolderVideoConfigEntity>>

    @Query("SELECT * FROM folder_video_configs")
    suspend fun getAll(): List<FolderVideoConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FolderVideoConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FolderVideoConfigEntity>)

    @Query("DELETE FROM folder_video_configs WHERE scopeKey = :key")
    suspend fun deleteByKey(key: String)
}
