package io.localplayer.app

import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run prepare and recover in separate instrumentations, with force-stop between them. */
class OrientationProcessRecoveryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val media get()=MediaRef("persistent-orientation-fixture","Portrait.mp4","fixture",localUri="content://orientation.fixture/portrait",sizeBytes=42000)
    @Test fun prepareStoredShape(){
        PlaybackOrientationProbe.clear(context)
        PlaybackOrientationProbe.remember(context,media,ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
        assertTrue(context.getSharedPreferences("video-orientation-v1",0).edit().commit())
    }
    @Test fun directHttpEntryDoesNotOpenASecondMetadataConnection():Unit=runBlocking{
        java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use{server->
            server.soTimeout=250
            val uri=android.net.Uri.parse("http://127.0.0.1:${server.localPort}/video.mp4")
            val ref=media.copy(localUri=uri.toString())
            assertNull(PlaybackOrientationProbe.initial(context,PlayTarget(uri,ref.title,0,ref)))
            val connected=try{server.accept().use{true}}catch(_:java.net.SocketTimeoutException){false}
            assertFalse("A direct HTTP entry must leave metadata reads to its player",connected)
        }
    }
    @Test fun recoverStoredShape():Unit=runBlocking{
        if(InstrumentationRegistry.getArguments().getString("orientationRecovery")!="true")prepareStoredShape()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,PlaybackOrientationProbe.cached(context,media))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,PlaybackOrientationProbe.initial(context,PlayTarget(android.net.Uri.parse(media.localUri),media.title,0,media)))
        assertNull(PlaybackOrientationProbe.cached(context,media.copy(sizeBytes=42001)))
    }
    @Test fun staleAndInvalidRecordsAreIgnored(){
        val p=context.getSharedPreferences("video-orientation-v1",0);val key=enrichmentKey(media)
        for(raw in listOf("broken",org.json.JSONObject().put("orientation",6).put("recordedAt",System.currentTimeMillis()-25L*60*60*1000).toString(),org.json.JSONObject().put("orientation",99).put("recordedAt",System.currentTimeMillis()).toString())){
            p.edit().putString(key,raw).commit()
            assertNull(raw,PlaybackOrientationProbe.cached(context,media))
        }
        PlaybackOrientationProbe.remove(context,media)
        assertFalse(p.contains(key))
    }
    @Test fun cacheRemainsBoundedAndStoresNoPlainMediaPath(){
        PlaybackOrientationProbe.clear(context)
        repeat(70){i->PlaybackOrientationProbe.remember(context,media.copy(key="orientation-$i",localUri="content://orientation.fixture/$i"),6)}
        val entries=context.getSharedPreferences("video-orientation-v1",0).all
        assertEquals(64,entries.size)
        assertTrue(entries.keys.all{it.matches(Regex("[a-f0-9]{64}"))})
        assertFalse(entries.toString().contains("content://"))
        PlaybackOrientationProbe.clear(context)
    }
    @Test fun sealedMediaNeverWritesAnOrientationRecord(){
        PlaybackOrientationProbe.clear(context)
        val main=InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync{SealedMedia.unlock();SealedMedia.seal(SealedItem(media,false))}
        try{
            PlaybackOrientationProbe.remember(context,media,6)
            assertNull(PlaybackOrientationProbe.cached(context,media))
            assertFalse(context.getSharedPreferences("video-orientation-v1",0).contains(enrichmentKey(media)))
        }finally{main.runOnMainSync{SealedMedia.unlock();SealedMedia.unseal(media.key);SealedMedia.lock()}}
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun compatibilityInitialSizeRespectsContainerRotation(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{
            val player=MpvPlayer(context,androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc").setWidth(1920).setHeight(1080).setRotationDegrees(90).setPixelWidthHeightRatio(2f).build())
            try{
                assertEquals(1080,player.videoSize.width);assertEquals(1920,player.videoSize.height)
                assertEquals(.5f,player.videoSize.pixelWidthHeightRatio,0f)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,videoOrientation(player.videoSize))
            }finally{player.release()}
        }
    }
    @Test fun containerRotationIsAppliedWithoutDeprecatedVideoSizeRotation(){
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,videoOrientation(1920,1080,90,1f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,videoOrientation(1920,1080,270,1f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,videoOrientation(1080,1920,90,1f))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,videoOrientation(1920,1080,180,1f))
    }
    @Test fun selectedVideoVariantDeterminesTheDisplayShape(){
        val wide=androidx.media3.common.Format.Builder().setSampleMimeType("video/avc").setWidth(640).setHeight(360).build()
        val tall=wide.buildUpon().setWidth(360).setHeight(640).build()
        val group=androidx.media3.common.Tracks.Group(androidx.media3.common.TrackGroup(wide,tall),false,intArrayOf(androidx.media3.common.C.FORMAT_HANDLED,androidx.media3.common.C.FORMAT_HANDLED),booleanArrayOf(false,true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,videoOrientation(androidx.media3.common.Tracks(com.google.common.collect.ImmutableList.of(group))))
    }

}
