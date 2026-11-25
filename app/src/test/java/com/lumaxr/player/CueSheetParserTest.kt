package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.music.CueSheetParser
import org.junit.Test

class CueSheetParserTest {

    @Test
    fun stripsBomAndParsesQuotedFileAnd75FpsIndex() {
        val cue = "\uFEFF" + """
            PERFORMER "Pink Floyd"
            TITLE "Dark Side of the Moon"
            FILE "01 - Complete Album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Speak to Me"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Breathe"
                INDEX 00 01:08:00
                INDEX 01 01:10:37
        """.trimIndent()

        val tracks = CueSheetParser.parse(cue, "/music/dsotm", audioDurationMs = 240_000L)
        assertThat(tracks).hasSize(2)
        assertThat(tracks[0].filePath).isEqualTo("/music/dsotm/01 - Complete Album.flac")
        assertThat(tracks[0].index01Ms).isEqualTo(0L)
        assertThat(tracks[1].title).isEqualTo("Breathe")
        // 1m 10s + 37/75s = 70000 + 493 = 70493ms
        assertThat(tracks[1].index01Ms).isEqualTo(70_493L)
        assertThat(tracks[0].endMs).isEqualTo(70_493L)
        assertThat(tracks[1].endMs).isEqualTo(240_000L)
    }
}
