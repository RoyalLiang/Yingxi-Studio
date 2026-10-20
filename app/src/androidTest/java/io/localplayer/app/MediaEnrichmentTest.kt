package io.localplayer.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import io.localplayer.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaEnrichmentTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=ui.activity.application
    private val sources=mutableListOf<Source>()
    private val models=ViewModelStore()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var oldPosters=true
    private var oldSubtitles=true
    private lateinit var oldLookup:(MediaIdentity,Cancellation)->PosterMatch?
    private lateinit var oldConnector:Connector
    @Before fun setup(){
        val prefs=EnrichmentPreferences(context);oldPosters=prefs.posters;oldSubtitles=prefs.subtitles
        prefs.posters=false;prefs.subtitles=false
        oldLookup=MediaPosters.onlineLookup;oldConnector=MediaPosters.connector
        OnlineSubtitles.cancel()
    }
    @After fun cleanup(){
        scope.cancel();ui.runOnUiThread{models.clear();OnlineSubtitles.cancel()}
        MediaPosters.onlineLookup=oldLookup;MediaPosters.connector=oldConnector
        sources.forEach{SourceStore(context).delete(it.id)}
        EnrichmentPreferences(context).apply{posters=oldPosters;subtitles=oldSubtitles}
    }
    @Test fun cloudHistoryCanBePreparedTwiceWithoutBrowsingSource(){
        val source=Source(id="replay-"+UUID.randomUUID(),label="云盘重播",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
        sources+=source;SourceStore(context).save(source,"synthetic-fixture",true)
        val lists=AtomicInteger()
        val connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String):List<Entry>{cancellation.check();lists.incrementAndGet();return listOf(Entry("Film.mp4","video",false,48000))}
                override fun open(path:String):RemoteFile=error("Preparation only")
                override fun close(){}
            }
        }
        val media=MediaRef(source.id+":video","Film.mp4",source.label,source.id,"video",sizeBytes=48000,location=mediaLocation(source))
        val history=HistoryEntry(media,2500,120000,System.currentTimeMillis(),false)
        val deliveries=AtomicInteger()
        lateinit var vm:LibraryViewModel
        ui.runOnUiThread{
            vm=LibraryViewModel(context,connector);models.put("replay",vm)
            scope.launch{vm.plays.collect{deliveries.incrementAndGet();it.release()}}
            vm.resume(history)
        }
        ui.waitUntil(10000){deliveries.get()==1&&vm.preparingKey==null}
        ui.runOnUiThread{vm.resume(history)}
        ui.waitUntil(10000){deliveries.get()==2&&vm.preparingKey==null}
        assertEquals(2,lists.get());assertNull(vm.error);assertEquals(media.key,vm.selectedHistoryKey)
    }
    @Test fun recentSelectionHasSemanticsAndDoesNotShiftCardBounds(){
        val media=MediaRef("selection-"+UUID.randomUUID(),"测试影片.mp4","本机")
        var selected by mutableStateOf(false)
        ui.activity.runOnUiThread{ui.activity.setContent{PlayerTheme{
            androidx.compose.foundation.layout.Box(Modifier.width(260.dp)){
                RecentFilm(HistoryEntry(media,2500,120000,1,false),false,selected,false,{selected=true},{},{})
            }
        }}}
        val node=ui.onNodeWithTag("home-film:"+media.key)
        node.assertIsNotSelected()
        val before=node.fetchSemanticsNode().boundsInRoot
        val title=ui.onNodeWithText(media.title,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val status=ui.onNodeWithText("00:02 / 02:00",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("Playback status must sit inside the poster, above its title",status.bottom<title.top)
        val unselected=node.captureToImage().asAndroidBitmap()
        node.performClick().assertIsSelected()
        ui.mainClock.advanceTimeBy(500) // Let the touch ripple finish before comparing the selected state.
        assertEquals(before,node.fetchSemanticsNode().boundsInRoot)
        val selectedImage=node.captureToImage().asAndroidBitmap()
        File(context.cacheDir,"enrichment-selected.png").outputStream().use{selectedImage.compress(Bitmap.CompressFormat.PNG,100,it)}
        File(context.cacheDir,"enrichment-unselected.png").outputStream().use{unselected.compress(Bitmap.CompressFormat.PNG,100,it)}
        val belowPoster=(title.top-before.top).toInt()
        for(y in belowPoster until unselected.height)for(x in 0 until unselected.width) {
            assertEquals("Selection must not paint the title area at $x,$y",unselected.getPixel(x,y),selectedImage.getPixel(x,y))
        }
    }

    @Test fun posterStatusKeepsWhiteTextReadableOverWhiteAndBlackImages() {
        val entries=listOf(Color.White,Color.Black).mapIndexed { index,color ->
            HistoryEntry(MediaRef("contrast-$index","高对比海报","本机"),65000,7200000,1,false) to color
        }
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            Column(Modifier.width(220.dp)) {
                entries.forEach { (entry,color) ->
                    Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(color)) {
                        PosterPlaybackStatus(entry,Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        } } }
        entries.forEach { (entry,_) ->
            val status=ui.onNodeWithTag("film-status:${entry.media.key}",useUnmergedTree=true)
            val image=status.captureToImage().asAndroidBitmap()
            val white=androidx.core.graphics.ColorUtils.calculateLuminance(android.graphics.Color.WHITE)
            val background=image.getPixel(2,image.height/2)
            val contrast=(white+.05)/(androidx.core.graphics.ColorUtils.calculateLuminance(background)+.05)
            assertTrue("Status scrim must preserve at least 4.5:1 contrast",contrast>=4.5)
            File(context.cacheDir,"poster-${entry.media.key}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @Test fun sidecarPosterUsesShortReadsCachesAndRespectsSourceIdentity()=runBlocking{
        EnrichmentPreferences(context).posters=true
        val source=Source(id="poster-"+UUID.randomUUID(),label="海报夹具",protocol=Protocol.WEBDAV,host="poster.invalid")
        sources+=source;SourceStore(context).save(source,"",true)
        val bytes=java.io.ByteArrayOutputStream().also{out->
            Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.MAGENTA)}.compress(Bitmap.CompressFormat.PNG,100,out)
        }.toByteArray()
        val opens=AtomicInteger()
        MediaPosters.onlineLookup={_,_->throw AssertionError("Directory poster must take priority")}
        MediaPosters.connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String)=listOf(Entry("Film.mp4","Film.mp4",false,100000),Entry("poster.jpg","poster.jpg",false,bytes.size.toLong()))
                override fun open(path:String):RemoteFile{
                    opens.incrementAndGet()
                    return object:RemoteFile{
                        override val size=bytes.size.toLong()
                        override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{
                            val n=minOf(31,length,bytes.size-position.toInt());if(n<=0)return -1
                            bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+n);return n
                        }
                        override fun close(){}
                    }
                }
                override fun close(){}
            }
        }
        val media=MediaRef(source.id+":film","Film.mp4",source.label,source.id,"Film.mp4",location=mediaLocation(source))
        try{
            val poster=MediaPosters.load(context,media)
            assertNotNull(poster);assertEquals(160,poster!!.width);assertEquals(240,poster.height)
            assertNotNull(MediaPosters.load(context,media));assertEquals(1,opens.get())
            val metadata=File(context.cacheDir,"media-posters-v1/${enrichmentKey(media)}.json")
            val expired=org.json.JSONObject(metadata.readText()).put("fetchedAt",System.currentTimeMillis()-8L*24*60*60*1000)
            metadata.writeText(expired.toString())
            assertNull(MediaPosters.credit(context,media))
            assertNotNull(MediaPosters.load(context,media));assertEquals(2,opens.get())
            metadata.writeText(org.json.JSONObject(metadata.readText()).put("policy",1).toString())
            assertNotNull(MediaPosters.load(context,media));assertEquals(3,opens.get())
            metadata.writeText("{\"credit\":\"old\",\"url\":\"\"}")
            assertNotNull(MediaPosters.load(context,media));assertEquals(4,opens.get())

            SourceStore(context).save(source.copy(root="/different"),"",true)
            assertNull(MediaPosters.load(context,media))
            EnrichmentPreferences(context).posters=false
            assertNull(MediaPosters.load(context,media))
        }finally{MediaPosters.remove(context,media)}
    }
    @Test fun cancelledPosterRequestClosesBlockingWork(): Unit = runBlocking {
        EnrichmentPreferences(context).posters=true
        val started=java.util.concurrent.CountDownLatch(1)
        val stopped=java.util.concurrent.CountDownLatch(1)
        MediaPosters.onlineLookup={_,c->
            started.countDown()
            try{while(true){c.check();Thread.sleep(10)}}finally{stopped.countDown()}
            @Suppress("UNREACHABLE_CODE") null
        }
        val media=MediaRef("cancel-poster-"+UUID.randomUUID(),"Example.mp4","Local")
        val request=launch(Dispatchers.IO){MediaPosters.load(context,media)}
        try{
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
            request.cancel()
            withTimeout(3000){request.join()}
            assertEquals(0L,stopped.count)
        }finally{request.cancel();MediaPosters.remove(context,media)}
    }

    @Test fun automaticSubtitleAttachmentPreservesQueueAndPositionAndRendersCue(){
        val media=File(context.cacheDir,"seek-performance.mp4");assertTrue(media.isFile)
        val subtitle=File(context.cacheDir,"enrichment-test.srt").apply{writeText("1\n00:00:00,000 --> 00:00:30,000\n自动匹配字幕验证\n")}
        lateinit var player:ExoPlayer
        var initialized=false
        try{
            ui.runOnUiThread{
                player=ExoPlayer.Builder(context).build();initialized=true
                player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(media)),MediaItem.fromUri(Uri.fromFile(media))),0,2500)
                player.prepare()
            }
            ui.waitUntil(15000){var ready=false;ui.runOnIdle{ready=player.playbackState==Player.STATE_READY};ready}
            ui.runOnUiThread{OnlineSubtitles.attach(player,subtitle)}
            ui.waitUntil(15000){var ready=false;ui.runOnIdle{ready=player.playbackState==Player.STATE_READY&&player.currentCues.cues.any{it.text.toString().contains("自动匹配字幕验证")}};ready}
            ui.runOnUiThread{
                assertEquals(2,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex)
                assertTrue(kotlin.math.abs(player.currentPosition-2500)<500);assertFalse(player.playWhenReady)
                assertEquals("zh",player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.single().language)
            }
        }finally{ui.runOnUiThread{if(initialized)player.release()};subtitle.delete()}
    }
}
