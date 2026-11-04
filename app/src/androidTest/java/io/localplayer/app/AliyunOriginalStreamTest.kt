package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live original-file probe. Credentials arrive through an app-private file, never runner arguments. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AliyunOriginalStreamTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null;instrumentation.runOnMainSync{result=runCatching(block)}
        return result!!.getOrThrow()
    }
    private fun output(view:View):View? {
        if(view is PlayerView&&view.isShown)return view.videoSurfaceView
        if(view is ViewGroup)for(i in 0 until view.childCount)output(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun pixels(scenario:ActivityScenario<PlaybackActivity>):IntArray? {
        var surface:View?=null;scenario.onActivity{surface=output(it.window.decorView)}
        val view=surface?:return null
        val bitmap=when(view){
            is TextureView->main{view.getBitmap(160,90)}?:return null
            is SurfaceView->{
                val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888)
                val done=CountDownLatch(1);var status=-1
                try{PixelCopy.request(view,image,{status=it;done.countDown()},Handler(Looper.getMainLooper()))}
                catch(_:IllegalArgumentException){image.recycle();return null}
                if(!done.await(2,TimeUnit.SECONDS)||status!=PixelCopy.SUCCESS){image.recycle();return null}
                image
            }
            else->return null
        }
        try{return IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}}
        finally{bitmap.recycle()}
    }
    @Test fun requestedOriginalStreamsThroughTheRealAccountAndPlayer(){
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live account opt-in required",args.getString("allow_live_aliyun")=="true")
        val app=instrumentation.targetContext.applicationContext
        val input=File(app.filesDir,"aliyun-stream-repro.private.json")
        check(input.isFile){"Provide the app-private account fixture"}
        val fixture=JSONObject(input.readText());input.delete()
        val source=Source(id="live-diagnostic-"+UUID.randomUUID(),label="原片流式诊断",protocol=Protocol.ALIYUN,
            host="openapi.alipan.com",domain=fixture.getString("driveId"),username=fixture.getString("userId"),initialPath=fixture.getString("folderId"))
        val path=Paths.join(source.initialPath,fixture.getString("fileId"))
        val media=MediaRef(source.id+":"+path,fixture.getString("name"),source.label,source.id,path,
            sizeBytes=fixture.getLong("size"),location=mediaLocation(source))
        val store=SourceStore(app);val preferences=EnrichmentPreferences(app)
        val posters=preferences.posters;val subtitles=preferences.subtitles
        val resume=args.getString("resume_ms")?.toLong()?:3_159_656L
        val timeout=args.getString("timeout_ms")?.toLong()?:90_000L
        val budget=128L*1024*1024
        var controller:MediaController?=null;var scenario:ActivityScenario<PlaybackActivity>?=null
        var uri:android.net.Uri?=null;var playId:String?=null
        try{
            preferences.posters=false;preferences.subtitles=false
            store.save(source,fixture.getJSONObject("credentials").toString(),true)
            val registered=PlaybackRegistry.add(source,Credentials(""),path,media.title);uri=registered
            main{PlaybackMetrics.reset()}
            val target=PlayTarget(registered,media.title,resume,media);playId=target.playId
            scenario=ActivityScenario.launch(PlaybackActivity.intent(app,target))
            val active=checkNotNull(scenario)
            val player=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}.get(15,TimeUnit.SECONDS)
            controller=player
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout)
            var nextReport=0L;var first:IntArray?=null;var rendered=false
            while(System.nanoTime()<deadline){
                val used=maxOf(PlaybackMetrics.snapshot().bytes,PlaybackIoProbe.transportSnapshots().sumOf{it.sourceBytes})
                if(used>budget)break
                val now=android.os.SystemClock.elapsedRealtime()
                if(now>=nextReport){println("ALIYUN_ORIGINAL_REPRO "+main{playbackDiagnostic(player)}.replace('\n',' '));nextReport=now+5000}
                if(main{player.playerError!=null})break
                if(main{player.isPlaying&&player.currentPosition>=resume+1000&&PlaybackMetrics.snapshot().firstFrameMs!=null}){
                    val frame=pixels(active)
                    if(frame!=null&&frame.toSet().size>24){
                        val previous=first
                        if(previous!=null&&frame.indices.count{frame[it]!=previous[it]}>frame.size/100){rendered=true;break}
                        first=frame
                    }
                }
                Thread.sleep(250)
            }
            println("ALIYUN_ORIGINAL_FINAL "+main{playbackDiagnostic(player)}.replace('\n',' '))
            main{assertTrue("Playback error code: "+player.playerError?.errorCodeName,player.playerError==null)}
            assertTrue("No moving decoded scene within time/read budget",rendered)
            main{assertTrue(player.currentTracks.groups.any{it.type==C.TRACK_TYPE_VIDEO&&it.isSelected})}
        }finally{
            main{controller?.stop();controller?.clearMediaItems();controller?.release()}
            scenario?.close();playId?.let(PlaybackRequests::remove);uri?.let(PlaybackRegistry::remove);store.delete(source.id)
            HistoryStore(app).remove(media.key);preferences.posters=posters;preferences.subtitles=subtitles;input.delete()
        }
    }

    @Test fun cleanupPrivateAccountAfterInterruptedProbe(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("allow_live_aliyun")=="true")
        val app=instrumentation.targetContext.applicationContext;val store=SourceStore(app)
        val owned=store.load().filter{it.id.startsWith("live-diagnostic-")&&it.label=="原片流式诊断"}.map{it.id}.toSet()
        val history=HistoryStore(app)
        history.entries().filter{it.media.sourceId in owned}.forEach{history.remove(it.media.key)}
        owned.forEach(store::delete)
        File(app.filesDir,"aliyun-stream-repro.private.json").delete()
    }
}
