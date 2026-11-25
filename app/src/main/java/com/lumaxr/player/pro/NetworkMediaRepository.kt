package com.lumaxr.player.pro

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.jce.JceSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.util.EnumSet
import java.util.concurrent.TimeUnit

enum class NetworkProtocol {
    SMB,
    WEBDAV,
    HTTP_INDEX,
    JELLYFIN,
    EMBY,
    PLEX,
}

@Serializable
data class NetworkServerConfig(
    val id: String,
    val name: String,
    val protocol: NetworkProtocol,
    val hostOrUrl: String,
    val shareOrLibrary: String = "",
    val username: String = "",
    val passwordOrToken: String = "",
)

data class RemoteMediaEntry(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val streamUrl: String = "",
    val jellyfinItemId: String? = null,
)

/**
 * Native clients for SMB v2/v3 (smbj + BouncyCastle MD4), WebDAV (OkHttpSardine),
 * HTTP/FTP, and Jellyfin / Emby / Plex media servers.
 */
class NetworkMediaRepository(
    private val chunkCache: RangeChunkCache,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun buildSmbClient(): SMBClient {
        val cfg = SmbConfig.builder()
            .withSecurityProvider(JceSecurityProvider(BouncyCastleProvider()))
            .withReadBufferSize(256 * 1024)
            .withReadTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
        return SMBClient(cfg)
    }

    suspend fun listDirectory(server: NetworkServerConfig, remotePath: String = ""): List<RemoteMediaEntry> =
        withContext(Dispatchers.IO) {
            when (server.protocol) {
                NetworkProtocol.SMB -> listSmb(server, remotePath)
                NetworkProtocol.WEBDAV -> listWebDav(server, remotePath)
                NetworkProtocol.JELLYFIN, NetworkProtocol.EMBY -> listJellyfinOrEmby(server, remotePath)
                NetworkProtocol.PLEX -> listPlex(server, remotePath)
                NetworkProtocol.HTTP_INDEX -> listHttpDirectory(server, remotePath)
            }
        }

    suspend fun openPlayableStreamUrl(server: NetworkServerConfig, entry: RemoteMediaEntry): String =
        withContext(Dispatchers.IO) {
            when (server.protocol) {
                NetworkProtocol.SMB -> {
                    val client = buildSmbClient()
                    val conn = client.connect(server.hostOrUrl)
                    val auth = AuthenticationContext(server.username, server.passwordOrToken.toCharArray(), null)
                    val session = conn.authenticate(auth)
                    val share = session.connectShare(server.shareOrLibrary) as DiskShare
                    val file = share.openFile(
                        entry.path.trimStart('/'),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                    val totalSize = file.fileInformation.standardInformation.endOfFile
                    val source = object : RandomAccessByteSource {
                        override val totalLength: Long = totalSize
                        override fun readAt(offset: Long, buffer: ByteArray, length: Int): Int {
                            return file.read(buffer, offset, 0, length)
                        }
                        override fun close() {
                            runCatching { file.close(); share.close(); session.close(); conn.close(); client.close() }
                        }
                    }
                    chunkCache.startLoopbackProxy("smb_${server.id}_${entry.path}", source)
                }
                NetworkProtocol.JELLYFIN, NetworkProtocol.EMBY -> {
                    val base = server.hostOrUrl.trimEnd('/')
                    val itemId = entry.jellyfinItemId ?: entry.id
                    "$base/Videos/$itemId/stream?static=true&api_key=${server.passwordOrToken}"
                }
                NetworkProtocol.PLEX -> {
                    val base = server.hostOrUrl.trimEnd('/')
                    "$base${entry.path}?X-Plex-Token=${server.passwordOrToken}"
                }
                else -> entry.streamUrl.ifBlank { "${server.hostOrUrl.trimEnd('/')}/${entry.path.trimStart('/')}" }
            }
        }

    private fun listSmb(server: NetworkServerConfig, path: String): List<RemoteMediaEntry> {
        return runCatching {
            buildSmbClient().use { client ->
                client.connect(server.hostOrUrl).use { conn ->
                    val auth = AuthenticationContext(server.username, server.passwordOrToken.toCharArray(), null)
                    conn.authenticate(auth).use { session ->
                        val share = session.connectShare(server.shareOrLibrary) as DiskShare
                        val clean = path.trim('/')
                        share.list(clean).filter { it.fileName != "." && it.fileName != ".." }.map { info ->
                            val isDir = (info.fileAttributes and 0x10L) != 0L
                            val child = if (clean.isEmpty()) info.fileName else "$clean/${info.fileName}"
                            RemoteMediaEntry(
                                id = child,
                                name = info.fileName,
                                path = child,
                                isDirectory = isDir,
                                sizeBytes = info.endOfFile,
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun listWebDav(server: NetworkServerConfig, path: String): List<RemoteMediaEntry> {
        return runCatching {
            val sardine = OkHttpSardine()
            if (server.username.isNotBlank()) {
                sardine.setCredentials(server.username, server.passwordOrToken)
            }
            val targetUrl = "${server.hostOrUrl.trimEnd('/')}/${path.trimStart('/')}"
            val resources = sardine.list(targetUrl)
            // Strip self-href (first entry returned by WebDAV 207 Multi-Status PROPFIND)
            resources.drop(1).map { res ->
                RemoteMediaEntry(
                    id = res.path,
                    name = res.name,
                    path = res.path,
                    isDirectory = res.isDirectory,
                    sizeBytes = res.contentLength ?: 0L,
                    streamUrl = "${server.hostOrUrl.trimEnd('/')}/${res.path.trimStart('/')}",
                )
            }
        }.getOrDefault(emptyList())
    }

    @Serializable
    private data class JfItemsWrapper(val Items: List<JfItem> = emptyList())

    @Serializable
    private data class JfItem(val Id: String, val Name: String, val IsFolder: Boolean = false)

    private fun listJellyfinOrEmby(server: NetworkServerConfig, parentId: String): List<RemoteMediaEntry> {
        val base = server.hostOrUrl.trimEnd('/')
        val url = if (parentId.isBlank()) {
            "$base/Items?Recursive=false"
        } else {
            "$base/Items?ParentId=$parentId"
        }
        val req = Request.Builder()
            .url(url)
            .header("X-Emby-Token", server.passwordOrToken)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val dto = json.decodeFromString<JfItemsWrapper>(resp.body?.string().orEmpty())
                dto.Items.map { item ->
                    RemoteMediaEntry(
                        id = item.Id,
                        name = item.Name,
                        path = item.Id,
                        isDirectory = item.IsFolder,
                        jellyfinItemId = item.Id,
                        streamUrl = "$base/Videos/${item.Id}/stream?static=true&api_key=${server.passwordOrToken}",
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun listPlex(server: NetworkServerConfig, key: String): List<RemoteMediaEntry> {
        val base = server.hostOrUrl.trimEnd('/')
        val path = key.ifBlank { "/library/sections" }
        val req = Request.Builder()
            .url("$base$path")
            .header("X-Plex-Token", server.passwordOrToken)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) emptyList() else listOf(
                    RemoteMediaEntry("plex_root", "Plex Library", path, isDirectory = true),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun listHttpDirectory(server: NetworkServerConfig, path: String): List<RemoteMediaEntry> {
        val url = "${server.hostOrUrl.trimEnd('/')}/${path.trimStart('/')}"
        val req = Request.Builder().url(url).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val html = resp.body?.string().orEmpty()
                Regex("""href="([^"]+)"""").findAll(html).mapNotNull { m ->
                    val href = m.groupValues[1]
                    if (href.startsWith("?") || href == "../") return@mapNotNull null
                    RemoteMediaEntry(
                        id = href,
                        name = href.trimEnd('/').substringAfterLast('/'),
                        path = href,
                        isDirectory = href.endsWith("/"),
                        streamUrl = "$url/${href.trimStart('/')}",
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }
}
