package io.localplayer.core

import org.junit.Assert.*
import org.junit.Test

class ArchiveIdentityTest {
    @Test fun preservesEpisodeAndSeasonWithoutChangingFileIdentity() {
        val value=ArchiveIdentity.parse("Show.S02E03.2160p.mkv")
        assertEquals("Show",value.title);assertEquals(2,value.season);assertEquals(3,value.episode);assertEquals("剧集",value.category)
    }
    @Test fun numberedFilesInSeasonFoldersInheritTheShow() {
        val value=ArchiveIdentity.parse("02.mkv","影视/漫长的季节/Season 2/02.mkv")
        assertEquals("漫长的季节",value.title);assertEquals(2,value.season);assertEquals(2,value.episode)
    }
    @Test fun chineseEpisodeNamesGroupUnderOneShow() {
        assertEquals(ArchiveIdentity.parse("节目第2集.mkv").title,ArchiveIdentity.parse("节目第3集.mkv").title)
        assertEquals("综艺",ArchiveIdentity.parse("节目第3期.mp4").category)
    }
    @Test fun sidecarsNeverMatchAnotherEditionOrEpisode() {
        val matches=SubtitleSidecars.candidates("Show.S01E02.2160p.mkv",listOf("Show.S01E02.2160p.chs.srt","Show.S01E03.2160p.srt","Show.S01E02.1080p.srt","Show.S01E02.2160p.directors-cut.srt","Show.S01E02.2160p.en.vtt"))
        assertEquals(listOf("Show.S01E02.2160p.chs.srt","Show.S01E02.2160p.en.vtt"),matches)
    }
    @Test fun bilingualPreferenceIsDeterministicAndValidationRejectsHtml() {
        val names=listOf("Film.chs.srt","Film.bilingual.ass","Film.en.srt")
        assertEquals("Film.bilingual.ass",SubtitleSidecars.candidates("Film.mkv",names,true).first())
        assertThrows(IllegalArgumentException::class.java){ShooterSubtitles().normalizeSidecar("<html>not captions</html>".toByteArray(),"srt")}
    }
    @Test fun sidecarCharsetIsNormalizedBeforeAttachment() {
        val text="1\n00:00:01,000 --> 00:00:02,000\n你好世界\n"
        val bytes=text.toByteArray(java.nio.charset.Charset.forName("GB18030"))
        assertTrue(ShooterSubtitles().normalizeSidecar(bytes,"srt").toString(Charsets.UTF_8).contains("你好世界"))
    }
}
