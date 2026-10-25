package io.localplayer.app

import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class WebDavPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;instrumentation.runOnMainSync{result=runCatching(block)};return result!!.getOrThrow()}
    private fun await(detail:()->String={""},block:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(System.nanoTime()<end){ui.waitForIdle();if(block())return;Thread.sleep(100)};if(!block()){
        val screen=instrumentation.uiAutomation.takeScreenshot()
        try{File(context.getExternalFilesDir(null),"dav-timeout.png").outputStream().use{screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{screen.recycle()}
        fail("Timed out waiting for playback: ${detail()}")
    }}

    @Test fun webDavSourceAuthenticatesBrowsesSeeksAndKeepsEpisodeQueue() {
        val sample=File(context.getExternalFilesDir(null),"dav-sample.mp4")
        assertTrue("Push synthetic dav-sample.mp4 before this test",sample.isFile)
        DavFixture(sample).use { server ->
            val source=Source(label="DAV-${UUID.randomUUID()}",protocol=Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val store=SourceStore(context)
            var controller:MediaController?=null
            val keys=server.names.map{"${source.id}:$it"}
            try {
                // Exercise the real form, including the explicit cleartext choice.
                ui.onNodeWithTag("nav:Sources").performClick()
                ui.onNodeWithContentDescription("登记信源").performClick()
                ui.onNodeWithTag("source-category-tower").performClick()
                ui.onNodeWithTag("connection-protocol-selector").performClick()
                ui.onNodeWithText("WebDAV").performClick()

                ui.onNode(hasText("WebDAV 地址") and hasSetTextAction()).assertIsDisplayed()
                // Fill form state without opening an IME that can recycle LazyColumn nodes.
                ui.runOnIdle {
                    val vm=androidx.lifecycle.ViewModelProvider(ui.activity)[LibraryViewModel::class.java]
                    vm.draft=vm.draft!!.copy(source=vm.draft!!.source.copy(label=source.label,username=server.username),webDavUrl=source.webDavAddress,password=server.password)
                }
                ui.onNodeWithTag("connection-form").performScrollToNode(hasText("连接并浏览"))
                ui.onNodeWithText("连接并浏览").performClick()
                ui.onAllNodesWithText("HTTP 不加密，请先确认允许明文连接").onFirst().assertExists()
                ui.onNodeWithTag("connection-form").performScrollToNode(hasTestTag("allow-http"))
                ui.onNodeWithTag("allow-http").performClick()
                ui.onNodeWithTag("connection-form").performScrollToNode(hasText("连接并浏览"))
                ui.onNodeWithText("连接并浏览").performClick()
                ui.waitUntil(15000){ui.onAllNodesWithText(mediaDisplayTitle(server.names.first())).fetchSemanticsNodes().isNotEmpty()}
                val saved=store.load().single{it.label==source.label}
                assertEquals(server.password,store.password(saved.id));assertFalse(saved.secure)
                // Connect the observer before the UI controller owns the video surface.
                val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
                val player=future.get(15,TimeUnit.SECONDS);controller=player
                ui.onNodeWithText(mediaDisplayTitle(server.names.first())).performClick()
                await(detail={main{"state=${player.playbackState}, playing=${player.isPlaying}, ready=${player.playWhenReady}, suppressed=${player.playbackSuppressionReason}, items=${player.mediaItemCount}, title=${player.currentMediaItem?.mediaMetadata?.title}, duration=${player.duration}, video=${player.videoSize.width}x${player.videoSize.height}, error=${player.playerError?.cause}, rangeRequests=${server.positions.size}"}}){main{player.isPlaying&&player.videoSize.width>0&&player.duration>90000}}
                await{PlaybackMetrics.snapshot().firstFrameMs!=null}
                println("DAV first frame: ${PlaybackMetrics.snapshot().firstFrameMs} ms")
                main{player.pause()}
                val preview=PreviewFrames(context)
                val probesBefore=server.previewProbes.get()
                try {
                    val uri=PlaybackRegistry.add(saved,Credentials(server.password),server.names.last(),server.names.last())
                    try { kotlinx.coroutines.runBlocking {
                        assertNotNull(kotlinx.coroutines.withTimeout(12000){preview.get(uri,15000)})
                        assertNotNull(kotlinx.coroutines.withTimeout(12000){preview.get(uri,60000)})
                    }
                    }finally{PlaybackRegistry.remove(uri)}
                    assertEquals("Seek previews add no separate one-byte metadata probes",0,server.previewProbes.get()-probesBefore)
                }finally{preview.close()}
                ui.onNodeWithTag("player-subtitles").performClick()
                val panel=ui.onNodeWithTag("audio-subtitle-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val display=instrumentation.uiAutomation.takeScreenshot()
                try {
                    assertTrue("Track panel must leave video visible",panel.width<display.width*.65f)
                    assertTrue("Track panel must fit vertically",panel.height<=display.height)
                    var colorful=0
                    for(y in display.height/4 until display.height*3/4 step 12)for(x in display.width/8 until display.width/3 step 12){
                        val pixel=display.getPixel(x,y)
                        val channels=listOf(android.graphics.Color.red(pixel),android.graphics.Color.green(pixel),android.graphics.Color.blue(pixel))
                        if(channels.max()-channels.min()>40&&channels.max()>60)colorful++
                    }
                    assertTrue("Rendered video must remain visible beside the panel, not black",colorful>20)
                    File(context.getExternalFilesDir(null),"audio-subtitle-panel.png").outputStream().use{display.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }finally{display.recycle()}
                ui.onNodeWithText("播放速度").assertDoesNotExist()
                ui.onNodeWithContentDescription("关闭播放选项").performClick()
                ui.onNodeWithTag("playback-speed").performClick()
                ui.onNodeWithTag("speed-option-1.0",useUnmergedTree=true).performClick()
                assertEquals(2,main{player.mediaItemCount})
                ui.onNodeWithTag("playback-progress").performTouchInput{down(center);moveBy(androidx.compose.ui.geometry.Offset(24f,0f))}
                try {
                    ui.waitUntil(15000){ui.onAllNodesWithContentDescription("进度预览").fetchSemanticsNodes().isNotEmpty()}
                }finally{ui.onNodeWithTag("playback-progress").performTouchInput{up()}}
                main{player.pause();player.seekTo(75000);player.play()}
                await{main{player.isPlaying&&player.currentPosition>=75000&&player.playerError==null}}
                main{player.seekToNextMediaItem()}
                await{main{player.currentMediaItemIndex==1&&player.isPlaying&&player.videoSize.width>0}}
                main{player.seekToPreviousMediaItem()}
                await{main{player.currentMediaItemIndex==0&&player.isPlaying}}
                assertTrue(server.positions.any{it>0})
                main{player.pause()}
                await{HistoryStore(context).entries().any{it.media.sourceId==saved.id}}
                val entry=HistoryStore(context).entries().first{it.media.sourceId==saved.id}
                assertEquals(mediaLocation(saved),entry.media.location)
            } finally {
                controller?.let{main{it.stop();it.clearMediaItems();it.release()}}
                store.load().filter{it.label==source.label}.forEach{saved->
                    HistoryStore(context).entries().filter{it.media.sourceId==saved.id}.forEach{HistoryStore(context).remove(it.media.key)}
                    store.delete(saved.id)
                }
                keys.forEach{HistoryStore(context).remove(it)}
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            }
        }
    }
}

/** A local authenticated DAV fixture; no real accounts or network services are used. */
internal class DavFixture(private val media:File,private val chunkDelayMs:Long=40,private val headerDelayMs:Long=0):java.io.Closeable {
    val username="reader";val password=UUID.randomUUID().toString()
    val names=listOf("01-海边.mp4","02-落日.mp4")
    val mediaProbes=java.util.concurrent.atomic.AtomicInteger()
    val previewProbes=java.util.concurrent.atomic.AtomicInteger()
    val positions=java.util.Collections.synchronizedList(mutableListOf<Long>())
    private val server=ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"))
    val port get()=server.localPort
    private val pool=Executors.newCachedThreadPool()
    init{pool.execute{while(!server.isClosed){val socket=try{server.accept()}catch(e:Exception){break};pool.execute{runCatching{respond(socket)};runCatching{socket.close()}}}}}
    private fun respond(socket:Socket) {
        socket.soTimeout=10000
        val reader=socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val first=reader.readLine()?.split(' ') ?: return
        val headers=mutableMapOf<String,String>()
        while(true){val line=reader.readLine() ?: return;if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
        val length=headers["content-length"]?.toIntOrNull() ?: 0
        if(length>0){val body=CharArray(length);var count=0;while(count<length){val n=reader.read(body,count,length-count);if(n<0)return;count+=n}}
        val auth="Basic "+android.util.Base64.encodeToString("$username:$password".toByteArray(),android.util.Base64.NO_WRAP)
        val output=socket.getOutputStream()
        fun header(status:String,size:Long,extra:String=""){output.write("HTTP/1.1 $status\r\nContent-Length: $size\r\nConnection: close\r\n$extra\r\n".toByteArray())}
        if(headers["authorization"]!=auth){header("401 Unauthorized",0);return}
        if(first[0]=="PROPFIND"){
            val body=("<d:multistatus xmlns:d=\"DAV:\">"+names.joinToString(""){name->"<d:response><d:href>"+URI(null,null,"/dav/$name",null).toASCIIString()+"</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${media.length()}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"}+"</d:multistatus>").toByteArray()
            header("207 Multi-Status",body.size.toLong(),"Content-Type: application/xml\r\n");output.write(body);return
        }
        if(headerDelayMs>0)Thread.sleep(headerDelayMs)
  val range=headers["range"]?.removePrefix("bytes=") ?: "0-"
        val start=range.substringBefore('-').toLong();val end=range.substringAfter('-').toLongOrNull() ?: media.length()-1
        positions.add(start)
        if(range=="0-0")mediaProbes.incrementAndGet()
        if(range=="0-0"&&URI(first[1]).path.endsWith(names.last()))previewProbes.incrementAndGet()
        header("206 Partial Content",end-start+1,"Content-Type: video/mp4\r\nContent-Range: bytes $start-$end/${media.length()}\r\n")
        RandomAccessFile(media,"r").use { input->input.seek(start);var remaining=end-start+1;val buffer=ByteArray(8192);while(remaining>0){val n=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(n<0)break;output.write(buffer,0,n);output.flush();remaining-=n;if(remaining>0&&end>0)Thread.sleep(chunkDelayMs)} }
    }
    override fun close(){server.close();pool.shutdownNow()}
}
