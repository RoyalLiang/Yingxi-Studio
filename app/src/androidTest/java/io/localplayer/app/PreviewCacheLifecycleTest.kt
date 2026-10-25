package io.localplayer.app

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreviewCacheLifecycleTest {
    @Test fun recreationReusesBoundedFramesAndAnotherPlayRequestCannotSeeThem()=runBlocking {
        val uri=Uri.parse("content://fixture/cache-recreation")
        val cache=PreviewFrames.playbackCache
        PreviewFrames.clearPlaybackCache()
        val image=Bitmap.createBitmap(32,18,Bitmap.Config.ARGB_8888)
        val first=PreviewFrames(cache,"play-one"){object:PreviewDecoder{override fun frame(timeMs:Long)=image;override fun close(){}}}
        assertSame(image,first.get(uri,5000));first.close()
        var opens=0
        val rotated=PreviewFrames(cache,"play-one"){opens++;error("Cached frame must not reopen media")}
        val another=PreviewFrames(cache,"play-two"){error("No decode requested")}
        try{
            assertSame(image,rotated.get(uri,5000));assertEquals(0,opens)
            assertEquals(5000L,rotated.nearest(uri,6500)?.timeMs)
            assertNull(rotated.nearest(uri,60000));assertNull(another.cached(uri,5000))
        }finally{rotated.close();another.close();PreviewFrames.clearPlaybackCache()}
    }
    @Test fun lockInvalidationRejectsAnInFlightFrameWithoutHangingTheCaller()=runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val uri=Uri.parse("content://fixture/cache-lock")
        val loader=PreviewFrames(PreviewFrames.playbackCache,"private-play"){object:PreviewDecoder{
            override fun frame(timeMs:Long):Bitmap{started.countDown();release.await();return Bitmap.createBitmap(32,18,Bitmap.Config.ARGB_8888)}
            override fun close(){release.countDown()}
        }}
        try{
            val request=async(Dispatchers.Default){loader.get(uri,5000)}
            assertTrue(withContext(Dispatchers.IO){started.await(2,TimeUnit.SECONDS)})
            SealedMedia.lock();release.countDown()
            assertNull(withTimeout(2000){request.await()});assertNull(loader.cached(uri,5000))
        }finally{release.countDown();loader.close();PreviewFrames.clearPlaybackCache()}
    }
    @Test fun memoryBudgetEvictsOldFrames()=runBlocking {
        val cache=PreviewFrames.memoryCache();val uri=Uri.parse("content://fixture/cache-budget")
        val loader=PreviewFrames(cache,"budget"){object:PreviewDecoder{
            override fun frame(timeMs:Long)=Bitmap.createBitmap(1024,1024,Bitmap.Config.ARGB_8888)
            override fun close(){}
        }}
        try{repeat(7){assertNotNull(loader.get(uri,it*1000L))};assertTrue(cache.size()<=24*1024*1024);assertNull(loader.cached(uri,0))}
        finally{loader.close()}
    }
}
