package com.lumaxr.player.video

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import `is`.xyz.mpv.Utils

data class ResolvedMpvSource(
    val uriOrPath: String,
    val usedContentFd: Boolean = false,
)

object MpvUriResolver {

    /**
     * Resolves a media or subtitle URI for libmpv.
     * For Android `content://` URIs, tries `Utils.findRealPath(fd)` first (closing the FD if a
     * directly readable path exists), otherwise detaches the FD and returns `"fd://$fd"` so libmpv
     * reads directly via avformat's fd protocol and owns closing it.
     */
    fun resolveForMpv(context: Context, rawUri: String): String {
        return resolveForMpv(
            rawUri = rawUri,
            openDetachedFd = { uriStr ->
                runCatching {
                    val uri = Uri.parse(uriStr)
                    context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
                }.getOrNull()
            },
            findRealPath = { fd -> Utils.findRealPath(fd) },
            closeDetachedFd = { fd ->
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            },
        ).uriOrPath
    }

    internal fun resolveForMpv(
        rawUri: String,
        openDetachedFd: (String) -> Int?,
        findRealPath: (Int) -> String?,
        closeDetachedFd: (Int) -> Unit,
    ): ResolvedMpvSource {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return ResolvedMpvSource("")

        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val path = Uri.parse(trimmed).path ?: trimmed.removePrefix("file://")
            return ResolvedMpvSource(path, usedContentFd = false)
        }

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val fd = openDetachedFd(trimmed)
            if (fd != null && fd >= 0) {
                val realPath = findRealPath(fd)
                if (!realPath.isNullOrBlank()) {
                    closeDetachedFd(fd)
                    return ResolvedMpvSource(realPath, usedContentFd = false)
                }
                return ResolvedMpvSource("fd://$fd", usedContentFd = true)
            }
        }

        return ResolvedMpvSource(trimmed, usedContentFd = false)
    }
}
