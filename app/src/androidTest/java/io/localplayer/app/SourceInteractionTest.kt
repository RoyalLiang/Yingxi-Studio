package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=ViewModelStore()
    private val saved=mutableListOf<String>()
    private lateinit var vm:LibraryViewModel
    private fun capture(name:String){ui.waitForIdle();shell("screencap -p /sdcard/Download/source-$name.png")}
    private fun shell(command:String):String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use{
        java.io.FileInputStream(it.fileDescriptor).use{stream->stream.readBytes().toString(Charsets.UTF_8)}
    }
    private fun awaitSystemPrompt(){
        ui.waitForIdle()
        val deadline=android.os.SystemClock.elapsedRealtime()+10_000
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(shell("dumpsys window").lineSequence().any{it.contains("mCurrentFocus=")&&it.contains("BiometricPrompt")}){Thread.sleep(1500);return}
            Thread.sleep(100)
        }
        fail("System authentication window must be focused")
    }
    private fun cancelSystemPrompt(){
        // The first Back can dismiss the PIN keyboard. Only send another while the
        // system authentication window still owns focus; never navigate the app.
        repeat(3){
            shell("input keyevent 4")
            Thread.sleep(800)
            ui.waitForIdle()
            if(shell("dumpsys window").lineSequence().none{it.contains("mCurrentFocus=")&&it.contains("BiometricPrompt")})return
        }
    }
    private fun mount(source:Source?=null){
        if(source!=null){SourceStore(context).save(source,TestFixtures.password,true);saved+=source.id}
        ui.runOnUiThread {
            vm=LibraryViewModel(context.applicationContext as android.app.Application)
            val files=LocalFilesViewModel(context.applicationContext as android.app.Application)
            scope.put("library",vm);scope.put("files",files)
            ui.activity.setContent {PlayerTheme {AppShell(vm,files,{},{},{})}}
        }
        ui.waitForIdle()
    }
    @After fun cleanup(){ui.runOnUiThread{scope.clear()};saved.forEach{SourceStore(context).delete(it)}}
    @Test fun clickingSavedSftpBrowsesWithoutOpeningConfiguration(){
        val unknown=Source(label="已保存SFTP验证",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
        val key=try{SftpConnector().connect(unknown,Credentials(TestFixtures.password)).close();error("Expected trust verification")}
        catch(e:HostKeyRequired){e.fingerprint}
        val source=unknown.copy(trustedKey=key,trustedAuthority=unknown.authority)
        mount(source)
        // Keep the saved row above the floating navigation touch area.
        ui.onNodeWithTag("home-list").performScrollToNode(hasText(source.label))
        ui.onNodeWithText(source.label).performClick()
        ui.runOnIdle{assertTrue("Saved source click must start opening its directory",vm.openingSource?.id==source.id||vm.source?.id==source.id)}
        ui.onNodeWithText("连接来源").assertDoesNotExist()
        ui.waitUntil(15_000){vm.source?.id==source.id||vm.error!=null}
        ui.runOnIdle {assertNull(vm.error);assertEquals(source.id,vm.source?.id);assertNull(vm.draft)}
        ui.onNodeWithText("附近服务").assertDoesNotExist()
        capture("sftp-browser")
    }
    @Test fun discoveryRejectsCellularAndStopsOutsideHome(){
        mount()
        ui.runOnIdle{vm.discover();if(!vm.scanning)assertTrue(vm.discoveryMessage.contains("Wi-Fi"))}
        ui.onNodeWithText("来源").performClick()
        ui.runOnIdle{assertFalse("discovery must stop outside Home",vm.scanning)}
        ui.onNodeWithText("附近服务").assertDoesNotExist()
        capture("sources-empty")
    }
    @Test fun expandedConnectionSheetKeepsTopWhenProtocolChanges(){
        mount()
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithContentDescription("添加来源").performClick()
        ui.onNodeWithTag("connection-form").performTouchInput{swipeUp()}
        ui.onNodeWithTag("connection-form").performScrollToIndex(0)
        val top=ui.onNodeWithText("连接来源").getUnclippedBoundsInRoot().top
        for(protocol in listOf("SFTP","WebDAV","SMB")){
            ui.runOnIdle{vm.draft=vm.draft?.let{it.copy(source=it.source.copy(initialPath="legacy"),browsePath="legacy-folder")}}
            ui.onNodeWithText(protocol).performClick()
            ui.runOnIdle{assertEquals("",vm.draft?.source?.initialPath);assertNull(vm.draft?.browsePath)}
            ui.onNodeWithTag("connection-form").performScrollToIndex(0)
            assertEquals(top.value,ui.onNodeWithText("连接来源").getUnclippedBoundsInRoot().top.value,4f)
            capture("sheet-${protocol.lowercase()}")
        }
    }

    @Test fun savedSourceOnlyEditsFromItsConfigurationIcon(){
        val source=Source(label="配置入口验证",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
        mount(source)
        ui.onNodeWithText("添加第一个来源").assertDoesNotExist()
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithContentDescription("修改配置 ${source.label}").performClick()
        ui.onNodeWithText("连接来源").assertIsDisplayed()
        ui.runOnIdle{assertEquals(source.id,vm.draft?.source?.id)}
    }

    @Test fun swipingOnlyRevealsActionsAndPinLimitIsPersisted(){
        val sources=(1..4).map{index->Source(label="滑动来源 $index",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)}
        val store=SourceStore(context)
        sources.forEach{store.save(it,TestFixtures.password,true);saved+=it.id}
        mount()
        ui.onNodeWithText("来源").performClick()
        for(source in sources.take(3)){
            ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${source.id}"))
            ui.onNodeWithTag("source-row:${source.id}").performTouchInput{swipeLeft()}
            ui.runOnIdle{assertNull(vm.source);assertNull(vm.draft)}
            capture("swipe-left")
            ui.onNodeWithText("置顶").performClick()
            ui.runOnIdle{assertTrue(source.id in vm.pinnedIds)}
        }
        ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${sources[3].id}"))
        ui.onNodeWithTag("source-row:${sources[3].id}").performTouchInput{swipeLeft()}
        ui.onNodeWithText("置顶").performClick()
        ui.runOnIdle{assertEquals(3,vm.pinnedIds.size);assertTrue(vm.error?.contains("最多置顶") == true)}
        assertEquals(sources.take(3).map{it.id}.toSet(),SourceStore(context).pinnedIds())
        ui.onNodeWithContentDescription("关闭提示").performClick()
        ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${sources[0].id}"))
        ui.onNodeWithTag("source-row:${sources[0].id}").performTouchInput{swipeLeft()}
        ui.onNodeWithText("取消置顶").performClick()
        assertFalse(sources[0].id in SourceStore(context).pinnedIds())
    }

    @Test fun swipeDeleteRequiresConfirmationAndKeepsServerFiles(){
        val source=Source(label="删除确认验证",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        mount(source)
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${source.id}"))
        ui.onNodeWithTag("source-row:${source.id}").performTouchInput{swipeLeft()}
        assertTrue(SourceStore(context).hasPassword(source.id))
        ui.onNodeWithText("删除").performClick()
        ui.onNodeWithText("取消").performClick()
        assertTrue(SourceStore(context).load().any{it.id==source.id})
        ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${source.id}"))
        ui.onNodeWithTag("source-row:${source.id}").performTouchInput{swipeLeft()}
        ui.onNodeWithText("删除").performClick()
        ui.onNodeWithText("删除来源").performClick()
        assertFalse(SourceStore(context).load().any{it.id==source.id})
        assertFalse(SourceStore(context).hasPassword(source.id))
        Protocols().connect(source,Credentials(TestFixtures.password)).use{session->assertTrue(session.list("").isNotEmpty())}
    }

    @Test fun privateAccessCancellationNeverOpensSource(){
        val source=Source(label="一次性系统验证",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        mount(source)
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${source.id}"))
        ui.onNodeWithTag("source-row:${source.id}").performTouchInput{swipeRight()}
        capture("swipe-right")
        ui.onNodeWithText("私密访问").performClick()
        ui.runOnIdle{assertNull(vm.source);assertNull(vm.openingSource);assertNull(vm.draft)}
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure){
            awaitSystemPrompt()
            cancelSystemPrompt()
        }
        ui.waitUntil(5_000){vm.privateRequest==null}
        ui.runOnIdle{assertNull(vm.source);assertNull(vm.openingSource)}
    }

    @Test fun missingPasswordRequestsCredentialsWithoutOpeningConfiguration(){
        val source=Source(label="临时认证验证",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        SourceStore(context).save(source,"",false);saved+=source.id
        mount()
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithText(source.label).performClick()
        ui.waitUntil(5_000){vm.access!=null}
        ui.onNodeWithText("验证来源").assertIsDisplayed()
        ui.onNodeWithText("连接来源").assertDoesNotExist()
        ui.runOnIdle{assertNull(vm.draft);vm.updateAccess(password=TestFixtures.password);vm.connectAccess()}
        ui.waitUntil(15_000){vm.source?.id==source.id||vm.error!=null}
        ui.runOnIdle{assertNull(vm.error);assertEquals(source.id,vm.source?.id);assertNull(vm.access)}
    }

    @Test fun historyUsesCompactRowsAndDateSections(){
        val store=HistoryStore(context)
        val media=MediaRef("source-ux-history","漫长夏日 · 特别收藏版 · 第十二集 2160p.mkv","私人片库")
        store.update(media,store.token(media.key),1_245_000,6_480_000,false)
        try{
            mount()
            ui.onNodeWithText("播放记录").performClick()
            ui.onNodeWithText("今天").assertIsDisplayed()
            val bounds=ui.onNodeWithTag("history-row:${media.key}").getUnclippedBoundsInRoot()
            assertTrue("Mini-player row should stay compact",bounds.bottom.value-bounds.top.value<=84f)
            capture("history")
            ui.onNodeWithContentDescription("记录操作 ${media.title}").performClick()
            ui.onNodeWithText("从头播放").assertIsDisplayed()
            ui.onNodeWithText("移除此记录").assertIsDisplayed()
        }finally{store.remove(media.key)}
    }

    @Test fun systemCredentialUnlocksOnlyThisPrivateOpen(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val pin=InstrumentationRegistry.getArguments().getString("device_pin")
        org.junit.Assume.assumeTrue("Dedicated emulator PIN required",pin!=null)
        requireNotNull(pin)
        require(pin.matches(Regex("[0-9]{4,8}")))
        val source=Source(label="系统锁屏验证",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        mount(source)
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithTag("source-bookmarks").performScrollToNode(hasTestTag("source-row:${source.id}"))
        ui.onNodeWithTag("source-row:${source.id}").performTouchInput{swipeRight()}
        ui.onNodeWithText("私密访问").performClick()
        assertNull(vm.source)
        awaitSystemPrompt()
        shell("input text $pin")
        shell("input keyevent 66")
        ui.waitUntil(15_000){vm.source?.id==source.id||vm.error!=null}
        ui.runOnIdle{assertEquals(source.id,vm.source?.id);assertNull(vm.privateRequest);assertNull(vm.draft)}
        ui.runOnIdle{vm.closeSource();vm.privateAccess(source)}
        ui.runOnIdle{assertNull(vm.source);assertNotNull("second private action must authenticate again",vm.privateRequest)}
        awaitSystemPrompt()
        cancelSystemPrompt()
        ui.waitUntil(5_000){vm.privateRequest==null}
    }
}
