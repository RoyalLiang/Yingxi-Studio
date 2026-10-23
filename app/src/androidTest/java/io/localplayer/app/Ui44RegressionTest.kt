package io.localplayer.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.localplayer.core.Entry
import io.localplayer.core.Protocol
import io.localplayer.core.Source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Ui44RegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    private val source=Source(label="Fixture",protocol=Protocol.WEBDAV,host="example.invalid")

    @Test fun sourcePosterTitlesMetadataAndNextRowAlignWithMixedTitleLengths() {
        val prefs=EnrichmentPreferences(ui.activity);val previous=prefs.posters
        try {
            prefs.posters=false
            val entries=listOf(Entry("短片","/short",true,0),Entry("Long.series.episode.with.a.very.long.title.mp4","/long.mp4",false,1024),Entry("下一排","/next",true,0))
            for(width in listOf(393f,310.1f,452f)) {
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{Box(Modifier.width(width.dp).fillMaxHeight()){
                SourceDirectory(entries,source,null,false,null,{},{})
            }}}}
            val first=ui.onNodeWithTag("shelter-title:/short",true).getUnclippedBoundsInRoot()
            val second=ui.onNodeWithTag("shelter-title:/long.mp4",true).getUnclippedBoundsInRoot()
            assertEquals(first.top.value,second.top.value,.5f)
            assertEquals(first.bottom.value,second.bottom.value,.5f)
            ui.onNodeWithTag("shelter-info:/short",true).assertDoesNotExist()
            ui.onNodeWithTag("shelter-info:/long.mp4",true).assertDoesNotExist()
            assertEquals(ui.onNodeWithTag("shelter-entry:/short").getUnclippedBoundsInRoot().let{it.bottom.value-it.top.value},
                ui.onNodeWithTag("shelter-entry:/long.mp4").getUnclippedBoundsInRoot().let{it.bottom.value-it.top.value},.5f)
            val card=ui.onNodeWithTag("shelter-entry:/short").getUnclippedBoundsInRoot()
            val imageHeight=first.top.value-card.top.value-7f
            assertEquals((card.right.value-card.left.value)*1.5f,imageHeight,1.5f)
            capture("source-grid-$width")
            }
        } finally {prefs.posters=previous}
    }

    @Test fun sourceDirectoriesHideSidecarsAndPartialDownloads() {
        val entries=listOf(Entry("Elemental.2023.nfo","/film.nfo",false,2048),Entry("疯狂元素城.jpg","/poster.jpg",false,3000),Entry("疯狂元素城.mkv.part","/movie.mkv.part",false,4000))
        assertFalse(entries.last().isVideo)
        for(scale in listOf(1f,1.8f)) {
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                    Box(Modifier.width(320.dp).fillMaxHeight()){SourceDirectory(entries,source,null,false,null,{fail("A sidecar cannot open")},{})}
                }
            }}}
            entries.forEach{ui.onNodeWithText(it.name).assertDoesNotExist()}
            ui.onNodeWithText("非视频文件").assertDoesNotExist()
        }
    }

    @Test fun findCardsHaveEqualTitleAreasAndOnlyFormatOnPosters() {
        val a=StationFinding(MediaRef("fixture-short","短片.mp4","private-source-label"),0)
        val b=StationFinding(MediaRef("fixture-long","A very long film title that needs two lines.mp4","private-source-label"),0)
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{Row(Modifier.width(393.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Box(Modifier.weight(1f)){FindingCard(a){}}
            Box(Modifier.weight(1f)){FindingCard(b){}}
        }}}}
        val one=ui.onNodeWithTag("found-title:${a.media.key}",true).getUnclippedBoundsInRoot()
        val two=ui.onNodeWithTag("found-title:${b.media.key}",true).getUnclippedBoundsInRoot()
        assertEquals(one.top.value,two.top.value,.5f);assertEquals(one.bottom.value,two.bottom.value,.5f)
        ui.onAllNodesWithText("private-source-label",useUnmergedTree=true).assertCountEquals(0)
        ui.onAllNodesWithText("MP4",useUnmergedTree=true).assertCountEquals(2)
        capture("finds")
    }

    @Test fun audioChoicesAreInlinePersistAndRemainReachableAtLargeText() {
        val prefs=PlaybackPreferences(ui.activity);val previous=prefs.audioLanguage
        try {
            for(scale in listOf(1f,1.8f)) {
                ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                        Column(Modifier.width(if(scale==1f)393.dp else 320.dp).fillMaxHeight().verticalScroll(rememberScrollState())){StationAudioSettings();EnrichmentSettings();CloudPlaybackSettings();StationAtmosphereSettings()}
                    }
                }}}
                for((value,label) in listOf("en" to "英文","zh" to "中文","follow" to "跟随")) {
                    ui.onNodeWithTag("audio-language-$value").performScrollTo().performClick().assertIsSelected()
                    assertEquals(if(value=="follow")null else value,PlaybackPreferences(ui.activity).audioLanguage)
                    ui.onNodeWithText(label).assertExists()
                }
                if(scale==1f) {
                    val label=ui.onNodeWithText("音轨偏好").getUnclippedBoundsInRoot()
                    val options=ui.onNodeWithTag("audio-language-options").getUnclippedBoundsInRoot()
                    assertTrue(options.left>=label.right)
                }
                for(text in listOf("音轨与字幕","海报与字幕","据点氛围","阿里云盘原画使用；下次打开影片时生效。","当天首次进入时出现"))ui.onNodeWithText(text).assertDoesNotExist()
                ui.onNodeWithText("信号塔 · 采集频段").performScrollTo().assertIsDisplayed()
            }
        } finally {prefs.audioLanguage=previous}
    }

    @Test fun diaryKeepsItsRecordAcrossInstancesAndRotatesAuthorsWithoutRepeats() {
        val name="ui44-diary-${UUID.randomUUID()}"
        try {
            val prefs=StationPreferences(ui.activity,name)
            val first=prefs.diary(now=1_000_000)
            assertEquals(first,StationPreferences(ui.activity,name).diary(now=1_000_001))
            assertEquals(first,prefs.diary(now=2_799_999))
            val expired=prefs.diary(now=2_800_000)
            assertNotEquals(first.author,expired.author)
            val authors=mutableListOf(expired.author)
            repeat(8){authors+=prefs.diary(now=2_800_001+it.toLong(),force=true).author}
            assertEquals(StationWorld.load(ui.activity).people.map{it.id}.toSet(),authors.toSet())
            authors.zipWithNext().forEach{assertNotEquals(it.first,it.second)}
            val line=prefs.diary()
            assertEquals(line.author,prefs.diaryAuthor(line).id)
            assertEquals(line.text.zh,prefs.dailyLine("zh"))
            assertEquals(line.text.en,prefs.dailyLine("en"))
        } finally {ui.activity.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()}
    }
    @Test fun tappingDiaryChangesAuthorPortraitAndQuoteTogether() {
        val prefs=StationPreferences(ui.activity)
        val before=prefs.diary()
        ui.onNodeWithTag("home-admin-title",true).assertDoesNotExist()
        ui.onNodeWithTag("home-diary-refresh").performClick()
        val after=prefs.diary()
        assertNotEquals(before.author,after.author)
        val language=ui.activity.resources.configuration.locales[0].language
        ui.onNodeWithContentDescription(prefs.diaryAuthor(after).name.inLanguage(language)+"，换一则札记",useUnmergedTree=true).assertExists()
        ui.onNodeWithTag("home-admin-quote",true).assertTextEquals(after.text.inLanguage(language))
        ui.onNodeWithText("人物与来信").assertDoesNotExist()
        capture("home-diary")
    }

    @Test fun signalTowerUsesTheSameGapAsOtherSettingsModules() {
        ui.onNodeWithContentDescription("站务").performClick()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("cloud-playback-settings"))
        val before=ui.onNodeWithTag("enrichment-settings").getUnclippedBoundsInRoot()
        val tower=ui.onNodeWithTag("cloud-playback-settings").getUnclippedBoundsInRoot()
        assertEquals(12f,tower.top.value-before.bottom.value,.5f)
        ui.onNodeWithText("信号塔 · 采集频段").assertIsDisplayed()
        capture("settings")
    }

    private fun capture(name:String) {
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.cacheDir,"ui44-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

}
