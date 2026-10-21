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
    @Test fun outpostAndTowerHaveIndependentEntriesAndProtocolDropdowns(){
        val models=ViewModelStore();lateinit var vm:LibraryViewModel
        ui.runOnIdle{vm=LibraryViewModel(ui.activity.application as Application);models.put("sources",vm);ui.activity.setContent{PlayerTheme{SourcesCatalog(vm);SourceDialogs(vm)}}}
        try{
            ui.onNodeWithTag("add-outpost").performClick()
            ui.onNodeWithTag("connection-title").assertTextEquals("连接幸存者据点")
            ui.onNodeWithText("连接 NAS、服务器或网盘，把影像接回片架。").assertDoesNotExist()
            ui.onNodeWithTag("connection-protocol-selector").performClick()
            ui.onNodeWithText("SFTP").performClick()
            ui.runOnIdle{assertEquals(Protocol.SFTP,vm.draft?.source?.protocol);vm.dismissDraft()}
            ui.onNodeWithTag("add-tower").performClick()
            ui.onNodeWithTag("connection-title").assertTextEquals("接入外部信号塔")
            ui.onNodeWithTag("auto-scan-entry").assertDoesNotExist()
            ui.onNodeWithTag("connection-protocol-selector").performClick()
            ui.onNodeWithText("WebDAV").performClick()
            ui.runOnIdle{assertEquals(Protocol.WEBDAV,vm.draft?.source?.protocol)}
            ui.onNodeWithTag("connection-protocol-selector").performClick()
            ui.onNodeWithText("SMB").assertDoesNotExist()
            ui.onNodeWithText("SFTP").assertDoesNotExist()
        }finally{ui.runOnIdle{vm.dismissDraft();models.clear()}}
    }
}
