package com.lumaxr.player.data

import android.content.Context
import android.net.Uri
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

/**
 * 1-tap JSON/ZIP Config Export & Import so CI APK reinstalls / dynamic package ID changes
 * never lose per-folder XR rules, mpv.conf, shader/glow toggles, playlists, EQ profiles, or stats.
 */
class ConfigBackupManager(
    private val context: Context,
    private val db: LumaDatabase,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportBackupZip(destinationUri: Uri? = null): File = withContext(Dispatchers.IO) {
        val mpvConfFile = File(context.filesDir, "mpv_config/mpv.conf")
        val mpvConfText = if (mpvConfFile.exists()) mpvConfFile.readText() else ""
        val shaderPrefs = context.getSharedPreferences("lumaxr_visual_studio", Context.MODE_PRIVATE)
        val shaderJson = shaderPrefs.getString("glow_config_json", "{}") ?: "{}"

        val payload = LumaFullBackupPayload(
            folderVideoConfigs = db.folderConfigDao().getAll(),
            videoBookmarks = db.bookmarkDao().getAllBookmarks(),
            deviceEqProfiles = db.musicLibraryDao().getAllDeviceEqProfiles(),
            playlists = db.musicLibraryDao().getAllPlaylists(),
            listeningEvents = db.listeningStatsDao().getRecentEvents(),
            customMpvConf = mpvConfText,
            shaderTogglesJson = shaderJson,
        )

        val outFile = File(context.cacheDir, "lumaxr_backup_${System.currentTimeMillis()}.zip")
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
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                outFile.inputStream().use { it.copyTo(os) }
            }
        }
        outFile
    }

    suspend fun importBackup(sourceUri: Uri): Result<LumaFullBackupPayload> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: error("Cannot open backup URI")

            val jsonString: String = if (rawBytes.size >= 2 && rawBytes[0] == 0x50.toByte() && rawBytes[1] == 0x4B.toByte()) {
                var extracted: String? = null
                ZipInputStream(rawBytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith("lumaxr_backup.json")) {
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
            db.folderConfigDao().upsertAll(payload.folderVideoConfigs)
            db.bookmarkDao().insertBookmarks(payload.videoBookmarks)
            db.musicLibraryDao().upsertAllDeviceEqProfiles(payload.deviceEqProfiles)
            db.musicLibraryDao().upsertAllPlaylists(payload.playlists)
            if (payload.listeningEvents.isNotEmpty()) {
                db.listeningStatsDao().insertAll(payload.listeningEvents)
            }

            if (payload.customMpvConf.isNotBlank()) {
                val mpvDir = File(context.filesDir, "mpv_config").apply { mkdirs() }
                File(mpvDir, "mpv.conf").writeText(payload.customMpvConf)
            }
            if (payload.shaderTogglesJson.isNotBlank()) {
                context.getSharedPreferences("lumaxr_visual_studio", Context.MODE_PRIVATE)
                    .edit()
                    .putString("glow_config_json", payload.shaderTogglesJson)
                    .apply()
            }
            payload
        }
    }
}
