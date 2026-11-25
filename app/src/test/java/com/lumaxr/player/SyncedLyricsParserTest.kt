package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.lyrics.LyricAgentRole
import com.lumaxr.player.lyrics.WordSyncedLyricsParser
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncedLyricsParserTest {

    @Test
    fun parsesTtmlSyllablesDuetsBackgroundVocalsAndTranslations() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/><songwriter>Test Writer</songwriter></metadata></head>
              <body>
                <div>
                  <p begin="00:01.000" end="00:03.000" ttm:agent="v1">
                    <span begin="00:01.000" end="00:01.800">Hello </span>
                    <span begin="00:01.800" end="00:02.900">world</span>
                    <span ttm:role="x-bg" begin="00:02.000" end="00:02.950">(ooh)</span>
                    <span ttm:role="x-translation" xml:lang="en">Greetings</span>
                  </p>
                  <p begin="00:08.500" end="00:10.500" ttm:agent="v2">
                    <span begin="00:08.500" end="00:10.500">Reply</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val doc = WordSyncedLyricsParser.parseAuto(ttml, "AMLL TTML DB")
        assertThat(doc.isSyllableSynced).isTrue()
        assertThat(doc.lines).hasSize(2)
        assertThat(doc.lines[0].agent).isEqualTo(LyricAgentRole.PRIMARY_V1)
        assertThat(doc.lines[0].words).hasSize(2)
        assertThat(doc.lines[0].backgroundVocalWords.first().text).contains("ooh")
        assertThat(doc.lines[0].translation).isEqualTo("Greetings")
        assertThat(doc.lines[1].agent).isEqualTo(LyricAgentRole.DUET_V2)
        // Interlude gap > 4 seconds between line 0 end (3000ms) and line 1 start (8500ms)
        assertThat(doc.lines[1].startMs - doc.lines[0].endMs).isGreaterThan(4000L)
        assertThat(doc.songwriters).contains("Test Writer")
    }

    @Test
    fun parsesEnhancedLrcWordTagsAccurately() {
        val elrc = "[00:10.50]<00:10.50>Neon <00:11.20>lights <00:12.00>glow"
        val doc = WordSyncedLyricsParser.parseAuto(elrc)
        assertThat(doc.sourceFormat).isEqualTo("ELRC")
        assertThat(doc.isSyllableSynced).isTrue()
        assertThat(doc.lines).hasSize(1)
        assertThat(doc.lines[0].words).hasSize(3)
        assertThat(doc.lines[0].words[1].startMs).isEqualTo(11_200L)
    }
}
