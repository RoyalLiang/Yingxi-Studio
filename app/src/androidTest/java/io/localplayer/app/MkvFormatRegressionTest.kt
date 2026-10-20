package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.localplayer.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MkvFormatRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun avcMatroskaRendersLocallyAndThroughSource()=exercise("mkv-avc.mkv")
    @Test fun main10RendersOrExplainsTheUnavailableDecoder()=exercise("mkv-hevc10.mkv",allowUnsupported=true)
    @Test fun hevcMatroskaRendersLocallyAndThroughSource()=exercise("mkv-hevc.mkv")
    @Test fun unsupportedDtsDoesNotTurnSupportedMkvVideoBlack()=exercise("mkv-dts.mkv")
    private fun assertVisiblePicture(label:String){
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ui.waitUntil(6000){
            val bitmap=instrumentation.uiAutomation.takeScreenshot()?:return@waitUntil false
            try{
                var colored=0
                for(y in bitmap.height*3/10 until bitmap.height*7/10 step 8)
                    for(x in bitmap.width/5 until bitmap.width*4/5 step 8){
                        val p=bitmap.getPixel(x,y)
                        val r=android.graphics.Color.red(p);val g=android.graphics.Color.green(p);val b=android.graphics.Color.blue(p)
                        if(maxOf(r,g,b)-minOf(r,g,b)>70)colored++
                    }
                if(colored>80){
                    File(instrumentation.targetContext.getExternalFilesDir(null),"picture-$label.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    true
                }else false
            }finally{bitmap.recycle()}
        }
    }
    private fun exercise(name:String,allowUnsupported:Boolean=false){
        val app=ui.activity.application
        val prefs=EnrichmentPreferences(app);val posters=prefs.posters;val subtitles=prefs.subtitles;prefs.posters=false;prefs.subtitles=false
        val sample=File(app.getExternalFilesDir(null),name);assertTrue("Push MKV fixture before running",sample.isFile)
        try{for(remote in listOf(false,true)){
            val source=Source(label="MKV fixture",protocol=Protocol.WEBDAV,host="10.0.2.2",port=6387,root="/",secure=false)
            val uri=if(remote)PlaybackRegistry.add(source,Credentials(""),name,name)else Uri.fromFile(sample)
            val media=MediaRef("mkv-"+java.util.UUID.randomUUID(),name,"MKV fixture",if(remote)source.id else null,if(remote)name else null,if(remote)null else uri.toString(),sample.length(),if(remote)mediaLocation(source)else null)
            val target=PlayTarget(uri,name,0,media);val token=HistoryStore(app).token(media.key)
            val scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app,target.copy(historyToken=token)))
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            ui.runOnUiThread{future=MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
            val c=future.get(10,TimeUnit.SECONDS)
            try{
                ui.waitUntil(15000){var done=false;ui.runOnUiThread{done=PlaybackMetrics.snapshot().firstFrameMs!=null||c.playerError!=null};done}
                var unsupported=false
                ui.runOnUiThread{unsupported=allowUnsupported&&c.playerError!=null}
                if(unsupported){
                    ui.runOnUiThread{assertTrue(playbackFailure(c.playerError!!).startsWith("视频解码失败"))}
                    continue
                }
                ui.runOnUiThread{assertNull("$name remote=$remote must not fail: "+c.playerError,c.playerError);assertNotNull("$name remote=$remote must render a frame",PlaybackMetrics.snapshot().firstFrameMs);c.seekTo(2500);c.play()}
                ui.waitUntil(10000){var done=false;ui.runOnUiThread{done=c.currentPosition>2800||c.playerError!=null};done}
                ui.runOnUiThread{assertNull(c.playerError);assertTrue(c.videoSize.width>0)}
                assertVisiblePicture("$name-$remote")
                scenario.recreate()
                ui.waitForIdle()
                assertVisiblePicture("$name-$remote-recreated")
            }finally{ui.runOnUiThread{c.stop();c.clearMediaItems();c.release()};scenario.close();target.release();HistoryStore(app).remove(media.key)}
        }}finally{prefs.posters=posters;prefs.subtitles=subtitles}
    }
}
