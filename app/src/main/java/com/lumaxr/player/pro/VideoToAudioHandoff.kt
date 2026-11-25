package com.lumaxr.player.pro

import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.music.MusicPlaybackCoordinator
import com.lumaxr.player.video.DualEngineCoordinator
import com.lumaxr.player.video.ProbedMediaInfo

class VideoToAudioHandoff(
    private val videoCoordinator: DualEngineCoordinator,
    private val musicCoordinator: MusicPlaybackCoordinator,
) {
    fun handoffToBackgroundAudio(): Boolean {
        val media: ProbedMediaInfo = videoCoordinator.currentMedia.value ?: return false
        val posMs = (videoCoordinator.currentPositionSec() * 1000.0).toLong().coerceAtLeast(0L)

        videoCoordinator.stopAndRestoreDisplay()

        val audioTrack = AudioTrackEntity(
            uri = media.uri,
            title = media.title,
            artist = "Video Background Audio",
            album = media.container,
            albumArtist = "LumaXR",
            composer = "",
            year = 0,
            trackNumber = 1,
            discNumber = 1,
            durationMs = media.durationMs,
            folderPath = "",
            codec = media.audioCodec,
            container = media.container,
            sampleRateHz = 48000,
            bitDepth = 24,
            channelCount = 2,
            bitrateKbps = (media.bitrateMbps * 1000).toInt(),
            isDolbyAtmos = media.audioCodec.contains("eac3", true),
            isDolbyAc4 = media.audioCodec.contains("ac4", true),
            isDsd = false,
            isLossless = media.audioCodec.contains("flac", true) || media.audioCodec.contains("truehd", true),
        )
        musicCoordinator.playQueue(listOf(audioTrack), startIndex = 0, startPositionMs = posMs)
        return true
    }
}
