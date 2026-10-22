package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.localplayer.core.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Real Aliyun connector, signed HTTPS ranges, service, codecs and video pixels. No provider account. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AliyunActualPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun avcFromBeginning()=play(false,0)
    @Test fun avcFromResume()=play(false,2000)
    @Test fun main10FromBeginning()=play(true,0)
    @Test fun main10FromResume()=play(true,2000)
    @Test fun main10MkvFromBeginningWithSlowHeaders()=play(true,0,true)
    @Test fun main10MkvFromResumeWithSlowHeaders()=play(true,2000,true)
    private fun surface(view:View):SurfaceView?{
        if(view is SurfaceView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)surface(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun picture(surface:SurfaceView):Long{
        val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);val done=CountDownLatch(1);var status=-1
        PixelCopy.request(surface,image,{status=it;done.countDown()},Handler(Looper.getMainLooper()))
        assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
        val pixels=IntArray(160*90);image.getPixels(pixels,0,160,0,0,160,90);image.recycle()
        assertTrue("Decoded picture should contain real colors",pixels.toSet().size>24)
        return pixels.fold(17L){hash,pixel->hash*31+pixel}
    }
    private fun play(hevc:Boolean,start:Long,matroska:Boolean=false){
        val app=ui.activity.application
        val sample=File(app.cacheDir,if(matroska)"hevc-main10-1080p.mkv" else if(hevc)"hevc-main10-1080p.mp4" else "continuity-avc1080.mp4")
        assertTrue(sample.isFile);val data=sample.readBytes()
        val held=HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(held).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        MockWebServer().use{cdn->MockWebServer().use{apiServer->
            cdn.useHttps(serverTls.sslSocketFactory(),false)
            val fileQueries=AtomicInteger();val urlQueries=AtomicInteger();val rangeQueries=AtomicInteger()
            cdn.dispatcher=object:Dispatcher(){
                override fun dispatch(request:RecordedRequest):MockResponse{
                    assertNull(request.getHeader("Authorization"));rangeQueries.incrementAndGet()
                    val range=Regex("bytes=([0-9]+)-").matchEntire(request.getHeader("Range").orEmpty())?:return MockResponse().setResponseCode(400)
                    val position=range.groupValues[1].toInt()
                    if(matroska)println("ALIYUN_MKV_RANGE position=$position")
                    return MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $position-${data.lastIndex}/${data.size}")
                        .setHeadersDelay(if(matroska)3000 else 150,TimeUnit.MILLISECONDS).setBody(okio.Buffer().write(data,position,data.size-position))
                }
            }
            apiServer.dispatcher=object:Dispatcher(){
                override fun dispatch(request:RecordedRequest):MockResponse=when(request.path){
                    "/adrive/v1.0/openFile/get"->{fileQueries.incrementAndGet();MockResponse().setBody(JSONObject().put("file_id","video").put("name",sample.name).put("type","file").put("size",data.size).toString())}
                    "/adrive/v1.0/openFile/getDownloadUrl"->{urlQueries.incrementAndGet();MockResponse().setHeadersDelay(250,TimeUnit.MILLISECONDS).setBody(JSONObject().put("url",cdn.url("/video").toString()).apply{if(matroska)put("description","当前用户未购买权益包，下载速度可能较慢。")}.toString())}
                    else->MockResponse().setResponseCode(404)
                }
            }
            val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T)=action("synthetic-token")}
            // Keep fixture endpoint injection inside instrumentation; production exposes only the official API.
        val api=AliyunApi::class.java.getConstructor(okhttp3.HttpUrl::class.java,OkHttpClient::class.java)
            .newInstance(apiServer.url("/"),OkHttpClient.Builder().followRedirects(false).build())
            val source=Source(label="Aliyun playback fixture",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
            val connector=AliyunConnector(api,access,downloadClient=client)
            val ref=MediaRef(source.id+":video",sample.name,source.label,source.id,"video",sizeBytes=data.size.toLong(),location=mediaLocation(source))
            val uri=PlaybackRegistry.add(source,Credentials(""),"video",sample.name,connector)
            val target=PlayTarget(uri,ref.title,start,ref)
            val prefs=EnrichmentPreferences(app);val posters=prefs.posters;val subtitles=prefs.subtitles
            prefs.posters=false;prefs.subtitles=false
            var activity:ActivityScenario<PlaybackActivity>?=null;var controller:MediaController?=null
            try{
                main{PlaybackMetrics.reset()};val began=android.os.SystemClock.elapsedRealtime()
                activity=ActivityScenario.launch(PlaybackActivity.intent(app,target))
                val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}
                val player=future.get(10,TimeUnit.SECONDS);controller=player
                ui.waitUntil(25000){main{player.playerError!=null||(player.isPlaying&&player.currentPosition>=start+250&&PlaybackMetrics.snapshot().firstFrameMs!=null)}}
                main{assertNull(player.playerError);assertTrue(player.videoSize.width>0)}
                var output:SurfaceView?=null;activity.onActivity{output=surface(it.window.decorView)}
                assertNotNull(output);val first=picture(output!!)
                val then=main{player.currentPosition}
                ui.waitUntil(10000){main{player.currentPosition>=then+500||player.playerError!=null}}
                main{assertNull(player.playerError)};assertNotEquals(first,picture(output!!))
                println("ALIYUN_ACTUAL hevc=$hevc mkv=$matroska start=$start validatedAtMs=${android.os.SystemClock.elapsedRealtime()-began} files=${fileQueries.get()} urls=${urlQueries.get()} ranges=${rangeQueries.get()}")
                val seek=if(start==0L)4000L else 1000L
                main{player.seekTo(seek)}
                ui.waitUntil(15000){main{player.playerError!=null||(player.playbackState==Player.STATE_READY&&player.isPlaying&&player.currentPosition in (seek+250)..(seek+1800))}}
                main{assertNull(player.playerError)};assertNotEquals(first,picture(output!!))
                assertEquals("Same playback session reuses file metadata",1,fileQueries.get())
                assertEquals("Same playback session reuses the signed location",1,urlQueries.get())
                val report=main{JSONObject(playbackDiagnostic(player))}
                assertTrue(report.getInt("videoInputBuffers")>0);assertTrue(report.getInt("videoRenderedBuffers")>0)
                assertEquals(matroska,report.getBoolean("downloadRightsNotice"));assertEquals("ALIYUN",report.getString("source"));assertTrue(report.getLong("rangeRequests")>0)
                assertTrue(report.getLong("sourceReadBytes")>0)
                println("ALIYUN_ACTUAL_SEEK hevc=$hevc start=$start files=${fileQueries.get()} urls=${urlQueries.get()} ranges=${rangeQueries.get()} diagnostics=$report")
            }finally{
                main{controller?.let{it.stop();it.clearMediaItems();it.release()}};activity?.close()
                PlaybackRequests.remove(target.playId);PlaybackRegistry.remove(uri);HistoryStore(app).remove(ref.key)
                prefs.posters=posters;prefs.subtitles=subtitles
            }
        }}
    }
}
