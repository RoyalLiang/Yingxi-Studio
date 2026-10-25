package io.localplayer.app

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackLanguageRegressionTest {
    private fun label(format:Format,fallback:String):String {
        val method=Class.forName("io.localplayer.app.PlaybackOptionsKt").getDeclaredMethod("trackLabel",Format::class.java,String::class.java)
        method.isAccessible=true
        return method.invoke(null,format,fallback) as String
    }
    @Test fun languageLeadsEvenWhenTheContainerUsesAGenericTitle() {
        val audio=Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("eng").setLabel("Audio 1").build()
        assertTrue(label(audio,"音轨 1").startsWith("英语"))
        val caption=Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("chi").setLabel("Subtitle 2").build()
        assertTrue(label(caption,"字幕 2").startsWith("中文"))
    }
    @Test fun sidecarDialectAndScriptArePreserved() {
        assertEquals("yue",sidecarLanguage("剧集.S01E01.粤语.ac3"))
        assertEquals("zh-Hant",sidecarLanguage("剧集.S01E01.cht.ass"))
        assertEquals("fr",sidecarLanguage("Film.fre.srt"))
        assertEquals("zh",sidecarLanguage("Film.Chinese.srt"))
        assertEquals("zh-Hans",sidecarLanguage("剧集.简体中文.srt"))
        assertEquals("cmn",sidecarLanguage("Film.国语配音.mka"))
    }
    @Test fun missingLanguageIsExplicitAndDistinctTracksKeepTheirNumbers() {
        val format=Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        assertTrue(label(format,"音轨 2").startsWith("未标注语种"))
        assertTrue(label(format,"音轨 2").contains("音轨 2"))
        assertNull(sidecarLanguage("普通的文件名.mkv"))
    }
}
