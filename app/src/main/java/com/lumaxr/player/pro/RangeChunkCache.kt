package com.lumaxr.player.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

interface RandomAccessByteSource {
    val totalLength: Long
    fun readAt(offset: Long, buffer: ByteArray, length: Int): Int
    fun close() {}
}

/**
 * Sequential Range-Chunk Streaming Cache + Local 127.0.0.1 HTTP/1.1 206 Partial Content Proxy.
 * Prefetches sequential chunks (default 2 MiB chunks) to disk/RAM so 60-90 GB 4K UHD Remuxes
 * over SMB/WebDAV/HTTP seek cleanly inside libmpv without Transfer-Encoding: chunked stalls.
 */
class RangeChunkCache(
    private val cacheRoot: File,
    private val scope: CoroutineScope,
    val chunkSizeBytes: Int = 2 * 1024 * 1024,
    val prefetchAheadChunks: Int = 3,
) {
    private val ramChunks = ConcurrentHashMap<String, ByteArray>()
    private val maxRamChunks = 16
    private var serverSocket: ServerSocket? = null

    init {
        cacheRoot.mkdirs()
    }

    fun readRange(
        key: String,
        source: RandomAccessByteSource,
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset >= source.totalLength || length <= 0) return ByteArray(0)
        val clampedLen = length.toLong().coerceAtMost(source.totalLength - offset).toInt()
        val out = ByteArray(clampedLen)
        var filled = 0
        while (filled < clampedLen) {
            val pos = offset + filled
            val chunkIdx = pos / chunkSizeBytes
            val inChunkOff = (pos % chunkSizeBytes).toInt()
            val chunk = loadOrFetchChunk(key, source, chunkIdx)
            if (chunk.isEmpty() || inChunkOff >= chunk.size) break
            val copyLen = minOf(clampedLen - filled, chunk.size - inChunkOff)
            System.arraycopy(chunk, inChunkOff, out, filled, copyLen)
            filled += copyLen
            triggerSequentialPrefetch(key, source, chunkIdx)
        }
        return if (filled == clampedLen) out else out.copyOf(filled)
    }

    private fun loadOrFetchChunk(
        key: String,
        source: RandomAccessByteSource,
        chunkIdx: Long,
    ): ByteArray {
        val cacheId = "${key.hashCode()}_$chunkIdx"
        ramChunks[cacheId]?.let { return it }

        val diskFile = File(cacheRoot, "$cacheId.bin")
        if (diskFile.exists() && diskFile.length() > 0L) {
            val bytes = diskFile.readBytes()
            putRam(cacheId, bytes)
            return bytes
        }

        val start = chunkIdx * chunkSizeBytes
        if (start >= source.totalLength) return ByteArray(0)
        val toRead = minOf(chunkSizeBytes.toLong(), source.totalLength - start).toInt()
        val buf = ByteArray(toRead)
        var totalRead = 0
        while (totalRead < toRead) {
            val n = source.readAt(start + totalRead, buf.copyOfRange(totalRead, toRead), toRead - totalRead)
            if (n <= 0) break
            totalRead += n
        }
        // Fallback if readAt wrote directly into passed buffer
        val finalBuf = if (totalRead == 0) {
            val direct = ByteArray(toRead)
            val n = source.readAt(start, direct, toRead).coerceAtLeast(0)
            direct.copyOf(n)
        } else {
            buf.copyOf(totalRead)
        }
        if (finalBuf.isNotEmpty()) {
            runCatching { diskFile.writeBytes(finalBuf) }
            putRam(cacheId, finalBuf)
        }
        return finalBuf
    }

    private fun triggerSequentialPrefetch(key: String, source: RandomAccessByteSource, currentChunkIdx: Long) {
        scope.launch(Dispatchers.IO) {
            for (ahead in 1..prefetchAheadChunks) {
                val idx = currentChunkIdx + ahead
                val start = idx * chunkSizeBytes
                if (start >= source.totalLength) break
                val cacheId = "${key.hashCode()}_$idx"
                if (!ramChunks.containsKey(cacheId) && !File(cacheRoot, "$cacheId.bin").exists()) {
                    loadOrFetchChunk(key, source, idx)
                }
            }
        }
    }

    private fun putRam(id: String, bytes: ByteArray) {
        if (ramChunks.size >= maxRamChunks) {
            ramChunks.keys.firstOrNull()?.let { ramChunks.remove(it) }
        }
        ramChunks[id] = bytes
    }

    /**
     * Starts a local loopback HTTP/1.1 server returning strict 206 Partial Content for libmpv.
     */
    fun startLoopbackProxy(key: String, source: RandomAccessByteSource): String {
        stopProxy()
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        val port = ss.localPort
        scope.launch(Dispatchers.IO) {
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { handleHttpClient(client, key, source) }
            }
        }
        return "http://127.0.0.1:$port/stream"
    }

    private fun handleHttpClient(socket: Socket, key: String, source: RandomAccessByteSource) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val reqLine = reader.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }
            val total = source.totalLength
            var start = 0L
            var end = (total - 1).coerceAtLeast(0L)
            val isPartial = rangeHeader != null && rangeHeader.startsWith("bytes=")
            if (isPartial) {
                val spec = rangeHeader!!.substringAfter("bytes=").substringBefore(',')
                val parts = spec.split('-')
                start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
            }
            val contentLen = (end - start + 1).coerceAtLeast(0L)
            val out = BufferedOutputStream(s.getOutputStream())
            val header = buildString {
                if (isPartial) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Range: bytes $start-$end/$total\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $contentLen\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.encodeToByteArray())
            if (!reqLine.startsWith("HEAD")) {
                var cursor = start
                val step = 256 * 1024
                while (cursor <= end) {
                    val toSend = minOf(step.toLong(), end - cursor + 1).toInt()
                    val data = readRange(key, source, cursor, toSend)
                    if (data.isEmpty()) break
                    out.write(data)
                    cursor += data.size
                }
            }
            out.flush()
        }
    }

    fun stopProxy() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
