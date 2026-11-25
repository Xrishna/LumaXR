package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.pro.MediaStreamTrack
import com.lumaxr.player.pro.TrackLanguageRuleEngine
import org.junit.Test

class TrackLanguageRuleEngineTest {

    private val engine = TrackLanguageRuleEngine()

    @Test
    fun selectsJapaneseAudioAndEnglishAssSignsForAnimeFolder() {
        val rule = engine.defaultRuleForFolder("/storage/emulated/0/Movies/Anime/Frieren")
        val tracks = listOf(
            MediaStreamTrack(1, "audio", "eng", "English Dub", "aac"),
            MediaStreamTrack(2, "audio", "jpn", "Japanese Original", "flac"),
            MediaStreamTrack(3, "sub", "eng", "English Dialogue", "subrip"),
            MediaStreamTrack(4, "sub", "eng", "Signs & Songs", "ass"),
        )
        val rec = engine.selectTracks(tracks, rule)
        assertThat(rec.audioTrackId).isEqualTo(2)
        assertThat(rec.subtitleTrackId).isEqualTo(4)
    }

    @Test
    fun selectsTrueHdAndDisablesSubsForUhdRemuxFolder() {
        val rule = engine.defaultRuleForFolder("/NAS/UHD Remux/Dune")
        val tracks = listOf(
            MediaStreamTrack(1, "audio", "eng", "AC-3 Compatibility", "ac3"),
            MediaStreamTrack(2, "audio", "eng", "TrueHD Atmos 7.1", "truehd"),
            MediaStreamTrack(3, "sub", "eng", "English SDH", "pgs"),
        )
        val rec = engine.selectTracks(tracks, rule)
        assertThat(rec.audioTrackId).isEqualTo(2)
        assertThat(rec.subtitleTrackId).isNull()
    }

    @Test
    fun matchesIso639TwoLetterJaTagForJapaneseRule() {
        val rule = engine.defaultRuleForFolder("/storage/emulated/0/Movies/Anime/Ghibli")
        val tracks = listOf(
            MediaStreamTrack(1, "audio", "en", "English", "aac"),
            MediaStreamTrack(2, "audio", "ja", "Japanese", "flac"),
        )
        val rec = engine.selectTracks(tracks, rule)
        assertThat(rec.audioTrackId).isEqualTo(2)
    }
}
