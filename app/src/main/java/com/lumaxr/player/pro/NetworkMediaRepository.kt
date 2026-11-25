package com.lumaxr.player.pro

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.jce.JceSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
                    var client: SMBClient? = null
                    var conn: Connection? = null
                    var session: Session? = null
                    var share: DiskShare? = null
                    var file: SmbFile? = null
                    try {
                        client = buildSmbClient()
                        conn = client.connect(server.hostOrUrl)
                        val auth = AuthenticationContext(server.username, server.passwordOrToken.toCharArray(), null)
                        session = conn.authenticate(auth)
                        share = session.connectShare(server.shareOrLibrary) as DiskShare
                        file = share.openFile(
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
                                runCatching { file.close() }
                                runCatching { share.close() }
                                runCatching { session.close() }
                                runCatching { conn.close() }
                                runCatching { client.close() }
                            }
                        }
                        chunkCache.startLoopbackProxy("smb_${server.id}_${entry.path}", source)
                    } catch (t: Throwable) {
                        runCatching { file?.close() }
                        runCatching { share?.close() }
                        runCatching { session?.close() }
                        runCatching { conn?.close() }
                        runCatching { client?.close() }
                        throw t
                    }
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
                        (session.connectShare(server.shareOrLibrary) as DiskShare).use { share ->
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
            }
        }.getOrDefault(emptyList())
    }

    private fun listWebDav(server: NetworkServerConfig, path: String): List<RemoteMediaEntry> {
        return runCatching {
            val sardine = OkHttpSardine(httpClient)
            if (server.username.isNotBlank()) {
                sardine.setCredentials(server.username, server.passwordOrToken)
            }
            val targetUrl = "${server.hostOrUrl.trimEnd('/')}/${path.trimStart('/')}"
            val resources = sardine.list(targetUrl)
            resources.drop(1).map { res ->
                val resolved = targetUrl.toHttpUrlOrNull()?.resolve(res.path)?.toString()
                    ?: "${server.hostOrUrl.trimEnd('/')}/${res.path.trimStart('/')}"
                RemoteMediaEntry(
                    id = res.path,
                    name = res.name,
                    path = res.path,
                    isDirectory = res.isDirectory,
                    sizeBytes = res.contentLength ?: 0L,
                    streamUrl = resolved,
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
        val baseUrl = base.toHttpUrlOrNull() ?: return emptyList()
        val urlBuilder = baseUrl.newBuilder().addPathSegment("Items")
        if (parentId.isBlank()) {
            urlBuilder.addQueryParameter("Recursive", "false")
        } else {
            urlBuilder.addQueryParameter("ParentId", parentId)
        }
        val req = Request.Builder()
            .url(urlBuilder.build())
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

    @Serializable
    private data class PlexMediaContainer(val Directory: List<PlexDir> = emptyList(), val Metadata: List<PlexMeta> = emptyList())

    @Serializable
    private data class PlexDir(val key: String = "", val title: String = "")

    @Serializable
    private data class PlexMeta(val key: String = "", val title: String = "", val type: String = "")

    @Serializable
    private data class PlexWrapper(val MediaContainer: PlexMediaContainer = PlexMediaContainer())

    private fun listPlex(server: NetworkServerConfig, key: String): List<RemoteMediaEntry> {
        val base = server.hostOrUrl.trimEnd('/')
        val path = when {
            key.isBlank() -> "/library/sections"
            key.startsWith("/") -> key
            else -> "/library/sections/$key/all"
        }
        val req = Request.Builder()
            .url("$base$path")
            .header("X-Plex-Token", server.passwordOrToken)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val dto = json.decodeFromString<PlexWrapper>(resp.body?.string().orEmpty())
                val dirs = dto.MediaContainer.Directory.map {
                    RemoteMediaEntry(it.key, it.title, it.key, isDirectory = true)
                }
                val metas = dto.MediaContainer.Metadata.map {
                    RemoteMediaEntry(it.key, it.title, it.key, isDirectory = it.type != "movie" && it.type != "episode")
                }
                dirs + metas
            }
        }.getOrDefault(emptyList())
    }

    private fun listHttpDirectory(server: NetworkServerConfig, path: String): List<RemoteMediaEntry> {
        val baseUrl = "${server.hostOrUrl.trimEnd('/')}/${path.trimStart('/')}".toHttpUrlOrNull() ?: return emptyList()
        val req = Request.Builder().url(baseUrl).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val html = resp.body?.string().orEmpty()
                Regex("""href="([^"]+)"""").findAll(html).mapNotNull { m ->
                    val href = m.groupValues[1]
                    if (href.startsWith("?") || href.startsWith("#") || href.startsWith("mailto:", true) || href == "../") {
                        return@mapNotNull null
                    }
                    val resolved = baseUrl.resolve(href)?.toString() ?: return@mapNotNull null
                    RemoteMediaEntry(
                        id = href,
                        name = href.trimEnd('/').substringAfterLast('/'),
                        path = href,
                        isDirectory = href.endsWith("/"),
                        streamUrl = resolved,
                    )
                }.toList()
            }
        }.getOrDefault(emptyList())
    }
}
