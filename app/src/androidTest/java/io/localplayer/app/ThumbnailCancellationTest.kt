package io.localplayer.app

import io.localplayer.core.Cancellation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThumbnailCancellationTest {
    @Test fun cancellingProviderOpenSignalsCancellationAndFreesDecoderLane():Unit=runBlocking {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;val resolver=context.contentResolver
        val uri=android.provider.DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY,"thumbnail-block")
        context.startActivity(android.content.Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",uri.toString()).putExtra("target",context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(2000){while(context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)delay(10)}
        resolver.call(uri,"thumbnail-reset",null,null)
        val extraction=launch(Dispatchers.IO){MediaThumbnails.load(context,MediaRef("blocked-provider-${java.util.UUID.randomUUID()}", "Blocked.mp4","Local",localUri=uri.toString()))}
        try {
            withTimeout(2000){while(resolver.call(uri,"thumbnail-state",null,null)?.getBoolean("entered")!=true)delay(10)}
            assertTrue("Provider did not receive a cancellation signal",resolver.call(uri,"thumbnail-state",null,null)!!.getBoolean("signal"))
            val started=android.os.SystemClock.elapsedRealtime()
            withTimeout(500){extraction.cancelAndJoin()}
            assertEquals(42,withTimeout(500){MediaThumbnails.extract(Cancellation()){42}})
            withTimeout(500){while(resolver.call(uri,"thumbnail-state",null,null)?.getBoolean("cancelled")!=true)delay(5)}
            println("THUMBNAIL_PROVIDER_CANCEL elapsed_ms=${android.os.SystemClock.elapsedRealtime()-started}")
        }finally{extraction.cancelAndJoin()}
    }
    @Test fun realLocalExtractionReturnsDetailedFrameAndReusesMemory():Unit=runBlocking {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.cacheDir,"seek-performance.mp4");check(file.isFile)
        val media=MediaRef("local-frame-${java.util.UUID.randomUUID()}",file.name,"Local",localUri=android.net.Uri.fromFile(file).toString(),sizeBytes=file.length())
        try {
            val frame=withTimeout(5000){MediaThumbnails.load(context,media)}!!
            assertTrue(frame.width in 1..320&&frame.height in 1..180)
            val colors=mutableSetOf<Int>();for(y in 0 until frame.height step 8)for(x in 0 until frame.width step 8)colors+=frame.getPixel(x,y)
            assertTrue(colors.size>8)
            assertSame(frame,withTimeout(500){MediaThumbnails.load(context,media)})
        }finally{HistoryStore(context).remove(media.key);MediaThumbnails.remove(context,media)}
    }
    @Test fun cancellingBlockedExtractionClosesTransportAndFreesTheDecoderLane():Unit=runBlocking {
        val entered=CountDownLatch(1);val closed=CountDownLatch(1);val token=Cancellation()
        token.own(Closeable{closed.countDown()})
        val extraction=launch(Dispatchers.IO){MediaThumbnails.extract(token){
            entered.countDown()
            // Model a native reader that does not react to Java interruption.
            while(closed.count>0)try{closed.await()}catch(_:InterruptedException){}
        }}
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            withTimeout(500){extraction.cancelAndJoin()}
            assertTrue(closed.await(500,TimeUnit.MILLISECONDS))
            assertEquals(42,withTimeout(500){MediaThumbnails.extract(Cancellation()){42}})
        }finally{token.close();extraction.cancelAndJoin()}
    }
    @Test fun cancelledQueuedExtractionNeverOpensItsReader():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val first=launch(Dispatchers.IO){MediaThumbnails.extract(Cancellation()){entered.countDown();release.await()}}
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val queued=launch(start=CoroutineStart.UNDISPATCHED){MediaThumbnails.extract(Cancellation()){throw AssertionError("Cancelled work ran")}}
            withTimeout(500){queued.cancelAndJoin()}
        }finally{release.countDown();first.join()}
    }
    @Test fun localRevocationRejectsAnAlreadyOpenThumbnail():Unit=runBlocking {
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;val resolver=context.contentResolver
        val uri=android.provider.DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY,"thumbnail-revoke")
        context.startActivity(android.content.Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",uri.toString()).putExtra("target",context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(2000){while(context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)delay(10)}
        resolver.call(uri,"thumbnail-reset",null,null)
        val media=MediaRef("revoke-thumbnail-${java.util.UUID.randomUUID()}","旧片.mp4","本机",localUri=uri.toString(),bindingEpoch=LocalBindingEpochs.current(context,uri))
        val result=async(Dispatchers.IO){MediaThumbnails.load(context,media)}
        try{
            withTimeout(2000){while(resolver.call(uri,"thumbnail-state",null,null)?.getBoolean("entered")!=true)delay(10)}
            LocalBindingEpochs.revoke(context,uri);resolver.call(uri,"thumbnail-release",null,null)
            assertNull(withTimeout(5000){result.await()});assertNull(MediaThumbnails.load(context,media))
            assertFalse(java.io.File(context.cacheDir,"media-thumbnails-v1/${enrichmentKey(media)}.jpg").exists())
        }finally{resolver.call(uri,"thumbnail-release",null,null);result.cancelAndJoin();MediaThumbnails.remove(context,media)}
    }

}
