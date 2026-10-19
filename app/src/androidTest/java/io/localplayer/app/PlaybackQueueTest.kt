package io.localplayer.app

import android.content.ComponentName
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackQueueTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync{result=runCatching(block)}
        return result!!.getOrThrow()
    }
    private fun await(message:String,condition:()->Boolean){
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
        while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(100)}
        fail(message)
    }
    private fun dismissImmersiveConfirmation(){
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply{
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun visit(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
            val system=node.packageName?.toString() in setOf("android","com.android.systemui")
            val confirmation=node.viewIdResourceName in setOf("android:id/ok","com.android.systemui:id/confirm") || node.text?.toString() in setOf("Got it","知道了","我知道了")
            if(system&&node.isVisibleToUser&&confirmation&&node.isClickable)return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            for(index in 0 until node.childCount){val child=node.getChild(index)?:continue;if(visit(child))return true}
            return false
        }
        (listOfNotNull(automation.rootInActiveWindow)+automation.windows.mapNotNull{it.root}).any(::visit)
    }
    private fun source(protocol:Protocol):Source {
        val s=Source(label="Queue fixture",protocol=protocol,host="10.0.2.2",port=if(protocol==Protocol.SMB)TestFixtures.smbPort else TestFixtures.sftpPort,
            root=if(protocol==Protocol.SMB)"media"else"/",username=TestFixtures.username)
        if(protocol==Protocol.SMB)return s
        val key=try{SftpConnector().connect(s,Credentials(TestFixtures.password)).close();error("Expected host-key verification")}
            catch(e:HostKeyRequired){e.fingerprint}
        return s.copy(trustedKey=key,trustedAuthority=s.authority)
    }
    private fun receive(vm:LibraryViewModel,play:()->Unit):PlayTarget=runBlocking {
        val next=async(Dispatchers.Default){withTimeout(10000){vm.plays.first()}}
        main(play);next.await()
    }
    @Test fun boundedQueueAlwaysIncludesSelectedFile(){
        val files=(0 until 550).toList()
        for(selected in listOf(0,199,300,549)){
            val window=PlaybackRequests.window(files,selected)
            assertEquals(200,window.size);assertTrue(selected in window)
            assertEquals(window.sorted(),window);assertEquals(window.size,window.distinct().size)
        }
        assertEquals(listOf(-1),PlaybackRequests.window(files,-1))
    }
    @Test fun smbDirectoryQueuePlaysBackwardsAndAutomaticallyAdvances()=remote(Protocol.SMB)
    @Test fun sftpDirectoryQueuePlaysBackwardsAndAutomaticallyAdvances()=remote(Protocol.SFTP)
    private fun remote(protocol:Protocol){
        val vm=main{LibraryViewModel(context.applicationContext as android.app.Application)}
        val scope=ViewModelStore().apply{main{put("queue",vm)}}
        try{
            val remote=source(protocol)
            main{vm.draft=Draft(remote,password=TestFixtures.password,remember=false);vm.connectDraft()}
            await("$protocol connect: ${vm.error}"){main{!vm.busy&&(vm.source!=null||vm.error!=null)}}
            main{assertNull(vm.error);vm.browse("QueueTest")}
            await("$protocol list"){main{!vm.busy}}
            main{assertNull(vm.error)}
            val target=receive(vm){vm.play(vm.entries.first{it.name=="01-first.mp4"})}
            assertEquals(listOf("01-first.mp4","02-second.mp4"),target.queue.map{it.media.title})
            exercise(target,clearBeforeEnd=protocol==Protocol.SFTP,label=protocol.name.lowercase())
        }finally{main{scope.clear();vm.closeSource()}}
    }
    @Test fun smbLargeDirectoryShowsTruncatedQueueAndCanSelectFirstItem()=largeDirectory(Protocol.SMB)
    @Test fun sftpLargeDirectoryShowsTruncatedQueueAndCanSelectFirstItem()=largeDirectory(Protocol.SFTP)
    private fun largeDirectory(protocol:Protocol){
        val vm=main{LibraryViewModel(context.applicationContext as android.app.Application)}
        val scope=ViewModelStore().apply{main{put("large-queue",vm)}}
        val source=source(protocol)
        var target:PlayTarget?=null
        var scenario:ActivityScenario<PlaybackActivity>?=null
        var player:MediaController?=null
        try{
            main{vm.draft=Draft(source,password=TestFixtures.password,remember=false);vm.connectDraft()}
            await("large source connected"){main{!vm.busy&&(vm.source!=null||vm.error!=null)}}
            main{assertNull(vm.error);vm.browse("QueueLarge")}
            await("550 synthetic videos listed"){main{!vm.busy}}
            main{assertNull(vm.error);assertEquals(550,vm.entries.size)}
            val request=receive(vm){vm.play(vm.entries.first{it.name=="episode-549.mp4"})};target=request
            assertEquals(200,request.queue.size);assertTrue(request.truncated)
            assertEquals("episode-350.mp4",request.queue.first().media.title)
            assertEquals("episode-549.mp4",request.queue.last().media.title)
            scenario=ActivityScenario.launch(PlaybackActivity.intent(context,request))
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val controller=future.get(10,TimeUnit.SECONDS);player=controller
            await("large queue selected last item ready"){
                ui.waitForIdle();main{controller.currentMediaItemIndex==199&&controller.playbackState==Player.STATE_READY&&controller.videoSize.width>0}
            }
            main{controller.pause()}
            ui.waitUntil(5000){runCatching{ui.onNodeWithText("选集 200/200").assertIsDisplayed()}.isSuccess}
            ui.onNodeWithText("选集 200/200").performClick()
            ui.onNodeWithText("已截取点选项附近内容",substring=true).assertIsDisplayed()
            snapshot("large-${protocol.name.lowercase()}-last")
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("episode-350.mp4"))
            ui.onNodeWithText("episode-350.mp4").performClick()
            await("large queue can reopen first item"){
                ui.waitForIdle();main{controller.currentMediaItemIndex==0&&controller.playbackState==Player.STATE_READY&&controller.isPlaying}
            }
            main{controller.pause()}
            assertEquals(200,main{controller.mediaItemCount})
            ui.waitUntil(5000){runCatching{ui.onNodeWithContentDescription("上一集").assertIsNotEnabled()}.isSuccess}
            snapshot("large-${protocol.name.lowercase()}-first")
            scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            await("all large queue registrations released"){PlaybackRequests.get(request.playId)==null}
            request.queue.forEach{assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isFailure)}
        }finally{
            main{player?.let{it.stop();it.clearMediaItems();it.release()};scope.clear();vm.closeSource()}
            scenario?.close();target?.let{PlaybackRequests.remove(it.playId)}
            listOf("episode-350.mp4","episode-549.mp4").forEach{name->
                val key="${source.id}:QueueLarge/$name";HistoryStore(context).remove(key);SourceStore(context).forgetPosition(key)
            }
            SourceStore(context).delete(source.id)
        }
    }
    @Test fun localContentQueueFiltersNonVideoAndRetainsCurrentEpisode(){
        val resolver=context.contentResolver
        val s=source(Protocol.SMB)
        val bytes=Protocols().connect(s,Credentials(TestFixtures.password)).use{session->
            session.open("QueueTest/01-first.mp4").use{file->
                ByteArray(file.size.toInt()).also{data->var offset=0;while(offset<data.size){val n=file.readAt(offset.toLong(),data,offset,minOf(256*1024,data.size-offset));check(n>0);offset+=n}}
            }
        }
        val uris=mutableListOf<Uri>()
        val vm=main{LibraryViewModel(context.applicationContext as android.app.Application)}
        val scope=ViewModelStore().apply{main{put("queue",vm)}}
        try{
            for(name in listOf("01-local.mp4","02-local.mp4")){
                val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/YingxiQueueTest");put(MediaStore.MediaColumns.IS_PENDING,1)
                })!!
                uris+=uri
                resolver.openOutputStream(uri)!!.use{it.write(bytes)}
                resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            }
            val entries=uris.mapIndexed{i,uri->LocalEntry(uri,if(i==0)"01-local.mp4"else"02-local.mp4",false,true,bytes.size.toLong())}+
                LocalEntry(Uri.parse("content://queue-test/notes"),"notes.txt",false,false,0)+
                LocalEntry(Uri.parse("content://queue-test/folder"),"folder",true,false,0)
            val target=receive(vm){vm.playLocal(uris.first(),siblings=entries)}
            assertEquals(2,target.queue.size)
            exercise(target)
        }finally{main{scope.clear()};uris.forEach{resolver.delete(it,null,null)}}
    }
    @Test fun unreadableEpisodeStopsWithoutSkippingToAnotherFile(){
        val source=source(Protocol.SMB)
        val queue=listOf("QueueTest/01-first.mp4","QueueTest/missing.mp4","QueueTest/02-second.mp4").map{path->
            val media=MediaRef("${source.id}:$path",path.substringAfterLast('/'),source.label,source.id,path)
            QueuedMedia(PlaybackRegistry.add(source,Credentials(TestFixtures.password),path,media.title),media,HistoryStore(context).token(media.key))
        }
        val first=queue.first()
        val target=PlayTarget(first.uri,first.media.title,0,first.media,first.token,queue=queue)
        val scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(context,target))
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        try{
            await("first item ready"){ui.waitForIdle();main{player.playbackState==Player.STATE_READY}}
            main{player.seekTo(1,0);player.play()}
            await("unreadable item reports error"){ui.waitForIdle();main{player.playerError!=null}}
            Thread.sleep(500)
            assertEquals(1,main{player.currentMediaItemIndex});assertEquals(3,main{player.mediaItemCount})
            ui.waitUntil(5000){runCatching{ui.onNodeWithText("返回重试").assertIsDisplayed()}.isSuccess}
        }finally{
            main{player.stop();player.clearMediaItems();player.release()};scenario.close();PlaybackRequests.remove(target.playId)
            queue.forEach{HistoryStore(context).remove(it.media.key);SourceStore(context).forgetPosition(it.media.key)}
        }
    }
    private fun snapshot(name:String){
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle();Thread.sleep(250)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yingxi-queue-$name.png").use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}
    }
    private fun exercise(target:PlayTarget,clearBeforeEnd:Boolean=false,label:String="local"){
        val history=HistoryStore(context)
        val captions=mutableListOf<Uri>()
        val scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(context,target.copy(resume=3500)))
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        fun ready(index:Int)=await("queue item $index: ${main{player.playerError}}"){
            ui.waitForIdle()
            main{player.currentMediaItemIndex==index&&player.playbackState==Player.STATE_READY&&player.videoSize.width>0}
        }
        try{
            ready(0);assertTrue(main{player.currentPosition>=3500});main{player.pause()};ui.waitForIdle()
            ui.onNodeWithContentDescription("上一集").assertIsNotEnabled()
            if(!clearBeforeEnd&&target.uri.scheme=="localplayer"){
                val resolver=context.contentResolver
                val caption=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
                    put(MediaStore.MediaColumns.DISPLAY_NAME,"yingxi-queue-caption.srt");put(MediaStore.MediaColumns.MIME_TYPE,"application/x-subrip")
                })!!
                try{
                    resolver.openOutputStream(caption)!!.use{it.write("1\n00:00:00,000 --> 00:00:30,000\nQUEUE CAPTION\n".toByteArray())}
                    val monitor=object:android.app.Instrumentation.ActivityMonitor(){
                        override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult?=
                            if(intent.action==android.content.Intent.ACTION_OPEN_DOCUMENT)
                                android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,android.content.Intent().setData(caption))else null
                    }
                    instrumentation.addMonitor(monitor)
                    try{
                        ui.onNodeWithContentDescription("播放设置").performClick()
                        ui.onNodeWithTag("playback-settings-list").performScrollToNode(hasText("载入字幕文件"));ui.onNodeWithText("载入字幕文件").performClick()
                        await("caption keeps playlist"){ui.waitForIdle();main{player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.isNotEmpty()==true}}
                        assertEquals(2,main{player.mediaItemCount});main{player.play()}
                        await("queue caption renders"){ui.waitForIdle();main{player.currentCues.cues.any{it.text?.contains("QUEUE CAPTION")==true}}}
                        main{player.pause()};ui.waitForIdle()
                    }finally{instrumentation.removeMonitor(monitor)}
                }finally{captions+=caption} // Keep the subtitle readable when returning to this episode.
            }
            ui.onNodeWithContentDescription("下一集").performClick();ready(1)
            assertTrue(main{player.currentPosition<5000})
            ui.waitUntil(5000){runCatching{ui.onNodeWithContentDescription("下一集").assertIsNotEnabled()}.isSuccess}
            main{player.seekTo(4500)}
            scenario.recreate();ready(1)
            assertEquals(2,main{player.mediaItemCount});assertTrue(main{player.currentPosition>=4000})
            // Drive the actual rotate control, beyond ActivityScenario.recreate().
            val rotationPosition=main{player.currentPosition}
            for(orientation in listOf(android.content.res.Configuration.ORIENTATION_PORTRAIT,android.content.res.Configuration.ORIENTATION_LANDSCAPE)){
                ui.onNodeWithContentDescription("旋转屏幕").performClick()
                await("actual orientation $orientation"){
                    dismissImmersiveConfirmation()
                    ui.mainClock.advanceTimeByFrame()
                    // Wait for the Android window before asking Compose to await a frame.
                    // Syncing during rotation may bind Espresso to the detached old root.
                    var settled=false
                    scenario.onActivity{
                        val view=it.window.decorView
                        val sizeMatches=if(orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)view.width>view.height else view.height>view.width
                        settled=it.resources.configuration.orientation==orientation&&view.isAttachedToWindow&&view.hasWindowFocus()&&sizeMatches
                    if(!settled)android.util.Log.i("QueueRotation","desired=$orientation actual=${it.resources.configuration.orientation} requested=${it.requestedOrientation} size=${view.width}x${view.height} focus=${view.hasWindowFocus()} attached=${view.isAttachedToWindow}")
                    }
                    settled
                }
                instrumentation.waitForIdleSync()
                ready(1);assertEquals(2,main{player.mediaItemCount})
                assertTrue("Paused position changed during rotation",kotlin.math.abs(main{player.currentPosition}-rotationPosition)<=500L)
                assertFalse(main{player.playWhenReady})
                snapshot("$label-orientation-$orientation")
            }
            ui.waitUntil(5000){runCatching{ui.onNodeWithText("选集 2/2").assertIsDisplayed()}.isSuccess}
            snapshot("$label-controls")
            ui.onNodeWithText("选集 2/2").performClick()
            ui.onNodeWithText("播放队列").assertIsDisplayed();snapshot("$label-sheet")
            ui.onNodeWithText(target.queue.first().media.title).performClick();ready(0)
            main{player.pause();player.seekTo(7000)}
            await("old item progress"){history.entries().any{it.media.key==target.queue.first().media.key&&it.positionMs>=7000}}
            main{player.seekTo(player.duration-400);player.play()};ready(1)
            await("automatic next is playing"){main{player.isPlaying}}
            await("previous completed"){history.entries().any{it.media.key==target.queue.first().media.key&&it.completed}}
            assertEquals(0L,history.resumePosition(target.queue.first().media))
            var backgroundActivity:PlaybackActivity?=null;scenario.onActivity{backgroundActivity=it}
            ui.onNodeWithContentDescription("后台播放").performClick()
            await("explicit background"){main{backgroundActivity?.hasWindowFocus()==false}}
        val backgroundPosition=main{player.currentPosition}
        await("background playback continues"){main{player.isPlaying&&player.currentPosition>backgroundPosition+300}}
            main{context.startActivity(PlaybackActivity.intent(context,target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))};ready(1)
        assertEquals(1,main{player.currentMediaItemIndex});assertTrue(main{player.playWhenReady})
            if(clearBeforeEnd)history.clear() // All queued tokens must respect a clear, not just the current item.
            main{player.seekTo(player.duration-400);player.play()}
            await("last item ended"){main{player.playbackState==Player.STATE_ENDED}}
            assertEquals(1,main{player.currentMediaItemIndex})
            if(clearBeforeEnd)assertTrue(history.entries().isEmpty())else{
                await("last completed"){history.entries().any{it.media.key==target.queue.last().media.key&&it.completed}}
                assertEquals(0L,history.resumePosition(target.queue.last().media))
            }
            scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            await("queue released"){PlaybackRequests.get(target.playId)==null}
            if(!clearBeforeEnd){
                await("completed state survives leaving"){history.entries().any{it.media.key==target.queue.last().media.key&&it.completed}}
                assertEquals(0L,history.resumePosition(target.queue.last().media))
            }
            target.queue.filter{it.uri.scheme=="localplayer"}.forEach{assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isFailure)}
        }finally{
            main{player.stop();player.clearMediaItems();player.release()};scenario.close();PlaybackRequests.remove(target.playId)
            target.queue.forEach{history.remove(it.media.key);SourceStore(context).forgetPosition(it.media.key)}
            captions.forEach{context.contentResolver.delete(it,null,null)}
        }
    }
}
