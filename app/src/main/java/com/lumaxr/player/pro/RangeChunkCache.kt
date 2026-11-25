package com.lumaxr.player.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface RandomAccessByteSource {
    val totalLength: Long
    fun readAt(offset: Long, buffer: ByteArray, length: Int): Int
    fun close() {}
}

class RangeChunkCache(
    private val cacheRoot: File,
    private val scope: CoroutineScope,
    val chunkSizeBytes: Int = 2 * 1024 * 1024,
    val prefetchAheadChunks: Int = 3,
) {
    private val ramChunks = ConcurrentHashMap<String, ByteArray>()
    private val chunkLocks = ConcurrentHashMap<String, Any>()
    private val sourceLock = Any()
    private val maxRamChunks = 16
    private var serverSocket: ServerSocket? = null
    private var activeSource: RandomAccessByteSource? = null

    init {
        cacheRoot.mkdirs()
    }

    fun readRange(
        key: String,
        source: RandomAccessByteSource,
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0L || offset >= source.totalLength || length <= 0) return ByteArray(0)
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

    private fun chunkHashId(key: String, chunkIdx: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$key#$chunkIdx".encodeToByteArray())
        val hex = digest.take(12).joinToString("") { String.format(Locale.US, "%02x", it) }
        return "${hex}_$chunkIdx"
    }

    private fun loadOrFetchChunk(
        key: String,
        source: RandomAccessByteSource,
        chunkIdx: Long,
    ): ByteArray {
        val cacheId = chunkHashId(key, chunkIdx)
        ramChunks[cacheId]?.let { return it }

        val chunkLock = chunkLocks.computeIfAbsent(cacheId) { Any() }
        synchronized(chunkLock) {
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
            val outBuf = ByteArray(toRead)
            val scratch = ByteArray(minOf(toRead, 256 * 1024))
            var totalRead = 0
            while (totalRead < toRead) {
                val step = minOf(scratch.size, toRead - totalRead)
                val n = synchronized(sourceLock) {
                    source.readAt(start + totalRead, scratch, step)
                }
                if (n <= 0) break
                val copied = n.coerceAtMost(step)
                System.arraycopy(scratch, 0, outBuf, totalRead, copied)
                totalRead += copied
            }
            val finalBuf = if (totalRead == toRead) outBuf else outBuf.copyOf(totalRead)
            if (finalBuf.isNotEmpty()) {
                runCatching {
                    val tmp = File(cacheRoot, "$cacheId.tmp")
                    tmp.writeBytes(finalBuf)
                    if (!tmp.renameTo(diskFile)) {
                        diskFile.writeBytes(finalBuf)
                        tmp.delete()
                    }
                }
                putRam(cacheId, finalBuf)
            }
            return finalBuf
        }
    }

    private fun triggerSequentialPrefetch(key: String, source: RandomAccessByteSource, currentChunkIdx: Long) {
        scope.launch(Dispatchers.IO) {
            for (ahead in 1..prefetchAheadChunks) {
                val idx = currentChunkIdx + ahead
                val start = idx * chunkSizeBytes
                if (start >= source.totalLength) break
                val cacheId = chunkHashId(key, idx)
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

    fun startLoopbackProxy(key: String, source: RandomAccessByteSource): String {
        stopProxy()
        activeSource = source
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
            var satisfiable = total > 0L
            val isPartial = rangeHeader != null && rangeHeader.startsWith("bytes=")
            if (isPartial) {
                val spec = rangeHeader!!.substringAfter("bytes=").substringBefore(',').trim()
                val parts = spec.split('-')
                val p0 = parts.getOrNull(0).orEmpty()
                val p1 = parts.getOrNull(1).orEmpty()
                if (p0.isEmpty() && p1.isNotEmpty()) {
                    val suffix = p1.toLongOrNull() ?: 0L
                    if (suffix <= 0L || total <= 0L) satisfiable = false
                    else {
                        start = (total - suffix).coerceAtLeast(0L)
                        end = total - 1
                    }
                } else {
                    start = p0.toLongOrNull() ?: 0L
                    end = p1.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                    if (start < 0L || start >= total || start > end) satisfiable = false
                }
            }

            val out = BufferedOutputStream(s.getOutputStream())
            if (isPartial && !satisfiable) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".encodeToByteArray())
                out.flush()
                return
            }

            val contentLen = if (total == 0L) 0L else (end - start + 1).coerceAtLeast(0L)
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
            if (!reqLine.startsWith("HEAD") && contentLen > 0L) {
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
        runCatching { activeSource?.close() }
        activeSource = null
    }
}
