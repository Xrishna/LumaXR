package com.lumaxr.player.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class LumaFullBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val folderVideoConfigs: List<FolderVideoConfigEntity> = emptyList(),
    val videoBookmarks: List<VideoBookmarkEntity> = emptyList(),
    val deviceEqProfiles: List<DeviceEqProfileEntity> = emptyList(),
    val playlists: List<MusicPlaylistEntity> = emptyList(),
    val listeningEvents: List<ListeningEventEntity> = emptyList(),
    val customMpvConf: String = "",
    val shaderTogglesJson: String = "{}",
)

class ConfigBackupManager(
    context: Context,
    private val db: LumaDatabase,
) {
    private val appContext = context.applicationContext
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportBackupZip(destinationUri: Uri? = null): File = withContext(Dispatchers.IO) {
        val mpvConfFile = File(appContext.filesDir, "mpv_config/mpv.conf")
        val mpvConfText = if (mpvConfFile.exists()) mpvConfFile.readText() else ""
        val shaderPrefs = appContext.getSharedPreferences("lumaxr_visual_studio", Context.MODE_PRIVATE)
        val shaderJson = shaderPrefs.getString("glow_config_json", "{}") ?: "{}"

        val payload = LumaFullBackupPayload(
            folderVideoConfigs = db.folderConfigDao().getAll(),
            videoBookmarks = db.bookmarkDao().getAllBookmarks(),
            deviceEqProfiles = db.musicLibraryDao().getAllDeviceEqProfiles(),
            playlists = db.musicLibraryDao().getAllPlaylists(),
            listeningEvents = db.listeningStatsDao().getAllEvents(),
            customMpvConf = mpvConfText,
            shaderTogglesJson = shaderJson,
        )

        val outFile = File(appContext.cacheDir, "lumaxr_backup_${System.currentTimeMillis()}.zip")
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("lumaxr_backup.json"))
            zip.write(json.encodeToString(payload).encodeToByteArray())
            zip.closeEntry()

            if (mpvConfText.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("mpv.conf"))
                zip.write(mpvConfText.encodeToByteArray())
                zip.closeEntry()
            }
        }

        if (destinationUri != null) {
            val os = appContext.contentResolver.openOutputStream(destinationUri)
                ?: error("Cannot open output stream for backup destination")
            os.use { out -> outFile.inputStream().use { it.copyTo(out) } }
            outFile.delete()
        }
        outFile
    }

    suspend fun importBackup(sourceUri: Uri): Result<LumaFullBackupPayload> = withContext(Dispatchers.IO) {
        try {
            val rawBytes = appContext.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: error("Cannot open backup URI")

            val jsonString: String = if (rawBytes.size >= 2 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) {
                var extracted: String? = null
                ZipInputStream(rawBytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "lumaxr_backup.json") {
                            extracted = zis.readBytes().decodeToString()
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
                extracted ?: error("lumaxr_backup.json missing in ZIP")
            } else {
                rawBytes.decodeToString()
            }

            val payload = json.decodeFromString<LumaFullBackupPayload>(jsonString)
            db.withTransaction {
                db.folderConfigDao().upsertAll(payload.folderVideoConfigs)
                db.bookmarkDao().insertBookmarks(payload.videoBookmarks.map { it.copy(id = 0) })
                db.musicLibraryDao().upsertAllDeviceEqProfiles(payload.deviceEqProfiles)
                db.musicLibraryDao().upsertAllPlaylists(payload.playlists)
                if (payload.listeningEvents.isNotEmpty()) {
                    db.listeningStatsDao().insertAll(payload.listeningEvents.map { it.copy(id = 0) })
                }
            }

            val mpvDir = File(appContext.filesDir, "mpv_config").apply { mkdirs() }
            val mpvFile = File(mpvDir, "mpv.conf")
            if (payload.customMpvConf.isNotBlank()) {
                mpvFile.writeText(payload.customMpvConf)
            } else if (mpvFile.exists()) {
                mpvFile.delete()
            }
            if (payload.shaderTogglesJson.isNotBlank()) {
                appContext.getSharedPreferences("lumaxr_visual_studio", Context.MODE_PRIVATE)
                    .edit()
                    .putString("glow_config_json", payload.shaderTogglesJson)
                    .commit()
            }
            Result.success(payload)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
