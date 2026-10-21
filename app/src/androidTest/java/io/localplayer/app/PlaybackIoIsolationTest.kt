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
