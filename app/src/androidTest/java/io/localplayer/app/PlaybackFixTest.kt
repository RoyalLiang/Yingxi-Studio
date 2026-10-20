package io.localplayer.app

import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackFixTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun timelineThumbAndTrackShareCenterAtEveryPositionAndFontSize() {
        for(font in listOf(1f,1.8f))for(position in listOf(0L,50000L,100000L)){
            ui.activity.runOnUiThread{ui.activity.setContent{PlayerTheme{
                CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density,font)){
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101113))){PlayerTimeline(position,100000,80000,false,true,{}, {})}
                }
            }}}
            val thumb=ui.onNodeWithTag("progress-thumb",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val track=ui.onNodeWithTag("progress-track",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertEquals("Thumb center must lie on track center",track.center.y,thumb.center.y,.5f)
            val pixels=ui.onNodeWithTag("playback-progress").captureToImage().toPixelMap()
            val whiteY=mutableListOf<Int>();val accentY=mutableListOf<Int>()
            for(y in 0 until pixels.height)for(x in 0 until pixels.width){
                val c=pixels[x,y]
                if(c.red>.9f&&c.green>.9f&&c.blue>.9f)whiteY+=y
                if(c.red>.9f&&c.green in .4f.. .8f&&c.blue in .5f.. .85f)accentY+=y
            }
            assertTrue("Visible thumb is present",whiteY.isNotEmpty())
            if(accentY.isNotEmpty())assertEquals("Painted centers match",accentY.average(),whiteY.average(),1.0)
        }
    }
    @Test fun trackPanelKeepsSpeedSeparateAtLargeFont() {
        lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        val prefs=PlaybackPreferences(context);val subtitlesBefore=prefs.subtitlesDisabled
        try{
            ui.activity.runOnUiThread{ui.activity.setContent{PlayerTheme{
                CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density,1.8f)){
                    PlaybackOptions(player,{}, {})
                }
            }}}
            ui.onNodeWithText("音轨与字幕").assertIsDisplayed()
            ui.onNodeWithText("播放速度").assertDoesNotExist()
            ui.onNodeWithText("1.5×").assertDoesNotExist()
            ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("关闭字幕"))
            ui.onNodeWithText("关闭字幕").performClick().assertIsSelected()
            ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("载入字幕文件"))
            ui.onNodeWithText("载入字幕文件").assertIsDisplayed()
        }finally{prefs.subtitlesDisabled=subtitlesBefore;instrumentation.runOnMainSync{player.release()}}
    }
    @Test fun narrowDirectoryKeepsFavoriteAndGroupLabelsOnOneLine() {
        val source=io.localplayer.core.Source(label="一个很长的来源目录名称",protocol=io.localplayer.core.Protocol.WEBDAV,host="nas.invalid",root="/dav")
        val connector=object:io.localplayer.core.Connector {
            override fun connect(source:io.localplayer.core.Source,credentials:io.localplayer.core.Credentials,cancellation:io.localplayer.core.Cancellation)=object:io.localplayer.core.RemoteSession {
                override fun list(path:String)=emptyList<io.localplayer.core.Entry>()
                override fun open(path:String):io.localplayer.core.RemoteFile=error("No media needed")
                override fun close(){}
            }
        }
        lateinit var vm:LibraryViewModel
        val owner=androidx.lifecycle.ViewModelStore()
        instrumentation.runOnMainSync{
            vm=LibraryViewModel(context.applicationContext as android.app.Application,connector)
            owner.put("narrow-directory",vm)
            vm.open(source)
        }
        try{
            ui.waitUntil(5000){vm.source!=null}
            ui.activity.runOnUiThread{ui.activity.setContent{PlayerTheme{
                CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density,1.8f)){
                    Box(Modifier.width(androidx.compose.ui.unit.Dp(240f)).fillMaxHeight()){LibraryScreen(vm,{}, {})}
                }
            }}}
            ui.onNodeWithText("WEBDAV · 文件浏览").assertDoesNotExist()
            ui.onNodeWithText("收藏文件夹").assertIsDisplayed()
            val favorite=ui.onNodeWithText("收藏文件夹").fetchSemanticsNode().boundsInRoot
            ui.onNodeWithTag("directory-actions").performScrollToNode(hasText("分组"))
            val group=ui.onNodeWithText("分组").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Actions share one row",favorite.center.y,group.center.y,1f)
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithText("收藏文件夹",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertEquals("Favorite label remains one line",1,layouts.single().lineCount)
        }finally{instrumentation.runOnMainSync{owner.clear()}}
    }

    @Test fun localSeekPreviewAndArtworkActuallyDecode()=runBlocking {
        val file=File(context.getExternalFilesDir(null),"dav-sample.mp4")
        assertTrue(file.isFile)
        val uri=android.net.Uri.fromFile(file)
        val previews=PreviewFrames(context)
        try{
            for(time in listOf(0L,15000L,60000L,15000L)){
                val bitmap=withTimeout(12000){previews.get(uri,time)}
                assertNotNull("Actual frame at $time",bitmap)
                assertTrue(bitmap!!.width>0&&bitmap.height>0)
            }
        }finally{previews.close()}
        assertNull(previews.get(uri,0))
        val media=MediaRef("preview-fix-${System.nanoTime()}","Sample","Local",localUri=uri.toString())
        try{assertNotNull(withTimeout(22000){MediaThumbnails.load(context,media)})}
        finally{MediaThumbnails.remove(context,media)}
    }
}
