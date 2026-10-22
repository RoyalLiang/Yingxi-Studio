package io.localplayer.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerPolishTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var value:Result<T>?=null
        instrumentation.runOnMainSync { value=runCatching(block) }
        return value!!.getOrThrow()
    }
    private fun await(message:String,condition:()->Boolean){
        val deadline=System.nanoTime()+30_000_000_000L
        while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(50)}
        fail(message)
    }
    private fun connect(vm:LibraryViewModel){
        val source=Source(label="目录与续播验证",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        main { vm.draft=Draft(source,password=TestFixtures.password,remember=false);vm.connectDraft() }
        await("fixture connection"){main{!vm.busy&&vm.source!=null}}
        main { assertNull(vm.error) }
    }
    @Test fun continuingPlaybackPreservesTheDirectoryBeingBrowsed()=runBlocking {
        val vm=main{LibraryViewModel(context.applicationContext as Application)}
        val owner=ViewModelStore().apply{main{put("resume-polish",vm)}}
        var target:PlayTarget?=null
        try {
            connect(vm)
            val source=main{vm.source!!}
            val file=main{vm.entries.first{it.name=="sample.mp4"}}
            main{vm.browse("QueueTest")}
            await("directory ready"){main{!vm.busy&&vm.path=="QueueTest"}}
            val before=main{vm.entries}
            val media=MediaRef("${source.id}:${file.path}",file.name,source.label,source.id,file.path,sizeBytes=file.size,location=mediaLocation(source))
            val history=HistoryStore(context)
            history.update(media,history.token(media.key),4500,30000,false)
            val replay=history.entries().first{it.media.key==media.key}
            val next=async(Dispatchers.Default){withTimeout(30000){vm.plays.first()}}
            main{vm.resume(replay)}
            target=next.await()
            main{
                assertEquals("Continue must not replace the open directory", "QueueTest",vm.path)
                assertEquals(before,vm.entries)
                assertEquals(source,vm.source)
            }
            assertEquals(4500L,target.resume)
            assertTrue(target.queue.isNotEmpty())
        } finally {
            target?.release()
            val id=main{vm.source?.id}
            if(id!=null){SourceStore(context).delete(id);HistoryStore(context).entries().filter{it.media.sourceId==id}.forEach{HistoryStore(context).remove(it.media.key)}}
            main{owner.clear()}
        }
    }
    @Test fun correctedPasswordStillContinuesPlayback()=runBlocking {
        val store=SourceStore(context)
        val saved=Source(label="续播认证验证",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        store.save(saved,"",false)
        val vm=main{LibraryViewModel(context.applicationContext as Application)}
        val owner=ViewModelStore().apply{main{put("retry-polish",vm)}}
        val media=MediaRef("${saved.id}:sample.mp4","sample.mp4",saved.label,saved.id,"sample.mp4",location=mediaLocation(saved))
        val history=HistoryStore(context)
        history.update(media,history.token(media.key),4500,30000,false)
        var target:PlayTarget?=null
        try {
            val next=async(Dispatchers.Default){withTimeout(30000){vm.plays.first()}}
            main{vm.resume(history.entries().first{it.media.key==media.key})}
            await("password prompt"){main{vm.access!=null}}
            main{vm.updateAccess(password="incorrect-fixture-password");vm.connectAccess()}
            await("wrong password rejected"){main{vm.error!=null&&vm.preparingKey==null}}
            main{assertNotNull(vm.access);assertNull(vm.source);vm.updateAccess(password=TestFixtures.password);vm.connectAccess()}
            target=next.await()
            main{assertNull(vm.source);assertEquals("",vm.path);assertNull(vm.access);assertNull(vm.error)}
            assertEquals(4500L,target.resume)
        } finally {
            target?.release();main{owner.clear()};store.delete(saved.id);history.remove(media.key)
        }
    }
    @Test fun deletingSourceCancelsInFlightContinue() {
        val server=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val accepted=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        var socket:java.net.Socket?=null
        val thread=Thread {
            try { socket=server.accept();accepted.countDown();release.await(15,java.util.concurrent.TimeUnit.SECONDS) }
            catch(_:java.io.IOException){} finally {socket?.close()}
        }.apply{isDaemon=true;start()}
        val saved=Source(label="慢连接取消验证",protocol=Protocol.SMB,host="127.0.0.1",port=server.localPort,root="media",username=TestFixtures.username)
        val store=SourceStore(context);store.save(saved,TestFixtures.password,true)
        val vm=main{LibraryViewModel(context.applicationContext as Application)}
        val owner=ViewModelStore().apply{main{put("cancel-polish",vm)}}
        val media=MediaRef("${saved.id}:sample.mp4","sample.mp4",saved.label,saved.id,"sample.mp4",location=mediaLocation(saved))
        val history=HistoryStore(context);history.update(media,history.token(media.key),4500,30000,false)
        try {
            main{vm.resume(history.entries().first{it.media.key==media.key})}
            assertTrue("SMB negotiation entered",accepted.await(10,java.util.concurrent.TimeUnit.SECONDS))
            main{assertEquals(media.key,vm.preparingKey);vm.removeSource(saved);assertNull(vm.preparingKey);assertFalse(vm.sources.any{it.id==saved.id})}
            release.countDown()
            runBlocking{assertNull(withTimeoutOrNull(1000){vm.plays.first()})}
            main{assertNull(vm.source);assertNull(vm.preparingKey)}
        } finally {
            release.countDown();server.close();thread.join(2000);main{owner.clear()};store.delete(saved.id);history.remove(media.key)
        }
    }
    @Test fun loadingContinueCardKeepsItsLayoutAndAnchor(){
        val loading=mutableStateOf(false)
        val media=MediaRef("layout-preview","很长的影片名称 · 继续放映时保持页面位置","本机")
        val entry=HistoryEntry(media,4500,30000,0,false)
        ui.activity.setContent{PlayerTheme{Column{
            ContinueCard(entry,preparing=loading.value){}
            Text("下方内容锚点",androidx.compose.ui.Modifier.testTag("resume-anchor"))
        }}}
        val card=ui.onNodeWithTag("continue-player").fetchSemanticsNode().boundsInRoot
        val anchor=ui.onNodeWithTag("resume-anchor").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle{loading.value=true}
        ui.onNodeWithTag("continue-loading",useUnmergedTree=true).assertIsDisplayed()
        assertEquals(card,ui.onNodeWithTag("continue-player").fetchSemanticsNode().boundsInRoot)
        assertEquals(anchor,ui.onNodeWithTag("resume-anchor").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("continue-player").assertIsNotEnabled()
    }
    @Test fun definitionUsesDecodedDimensionsIncludingPortrait(){
        assertNull(videoDefinition(0,0))
        assertEquals("1080P",videoDefinition(1920,1080))
        assertEquals("1080P",videoDefinition(1080,1920))
        assertEquals("2K",videoDefinition(2560,1440))
        assertEquals("2K",videoDefinition(2048,1080))
        assertEquals("4K",videoDefinition(3840,2160))
        assertEquals("4K",videoDefinition(2160,3840))
    }
    @Test fun sourcesTabReturnsToSavedSourcesInsteadOfAnOldDirectory(){
        val vm=main{ViewModelProvider(ui.activity)[LibraryViewModel::class.java]}
        connect(vm)
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithTag("source-list").assertIsDisplayed()
        ui.onNode(hasText("目录与续播验证") and hasAnyAncestor(hasTestTag("source-list")),useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithTag("source-browser").assertDoesNotExist()
    }
}
