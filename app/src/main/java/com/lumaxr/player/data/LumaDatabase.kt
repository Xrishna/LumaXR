package com.lumaxr.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderVideoConfigEntity::class,
        VideoBookmarkEntity::class,
        AudioTrackEntity::class,
        WaveformCacheEntity::class,
        DeviceEqProfileEntity::class,
        LyricsCacheEntity::class,
        MusicPlaylistEntity::class,
        ScrobbleQueueEntity::class,
        ListeningEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LumaDatabase : RoomDatabase() {
    abstract fun folderConfigDao(): FolderConfigDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun musicLibraryDao(): MusicLibraryDao
    abstract fun lyricsCacheDao(): LyricsCacheDao
    abstract fun scrobbleQueueDao(): ScrobbleQueueDao
    abstract fun listeningStatsDao(): ListeningStatsDao

    companion object {
        @Volatile
        private var INSTANCE: LumaDatabase? = null

        fun getInstance(context: Context): LumaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LumaDatabase::class.java,
                    "lumaxr_unified.db",
                ).fallbackToDestructiveMigration(dropAllTables = false).build().also { INSTANCE = it }
            }
        }
    }
}
