package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.pro.RandomAccessByteSource
import com.lumaxr.player.pro.RangeChunkCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class RangeChunkCacheTest {

    @Test
    fun readsExactCrossChunkRangesFromSourceAndCachesToDisk() {
        val tempDir = Files.createTempDirectory("lumaxr_chunk_test").toFile()
        val payload = ByteArray(5000) { idx -> (idx % 251).toByte() }
        val readCalls = AtomicInteger(0)
        val source = object : RandomAccessByteSource {
            override val totalLength: Long = payload.size.toLong()
            override fun readAt(offset: Long, buffer: ByteArray, length: Int): Int {
                readCalls.incrementAndGet()
                val avail = minOf(length, payload.size - offset.toInt())
                System.arraycopy(payload, offset.toInt(), buffer, 0, avail)
                return avail
            }
        }

        val cache = RangeChunkCache(
            cacheRoot = tempDir,
            scope = CoroutineScope(Dispatchers.Unconfined),
            chunkSizeBytes = 1024,
            prefetchAheadChunks = 0,
        )

        val slice = cache.readRange("test_movie", source, offset = 900L, length = 500)
        assertThat(slice).isEqualTo(payload.copyOfRange(900, 1400))

        val callsAfterFirstRead = readCalls.get()
        val cachedSlice = cache.readRange("test_movie", source, offset = 950L, length = 300)
        assertThat(cachedSlice).isEqualTo(payload.copyOfRange(950, 1250))
        assertThat(readCalls.get()).isEqualTo(callsAfterFirstRead)

        // Clamps cleanly at EOF and returns empty on out-of-range offset
        val tail = cache.readRange("test_movie", source, offset = 4950L, length = 200)
        assertThat(tail).isEqualTo(payload.copyOfRange(4950, 5000))
        assertThat(cache.readRange("test_movie", source, offset = 6000L, length = 10)).isEmpty()

        tempDir.deleteRecursively()
    }
}
