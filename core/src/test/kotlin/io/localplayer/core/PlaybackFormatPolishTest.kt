package io.localplayer.core

import kotlin.test.*

class PlaybackFormatPolishTest {
    @Test fun sidecarsRecognizeScriptTagsAndAccessibilityVariantsWithoutCrossingEditions() {
        val names=listOf("Film.2024.zh-Hans.srt","Film.2024.en.sdh.srt","Film.2024.zh-Hant.ass","Film.2024.Directors.Cut.srt","Film.2024.S01E02.srt")
        val candidates=SubtitleSidecars.candidates("Film.2024.mkv",names,false)
        assertEquals(setOf(names[0],names[1],names[2]),candidates.toSet())
        assertEquals(names[0],candidates.first())
    }
    @Test fun externalAudioMatchesTheSameEditionOnly() {
        val names=listOf("Film.2024.en.m4a","Film.2024.国语.5.1.mka","Film.2024.flac","Film.2024.Directors.Cut.mka","Another.Film.2024.mp3","Film.2024.dts")
        assertEquals(names.take(3).toSet(),SubtitleSidecars.audioCandidates("Film.2024.mkv",names).toSet())
    }

}
