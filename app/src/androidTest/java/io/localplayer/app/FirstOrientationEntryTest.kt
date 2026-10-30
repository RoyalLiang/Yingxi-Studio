package io.localplayer.app

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class FirstOrientationEntryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun assertMovingFrame(name:String){
        if(ui.onAllNodesWithTag("video-definition").fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.2f,height*.3f))}
        try{ui.waitUntil(3000){ui.onAllNodesWithTag("video-definition").fetchSemanticsNodes().isEmpty()&&ui.onAllNodesWithTag("playback-buffering").fetchSemanticsNodes().isEmpty()}}
        catch(failure:Throwable){
            println("FRAME_WAIT $name controls=${ui.onAllNodesWithTag("video-definition").fetchSemanticsNodes().size} buffering=${ui.onAllNodesWithTag("playback-buffering").fetchSemanticsNodes().size} metrics=${PlaybackMetrics.snapshot()}")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->File(ui.activity.cacheDir,"frame-wait-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            throw failure
        }
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun pixels():IntArray {
            val bitmap=checkNotNull(automation.takeScreenshot())
            File(ui.activity.cacheDir,"frame-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            val samples=ArrayList<Int>()
            for(y in bitmap.height/5 until bitmap.height*4/5 step 8)for(x in bitmap.width/5 until bitmap.width*4/5 step 8)samples+=bitmap.getPixel(x,y)
            bitmap.recycle();return samples.toIntArray()
        }
        val before=pixels()
        assertTrue("Real colorful video pixels must be rendered",before.count{maxOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))-minOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))>40}>before.size/20)
        var moving=false
        repeat(5){if(!moving){Thread.sleep(300);val after=pixels();moving=after.size==before.size&&after.indices.count{after[it]!=before[it]}>before.size/100}}
        assertTrue("Consecutive video frames must advance with controls hidden",moving)
        println("MOVING_FRAME $name samples=${before.size}")
    }

    @Test fun slowNetworkPreparationKeepsPauseAndRotationReachable(){
        val context=ui.activity.application
        val file=File(context.cacheDir,"direction-portrait.mp4");check(file.isFile)
        val opened=AtomicReference<PlaybackActivity>()
        fun capture(){androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->
            File(context.cacheDir,"entry-controls.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        val callback=ActivityLifecycleCallback{activity,stage->if(activity is PlaybackActivity&&stage==Stage.CREATED)opened.set(activity)}
        val monitor=ActivityLifecycleMonitorRegistry.getInstance();main{monitor.addLifecycleCallback(callback)}
        DavFixture(file,2,6000L).use{server->
            val source=io.localplayer.core.Source(label="慢首包",protocol=io.localplayer.core.Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val store=SourceStore(context);store.save(source,server.password,true)
            val model=main{ViewModelProvider(ui.activity)[LibraryViewModel::class.java]}
            try{
                main{model.open(source)}
                ui.waitUntil(10000){model.source?.id==source.id&&!model.busy&&model.entries.isNotEmpty()}
                main{model.play(model.entries.first{it.isVideo})}
                ui.waitUntil(5000){opened.get()!=null&&main{opened.get().hasWindowFocus()}}
                ui.waitUntil(2000){ui.onAllNodesWithContentDescription("旋转屏幕").fetchSemanticsNodes().isNotEmpty()}
                assertFalse("The fixture must still be waiting for video metadata",main{opened.get().entryReady})
                ui.onNodeWithContentDescription("旋转屏幕").assertIsDisplayed().performTouchInput{click()}
                ui.waitUntil(3000){main{opened.get().resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}}
                ui.onNodeWithContentDescription("暂停").assertIsDisplayed().performTouchInput{click()}
                ui.onNodeWithContentDescription("播放").assertIsDisplayed()
                capture()
                ui.onNodeWithContentDescription("返回").performTouchInput{click()}
                ui.waitUntil(3000){main{opened.get().isDestroyed}}
            }finally{
                if(opened.get()?.isDestroyed!=true)capture()
                main{opened.get()?.finish();monitor.removeLifecycleCallback(callback)};store.delete(source.id)
            }
        }
    }
    @Test fun firstVisibleNetworkPortraitUsesItsActualShape()=firstDraw("direction-portrait.mp4",Configuration.ORIENTATION_PORTRAIT)
    @Test fun firstVisibleNetworkLandscapeUsesItsActualShape()=firstDraw("direction-landscape.mp4",Configuration.ORIENTATION_LANDSCAPE)
    @Test fun compatibilityVideoStillProducesAFrameAfterDirectionAdmission()=firstDraw("compat-wmv.wmv",Configuration.ORIENTATION_LANDSCAPE)
    @Test fun compatibilityVideoStillProducesAFrameFromFastSource()=firstDraw("compat-wmv.wmv",Configuration.ORIENTATION_LANDSCAPE,headerDelayMs=0L)
    @Test fun rotatedNetworkVideoUsesDisplayShape()=firstDraw("direction-portrait-rotated.mp4",Configuration.ORIENTATION_PORTRAIT)
    @Test fun stalePortraitHintCannotExposeTheWrongLandscapeLayout()=firstDraw("direction-landscape.mp4",Configuration.ORIENTATION_LANDSCAPE,true)
    private fun firstDraw(name:String,expected:Int,staleHint:Boolean=false,headerDelayMs:Long=350L){
        val context=ui.activity.application
        val file=File(context.cacheDir,name);check(file.isFile)
        val first=AtomicReference<Int>()
        val firstMetadata=AtomicReference<Int>()
        val expectedMedia=AtomicReference<MediaRef>()
        val opened=AtomicReference<PlaybackActivity>()
        val callback=ActivityLifecycleCallback{activity,stage->
            if(activity is PlaybackActivity&&stage==Stage.CREATED){
                opened.set(activity)
                activity.window.decorView.viewTreeObserver.addOnDrawListener{
                    if(activity.entryReady&&first.get()==null){
                        expectedMedia.get()?.let{firstMetadata.set(PlaybackOrientationProbe.cached(context,it))}
                    first.set(activity.resources.configuration.orientation)
                    }
                }
            }
        }
        val monitor=ActivityLifecycleMonitorRegistry.getInstance()
        main{monitor.addLifecycleCallback(callback)}
        DavFixture(file,2,headerDelayMs,listOf("01-海边.${file.extension}","02-落日.${file.extension}")).use{server->
            val source=io.localplayer.core.Source(label="首次方向",protocol=io.localplayer.core.Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val store=SourceStore(context);store.save(source,server.password,true)
            val model=main{ViewModelProvider(ui.activity)[LibraryViewModel::class.java]}
            try{
                main{model.open(source)}
                ui.waitUntil(10000){model.source?.id==source.id&&!model.busy&&model.entries.isNotEmpty()}
                val entry=model.entries.first{it.isVideo}
                expectedMedia.set(MediaRef("${source.id}:${entry.path}",entry.name,source.label,source.id,entry.path,sizeBytes=entry.size,location=mediaLocation(source)))
                if(staleHint)PlaybackOrientationProbe.remember(context,expectedMedia.get(),android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
                main{model.play(model.entries.first{it.isVideo})}
                ui.waitUntil(15000){first.get()!=null}
                assertEquals("The first visible player layout must use the real stream shape",expected,first.get())
                assertEquals("Real metadata must exist before showing the player layout",if(expected==Configuration.ORIENTATION_PORTRAIT)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,firstMetadata.get())
                assertTrue("The real video stream must have been read",server.positions.isNotEmpty())
                ui.waitUntil(20000){PlaybackMetrics.snapshot().firstFrameMs!=null}
                assertMovingFrame(name)
            if(name.endsWith(".wmv")){
                ui.onNodeWithTag("playback-gesture-surface").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.2f,height*.3f))}
                ui.onNodeWithContentDescription("暂停").performTouchInput{click()}
                ui.onNodeWithContentDescription("播放").assertIsDisplayed()
                ui.onNodeWithContentDescription("旋转屏幕").performTouchInput{click()}
                ui.waitUntil(3000){main{opened.get().resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}}
                ui.onNodeWithContentDescription("旋转屏幕").performTouchInput{click()}
                ui.waitUntil(3000){main{opened.get().resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}}
                ui.onNodeWithContentDescription("播放").performTouchInput{click()}
            }
            println("FIRST_ORIENTATION expected=$expected actual=${first.get()} firstFrameMs=${PlaybackMetrics.snapshot().firstFrameMs} requests=${server.positions.size}")
            }finally{main{opened.get()?.finish();monitor.removeLifecycleCallback(callback)};store.delete(source.id)}
        }
    }
    @Test fun backCancelsAnEntryWhoseFirstFrameIsStillWaiting(){
        val context=ui.activity.application
        val file=File(context.cacheDir,"direction-portrait.mp4");check(file.isFile)
        val opened=AtomicReference<PlaybackActivity>()
        val callback=ActivityLifecycleCallback{activity,stage->if(activity is PlaybackActivity&&stage==Stage.CREATED)opened.set(activity)}
        val monitor=ActivityLifecycleMonitorRegistry.getInstance();main{monitor.addLifecycleCallback(callback)}
        DavFixture(file,2,3000L).use{server->
            val source=io.localplayer.core.Source(label="方向取消",protocol=io.localplayer.core.Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val store=SourceStore(context);store.save(source,server.password,true)
            val model=main{ViewModelProvider(ui.activity)[LibraryViewModel::class.java]}
            try{
                main{model.open(source)};ui.waitUntil(10000){model.source?.id==source.id&&!model.busy&&model.entries.isNotEmpty()}
                main{model.play(model.entries.first{it.isVideo})};ui.waitUntil(5000){opened.get()!=null&&main{opened.get().hasWindowFocus()}}
                assertFalse(main{opened.get().entryReady})
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.waitUntil(3000){main{opened.get().isDestroyed}}
            }finally{main{opened.get()?.finish();monitor.removeLifecycleCallback(callback)};store.delete(source.id)}
        }
    }

}
