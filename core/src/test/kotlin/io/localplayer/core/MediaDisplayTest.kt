package io.localplayer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDisplayTest {
    @Test fun presentationRemovesExtensionsAndReleaseTechnicalSuffixes(){
        assertEquals("星际穿越 2014",mediaDisplayTitle("星际穿越.2014.2160p.WEB-DL.H265.AAC.mkv"))
        assertEquals("Show S01E02",mediaDisplayTitle("Show.S01E02.1080p.BluRay.x264-GROUP.MKV"))
        assertEquals("Blade Runner 2049 (2017)",mediaDisplayTitle("Blade.Runner.2049.(2017).1080p.mkv"))
    }
    @Test fun meaningfulNumbersBracketsAndUnicodeSurvive(){
        assertEquals("2001 太空漫游",mediaDisplayTitle("2001.太空漫游.mkv"))
        assertEquals("[导演剪辑版] 1917",mediaDisplayTitle("[导演剪辑版] 1917.mkv"))
        assertEquals("第1080次相遇",mediaDisplayTitle("第1080次相遇.mp4"))
        assertEquals("1080p",mediaDisplayTitle("1080p.mkv"))
        assertEquals("电影",mediaDisplayTitle("电影.webm"))
    }
}
