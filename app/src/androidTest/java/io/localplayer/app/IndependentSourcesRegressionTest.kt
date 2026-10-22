package io.localplayer.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.Protocol
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IndependentSourcesRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String){
  ui.waitForIdle()
  androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/source35-$name.png").use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}
 }
 @Test fun singleEntryContainsCategoryTabsAndIndependentDrafts(){
        val models=ViewModelStore();lateinit var vm:LibraryViewModel
        ui.runOnIdle{vm=LibraryViewModel(ui.activity.application as Application);models.put("sources",vm);ui.activity.setContent{PlayerTheme{SourcesCatalog(vm);SourceDialogs(vm)}}}
        try{
            ui.onNodeWithTag("add-outpost").assertDoesNotExist()
 ui.onNodeWithTag("add-tower").assertDoesNotExist()
 ui.onNodeWithTag("source-category-outpost").assertDoesNotExist()
 ui.onNodeWithTag("add-source").performClick()
 ui.onNodeWithTag("connection-title").assertTextEquals("连接避难所")
 ui.onNodeWithTag("source-category-outpost").assertIsSelected()
 capture("outpost")
 ui.onNodeWithTag("connection-protocol-selector").performClick()
 ui.onNodeWithText("SFTP").performClick()
 ui.runOnIdle{vm.draft=vm.draft!!.let{it.copy(source=it.source.copy(host="outpost.invalid"),password="local-secret")}}
 ui.onNodeWithTag("source-category-tower").performClick()
 ui.onNodeWithTag("source-category-tower").assertIsSelected()
 ui.onNodeWithTag("auto-scan-entry").assertDoesNotExist()
 ui.onNodeWithTag("connection-protocol-selector").performClick()
 ui.onNodeWithText("WebDAV").performClick()
 capture("tower")
 ui.runOnIdle{assertEquals(Protocol.WEBDAV,vm.draft?.source?.protocol);assertEquals("",vm.draft?.password);vm.draft=vm.draft!!.copy(webDavUrl="https://tower.invalid/dav/",password="tower-secret")}
 ui.onNodeWithTag("source-category-outpost").performClick()
 ui.runOnIdle{assertEquals(Protocol.SFTP,vm.draft?.source?.protocol);assertEquals("outpost.invalid",vm.draft?.source?.host);assertEquals("local-secret",vm.draft?.password)}
 ui.onNodeWithTag("auto-scan-entry").assertExists()
 ui.onNodeWithTag("source-category-tower").performClick()
 ui.runOnIdle{assertEquals("https://tower.invalid/dav/",vm.draft?.webDavUrl);assertEquals("tower-secret",vm.draft?.password)}
 ui.onNodeWithTag("connection-close").performClick()
 ui.onNodeWithTag("connection-sheet").assertDoesNotExist()
 }finally{ui.runOnIdle{vm.dismissDraft();models.clear()}}
    }
}
