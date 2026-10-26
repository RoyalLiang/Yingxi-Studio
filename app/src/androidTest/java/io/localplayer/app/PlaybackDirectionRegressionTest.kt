package io.localplayer.app

import android.content.ComponentName
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDirectionRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T { var value:Result<T>?=null;ui.runOnUiThread{value=runCatching(block)};return value!!.getOrThrow() }
    private fun ready(c:MediaController)=ui.waitUntil(15000){main{c.playbackState==Player.STATE_READY&&c.videoSize.width>0}}
    private val context get()=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private fun playback():PlaybackActivity?=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()}
    private fun launch(target:PlayTarget):PlaybackActivity {
        main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
        ui.waitUntil(10000){playback()!=null};return playback()!!
    }
    private fun direction(expected:Int)=ui.waitUntil(10000){playback()?.resources?.configuration?.orientation==expected}
    private fun target():PlayTarget {
        val items=listOf("direction-landscape.mp4","direction-portrait.mp4").map { name ->
            val f=File(context.cacheDir,name);check(f.isFile)
            val uri=Uri.fromFile(f);val ref=MediaRef("direction-$name",name,"本机文件",localUri=uri.toString(),sizeBytes=f.length())
            QueuedMedia(uri,ref,HistoryStore(context).token(ref.key))
        }
        return PlayTarget(items[0].uri,items[0].media.title,0,items[0].media,queue=items)
    }
    @Test fun eachEpisodeUsesItsDisplayShapeAndManualRotationSurvivesEvents() {
        val target=target()
        val activity=launch(target)
        try {
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val c=future.get(10,TimeUnit.SECONDS)
            try {
                ready(c)
                direction(Configuration.ORIENTATION_LANDSCAPE)
                main{c.seekToNextMediaItem()};ready(c)
                direction(Configuration.ORIENTATION_PORTRAIT)
                main{c.pause()}
                ui.onNodeWithContentDescription("旋转屏幕").performClick()
                direction(Configuration.ORIENTATION_LANDSCAPE)
                main{c.seekTo(1000);c.play();c.pause()}
                main{assertEquals(Configuration.ORIENTATION_LANDSCAPE,activity.resources.configuration.orientation)}
                main{c.seekToPreviousMediaItem()};ready(c)
                direction(Configuration.ORIENTATION_LANDSCAPE)
                main{c.pause()}
                ui.onNodeWithContentDescription("锁定方向与控件").performClick()
                main{c.seekToNextMediaItem()};ready(c)
                ui.waitUntil(5000){main{c.videoSize.height>c.videoSize.width}}
                main{assertEquals(Configuration.ORIENTATION_LANDSCAPE,activity.resources.configuration.orientation)}
                ui.onNodeWithTag("playback-gesture-surface").performTouchInput{click()}
                ui.onNodeWithContentDescription("解锁方向与控件").performClick()
                direction(Configuration.ORIENTATION_PORTRAIT)
            } finally {main{c.release()}}
        } finally {main{activity.finish()}}
    }
    @Test fun reusedPlayerWindowAcceptsNewPortraitPreflightBeforeStreamDimensions(){
        fun pending(name:String):PlayTarget{
            val uri=Uri.fromFile(File(context.cacheDir,name))
            return PlayTarget(uri,name,0,MediaRef("reentry-$name",name,"Local",localUri=uri.toString()))
        }
        val first=pending("missing-landscape-reentry.mp4");val next=pending("missing-portrait-reentry.mp4")
        main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,first,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))}
        ui.waitUntil(10000){playback()!=null};val original=playback()!!
        try{
            direction(Configuration.ORIENTATION_LANDSCAPE)
            main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,next,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))}
            ui.waitUntil(5000){main{original.intent.getStringExtra("play-intent")==next.playId}}
            main{assertSame(original,playback());assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,original.requestedOrientation)}
            direction(Configuration.ORIENTATION_PORTRAIT)
        }finally{main{original.finish()};PlaybackRequests.remove(first.playId);PlaybackRequests.remove(next.playId)}
    }
    private fun shell(command:String) {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}}
    }
    @Test fun leavingLandscapePlaybackReturnsToPortraitLibrary() {
        val resolver=context.contentResolver
        val auto=android.provider.Settings.System.getInt(resolver,"accelerometer_rotation",1)
        val rotation=android.provider.Settings.System.getInt(resolver,"user_rotation",0)
        shell("settings put system accelerometer_rotation 0");shell("settings put system user_rotation 1")
        val activity=launch(target())
        try {
            direction(Configuration.ORIENTATION_LANDSCAPE)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
            ui.onNodeWithTag("home-archives").assertIsDisplayed()
        } finally {main{activity.finish()};shell("settings put system user_rotation $rotation");shell("settings put system accelerometer_rotation $auto")}
    }
    @Test fun backgroundEpisodeMetadataIsRetainedWhenReturningToThePlayer(){
        val prefix=java.util.UUID.randomUUID().toString()
        val items=listOf("direction-landscape.mp4","direction-portrait.mp4").map{name->
            val file=File(context.cacheDir,name);check(file.isFile)
            val uri=Uri.fromFile(file);val ref=MediaRef("$prefix-$name",name,"Local",localUri=uri.toString(),sizeBytes=file.length())
            QueuedMedia(uri,ref,HistoryStore(context).token(ref.key))
        }
        val target=PlayTarget(items[0].uri,items[0].media.title,0,items[0].media,queue=items)
        val scenario=androidx.test.core.app.ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(context,target))
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        try{
            ready(player);direction(Configuration.ORIENTATION_LANDSCAPE);main{player.pause()}
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            main{player.seekToNextMediaItem()}
            ui.waitUntil(15000){main{videoOrientation(player.currentTracks)==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT}}
            main{assertEquals("Background metadata must reach the current clip cache before resume",android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,PlaybackOrientationProbe.cached(items[1].media))}
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            direction(Configuration.ORIENTATION_PORTRAIT)
        }finally{main{player.stop();player.clearMediaItems();player.release()};scenario.close();PlaybackRequests.remove(target.playId)}
    }

}
