package com.lumaxr.player.pro

import com.lumaxr.player.data.FolderVideoConfigEntity

data class MediaStreamTrack(
    val id: Int,
    val type: String, // "audio" or "sub"
    val lang: String,
    val title: String,
    val codec: String,
    val isForced: Boolean = false,
)

data class TrackSelectionRecommendation(
    val audioTrackId: Int?,
    val subtitleTrackId: Int?, // null or -1 means disable subs
)

/**
 * Per-Series / Per-Folder Smart Track Selection Engine:
 * - Anime folders: Japanese audio (`jpn`/`ja`) + English ASS Signs/Songs or full dialogue
 * - UHD Remux folders: English Atmos/TrueHD (`eng`/`en`) + no forced subs unless specified
 */
class TrackLanguageRuleEngine {

    fun defaultRuleForFolder(folderPath: String): FolderVideoConfigEntity {
        val lower = folderPath.lowercase()
        return when {
            "anime" in lower -> FolderVideoConfigEntity(
                scopeKey = folderPath,
                isFolderRule = true,
                preferredAudioLang = "jpn",
                preferredSubtitleLang = "eng",
                preferAssSignsSongs = true,
                autoSkipIntro = true,
            )
            "remux" in lower || "uhd" in lower || "4k" in lower -> FolderVideoConfigEntity(
                scopeKey = folderPath,
                isFolderRule = true,
                preferredAudioLang = "eng",
                preferredSubtitleLang = "none",
                preferAssSignsSongs = false,
                autoSkipIntro = false,
            )
            else -> FolderVideoConfigEntity(
                scopeKey = folderPath,
                isFolderRule = true,
                preferredAudioLang = "eng",
                preferredSubtitleLang = "eng",
            )
        }
    }

    fun selectTracks(
        tracks: List<MediaStreamTrack>,
        rule: FolderVideoConfigEntity,
    ): TrackSelectionRecommendation {
        val audioTracks = tracks.filter { it.type == "audio" }
        val subTracks = tracks.filter { it.type == "sub" }

        val chosenAudio = audioTracks
            .filter { matchesLang(it.lang, rule.preferredAudioLang) }
            .maxByOrNull { scoreAudioCodec(it.codec) }
            ?: audioTracks.maxByOrNull { scoreAudioCodec(it.codec) }

        val chosenSub = if (rule.preferredSubtitleLang.equals("none", ignoreCase = true)) {
            null
        } else {
            val langMatches = subTracks.filter { matchesLang(it.lang, rule.preferredSubtitleLang) }
            if (rule.preferAssSignsSongs) {
                langMatches.firstOrNull {
                    it.codec.contains("ass", true) || "sign" in it.title.lowercase() || "song" in it.title.lowercase()
                } ?: langMatches.firstOrNull()
            } else {
                langMatches.firstOrNull { !it.isForced } ?: langMatches.firstOrNull()
            }
        }

        return TrackSelectionRecommendation(
            audioTrackId = chosenAudio?.id,
            subtitleTrackId = chosenSub?.id,
        )
    }

    private fun scoreAudioCodec(codec: String): Int {
        val c = codec.lowercase()
        return when {
            "truehd" in c || "mlp" in c -> 100
            "eac3" in c || "atmos" in c -> 90
            "dts" in c -> 80
            "flac" in c -> 75
            "ac3" in c -> 60
            else -> 40
        }
    }

    private fun matchesLang(trackLang: String, preferred: String): Boolean {
        val a = trackLang.lowercase().take(2)
        val b = preferred.lowercase().take(2)
        return a.isNotBlank() && a == b
    }
}
