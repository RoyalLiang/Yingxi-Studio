package io.localplayer.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
class BackgroundPlaybackTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync{result=runCatching(block)}
        return result!!.getOrThrow()
    }
    private fun await(message:String,condition:()->Boolean){
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
        while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)}
        fail(message)
    }
    private fun shell(command:String){
        instrumentation.uiAutomation.executeShellCommand(command).use{p->
            java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}
        }
    }
    private fun snapshot(name:String){shell("screencap -p /sdcard/Download/yingxi-background-$name.png")}
    private fun clickSystemText(labels:Set<String>,click:Boolean=true):Boolean {
        fun visit(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
            if(node.packageName=="com.android.systemui"&&node.isVisibleToUser&&
                (node.text?.toString() in labels||node.contentDescription?.toString() in labels||node.viewIdResourceName in labels)){
                if(!click)return true
                var target:android.view.accessibility.AccessibilityNodeInfo?=node
                repeat(8){
                    if(target?.isClickable==true)return target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    target=target?.parent
                }
            }
            for(index in 0 until node.childCount){val child=node.getChild(index)?:continue;if(visit(child))return true}
            return false
        }
        return (listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)+instrumentation.uiAutomation.windows.mapNotNull{it.root}).any(::visit)
    }
    private fun closeSystemPictureInPicture(){
        val automation=instrumentation.uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply{
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        // Invoke the system PiP window's public accessibility dismissal action.
        // Its client view/window metrics may still use the unscaled activity coordinates.
        await("system PiP dismiss action"){
            automation.windows.filter{it.isInPictureInPictureMode}.any{
                it.root?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS)==true
            }
        }
    }
    private fun source(protocol:Protocol):Source {
        val s=Source(label="Background fixture",protocol=protocol,host="10.0.2.2",
            port=if(protocol==Protocol.SMB)TestFixtures.smbPort else TestFixtures.sftpPort,
            root=if(protocol==Protocol.SMB)"media" else "/",username=TestFixtures.username)
        if(protocol==Protocol.SMB)return s
        val key=try{SftpConnector().connect(s,Credentials(TestFixtures.password)).close();error("Expected host key")}
            catch(e:HostKeyRequired){e.fingerprint}
        return s.copy(trustedKey=key,trustedAuthority=s.authority)
    }
    private fun receive(vm:LibraryViewModel,play:()->Unit):PlayTarget=runBlocking{
        val next=async(Dispatchers.Default){withTimeout(10000){vm.plays.first()}}
        main(play);next.await()
    }
    @Test fun smbPictureInPictureBackgroundAndMediaNotification()=remote(Protocol.SMB)
    @Test fun sftpPictureInPictureBackgroundAndMediaNotification()=remote(Protocol.SFTP)
    @Test fun supersededPendingRequestIsReleased()=remote(Protocol.SMB,true)
    @Test fun supersededPendingRequestIsReleasedWhenCancelled()=remote(Protocol.SMB,true,true)
    @Test fun configuredPictureInPictureCloseActionStopsPlayback()=remote(Protocol.SMB,closeAction=true)
    @Test fun pendingRequestSurvivesPreviousServiceShutdown()=remote(Protocol.SMB,replacePending=true,serviceHandoff=true)
    @Test fun extremeAspectRatiosRespectPlatformRange(){
        val minimum=android.util.Rational(100,239);val maximum=android.util.Rational(239,100)
        assertEquals(minimum,pictureInPictureAspectRatio(1,3))
        assertEquals(maximum,pictureInPictureAspectRatio(3,1))
        assertEquals(android.util.Rational(16,9),pictureInPictureAspectRatio(0,0))
        for(width in listOf(1,9,100,239,1080,10000))for(height in listOf(1,16,100,239,1920,10000)){
            val value=pictureInPictureAspectRatio(width,height)
            assertTrue(value>=minimum&&value<=maximum)
        }
    }
    private fun remote(protocol:Protocol,replacePending:Boolean=false,cancelPending:Boolean=false,serviceHandoff:Boolean=false,closeAction:Boolean=false){
        val source=source(protocol)
        val vm=main{LibraryViewModel(context.applicationContext as android.app.Application)}
        val scope=ViewModelStore().apply{main{put("background",vm)}}
        try{
            main{vm.draft=Draft(source,password=TestFixtures.password,remember=false);vm.connectDraft()}
            await("source connection"){main{!vm.busy&&(vm.source!=null||vm.error!=null)}}
            main{assertNull(vm.error);vm.browse("QueueTest")}
            await("directory"){main{!vm.busy}};main{assertNull(vm.error)}
            val pending=if(replacePending)receive(vm){vm.play(vm.entries.first{it.name=="01-first.mp4"})}else null
            val target=receive(vm){vm.play(vm.entries.first{it.name=="01-first.mp4"})}
            if(serviceHandoff)exerciseServiceHandoff(target,pending!!)else if(cancelPending)exerciseCancelled(target,pending!!)else exercise(target,if(closeAction)"close-action"else protocol.name.lowercase(),pending)
        }finally{main{scope.clear();vm.closeSource()};SourceStore(context).delete(source.id)}
    }
    @Test fun localPictureInPictureBackgroundAndMediaNotification(){
        val bytes=Protocols().connect(source(Protocol.SMB),Credentials(TestFixtures.password)).use{s->
            s.open("QueueTest/01-first.mp4").use{f->ByteArray(f.size.toInt()).also{data->
                var offset=0;while(offset<data.size){val n=f.readAt(offset.toLong(),data,offset,minOf(256*1024,data.size-offset));check(n>0);offset+=n}
            }}
        }
        val resolver=context.contentResolver
        val uris=mutableListOf<Uri>()
        val vm=main{LibraryViewModel(context.applicationContext as android.app.Application)}
        val scope=ViewModelStore().apply{main{put("local-background",vm)}}
        try{
            for(name in listOf("01-background.mp4","02-background.mp4")){
                val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/YingxiBackgroundTest");put(MediaStore.MediaColumns.IS_PENDING,1)
                })!!
                uris+=uri;resolver.openOutputStream(uri)!!.use{it.write(bytes)}
                resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            }
            val entries=uris.mapIndexed{i,uri->LocalEntry(uri,if(i==0)"01-background.mp4"else"02-background.mp4",false,true,bytes.size.toLong())}
            exercise(receive(vm){vm.playLocal(uris.first(),siblings=entries)},"local")
        }finally{main{scope.clear()};uris.forEach{resolver.delete(it,null,null)}}
    }
    private fun redirectBeforeStart(intent:Intent,cancel:Boolean=false,destroyed:()->Unit={}):android.app.Application.ActivityLifecycleCallbacks =
        object:android.app.Application.ActivityLifecycleCallbacks{
            override fun onActivityPostCreated(activity:android.app.Activity,state:android.os.Bundle?){
                if(activity is PlaybackActivity){instrumentation.callActivityOnNewIntent(activity,intent);if(cancel)activity.finish()}
            }
            override fun onActivityCreated(a:android.app.Activity,s:android.os.Bundle?){}
            override fun onActivityStarted(a:android.app.Activity){}
            override fun onActivityResumed(a:android.app.Activity){}
            override fun onActivityPaused(a:android.app.Activity){}
            override fun onActivityStopped(a:android.app.Activity){}
            override fun onActivitySaveInstanceState(a:android.app.Activity,s:android.os.Bundle){}
            override fun onActivityDestroyed(a:android.app.Activity){if(a is PlaybackActivity)destroyed()}
        }
    private fun exerciseServiceHandoff(target:PlayTarget,pending:PlayTarget){
        var scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(context,pending))
        fun connect():MediaController {
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            return future.get(10,TimeUnit.SECONDS)
        }
        var player:MediaController?=connect()
        try{
            await("previous playback"){ui.waitForIdle();main{player!!.isPlaying&&player!!.videoSize.width>0}}
            val nextIntent=PlaybackActivity.intent(context,target)
            scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            await("previous queue released"){main{player!!.mediaItemCount==0}}
            main{player!!.release()};player=null;scenario.close()
            await("previous service destroyed"){
                context.getSystemService(ActivityManager::class.java).getRunningServices(50).none{it.service.className==PlaybackService::class.java.name}
            }
            assertNotNull(PlaybackRequests.get(target.playId))
            target.queue.forEach{assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isSuccess)}
            scenario=ActivityScenario.launch(nextIntent);player=connect()
            await("next pending playback"){ui.waitForIdle();main{player!!.isPlaying&&player!!.videoSize.width>0&&player!!.mediaItemCount==2}}
        }finally{
            main{player?.let{it.stop();it.clearMediaItems();it.release()}};scenario.close()
            listOf(pending,target).forEach{item->PlaybackRequests.remove(item.playId);item.queue.forEach{HistoryStore(context).remove(it.media.key);SourceStore(context).forgetPosition(it.media.key)}}
        }
    }
    private fun exerciseCancelled(target:PlayTarget,pending:PlayTarget){
        val application=context.applicationContext as android.app.Application
        val closed=java.util.concurrent.atomic.AtomicBoolean()
        val redirect=redirectBeforeStart(PlaybackActivity.intent(context,target),true){closed.set(true)}
        application.registerActivityLifecycleCallbacks(redirect)
        try{
            main{context.startActivity(PlaybackActivity.intent(context,pending).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            await("cancelled Activity destroyed before controller connects"){closed.get()}
            for(item in listOf(pending,target)){
                assertNull(PlaybackRequests.get(item.playId))
                item.queue.forEach{assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isFailure)}
            }
        }finally{
            application.unregisterActivityLifecycleCallbacks(redirect)
            main{PlaybackRequests.remove(pending.playId);PlaybackRequests.remove(target.playId)}
        }
    }
    private fun exercise(target:PlayTarget,label:String,pending:PlayTarget?=null){
        val application=context.applicationContext as android.app.Application
        val intent=PlaybackActivity.intent(context,target)
        val redirect=redirectBeforeStart(intent)
        if(pending!=null)application.registerActivityLifecycleCallbacks(redirect)
        val scenario=try{ActivityScenario.launch<PlaybackActivity>(if(pending!=null)PlaybackActivity.intent(context,pending)else intent)}
            finally{application.unregisterActivityLifecycleCallbacks(redirect)}
        lateinit var activity:PlaybackActivity
        scenario.onActivity{activity=it}
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val player=future.get(10,TimeUnit.SECONDS)
        lateinit var platform:android.media.session.MediaController
        fun ready()=await("video ready: ${main{player.playerError}}"){ui.waitForIdle();main{player.playbackState==Player.STATE_READY&&player.videoSize.width>0}}
        fun notification()=context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull{it.notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,android.media.session.MediaSession.Token::class.java)!=null}?.notification
        fun returnFromNotification(){
            // A user/system notification click has different BAL privileges from an
            // app sending its own PendingIntent while completely in the background.
            val title=main{player.currentMediaItem?.mediaMetadata?.title?.toString()}!!
            await("system return notification visible"){
                shell("cmd statusbar expand-notifications");Thread.sleep(300)
                clickSystemText(setOf(title),click=false)
            }
            snapshot("$label-return-notification")
            await("system return notification click"){clickSystemText(setOf(title))}
            await("notification returns to fullscreen"){
                var returned=false
                scenario.onActivity{activity=it;returned=it.hasWindowFocus()&&!it.isInPictureInPictureMode}
                returned
            }
        }
        fun settlePictureInPicture(){
            ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
            ui.onNodeWithContentDescription("返回").assertDoesNotExist()
            ui.onNodeWithContentDescription("后台播放").assertDoesNotExist()
        }
        try{
            ready();await("first playing"){main{player.isPlaying}}
            if(pending!=null){
                assertEquals(target.playId,main{activity.intent.getStringExtra("play-intent")})
                await("superseded request released"){PlaybackRequests.get(pending.playId)==null}
                pending.queue.forEach{assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isFailure)}
                assertNotNull(PlaybackRequests.get(target.playId));assertEquals(2,main{player.mediaItemCount})
                target.queue.forEach{assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isSuccess)}
                return
            }
            main{for((w,h) in listOf(1 to 3,3 to 1))activity.setPictureInPictureParams(
                android.app.PictureInPictureParams.Builder().setAspectRatio(pictureInPictureAspectRatio(w,h)).build())}
            assertEquals(2,main{player.mediaItemCount})
            await("media notification"){notification()!=null}
            val token=notification()!!.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,android.media.session.MediaSession.Token::class.java)!!
            platform=android.media.session.MediaController(context,token)
            main{player.seekTo(5000)}
            instrumentation.waitForIdleSync()
            shell("input keyevent KEYCODE_HOME")
            await("Home enters real PiP"){main{activity.isInPictureInPictureMode}}
            settlePictureInPicture()
            android.util.Log.i("BackgroundTest","$label: auto PiP entered")
            val pipPosition=main{player.currentPosition}
            await("PiP playback continues"){main{player.isPlaying&&player.currentPosition>pipPosition+500}}
            Thread.sleep(700);snapshot("$label-auto-pip")
            shell("input keyevent KEYCODE_SLEEP")
            await("PiP display sleeps"){!context.getSystemService(PowerManager::class.java).isInteractive}
            val pipSleepPosition=main{player.currentPosition}
            await("screen-off from PiP keeps audio"){main{player.isPlaying&&player.currentPosition>pipSleepPosition+500}}
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            await("PiP survives display wake"){main{activity.isInPictureInPictureMode&&player.mediaItemCount==2}}
            platform.transportControls.pause()
            await("system pause in PiP"){main{!player.playWhenReady}}
            val paused=main{player.currentPosition};Thread.sleep(500)
            assertTrue(kotlin.math.abs(main{player.currentPosition}-paused)<300)
            returnFromNotification();ready()
            android.util.Log.i("BackgroundTest","$label: notification returned fullscreen")
            assertFalse(main{player.playWhenReady});assertEquals(0,main{player.currentMediaItemIndex})
            assertTrue(kotlin.math.abs(main{player.currentPosition}-paused)<500)
            shell("input keyevent KEYCODE_HOME")
            await("paused Home stays out of PiP"){main{!activity.hasWindowFocus()&&!activity.isInPictureInPictureMode}}
            assertFalse(main{player.playWhenReady});returnFromNotification();ready()
            ui.waitUntil(5000){runCatching{ui.onNodeWithContentDescription("画中画").assertIsEnabled()}.isSuccess}
            ui.onNodeWithContentDescription("画中画").performClick()
            await("manual paused PiP"){main{activity.isInPictureInPictureMode}}
            settlePictureInPicture()
            android.util.Log.i("BackgroundTest","$label: manual paused PiP entered")
            assertFalse(main{player.playWhenReady});snapshot("$label-manual-pip")
            returnFromNotification();ready()
            platform.transportControls.play();await("system play"){main{player.isPlaying}}
            ui.onNodeWithContentDescription("后台播放").performClick()
            await("explicit background has no PiP"){main{!activity.hasWindowFocus()&&!activity.isInPictureInPictureMode}}
            android.util.Log.i("BackgroundTest","$label: audio background entered")
            val backgroundPosition=main{player.currentPosition}
            await("background progress"){main{player.isPlaying&&player.currentPosition>backgroundPosition+500}}
            assertTrue(context.getSystemService(ActivityManager::class.java).getRunningServices(50)
                .any{it.service.className==PlaybackService::class.java.name&&it.foreground})
            shell("input keyevent KEYCODE_SLEEP")
            await("screen sleeps"){!context.getSystemService(PowerManager::class.java).isInteractive}
            val sleepPosition=main{player.currentPosition}
            await("screen-off playback"){main{player.isPlaying&&player.currentPosition>sleepPosition+500}}
            android.util.Log.i("BackgroundTest","$label: screen-off progress verified")
            platform.transportControls.pause();await("background system pause"){main{!player.playWhenReady}}
            platform.transportControls.skipToNext();await("system next episode"){main{player.currentMediaItemIndex==1}}
            platform.transportControls.seekTo(7000)
            await("system seek while screen off"){main{player.playbackState==Player.STATE_READY&&player.currentPosition in 6900L..7500L}}
            assertFalse(main{player.playWhenReady})
            platform.transportControls.play();await("background system play"){main{player.isPlaying}}
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            await("system notification shade visible"){
                shell("cmd statusbar expand-notifications");Thread.sleep(300)
                clickSystemText(setOf(target.queue[1].media.title),click=false)
            }
            snapshot("$label-notification-before")
            await("native media notification pause"){clickSystemText(setOf("Pause","暂停"))}
            await("native notification paused player"){main{!player.playWhenReady}}
            await("native media notification play"){clickSystemText(setOf("Play","播放"))}
            await("native notification resumed player"){main{player.isPlaying}}
            android.util.Log.i("BackgroundTest","$label: native notification pause/play verified")
            snapshot("$label-notification")
            await("native media notification title"){clickSystemText(setOf(target.queue[1].media.title))}
            await("native notification opens current player"){main{activity.hasWindowFocus()&&!activity.isInPictureInPictureMode}}
            ready()
            assertEquals(1,main{player.currentMediaItemIndex});assertEquals(2,main{player.mediaItemCount})
            assertTrue(main{player.currentPosition>=6900&&player.isPlaying})
            platform.transportControls.pause();await("pause before snapshot"){main{!player.playWhenReady}}
            ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
            ui.onNodeWithText(target.queue[1].media.title).assertIsDisplayed()
            ui.onNodeWithText("选集 2/2").assertIsDisplayed()
            snapshot("$label-restored")
            // Android's standard Previous first restarts an item when beyond its threshold.
            platform.transportControls.seekTo(0);await("rewind before system previous"){main{player.currentPosition<=300}}
            platform.transportControls.skipToPrevious();await("system previous episode"){main{player.currentMediaItemIndex==0}}
            ready()
            when(label){
                "close-action"->{
                    ui.onNodeWithContentDescription("画中画").performClick()
                    await("PiP before installed close action"){main{activity.isInPictureInPictureMode}}
                    settlePictureInPicture()
                    main{
                        val method=PlaybackActivity::class.java.getDeclaredMethod("pictureInPictureParams").apply{isAccessible=true}
                        val params=method.invoke(activity) as android.app.PictureInPictureParams
                        checkNotNull(params.closeAction).actionIntent.send()
                    }
                }
                "local"->{
                    ui.onNodeWithContentDescription("画中画").performClick()
                    await("PiP before native close"){main{activity.isInPictureInPictureMode}}
                    settlePictureInPicture();closeSystemPictureInPicture()
                }
                "smb"->{
                    val taskId=main{activity.taskId}
                    main{context.getSystemService(ActivityManager::class.java).appTasks.first{it.taskInfo.taskId==taskId}.finishAndRemoveTask()}
                }
                else->scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
            }
            await("explicit exit clears queue"){main{player.mediaItemCount==0&&!player.playWhenReady}}
            assertNull(PlaybackRequests.get(target.playId))
            target.queue.filter{it.uri.scheme=="localplayer"}.forEach{
                assertTrue(runCatching{PlaybackRegistry.get(it.uri)}.isFailure)
            }
        }finally{
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");shell("cmd statusbar collapse")
            main{player.stop();player.clearMediaItems();player.release()};scenario.close();PlaybackRequests.remove(target.playId)
            target.queue.forEach{HistoryStore(context).remove(it.media.key);SourceStore(context).forgetPosition(it.media.key)}
        }
    }
}
