package io.localplayer.app

import android.content.ComponentName
import android.content.res.Configuration
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in original bytes over loopback SFTP, through real catalog/source admission and session UI. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OriginalSftpPerformanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action:()->T):T {var result:Result<T>?=null;instrumentation.runOnMainSync{result=runCatching(action)};return result!!.getOrThrow()}
    @Test fun originalStartsInItsDirectionAndSustainsPlayback() {
        val args=InstrumentationRegistry.getArguments();assumeTrue("Explicit original-stream opt-in",args.getString("allow_live_original")=="true")
        val app=instrumentation.targetContext.applicationContext
        val input=File(app.filesDir,"original-sftp-performance.private.json")
        val fixture=try{JSONObject(input.readText())}finally{input.delete()}
        check(fixture.getString("host")=="10.0.2.2"&&fixture.getInt("port")==22905){"Use the owned loopback SFTP fixture"}
        val raw=Source(id="live-diagnostic-"+UUID.randomUUID(),label="原片流式诊断",protocol=Protocol.SFTP,host=fixture.getString("host"),port=fixture.getInt("port"),root="/",username=fixture.getString("username"))
        val password=fixture.getString("password")
        val rejected=try{SftpConnector().connect(raw,Credentials(password)).close();error("Fixture host key must require verification")}catch(error:HostKeyRequired){error}
        val source=raw.copy(trustedKey=rejected.fingerprint,trustedAuthority=raw.authority)
        val store=SourceStore(app);val history=HistoryStore(app)
        val resume=args.getString("resume_ms")?.toLong()?:3_159_656L
        val window=args.getString("window_ms")?.toLong()?:30_000L
        val timeout=args.getString("timeout_ms")?.toLong()?:420_000L
        val enrichment=EnrichmentPreferences(app);val oldPosters=enrichment.posters;val oldSubtitles=enrichment.subtitles
        var scenario:ActivityScenario<PlaybackActivity>?=null;var controller:MediaController?=null
        var ownedMedia:MediaRef?=null;var ownedUri:android.net.Uri?=null;var ownedTarget:PlayTarget?=null
        try {
            MediaDataMigration.session(app).migrate();check(MediaDataMigration.session(app).isCommitted())
            store.save(source,password,true)
            val media=history.reference(MediaRef(source.id+":original.mkv",fixture.getString("name"),source.label,source.id,"original.mkv",sizeBytes=fixture.getLong("size"),location=mediaLocation(source),bindingEpoch=SourceBindingEpochs.current(app,source)))
            ownedMedia=media
            check(media.mediaId!=null&&media.locatorId!=null){"Probe must use committed catalog admission"}
            val credentials=Credentials(requireNotNull(store.password(source.id)))
            val uri=PlaybackRegistry.add(source,credentials,media.path!!,media.title,context=app,bindingEpoch=media.bindingEpoch,media=media);ownedUri=uri
            val target=PlayTarget(uri,media.title,resume,media);ownedTarget=target
            enrichment.posters=if(args.getString("enrichment")=="current")oldPosters else false
            enrichment.subtitles=if(args.getString("enrichment")=="current")oldSubtitles else false
            main{PlaybackMetrics.reset()}
            val start=SystemClock.elapsedRealtime()
            scenario=ActivityScenario.launch(PlaybackActivity.intent(app,target,kotlinx.coroutines.runBlocking{PlaybackOrientationProbe.initial(app,target)}))
            val active=checkNotNull(scenario)
            val player=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}.get(15,TimeUnit.SECONDS);controller=player
            var firstPixel=-1L;var landscape=-1L;var windowStart=-1L;var positionStart=0L;var droppedStart=0;var decoderDroppedStart=0
            var buffering=0L;var lastTick=SystemClock.elapsedRealtime();var nextFrame=0L;var nextReport=0L;var moving=0;var previous:IntArray?=null
            val deadline=start+timeout
            while(SystemClock.elapsedRealtime()<deadline){
                val now=SystemClock.elapsedRealtime()
                if(PlaybackMetrics.snapshot().bytes>192L*1024*1024)break
                active.onActivity{if(it.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE&&landscape<0)landscape=now-start}
                val status=main{Triple(player.playerError,player.playbackState,player.isPlaying)}
                if(status.first!=null)break
                if(windowStart>=0&&status.second==Player.STATE_BUFFERING)buffering+=now-lastTick
                lastTick=now
                if(now>=nextFrame&&PlaybackMetrics.snapshot().firstFrameMs!=null){
                    val frame=originalStreamPixels(active);nextFrame=now+500
                    if(frame!=null&&frame.toSet().size>24){
                        if(firstPixel<0)firstPixel=now-start
                        previous?.let{old->if(frame.indices.count{frame[it]!=old[it]}>frame.size/100)moving++};previous=frame
                        if(windowStart<0&&status.third){
                            windowStart=now;positionStart=main{player.currentPosition};val snapshot=PlaybackMetrics.snapshot();droppedStart=snapshot.droppedFrames;decoderDroppedStart=snapshot.sdrDecoderDroppedFrames?:0
                        }
                    }
                }
                if(now>=nextReport){
                    println("ORIGINAL_SFTP_SAMPLE "+main{playbackDiagnostic(player)}.replace('\n',' '));nextReport=now+5000
                }
                if(windowStart>=0&&now-windowStart>=window)break
                Thread.sleep(100)
            }
            val ended=SystemClock.elapsedRealtime();val wall=if(windowStart>=0)ended-windowStart else 0L
            val advanced=if(windowStart>=0)main{player.currentPosition}-positionStart else 0L
            val snapshot=PlaybackMetrics.snapshot();val dropped=(snapshot.droppedFrames-droppedStart).coerceAtLeast(0)+(snapshot.sdrDecoderDroppedFrames?:0).minus(decoderDroppedStart).coerceAtLeast(0)
            val result=JSONObject().put("firstPixelMs",firstPixel).put("landscapeMs",landscape).put("windowMs",wall).put("advancedMs",advanced).put("pace",if(wall>0)advanced.toDouble()/wall else 0.0).put("bufferingMs",buffering).put("movingSamples",moving).put("droppedFrames",dropped).put("bytes",snapshot.bytes).put("decoder",snapshot.decoder).put("resumeMs",resume).put("postersEnabled",enrichment.posters).put("automaticSubtitlesEnabled",enrichment.subtitles)
            println("ORIGINAL_SFTP_PERFORMANCE "+result)
            println("ORIGINAL_SFTP_FINAL "+main{playbackDiagnostic(player)}.replace('\n',' '))
            main{assertNull(player.playerError?.errorCodeName,player.playerError);assertTrue(player.currentTracks.groups.any{it.type==C.TRACK_TYPE_VIDEO&&it.isSelected})}
            assertTrue("Actual original video scene must be visible",firstPixel>=0&&moving>=4)
            assertTrue("Landscape source must adapt without manual rotation",landscape>=0)
            assertTrue("Complete sustained playback window",wall>=window)
            assertTrue("Media clock must keep up with wall time: "+result,advanced>=wall*0.9)
            assertTrue("Buffering must remain below ten percent of the window",buffering<=wall/10)
            assertTrue("Video drops must remain below five percent at 24fps",dropped<=maxOf(3,wall*24/1000/20))
        }finally{
            cleanupOriginalStreamProbe(
                {main{controller?.stop()}},{main{controller?.clearMediaItems()}},{main{controller?.release()}},
                {scenario?.close()},{ownedTarget?.release()},{PlaybackRequests.remove(ownedTarget?.playId)},
                {PlaybackRegistry.remove(ownedUri)},{ownedMedia?.let{PlaybackOrientationProbe.remove(app,it)}},
                {ownedMedia?.let(history::remove)},{store.delete(source.id)},
                {enrichment.posters=oldPosters},{enrichment.subtitles=oldSubtitles},{input.delete()}
            )
        }
    }
}
