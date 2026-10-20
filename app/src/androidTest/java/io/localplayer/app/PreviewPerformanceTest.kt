package io.localplayer.app

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PreviewPerformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun latestCachedTargetBypassesSlowDecodeAndStaleCompletion(): Unit = runBlocking {
        val started=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val image=android.graphics.Bitmap.createBitmap(32,18,android.graphics.Bitmap.Config.ARGB_8888)
        val calls=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val loader=PreviewFrames { object:PreviewDecoder {
            override fun frame(timeMs:Long):android.graphics.Bitmap {
                calls.add(timeMs)
                if(timeMs==5000L){started.countDown();release.await()}
                return image
            }
            override fun close(){release.countDown()}
        } }
        val uri=Uri.parse("content://fixture/latest")
        val positions=kotlinx.coroutines.flow.MutableStateFlow(5000L)
        val received=java.util.Collections.synchronizedList(mutableListOf<Long>())
        var collecting:Job?=null
        try {
            loader.get(uri,0)
            collecting=launch(Dispatchers.Default){loader.frames(uri,positions).collect{received.add(it.timeMs)}}
            assertTrue(withContext(Dispatchers.IO){started.await(2,java.util.concurrent.TimeUnit.SECONDS)})
            positions.value=0
            withTimeout(300){while(received.isEmpty())delay(5)}
            assertEquals(listOf(0L),received.toList())
            positions.value=6000;delay(30)
            positions.value=7000;delay(30)
            positions.value=8250;delay(30)
            release.countDown()
            withTimeout(2000){while(received.last()!=8000L)delay(5)}
            assertEquals("An obsolete decode must not replace the current preview",listOf(0L,8000L),received.toList())
            assertEquals("Intermediate misses should not be decoded",listOf(0L,5000L,8000L),calls.toList())
        } finally { release.countDown();collecting?.cancelAndJoin();loader.close() }
    }
    @Test fun uncachedFramesReuseOneIndependentReadSession(): Unit = runBlocking {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val image = android.graphics.Bitmap.createBitmap(32, 18, android.graphics.Bitmap.Config.ARGB_8888)
        val loader = PreviewFrames {
            opens.incrementAndGet()
            Thread.sleep(80) // Controlled transport/metadata setup, independent of host/network noise.
            object : PreviewDecoder {
                override fun frame(timeMs: Long): android.graphics.Bitmap { Thread.sleep(10); return image }
                override fun close() { closes.incrementAndGet() }
            }
        }
        try {
            val start = SystemClock.elapsedRealtime()
            for (time in listOf(0L, 5000L, 10000L)) assertNotNull(loader.get(Uri.parse("content://fixture/video"), time))
            Log.i("PreviewBenchmark", "synthetic_ms=${SystemClock.elapsedRealtime()-start} opens=${opens.get()}")
            assertEquals("Consecutive frame requests must not repeat transport and metadata setup", 1, opens.get())
            assertEquals("Retain the independent reader until it is replaced or closed", 0, closes.get())
        } finally { loader.close() }
    }

    @Test fun cancellationNeverCachesAStaleFrameAndNextRequestRecovers(): Unit = runBlocking {
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val started=java.util.concurrent.CountDownLatch(1)
        val image=android.graphics.Bitmap.createBitmap(32,18,android.graphics.Bitmap.Config.ARGB_8888)
        val loader=PreviewFrames {
            val number=opens.incrementAndGet()
            object:PreviewDecoder {
                override fun frame(timeMs:Long):android.graphics.Bitmap {
                    if(number==1){started.countDown();Thread.sleep(5000)}
                    return image
                }
                override fun close() {}
            }
        }
        val uri=Uri.parse("content://fixture/cancel")
        try {
            val request=async(Dispatchers.Default){loader.get(uri,5000)}
            assertTrue(withContext(Dispatchers.IO){started.await(2,java.util.concurrent.TimeUnit.SECONDS)})
            request.cancelAndJoin()
            assertSame(image,withTimeout(2000){loader.get(uri,10000)})
            assertEquals(2,opens.get())
            assertNull(loader.cached(uri,5000))
        } finally { loader.close() }
    }

    @Test fun failuresAndMediaChangesReplaceTheReaderAndCloseIsFinal(): Unit = runBlocking {
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val closes=java.util.concurrent.atomic.AtomicInteger()
        val image=android.graphics.Bitmap.createBitmap(32,18,android.graphics.Bitmap.Config.ARGB_8888)
        val loader=PreviewFrames {
            val number=opens.incrementAndGet()
            object:PreviewDecoder {
                val closed=java.util.concurrent.atomic.AtomicBoolean(false)
                override fun frame(timeMs:Long):android.graphics.Bitmap {
                    if(number==1&&timeMs==5000L)throw java.io.IOException("Synthetic decoder failure")
                    return image
                }
                override fun close(){if(closed.compareAndSet(false,true))closes.incrementAndGet()}
            }
        }
        val one=Uri.parse("content://fixture/one");val two=Uri.parse("content://fixture/two")
        try {
            loader.get(one,0)
            assertTrue(runCatching{loader.get(one,5000)}.exceptionOrNull() is java.io.IOException)
            assertSame(image,loader.get(one,5000))
            assertSame(image,loader.get(two,0))
            assertEquals(3,opens.get())
        } finally { loader.close() }
        assertNull(loader.get(two,5000));assertNull(loader.latest(two))
        withTimeout(2000){while(closes.get()!=3)delay(10)}
    }

    @Test fun cachedFrameDoesNotWaitForAnotherDecode(): Unit = runBlocking {
        val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val image=android.graphics.Bitmap.createBitmap(32,18,android.graphics.Bitmap.Config.ARGB_8888)
        val loader=PreviewFrames { object:PreviewDecoder {
            override fun frame(timeMs:Long):android.graphics.Bitmap {if(timeMs==5000L){started.countDown();release.await()};return image}
            override fun close(){release.countDown()}
        } }
        val uri=Uri.parse("content://fixture/cache")
        try {
            loader.get(uri,0)
            val request=async(Dispatchers.Default){loader.get(uri,5000)}
            assertTrue(withContext(Dispatchers.IO){started.await(2,java.util.concurrent.TimeUnit.SECONDS)})
            assertSame(image,withTimeout(300){loader.get(uri,0)})
            release.countDown();request.await()
        } finally {release.countDown();loader.close()}
    }

    @Test fun releasingAnIdleReaderKeepsFramesAndReopensOnDemand(): Unit = runBlocking {
        val opens=java.util.concurrent.atomic.AtomicInteger()
        val closes=java.util.concurrent.atomic.AtomicInteger()
        val image=android.graphics.Bitmap.createBitmap(32,18,android.graphics.Bitmap.Config.ARGB_8888)
        val loader=PreviewFrames {
            opens.incrementAndGet()
            object:PreviewDecoder {
                private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
                override fun frame(timeMs:Long)=image
                override fun close(){if(closed.compareAndSet(false,true))closes.incrementAndGet()}
            }
        }
        val uri=Uri.parse("content://fixture/lifecycle")
        try {
            loader.get(uri,0)
            loader.releaseReader()
            withTimeout(2000){while(closes.get()!=1)delay(10)}
            assertSame(image,loader.get(uri,0));assertEquals(1,opens.get())
            assertSame(image,loader.get(uri,5000));assertEquals(2,opens.get())
        } finally {loader.close()}
    }

    @Test fun nativeSequentialFrameTiming(): Unit = runBlocking {
        val fixture = File(context.cacheDir, "seek-performance.mp4")
        check(fixture.isFile) { "Install the owned synthetic video fixture before this measurement" }
        val loader = PreviewFrames(context)
        val times = mutableListOf<Long>()
        try {
            for (position in listOf(0L, 5000L, 10000L, 15000L, 5000L)) {
                val start = SystemClock.elapsedRealtime()
                val frame = loader.get(Uri.fromFile(fixture), position)
                times += SystemClock.elapsedRealtime() - start
                assertNotNull(frame)
                assertTrue(frame!!.width <= 320 && frame.height <= 180)
            }
            Log.i("PreviewBenchmark", "native_ms=$times total_ms=${times.sum()}")
        } finally { loader.close() }
    }
}
