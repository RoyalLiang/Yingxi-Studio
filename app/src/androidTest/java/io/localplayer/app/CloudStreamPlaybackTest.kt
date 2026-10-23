package io.localplayer.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.localplayer.core.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CloudStreamPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun await(timeout:Long=20_000,condition:()->Boolean){val until=android.os.SystemClock.elapsedRealtime()+timeout;while(android.os.SystemClock.elapsedRealtime()<until){if(condition())return;Thread.sleep(100)};assertTrue("Playback condition timed out",condition())}

    @Test fun expiredSourceRefreshesWithoutLosingQueueProgressSpeedAndThenSwitchesBackToOriginal()=play(false)
    @Test fun realProviderMasterAdaptsDownWhenHighVariantBandwidthIsThrottled()=play(true)

    @Test fun variantOnlyAutoDegradesAfterRealBuffering()=play(true,variantOnly=true)
    @Test fun persistentSignedRejectionStopsAfterTwoAutomaticRenewals()=play(false,alwaysReject=true)

    private fun play(adaptive:Boolean,variantOnly:Boolean=false,alwaysReject:Boolean=false){
        val held=HeldCertificate.Builder().addSubjectAlternativeName("localhost").commonName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(held).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).followSslRedirects(false).build()
        val directory=File(ui.activity.cacheDir,"hls46")
        assertTrue("Seed HLS fixture",File(directory,"master.m3u8").isFile)
        val sample=File(ui.activity.cacheDir,"speed-2k-avc.mp4");assertTrue(sample.isFile)
        val served=AtomicInteger();val resolves=AtomicInteger();val authHeaders=AtomicInteger();val highChunks=AtomicInteger()
        val paths=java.util.concurrent.ConcurrentHashMap<String,AtomicInteger>()
        val server=MockWebServer();server.useHttps(serverTls.sslSocketFactory(),false)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(request.getHeader("Authorization")!=null)authHeaders.incrementAndGet()
            val name=request.requestUrl!!.encodedPath.removePrefix("/")
            paths.computeIfAbsent(name){AtomicInteger()}.incrementAndGet()
            if(name=="master.m3u8"&&!adaptive&&(alwaysReject||served.getAndIncrement()==0))return MockResponse().setResponseCode(403).setBody("expired")
            val file=File(directory,name)
            if('/' in name||!file.isFile)return MockResponse().setResponseCode(404)
            val response=MockResponse().setBody(Buffer().write(file.readBytes())).setHeader("Content-Type",if(name.endsWith("m3u8"))"application/vnd.apple.mpegurl"else"video/mp2t")
            if(adaptive&&name.startsWith("stream1-")&&name.endsWith(".ts")&&(!variantOnly||highChunks.incrementAndGet()>4))response.throttleBody(70*1024,1,TimeUnit.SECONDS)
            return response
        }}
        server.start()
        val source=Source(label="HLS测试片架",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="fixture-drive")
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String)=emptyList<Entry>()
            override fun open(path:String):RemoteFile=object:RemoteFile{
                private val file=RandomAccessFile(sample,"r")
                override val size=sample.length()
                @Synchronized override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{file.seek(position);return file.read(buffer,offset,length)}
                override fun close(){file.close()}
            }
            override fun close(){}
        }}
        val uris=listOf("one","two").map{PlaybackRegistry.add(source,Credentials(""),it,"$it.mp4",connector)}
        val originalItems=uris.mapIndexed{index,uri->MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).setMediaMetadata(MediaMetadata.Builder().setExtras(android.os.Bundle().apply{putString("preserved","history-token-$index")}).build()).build()}
        lateinit var player:ExoPlayer;lateinit var surface:SurfaceView;lateinit var recovery:CloudStreamRecovery;lateinit var autoController:CloudAdaptiveController
        var playerCreated=false;var recoveryCreated=false
        val frameCount=AtomicInteger();val highFrames=AtomicInteger();val lowAfterHigh=AtomicInteger();val originalFrames=AtomicInteger()
        try{
            main{
                val factory=PlaybackNetworkFactory(OkHttpDataSource.Factory(client)){uri,_->
                    resolves.incrementAndGet()
                    val selection=uri.getQueryParameter("quality")
                    val high=server.url("/stream1.m3u8?token=fixture-secret").toString()
                    val low=server.url("/stream0.m3u8?token=fixture-secret").toString()
                    if(selection=="FHD")high else PlaybackRegistry.get(uri).adaptive.resolve(
                        if(variantOnly)AliyunVideoInfo(listOf(AliyunVideoVariant("FHD",high),AliyunVideoVariant("LD",low)),null)
                        else AliyunVideoInfo(emptyList(),server.url("/master.m3u8?token=fixture-secret").toString()))
                }
                val bandwidth=DefaultBandwidthMeter.Builder(ui.activity).setInitialBitrateEstimate(12_000_000).build()
                player=ExoPlayer.Builder(ui.activity,HdrRenderersFactory(ui.activity)).setBandwidthMeter(bandwidth)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ui.activity,factory)).setLoadErrorHandlingPolicy(CloudStreamErrorPolicy())).build()
                playerCreated=true;recovery=CloudStreamRecovery(player);autoController=CloudAdaptiveController(player);recoveryCreated=true
                player.addListener(object:Player.Listener{
                    override fun onPlayerError(error:PlaybackException){println("HLS_ERROR adaptive=$adaptive code=${error.errorCodeName} chain="+generateSequence<Throwable>(error){it.cause}.take(10).map{it.javaClass.simpleName+(if(it is CloudStreamReadFailure)":"+it.status else "")}.toList())}
                    override fun onPlaybackStateChanged(state:Int){println("HLS_STATE adaptive=$adaptive state=$state")}
                    override fun onTracksChanged(tracks:Tracks){println("HLS_TRACKS "+tracks.groups.map{g->(0 until g.length).map{i->val f=g.getTrackFormat(i);listOf(g.type,g.isTrackSelected(i),g.isTrackSupported(i),f.sampleMimeType,f.width,f.height)}})}
                    override fun onVideoSizeChanged(size:VideoSize){println("HLS_SIZE adaptive=$adaptive width=${size.width} height=${size.height}")}
                })
                player.setVideoFrameMetadataListener{_,_,format,_->frameCount.incrementAndGet();if(format.height==1440)originalFrames.incrementAndGet();if(format.height>=720)highFrames.incrementAndGet();if(format.height==360&&highFrames.get()>0)lowAfterHigh.incrementAndGet()}
                ui.activity.setContent{AndroidView(modifier=Modifier.fillMaxSize(),factory={SurfaceView(it).also{view->surface=view;player.setVideoSurfaceView(view)}})}
            }
            ui.waitForIdle()
            main{
                player.setMediaItems(originalItems,1,2000);player.setPlaybackSpeed(if(adaptive)1f else 1.5f);player.playWhenReady=true
                changePlaybackQuality(player,"auto")
            }
            if(alwaysReject){
                await{resolves.get()>=3&&main{player.playerError!=null}}
                Thread.sleep(700);assertEquals(3,resolves.get())
                val messages=main{generateSequence<Throwable>(player.playerError!!){it.cause}.take(12).map{it.toString()}.joinToString()}
                assertFalse(messages.contains("fixture-secret"));assertFalse(messages.contains("https://"))
                assertQueue(player,originalItems,1.5f)
            }else if(adaptive){
                await(50_000){highFrames.get()>=8&&lowAfterHigh.get()>=8}
                assertNull(main{player.playerError})
                println("HLS_ADAPT variantOnly=$variantOnly highFrames=${highFrames.get()} lowAfterHigh=${lowAfterHigh.get()} position=${main{player.currentPosition}}")
            }else{
                await{frameCount.get()>=12&&main{player.playerError==null&&player.currentPosition>2600}}
                assertEquals(2,resolves.get())
                assertQueue(player,originalItems,1.5f)
                val first=picture(surface);Thread.sleep(350);assertNotEquals(first,picture(surface))
                val before=main{player.currentPosition}
                val framesBefore=frameCount.get()
                main{changePlaybackQuality(player,"FHD")}
                await{resolves.get()>=3&&frameCount.get()>=framesBefore+6&&main{player.videoFormat?.height==720&&player.currentPosition>=before}}
                assertQueue(player,originalItems,1.5f)
                main{changePlaybackQuality(player,"original")}
                await{originalFrames.get()>=6&&main{player.videoFormat?.height==1440&&player.currentPosition>=before}}
                assertQueue(player,originalItems,1.5f)
                assertTrue("Original bytes still present frames",frameCount.get()>20)
                println("HLS_REFRESH resolves=${resolves.get()} queue=2 index=1 speed=${main{player.playbackParameters.speed}} position=${main{player.currentPosition}} frames=${frameCount.get()}")
            }
            assertEquals("OAuth credentials must never reach CDN",0,authHeaders.get())
        }finally{
            println("HLS_END adaptive=$adaptive resolves=${resolves.get()} frames=${frameCount.get()} high=${highFrames.get()} lowAfterHigh=${lowAfterHigh.get()} paths="+paths.mapValues{it.value.get()})
            main{if(playerCreated)println("HLS_FINAL state=${player.playbackState} position=${player.currentPosition} error=${player.playerError?.errorCodeName} surface=${surface.width}x${surface.height}/${surface.holder.surface.isValid} video=${player.videoFormat?.height}");if(recoveryCreated){autoController.close();recovery.close()};if(playerCreated)player.release()}
            uris.forEach(PlaybackRegistry::remove);server.close()
        }
    }
    private fun assertQueue(player:Player,items:List<MediaItem>,speed:Float)=main{
        assertEquals(2,player.mediaItemCount);assertEquals(1,player.currentMediaItemIndex);assertEquals(speed,player.playbackParameters.speed,.001f);assertTrue(player.playWhenReady)
        assertEquals(items.map{it.mediaId},(0 until player.mediaItemCount).map{player.getMediaItemAt(it).mediaId})
        assertEquals("history-token-1",player.currentMediaItem!!.mediaMetadata.extras!!.getString("preserved"))
    }
    private fun picture(surface:SurfaceView):Long{
        val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);val done=CountDownLatch(1);var result=-1
        PixelCopy.request(surface,image,{result=it;done.countDown()},Handler(Looper.getMainLooper()))
        assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,result)
        val values=IntArray(160*90);image.getPixels(values,0,160,0,0,160,90);image.recycle()
        assertTrue(values.toSet().size>24)
        return values.fold(17L){a,b->a*31+b}
    }
}
