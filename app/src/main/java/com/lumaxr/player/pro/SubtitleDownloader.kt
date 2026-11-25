package com.lumaxr.player.pro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class SubtitleSearchResult(
    val fileId: Long,
    val fileName: String,
    val language: String,
    val downloadCount: Int,
)

class SubtitleDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) {
    companion object {
        private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "LumaXR v1.0.0"
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class OsFile(val file_id: Long = 0, val file_name: String = "")

    @Serializable
    private data class OsAttributes(
        val language: String = "",
        val download_count: Int = 0,
        val files: List<OsFile> = emptyList(),
    )

    @Serializable
    private data class OsDataItem(val attributes: OsAttributes = OsAttributes())

    @Serializable
    private data class OsSearchResponse(val data: List<OsDataItem> = emptyList())

    @Serializable
    private data class OsDownloadResponse(val link: String = "", val file_name: String = "subtitle.srt")

    fun computeMovieHash(file: File): String? {
        if (!file.exists() || file.length() < 131_072L) return null
        val size = file.length()
        val chunk = 65_536
        var hash = size
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            raf.readFully(buf)
            hash += sumLongsLe(buf)
            raf.seek((size - chunk).coerceAtLeast(0L))
            raf.readFully(buf)
            hash += sumLongsLe(buf)
        }
        return String.format(Locale.US, "%016x", hash)
    }

    private fun sumLongsLe(bytes: ByteArray): Long {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var acc = 0L
        while (bb.remaining() >= 8) {
            acc += bb.long
        }
        return acc
    }

    suspend fun searchSubtitles(
        apiKey: String,
        query: String,
        movieHash: String? = null,
        language: String = "en",
    ): List<SubtitleSearchResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val urlBuilder = "$BASE_URL/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("languages", language)
        if (!movieHash.isNullOrBlank()) {
            urlBuilder.addQueryParameter("moviehash", movieHash)
        } else {
            urlBuilder.addQueryParameter("query", query)
        }

        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("Api-Key", apiKey)
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val dto = json.decodeFromString<OsSearchResponse>(resp.body?.string().orEmpty())
                dto.data.mapNotNull { item ->
                    val f = item.attributes.files.firstOrNull() ?: return@mapNotNull null
                    SubtitleSearchResult(
                        fileId = f.file_id,
                        fileName = f.file_name,
                        language = item.attributes.language,
                        downloadCount = item.attributes.download_count,
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun downloadSubtitle(
        apiKey: String,
        jwtToken: String,
        fileId: Long,
        targetDir: File,
    ): File? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || jwtToken.isBlank()) return@withContext null
        targetDir.mkdirs()
        val body = """{"file_id":$fileId}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/download")
            .header("Api-Key", apiKey)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $jwtToken")
            .post(body)
            .build()

        try {
            val dlInfo = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                json.decodeFromString<OsDownloadResponse>(resp.body?.string().orEmpty())
            }
            if (dlInfo.link.isBlank()) return@withContext null
            val safeName = File(dlInfo.file_name.ifBlank { "downloaded.srt" }).name
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .ifBlank { "downloaded.srt" }
            val out = File(targetDir, safeName)
            if (!out.canonicalPath.startsWith(targetDir.canonicalPath)) return@withContext null

            val subReq = Request.Builder().url(dlInfo.link).build()
            client.newCall(subReq).execute().use { subResp ->
                if (!subResp.isSuccessful) return@withContext null
                val stream = subResp.body?.byteStream() ?: return@withContext null
                out.outputStream().use { fos -> stream.copyTo(fos) }
                out
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }
}
