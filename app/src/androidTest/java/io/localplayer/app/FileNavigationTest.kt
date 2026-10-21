package io.localplayer.app

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.Protocol
import io.localplayer.core.Source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FileNavigationTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun snapshot(name:String){
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yingxi-alpha-$name.png").use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}
    }

    @Test fun localBrowserHasBackAndRealDocumentBreadcrumbs(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val tree=DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"root")
        context.startActivity(Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",tree.toString()).putExtra("target",context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.waitUntil(10000){context.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lateinit var files:LocalFilesViewModel
        ui.runOnIdle{files=ViewModelProvider(ui.activity)[LocalFilesViewModel::class.java]}
        try{
            ui.onNodeWithContentDescription("打开视频").performClick()
            ui.onNodeWithContentDescription("返回").assertIsDisplayed()
            ui.onNodeWithTag("breadcrumb:local").assertDoesNotExist()
            ui.runOnIdle{files.add(tree)}
            ui.waitUntil(10000){(files.active!=null&&!files.busy)||files.error!=null}
            ui.runOnIdle{assertNull(files.error);assertNotNull(files.active)}
            ui.onNodeWithText("影片").performClick()
            ui.waitUntil(10000){files.trail.size==2&&!files.busy}
            ui.onNodeWithText("短片").performClick()
            ui.waitUntil(10000){files.trail.size==3&&!files.busy}
            snapshot("local-navigation")
            ui.onNodeWithTag("breadcrumb:local:1").performClick()
            ui.waitUntil(10000){files.trail.size==2&&!files.busy}
            snapshot("parent-navigation")
        ui.onNodeWithText("示例.mp4").assertIsDisplayed()
            ui.onNodeWithContentDescription("上一级").performClick()
            ui.waitUntil(10000){files.trail.size==1&&!files.busy}
            ui.onNodeWithTag("breadcrumb:local").performClick()
            ui.waitUntil{files.active==null}
            ui.runOnIdle{
                val folder=files.folders.first{it.tree==tree}
                files.open(folder)
                files.remove(folder)
            }
            Thread.sleep(300)
            ui.runOnIdle{assertNull(files.active);assertTrue(files.folders.none{it.tree==tree})}
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithText("映汐").assertIsDisplayed()
        }finally{ui.runOnIdle{files.folders.firstOrNull{it.tree==tree}?.let{files.remove(it)};files.root()}}
    }

    @Test fun remoteBreadcrumbCanJumpToRootAndExitSource(){
        val password=TestFixtures.password
        val source=Source(label="Navigation fixture",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        lateinit var vm:LibraryViewModel
        ui.runOnIdle{
            vm=ViewModelProvider(ui.activity)[LibraryViewModel::class.java]
            vm.draft=Draft(source,password=password,remember=true)
            vm.connectDraft()
        }
        try{
            ui.waitUntil(20000){vm.source!=null&&!vm.busy}
            ui.onNodeWithText("避难所").performClick()
            ui.onNodeWithTag("source-list").assertIsDisplayed()
            ui.onNode(hasText("Navigation fixture") and hasAnyAncestor(hasTestTag("source-list")),useUnmergedTree=true).performClick()
            ui.waitUntil(20000){vm.source!=null&&!vm.busy}
            ui.onNodeWithText("影片").performClick()
            ui.waitUntil(20000){vm.path=="影片"&&!vm.busy}
            ui.onNodeWithTag("breadcrumb:remote:影片").assertIsDisplayed()
            snapshot("remote-navigation")
            ui.onNodeWithTag("breadcrumb:remote:").performClick()
            ui.waitUntil(20000){vm.path==""&&!vm.busy}
            ui.onNodeWithContentDescription("返回避难所").assertIsDisplayed()
            ui.onNodeWithContentDescription("返回避难所").performClick()
            ui.waitUntil{vm.source==null}
            ui.onNodeWithContentDescription("添加避难所").assertIsDisplayed()
        }finally{ui.runOnIdle{SourceStore(ui.activity).delete(source.id);vm.closeSource()}}
    }
}
