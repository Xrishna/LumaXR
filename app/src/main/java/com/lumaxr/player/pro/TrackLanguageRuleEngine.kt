package com.lumaxr.player.pro

import com.lumaxr.player.data.FolderVideoConfigEntity
import java.util.Locale

data class MediaStreamTrack(
    val id: Int,
    val type: String,
    val lang: String,
    val title: String,
    val codec: String,
    val isForced: Boolean = false,
)

data class TrackSelectionRecommendation(
    val audioTrackId: Int?,
    val subtitleTrackId: Int?,
)

class TrackLanguageRuleEngine {

    companion object {
        private val ISO_SYNONYMS = mapOf(
            "ja" to "jpn", "jpn" to "jpn",
            "en" to "eng", "eng" to "eng",
            "zh" to "zho", "zho" to "zho", "chi" to "zho",
            "de" to "deu", "deu" to "deu", "ger" to "deu",
            "fr" to "fra", "fra" to "fra", "fre" to "fra",
            "es" to "spa", "spa" to "spa",
            "pt" to "por", "por" to "por",
            "ko" to "kor", "kor" to "kor",
            "it" to "ita", "ita" to "ita",
            "ru" to "rus", "rus" to "rus",
            "hi" to "hin", "hin" to "hin",
        )
    }

    fun defaultRuleForFolder(folderPath: String): FolderVideoConfigEntity {
        val lower = folderPath.lowercase(Locale.ROOT)
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
                    val t = it.title.lowercase(Locale.ROOT)
                    ("sign" in t || "song" in t) && it.codec.contains("ass", true)
                } ?: langMatches.firstOrNull {
                    val t = it.title.lowercase(Locale.ROOT)
                    it.codec.contains("ass", true) || "sign" in t || "song" in t
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
        val c = codec.lowercase(Locale.ROOT)
        return when {
            "truehd" in c || "mlp" in c -> 100
            "eac3" in c || "atmos" in c -> 90
            "dts" in c -> 80
            "flac" in c -> 75
            "ac3" in c -> 60
            else -> 40
        }
    }

    private fun normalizeLang(code: String): String {
        val base = code.lowercase(Locale.ROOT).trim().substringBefore('-').substringBefore('_')
        return ISO_SYNONYMS[base] ?: base
    }

    private fun matchesLang(trackLang: String, preferred: String): Boolean {
        val a = normalizeLang(trackLang)
        val b = normalizeLang(preferred)
        return a.isNotBlank() && a == b
    }
}
