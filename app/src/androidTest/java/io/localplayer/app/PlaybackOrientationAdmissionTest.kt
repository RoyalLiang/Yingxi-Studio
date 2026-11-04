package io.localplayer.app

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackOrientationAdmissionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action:()->T):T {var result:Result<T>?=null;instrumentation.runOnMainSync{result=runCatching(action)};return result!!.getOrThrow()}
    @Test fun unknownNetworkHintIsNotRememberedBeforeRealVideoMetadata() {
        val app=instrumentation.targetContext;val sample=betaPlaybackFixture(app);val bytes=sample.readBytes()
        val release=CountDownLatch(1);val entered=CountDownLatch(1)
        val source=Source(label="Orientation admission fixture",protocol=Protocol.SFTP,host="fixture.invalid",root="/")
        val store=SourceStore(app);store.save(source,"",false)
        val media=HistoryStore(app).reference(MediaRef(source.id+":fixture.mp4","fixture.mp4",source.label,source.id,"fixture.mp4",sizeBytes=bytes.size.toLong(),location=mediaLocation(source),bindingEpoch=SourceBindingEpochs.current(app,source)))
        val connector=object:Connector {override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession {
            override fun list(path:String)=emptyList<Entry>()
            override fun open(path:String)=object:RemoteFile {
                override val size=bytes.size.toLong()
                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                    entered.countDown();check(release.await(10,TimeUnit.SECONDS));cancellation.check()
                    if(position>=size)return -1
                    val count=minOf(length.toLong(),size-position).toInt();bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count);return count
                }
                override fun close(){}
            }
            override fun close(){}
        }}
        val uri=PlaybackRegistry.add(source,Credentials(""),"fixture.mp4",media.title,connector,app,media.bindingEpoch,media)
        val target=PlayTarget(uri,media.title,0,media)
        val scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app,target,ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT))
        var controller:MediaController?=null
        try {
            val player=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}.get(10,TimeUnit.SECONDS);controller=player
            assertTrue(entered.await(5,TimeUnit.SECONDS));Thread.sleep(300)
            assertNull("A provisional portrait hint is not evidence about the film",PlaybackOrientationProbe.cached(app,media))
            release.countDown()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<deadline&&!main{player.playbackState==Player.STATE_READY&&player.videoSize.width>player.videoSize.height})Thread.sleep(100)
            var orientation=0;scenario.onActivity{orientation=it.resources.configuration.orientation}
            main{assertNull(player.playerError)}
            assertEquals(Configuration.ORIENTATION_LANDSCAPE,orientation)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,PlaybackOrientationProbe.cached(app,media))
        }finally{release.countDown();main{controller?.stop();controller?.clearMediaItems();controller?.release()};scenario.close();target.release();PlaybackRequests.remove(target.playId);PlaybackRegistry.remove(uri);PlaybackOrientationProbe.remove(app,media);HistoryStore(app).remove(media);store.delete(source.id)}
    }
    @Test fun unverifiedLegacyOrientationCacheDoesNotOverrideFreshAdmission() {
        val app=instrumentation.targetContext;val file=betaPlaybackFixture(app)
        val media=MediaRef("orientation-schema-fixture",file.name,"fixture",localUri=Uri.fromFile(file).toString(),sizeBytes=file.length())
        val legacy=app.getSharedPreferences("video-orientation-v1",0);val key=enrichmentKey(media)
        legacy.edit().putString(key,JSONObject().put("orientation",ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT).put("recordedAt",System.currentTimeMillis()).toString()).commit()
        try{
            assertNull("Old hints were cached without verified dimensions",PlaybackOrientationProbe.cached(app,media))
            PlaybackOrientationProbe.remember(app,media,ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,PlaybackOrientationProbe.cached(app,media))
        }finally{legacy.edit().remove(key).commit();PlaybackOrientationProbe.remove(app,media)}
    }
}
