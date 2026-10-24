package io.localplayer.app

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import io.localplayer.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDiagnosticTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun pendingOperationsSurviveWaitingButNotMediaReset(){
        PlaybackMetrics.reset()
        val open=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.OPEN,"SFTP")
        Thread.sleep(40)
        val read=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.READ)
        assertEquals(PlaybackIoProbe.Operation.OPEN,PlaybackIoProbe.snapshot().operation)
        assertTrue(PlaybackIoProbe.snapshot().waitingMs>=30)
        PlaybackIoProbe.end(open)
        assertEquals(PlaybackIoProbe.Operation.READ,PlaybackIoProbe.snapshot().operation)
        PlaybackIoProbe.progress(read,1)
        assertNotNull(PlaybackIoProbe.snapshot().sinceProgressMs)
        PlaybackMetrics.reset()
        PlaybackIoProbe.end(read,java.io.IOException("private server and filename"))
        assertNull(PlaybackIoProbe.snapshot().operation)
        assertEquals(0,PlaybackIoProbe.snapshot().failures)
        assertNull(PlaybackIoProbe.snapshot().sinceProgressMs)
    }

    @Test fun codecDiagnosticAcceptsDv5ButRejectsAddressesAndFreeText(){
        assertEquals("dvhe.05.06",diagnosticCodec("dvhe.05.06"))
        assertEquals("hvc1.2.4.L153.B0",diagnosticCodec("hvc1.2.4.L153.B0"))
        assertNull(diagnosticCodec(android.net.Uri.Builder().scheme("smb").encodedAuthority("user:password@private.invalid").appendPath("film.mkv").build().toString()))
        assertNull(diagnosticCodec("dvhe.05.06,private-film-title"))
    }
    @Test fun copiedReportDoesNotContainUriTitleOrRawFailure(){
        PlaybackMetrics.reset()
        val operation=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.OPEN,"SMB")
        val transport=RemoteReadTelemetry();transport.urlRequest();PlaybackIoProbe.attach(operation,transport)
        PlaybackIoProbe.end(operation,java.io.IOException("nas-secret/private-film password123"))
        var report=""
        ui.runOnUiThread{
            val player=ExoPlayer.Builder(ui.activity).build()
            try{
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.Builder().scheme("https").encodedAuthority("user:password123@nas-secret").path("/private-film").appendQueryParameter("token","secret-value").build()))
                transport.measure(RemoteReadTelemetry.Stage.HEADERS){
                    report=playbackDiagnostic(player)
                    assertTrue(playbackWaitSummary().startsWith("等待信源回应"))
                }
            }finally{player.release()}
        }
        listOf("nas-secret","private-film","password123","secret-value","https://").forEach{assertFalse(report.contains(it))}
        val json=JSONObject(report)
        assertEquals("SMB",json.getString("source"))
        assertEquals("HEADERS",json.getString("httpPhase"));assertEquals(1,json.getInt("urlRequests"))
        assertEquals("OPEN:IO",json.getString("lastIoFailure"))
        assertEquals(1,json.getInt("ioFailures"))
        assertTrue(json.has("model"));assertTrue(json.has("sdk"));assertTrue(json.has("decoder"))
    }

    @Test fun realRemoteOpenIsVisibleWhileWaitingAndClearsOnCompletion(){
        val app=ui.activity.application
        val sample=File(app.cacheDir,"continuity-avc1080.mp4");assertTrue(sample.isFile)
        PlaybackMetrics.reset()
        DavFixture(sample,2,1200).use{server->
            val source=Source(label="diagnostic-private-source",protocol=Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val uri=PlaybackRegistry.add(source,Credentials(server.password),server.names.first(),"diagnostic-private-film")
            val data=RemoteDataSource();val worker=Executors.newSingleThreadExecutor()
            try{
                val opened=worker.submit<Long>{data.open(DataSpec.Builder().setUri(uri).build())}
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(PlaybackIoProbe.snapshot().operation!=PlaybackIoProbe.Operation.OPEN&&System.nanoTime()<deadline)Thread.sleep(10)
                Thread.sleep(100)
                assertEquals(PlaybackIoProbe.Operation.OPEN,PlaybackIoProbe.snapshot().operation)
                assertTrue(PlaybackIoProbe.snapshot().waitingMs>=90)
                assertTrue(opened.get(20,TimeUnit.SECONDS)>0)
                assertNull(PlaybackIoProbe.snapshot().operation)
                assertTrue(worker.submit<Int>{data.read(ByteArray(32768),0,32768)}.get(20,TimeUnit.SECONDS)>0)
                assertNotNull(PlaybackIoProbe.snapshot().sinceProgressMs)
                var report="";ui.runOnUiThread{report=playbackDiagnostic(null)}
                assertFalse(report.contains(source.label));assertFalse(report.contains(uri.toString()))
                println("DIAGNOSTIC_REAL_IO "+PlaybackIoProbe.snapshot())
            }finally{data.close();PlaybackRegistry.remove(uri);worker.shutdownNow()}
        }
    }

    @Test fun longWaitCopyActionIsAccessibleAndFitsNarrowLargeText(){
        PlaybackMetrics.reset();val pending=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.OPEN,"SMB")
        val threshold=mutableLongStateOf(12_000);val show=mutableStateOf(true)
        ui.runOnUiThread{ui.activity.setContent{
            PlayerTheme{
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)){
                    Box(Modifier.width(320.dp).height(300.dp).background(Color(0xFF101113))){
                        StationBuffering(show.value,false){PlaybackWaitDiagnostics(null,threshold.longValue)}
                    }
                }
            }
        }}
        try{
            ui.onNodeWithTag("playback-diagnostic-copy").assertDoesNotExist()
            ui.runOnIdle{threshold.longValue=0}
            ui.onNodeWithTag("playback-diagnostic-copy").performScrollTo().assertIsDisplayed().performClick()
            ui.onNodeWithText("已复制检修记录").assertExists()
            var clipboard="";ui.runOnUiThread{clipboard=ui.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()}
            assertEquals("SESSION_CONNECTING",JSONObject(clipboard).getString("state"))
            val image=ui.onRoot().captureToImage().asAndroidBitmap()
            File(ui.activity.cacheDir,"diagnostic-wait.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ui.runOnIdle{show.value=false}
            ui.onNodeWithTag("playback-diagnostic-copy").assertDoesNotExist()
        }finally{PlaybackIoProbe.end(pending)}
    }
    @Test fun slowOriginalReadShowsAdvisoryAndCopyAtLargeFont() {
        PlaybackMetrics.reset()
        val pending=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.READ,"ALIYUN")
        val transport=RemoteReadTelemetry().apply{urlRequest();rangeRequest();received(128000);downloadAdvisory(true,1048576)}
        PlaybackIoProbe.attach(pending,transport)
        PlaybackMetrics.read(128000,1_000_000_000)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val worker=Executors.newSingleThreadExecutor()
        val reading=worker.submit{transport.measure(RemoteReadTelemetry.Stage.BODY){entered.countDown();release.await(30,TimeUnit.SECONDS)}}
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            ui.runOnUiThread{ui.activity.setContent{
                PlayerTheme{CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)){
                    Box(Modifier.width(320.dp).height(300.dp).background(Color(0xFF101113))){
                        StationBuffering(true,false){PlaybackWaitDiagnostics(null,0)}
                    }
                }}
            }}
            ui.onNodeWithContentDescription("原片传输中，正在缓冲").assertExists()
            ui.onNodeWithText("可核对账户的下载高速流量配额",substring=true).assertExists()
            ui.waitUntil(5000){ui.onAllNodesWithText("平均读取",substring=true).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("playback-diagnostic-copy").performScrollTo().assertIsDisplayed().performClick()
            var copied="";ui.runOnUiThread{copied=ui.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()}
            val json=JSONObject(copied)
            assertTrue(json.getBoolean("downloadRightsNotice"))
            assertEquals(1048576L,json.getLong("signedTrafficLimitBitsPerSecond"))
            assertTrue(json.getLong("sourceBodyAverageBytesPerSecond") in 1..128000)
            assertEquals("BODY",json.getString("httpPhase"))
            val image=ui.onRoot().captureToImage().asAndroidBitmap()
            File(ui.activity.cacheDir,"aliyun-slow-wait-2x.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        } finally { release.countDown();reading.get(3,TimeUnit.SECONDS);worker.shutdownNow();PlaybackIoProbe.end(pending);PlaybackMetrics.reset() }
    }

}
