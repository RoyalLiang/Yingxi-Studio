package io.localplayer.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class Ui47RegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun formatIsWhiteAtUpperLeftWithoutSourceSizeOrBackground() {
        val media=MediaRef("format47","片匣.mkv","不应覆盖海报的信源",sizeBytes=2L*1024*1024*1024)
        val ground=Color(0xFF285143)
        for(scale in listOf(1f,1.8f)) {
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                    Box(Modifier.size(180.dp,270.dp).background(ground).testTag("format-fixture")){PosterLabels(media)}
                }
            }}}
            ui.onNodeWithTag("poster-labels:${media.key}").assertTextEquals("MKV")
            ui.onNodeWithText(media.sourceLabel).assertDoesNotExist()
            ui.onNodeWithText("2.0 GB").assertDoesNotExist()
            val bounds=ui.onNodeWithTag("poster-labels:${media.key}").getUnclippedBoundsInRoot()
            val poster=ui.onNodeWithTag("format-fixture").getUnclippedBoundsInRoot()
            assertEquals(8f,bounds.left.value-poster.left.value,.5f)
            assertEquals(8f,bounds.top.value-poster.top.value,.5f)
            val pixels=ui.onNodeWithTag("format-fixture").captureToImage().toPixelMap()
            var white=0
            for(y in 0 until pixels.height)for(x in 0 until pixels.width) {
                val c=pixels[x,y]
                if(c.red>.98f&&c.green>.98f&&c.blue>.98f)white++
                assertTrue("No dark label panel may cover the poster",c.red>=ground.red-.01f&&c.green>=ground.green-.01f&&c.blue>=ground.blue-.01f)
            }
            assertTrue("The format must contain visible white glyphs",white>4)
            assertEquals(ground.toArgb(),pixels[pixels.width-1,pixels.height-1].toArgb())
            capture("format-$scale")
        }
    }

    @Test fun realRecentPosterKeepsFormatWhiteAboveItsOtherLayers() {
        val media=MediaRef("real-poster47","片匣.mp4","不要显示的信源",sizeBytes=1024)
        val prefs=EnrichmentPreferences(ui.activity);val before=prefs.posters
        try {
            ui.runOnIdle{prefs.posters=false}
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                val d=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f)) {
                    Box(Modifier.width(240.dp).statusBarsPadding().padding(12.dp)) {
                        RecentFilm(HistoryEntry(media,1000,10000,0,false),false,false,false,{},{},{})
                    }
                }
            }}}
            val label=ui.onNodeWithTag("poster-labels:${media.key}",true).assertTextEquals("MP4")
            ui.onNodeWithText(media.sourceLabel).assertDoesNotExist()
            val bounds=label.fetchSemanticsNode().boundsInRoot
            val origin=IntArray(2);ui.runOnIdle{ui.activity.window.decorView.getLocationOnScreen(origin)}
            ui.waitUntil(5000) {
                val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    var white=0
                    for(y in bounds.top.toInt() until bounds.bottom.toInt())for(x in bounds.left.toInt() until bounds.right.toInt()) {
                        val pixel=image.getPixel(origin[0]+x,origin[1]+y)
                        if(android.graphics.Color.red(pixel)>250&&android.graphics.Color.green(pixel)>250&&android.graphics.Color.blue(pixel)>250)white++
                    }
                    white>4
                }finally{image.recycle()}
            }
            capture("real-home-poster")
        }finally{ui.runOnIdle{prefs.posters=before}}
    }

    @Test fun directoriesAndUnknownFormatsHaveNoOverlay() {
        for((directory,title) in listOf(true to "片匣.mp4",false to "片匣.mkv.part",false to "没有扩展名")) {
            val media=MediaRef("unmarked47",title,"信源",sizeBytes=1024)
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{Box(Modifier.size(160.dp)){PosterLabels(media,directory)}}}}
            ui.onNodeWithTag("poster-labels:${media.key}").assertDoesNotExist()
        }
    }

    @Test fun diaryArchiveFooterFitsItsTextAndStillOpens() {
        var opened=0
        for(scale in listOf(1f,1.8f)) {
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                    Box(Modifier.width(320.dp)){StationNotice("映汐放映站 · 值班札记","拾荒者日记","把旧事夹回档案架。",Modifier.testTag("diary47"),onArchives={opened++})}
                }
            }}}
            val footer=ui.onNodeWithTag("home-archives").assertHasClickAction().getUnclippedBoundsInRoot()
            val text=ui.onNodeWithText("档案室").getUnclippedBoundsInRoot()
            val quote=ui.onNodeWithTag("home-admin-quote").getUnclippedBoundsInRoot()
            val card=ui.onNodeWithTag("diary47").getUnclippedBoundsInRoot()
            assertTrue("Footer follows text height",(footer.bottom-footer.top).value<=(text.bottom-text.top).value+4.5f)
            assertTrue("Footer has a compact upper gap",footer.top.value-quote.bottom.value<=26f)
            assertTrue("Footer has a compact lower gap",card.bottom.value-footer.bottom.value<=14.5f)
            ui.onNodeWithTag("home-archives").performClick()
            if(scale==1f)ui.onNodeWithTag("diary47").performTouchInput {
                click(androidx.compose.ui.geometry.Offset(((footer.left+footer.right)/2-card.left).toPx(),(footer.bottom-card.top+6.dp).toPx()))
            }
            capture("diary-$scale")
        }
        assertEquals(3,opened)
    }

    @Test fun chroniclesLiveOnlyInArchivesInBothLanguagesAtLargeText() {
        val world=StationWorld.load(ui.activity)
        assertTrue(world.dailyLines.single{it.id=="diary_022"}.text.en.isNotBlank())
        val latest=world.chronicles.last()
        for(language in listOf("zh","en")) {
            fun show(embedded:Boolean) {
                ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                    val d=LocalDensity.current
                    val c=Configuration(ui.activity.resources.configuration).apply{setLocale(Locale.forLanguageTag(language))}
                    CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f),LocalConfiguration provides c) {
                        Box(Modifier.width(320.dp).fillMaxHeight()){StationWorldPage(embedded){}}
                    }
                }}}
            }
            show(false)
            ui.onNodeWithTag("world-chronicles-title").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText(latest.version).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText(latest.text.inLanguage(language)).assertExists()
            capture("archive-$language")
            show(true)
            ui.onNodeWithTag("world-chronicles").assertDoesNotExist()
            ui.onNodeWithTag("world-map").assertExists()
            ui.onNodeWithTag("world-chapters").assertExists()
        }
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try{File(ui.activity.cacheDir,"ui47-$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }
}
