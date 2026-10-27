package io.localplayer.app

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PosterPersistenceTest {
    @Test fun bitmapIsReusedAcrossStoreInstancesAndEvictedOnReplacementAndRemoval() {
        val key=UUID.randomUUID().toString();val store=PosterStore(context)
        fun save(color:Int){store.save(key,Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888).apply{eraseColor(color)},JSONObject())}
        try {
            save(android.graphics.Color.RED)
            val old=store.metadata(key)!!;val first=store.image(old)!!
            assertSame(first,PosterStore(context).image(old))
            save(android.graphics.Color.BLUE)
            assertNull(store.image(old))
            val current=store.metadata(key)!!;val replacement=store.image(current)!!
            assertNotSame(first,replacement);assertColor(android.graphics.Color.BLUE,replacement.getPixel(0,0))
            store.remove(key);assertNull(store.image(current))
        } finally {store.remove(key)}
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun sourceRootAndDocumentVolumeUseTheActualShowDirectory() {
        val source=Source(label="Shows",protocol=Protocol.WEBDAV,host="example.invalid",root="/漫长的季节")
        assertEquals("漫长的季节",posterIdentity(MediaRef("remote","1.mkv","Shows",path="1.mkv"),source).title)
        val tree=android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
        val uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"primary:漫长的季节/1.mkv")
        val local=posterIdentity(MediaRef("local","1.mkv","Local",localUri=uri.toString()))
        assertEquals("漫长的季节",local.title);assertTrue(local.series)
    }
    private fun image(color:Int)=ByteArrayOutputStream().also{out->Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}.compress(Bitmap.CompressFormat.PNG,100,out)}.toByteArray()
    private fun assertColor(expected:Int,actual:Int){for(shift in listOf(0,8,16))assertTrue(kotlin.math.abs(((expected shr shift)and 255)-((actual shr shift)and 255))<=3)}
    private fun seed(media:MediaRef):PosterStore {
        val identity=posterIdentity(media).let{listOf(it.title,it.year,it.season,it.series).joinToString("|")}
        return PosterStore(context).apply{save(enrichmentKey(media),MediaPosters.decode(image(android.graphics.Color.RED))!!,
            JSONObject().put("identity",identity).put("title",media.title).put("policy",2).put("checkedAt",0).put("confidence",.96).put("credit","old").put("url","https://example.com/old"))}
    }
    @Test fun cachedImageReturnsWhileBackgroundIsBlockedThenPersistsTheBetterCover():Unit=runBlocking {
        val media=MediaRef("persistent-"+UUID.randomUUID(),"Example.mp4","Local")
        val previous=MediaPosters.onlineLookup;val downloader=MediaPosters.imageDownload
        val prefs=EnrichmentPreferences(context);val enabled=prefs.posters;prefs.posters=true
        val store=seed(media);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        MediaPosters.onlineLookup={_,c->entered.countDown();while(!release.await(10,TimeUnit.MILLISECONDS))c.check();PosterMatch("https://example.com/new.jpg","https://example.com/new","new",.99)}
        MediaPosters.imageDownload={_,_->image(android.graphics.Color.BLUE)}
        try {
            val old=withTimeout(1000){MediaPosters.load(context,media)}!!
            assertColor(android.graphics.Color.RED,old.getPixel(0,0))
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertEquals("old",store.metadata(enrichmentKey(media))!!.getString("credit"))
            release.countDown();withTimeout(4000){MediaPosters.awaitRefresh()}
            val saved=store.metadata(enrichmentKey(media))!!
            assertEquals("new",saved.getString("credit"));assertEquals(.99,saved.getDouble("confidence"),.001)
            assertColor(android.graphics.Color.BLUE,store.image(saved)!!.getPixel(0,0))
            File(context.cacheDir,"media-posters-v1/${enrichmentKey(media)}.jpg").delete()
            MediaPosters.onlineLookup={_,_->throw AssertionError("Fresh local poster must not be scraped again")}
            assertColor(android.graphics.Color.BLUE,MediaPosters.load(context,media)!!.getPixel(0,0))
        } finally {release.countDown();MediaPosters.awaitRefresh();MediaPosters.remove(context,media);MediaPosters.onlineLookup=previous;MediaPosters.imageDownload=downloader;prefs.posters=enabled}
    }
    @Test fun uncertainOrFailedRefreshKeepsTheSavedCover():Unit=runBlocking {
        val previous=MediaPosters.onlineLookup;val downloader=MediaPosters.imageDownload
        val prefs=EnrichmentPreferences(context);val enabled=prefs.posters;prefs.posters=true
        try {for(failure in listOf(false,true)){
            val media=MediaRef("keep-cover-"+UUID.randomUUID(),"Example.mp4","Local");val store=seed(media)
            MediaPosters.onlineLookup={_,_->if(failure)throw java.io.IOException("offline") else PosterMatch("https://example.com/wrong","https://example.com/wrong","wrong",.5)}
            MediaPosters.imageDownload={_,_->throw AssertionError("An uncertain poster cannot be downloaded")}
            try {
                assertNotNull(MediaPosters.load(context,media));MediaPosters.awaitRefresh()
                assertEquals("old",store.metadata(enrichmentKey(media))!!.getString("credit"))
                assertNotNull(MediaPosters.load(context,media));MediaPosters.awaitRefresh()
            } finally {MediaPosters.remove(context,media)}
        }}finally{MediaPosters.onlineLookup=previous;MediaPosters.imageDownload=downloader;prefs.posters=enabled}
    }
    @Test fun localReadsDoNotQueueBehindTwoBlockedOnlineChecks():Unit=runBlocking {
        val previous=MediaPosters.onlineLookup
        val prefs=EnrichmentPreferences(context);val enabled=prefs.posters;prefs.posters=true
        // Give the two blocked requests independent lock stripes; a deliberate same-cover lock is not network starvation.
        val media=mutableListOf<MediaRef>()
        while(media.size<3){
            val next=MediaRef("parallel-cover-"+UUID.randomUUID(),"Example.mp4","Local")
            if(media.none{(enrichmentKey(it).hashCode()and Int.MAX_VALUE)%16==(enrichmentKey(next).hashCode()and Int.MAX_VALUE)%16})media+=next.also(::seed)
        }
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        MediaPosters.onlineLookup={_,c->entered.countDown();while(!release.await(10,TimeUnit.MILLISECONDS))c.check();null}
        try{
            media.take(2).forEach{assertNotNull(withTimeout(1000){MediaPosters.load(context,it)})}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertNotNull(withTimeout(1000){MediaPosters.load(context,media.last())})
        } finally {release.countDown();MediaPosters.awaitRefresh();media.forEach{MediaPosters.remove(context,it)};MediaPosters.onlineLookup=previous;prefs.posters=enabled}
    }
    @Test fun removedHistoryCannotBeWrittenBackByAnOldBackgroundCheck():Unit=runBlocking {
        val previous=MediaPosters.onlineLookup;val downloader=MediaPosters.imageDownload
        val prefs=EnrichmentPreferences(context);val enabled=prefs.posters;prefs.posters=true
        val media=MediaRef("remove-cover-"+UUID.randomUUID(),"Example.mp4","Local");val store=seed(media)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        MediaPosters.onlineLookup={_,c->entered.countDown();while(!release.await(10,TimeUnit.MILLISECONDS))c.check();PosterMatch("https://example.com/new","https://example.com/new","new")}
        MediaPosters.imageDownload={_,_->image(android.graphics.Color.BLUE)}
        try{
            assertNotNull(MediaPosters.load(context,media));assertTrue(entered.await(3,TimeUnit.SECONDS))
            MediaPosters.remove(context,media);release.countDown();delay(100)
            assertNull(store.metadata(enrichmentKey(media)))
        }finally{release.countDown();MediaPosters.awaitRefresh();MediaPosters.remove(context,media);MediaPosters.onlineLookup=previous;MediaPosters.imageDownload=downloader;prefs.posters=enabled}
    }
}
