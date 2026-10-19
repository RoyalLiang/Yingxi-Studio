package io.localplayer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaThumbnailTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun source(protocol: Protocol): Source {
        val source = Source(label="缩略图验证", protocol=protocol, host="10.0.2.2",
            port=if(protocol==Protocol.SMB)TestFixtures.smbPort else TestFixtures.sftpPort,
            root=if(protocol==Protocol.SMB)"media"else"/", username=TestFixtures.username)
        if(protocol==Protocol.SMB)return source
        val key=try { SftpConnector().connect(source,Credentials(TestFixtures.password)).close();error("Expected host key") }
        catch(e:HostKeyRequired){e.fingerprint}
        return source.copy(trustedKey=key,trustedAuthority=source.authority)
    }
    private fun verifyFrame(frame: Bitmap?) {
        assertNotNull("real decoded frame",frame)
        frame!!
        assertTrue(frame.width in 1..320 && frame.height in 1..180)
        val colors = mutableSetOf<Int>()
        for(y in 0 until frame.height step 8)for(x in 0 until frame.width step 8)colors+=frame.getPixel(x,y)
        assertTrue("video frame must contain image detail",colors.size>8)
    }
    private fun show(media: MediaRef,label: String) {
        val history=HistoryStore(context)
        history.update(media,history.token(media.key),1500,10000,false)
        ui.runOnIdle { ViewModelProvider(ui.activity)[LibraryViewModel::class.java].refreshHistory() }
        ui.onNodeWithTag("continue-player").assertIsDisplayed()
        ui.onNodeWithTag("media-thumbnail:${media.key}",useUnmergedTree=true).assertIsDisplayed()
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/thumbnail-$label.png").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream->stream.readBytes() }
        }
    }

    @Test fun smbFrameUsesIndependentConnectionAndChecksSourceIdentity()=remote(Protocol.SMB)
    @Test fun sftpFrameUsesIndependentConnectionAndChecksSourceIdentity()=remote(Protocol.SFTP)
    private fun remote(protocol: Protocol) {
        val source=source(protocol)
        val store=SourceStore(context)
        val path="QueueTest/01-first.mp4"
        val uri=PlaybackRegistry.add(source,Credentials(TestFixtures.password),path,"影片")
        val playback=PlaybackRegistry.get(uri).open()
        val media=MediaRef("thumbnail-"+UUID.randomUUID(),"海边的夏日 · 真实影片.mp4",source.label,
            sourceId=source.id,path=path,sizeBytes=playback.size,location=mediaLocation(source))
        try {
            store.save(source,TestFixtures.password,true)
            verifyFrame(runBlocking { MediaThumbnails.load(context,media,uri) })
            val bytes=ByteArray(64)
            assertTrue("thumbnail extraction must preserve playback transport",playback.readAt(0,bytes,0,bytes.size)>0)
            show(media,protocol.name.lowercase())
            PlaybackRegistry.remove(uri)
            // Cached frames remain usable without an active playback registration or password.
            store.save(source,"",false)
            verifyFrame(runBlocking { MediaThumbnails.load(context,media) })
            store.save(source.copy(username="changed-principal"),"",false)
            assertNull(runBlocking { MediaThumbnails.load(context,media) })
        } finally {
            PlaybackRegistry.remove(uri)
            HistoryStore(context).remove(media.key)
            MediaThumbnails.remove(context,media)
            store.delete(source.id)
        }
    }

    @Test fun localContentFrameAndUnreadableFallback() {
        val source=source(Protocol.SMB)
        val bytes=Protocols().connect(source,Credentials(TestFixtures.password)).use { session->
            session.open("QueueTest/01-first.mp4").use { file->
                ByteArray(file.size.toInt()).also { data->var offset=0;while(offset<data.size){val n=file.readAt(offset.toLong(),data,offset,data.size-offset);check(n>0);offset+=n} }
            }
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"thumbnail-${UUID.randomUUID()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/YingxiThumbnailTest")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        val media=MediaRef("thumbnail-local-"+UUID.randomUUID(),"真实本机影片.mp4","本机文件",localUri=uri.toString(),sizeBytes=bytes.size.toLong())
        try {
            resolver.openOutputStream(uri)!!.use{it.write(bytes)}
            resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            verifyFrame(runBlocking{MediaThumbnails.load(context,media)})
            show(media,"local")
            assertNull(runBlocking { MediaThumbnails.load(context,media.copy(key=media.key+"missing",localUri=Uri.parse("content://unreadable/clip").toString())) })
            ui.onNodeWithText("首页").assertIsDisplayed()
        } finally {
            resolver.delete(uri,null,null)
            HistoryStore(context).remove(media.key)
            MediaThumbnails.remove(context,media)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun actualPlaybackWarmsFrameWithoutRememberingPassword() {
        val source=source(Protocol.SMB)
        val store=SourceStore(context)
        val path="QueueTest/01-first.mp4"
        val uri=PlaybackRegistry.add(source,Credentials(TestFixtures.password),path,"影片")
        val media=MediaRef("thumbnail-auto-"+UUID.randomUUID(),"正在播放的真实影片.mp4",source.label,
            sourceId=source.id,path=path,location=mediaLocation(source))
        var scenario:androidx.test.core.app.ActivityScenario<PlaybackActivity>?=null
        var controller:androidx.media3.session.MediaController?=null
        try {
            store.save(source,"",false)
            assertFalse(store.hasPassword(source.id))
            scenario=androidx.test.core.app.ActivityScenario.launch(PlaybackActivity.intent(context,PlayTarget(uri,media.title,0,media)))
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
            instrumentation.runOnMainSync {
                future=androidx.media3.session.MediaController.Builder(context,
                    androidx.media3.session.SessionToken(context,android.content.ComponentName(context,PlaybackService::class.java))).buildAsync()
            }
            controller=future.get(10,java.util.concurrent.TimeUnit.SECONDS)
            var frame:Bitmap?=null
            val deadline=SystemClock.elapsedRealtime()+30_000
            while(frame==null&&SystemClock.elapsedRealtime()<deadline){
                frame=runBlocking{MediaThumbnails.load(context,media)}
                if(frame==null)Thread.sleep(100)
            }
            verifyFrame(frame)
            val player=controller
            instrumentation.runOnMainSync {
                assertNull(player.playerError)
                assertTrue(player.currentPosition>0)
                player.stop();player.clearMediaItems()
            }
            scenario.close();scenario=null
            assertFalse(store.hasPassword(source.id))
        } finally {
            instrumentation.runOnMainSync { controller?.let{it.stop();it.clearMediaItems();it.release()} }
            scenario?.close()
            PlaybackRegistry.remove(uri)
            HistoryStore(context).remove(media.key)
            MediaThumbnails.remove(context,media)
            store.delete(source.id)
        }
    }
}
