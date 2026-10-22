package io.localplayer.app

import android.content.ComponentName
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackIoIsolationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}

    @Test fun localAndRemoteStartupHaveBoundedFirstFrameLatency() {
        val app=ui.activity.application
        val sample=File(app.cacheDir,"continuity-avc1080.mp4");assertTrue(sample.isFile)
        val preferences=EnrichmentPreferences(app)
        val oldPosters=preferences.posters;val oldSubtitles=preferences.subtitles
        preferences.posters=false;preferences.subtitles=false
        fun measure(uri:android.net.Uri,ref:MediaRef,limit:Long) {
            val target=PlayTarget(uri,ref.title,0,ref)
            var activity:ActivityScenario<PlaybackActivity>?=null
            var controller:MediaController?=null
            val start=android.os.SystemClock.elapsedRealtime()
            try {
                main{PlaybackMetrics.reset()}
                activity=ActivityScenario.launch(PlaybackActivity.intent(app,target))
                val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
                val player=future.get(10,TimeUnit.SECONDS);controller=player
                ui.waitUntil(20000){main{player.playerError!=null||PlaybackMetrics.snapshot().firstFrameMs!=null}}
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                val metrics=PlaybackMetrics.snapshot()
                main{assertNull(player.playerError);assertTrue(player.videoSize.width>0)}
                assertNotNull(metrics.firstFrameMs)
                println("STARTUP source=${uri.scheme} wallMs=$elapsed metrics=$metrics")
                assertTrue("First frame took ${elapsed}ms, budget ${limit}ms; $metrics",elapsed<limit)
    if(uri.scheme=="localplayer"){
     ui.waitUntil(15000){main{player.playerError!=null||player.currentPosition>=5500}}
     main{assertNull(player.playerError)}
     assertEquals("Sustained high-latency read should not rebuffer",0,PlaybackMetrics.snapshot().rebufferCount)
     main{player.seekTo(2000)}
     ui.waitUntil(15000){main{player.playerError!=null||(player.playbackState==Player.STATE_READY&&player.currentPosition>=3000&&player.currentPosition<5000)}}
     main{assertNull(player.playerError)}
     println("STARTUP_CONTINUITY metrics=${PlaybackMetrics.snapshot()}")
    }
            }finally{
                main{controller?.let{it.stop();it.clearMediaItems();it.release()}}
                activity?.close();PlaybackRequests.remove(target.playId);PlaybackRegistry.remove(uri)
                HistoryStore(app).remove(ref.key)
            }
        }
        try {
            val local=MediaRef("startup-local",sample.name,"起播计时",localUri=android.net.Uri.fromFile(sample).toString())
            measure(android.net.Uri.fromFile(sample),local,3000)
            DavFixture(sample,2,600).use{server->
                val source=Source(label="起播计时",protocol=Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
                val path=server.names.first()
                val ref=MediaRef(source.id+":"+path,path,source.label,source.id,path,sizeBytes=sample.length(),location=mediaLocation(source))
                val uri=PlaybackRegistry.add(source,Credentials(server.password),path,ref.title)
                measure(uri,ref,4000)
    println("STARTUP_REQUESTS count=${server.positions.size} probes=${server.mediaProbes.get()} positions=${server.positions}")
    assertEquals("No discarded one-byte probes",0,server.mediaProbes.get())
            }
        }finally{preferences.posters=oldPosters;preferences.subtitles=oldSubtitles}
    }

    @Test fun watchingWithoutSeekingDoesNotOpenCompetingPreviewOrCoverReaders() {
        val app=ui.activity.application
        val sample=File(app.cacheDir,"continuity-avc1080.mp4");assertTrue(sample.isFile)
        val preferences=EnrichmentPreferences(app);val oldPosters=preferences.posters;val oldSubtitles=preferences.subtitles
        DavFixture(sample,2).use { server ->
            val source=Source(label="播放读取回归",protocol=Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val ref=MediaRef(source.id+":isolated",server.names.first(),source.label,source.id,server.names.first(),sizeBytes=sample.length(),location=mediaLocation(source))
            val uri=PlaybackRegistry.add(source,Credentials(server.password),server.names.first(),ref.title)
            val target=PlayTarget(uri,ref.title,0,ref)
            var activity:ActivityScenario<PlaybackActivity>?=null;var controller:MediaController?=null
            try {
                preferences.posters=false;preferences.subtitles=false
                SourceStore(app).save(source,server.password,true)
                activity=ActivityScenario.launch(PlaybackActivity.intent(app,target))
                val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
                val player=future.get(10,TimeUnit.SECONDS);controller=player
                ui.waitUntil(15000){main{player.playerError!=null||PlaybackMetrics.snapshot().firstFrameMs!=null}}
                val initialProbes=server.mediaProbes.get()
                ui.waitUntil(15000){main{player.playerError!=null||player.currentPosition>=5500}}
                main{assertNull(player.playerError);assertTrue(player.videoSize.width>0)}
                assertNotNull(PlaybackMetrics.snapshot().firstFrameMs)
                assertEquals("Background artwork must not open another media reader during playback",initialProbes,server.mediaProbes.get())
                assertEquals("No repeated loading on a source with bandwidth above its media rate",0,PlaybackMetrics.snapshot().rebufferCount)
                println("PLAYBACK_IO probes=${server.mediaProbes.get()} metrics=${PlaybackMetrics.snapshot()}")
            }finally {
                main{controller?.let{it.stop();it.clearMediaItems();it.release()}}
                activity?.close();PlaybackRequests.remove(target.playId);PlaybackRegistry.remove(uri)
                SourceStore(app).delete(source.id);HistoryStore(app).remove(ref.key);MediaThumbnails.remove(app,ref)
                preferences.posters=oldPosters;preferences.subtitles=oldSubtitles
            }
        }
    }
}
