package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReportedPlaybackIssuesTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String){
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try{File(ui.activity.cacheDir,"reported-$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }

    @Test fun openingSettingsDoesNotReplayAnOldInstallationCancellation(){
        val app=ui.activity.application
        val owners=ViewModelStore()
        try{
            UpdateInstallResult.save(app,"已取消安装，原有数据保留",false)
            lateinit var vm:SettingsViewModel
            ui.runOnUiThread{
                vm=SettingsViewModel(app,AppUpdates(app));owners.put("settings",vm)
                ui.activity.setContent{PlayerTheme{SettingsRoute(vm){}}}
            }
            ui.waitForIdle()
            ui.onNodeWithText("已取消安装，原有数据保留").assertDoesNotExist()
            assertEquals(0,vm.feedbackRevision)
            capture("settings")
        }finally{ui.runOnUiThread{owners.clear()};UpdateInstallResult.take(app)}
    }

    @Test fun reportOverlayOwnsTheLoadingIndicatorUntilDismissed(){
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
            FullscreenPlayer(null,"测试影片",null,{},{},{},{},false,null,{},{},entryReady=false)
        }}}
        ui.onNodeWithTag("playback-buffering").assertExists()
        if(ui.onAllNodesWithContentDescription("更多播放操作").fetchSemanticsNodes().isNotEmpty()){
            ui.onNodeWithContentDescription("更多播放操作").performClick()
            ui.onNodeWithText("上报播放问题").performClick()
        }else ui.onNodeWithContentDescription("上报播放问题").performClick()
        ui.onNodeWithTag("playback-diagnostics").assertExists()
        ui.onNodeWithTag("playback-buffering").assertDoesNotExist()
        capture("report")
        ui.onNodeWithContentDescription("关闭问题上报").performClick()
        ui.onNodeWithTag("playback-buffering").assertExists()
    }

    @Test fun openingSettingsDoesNotAutomaticallyInstallARestoredDownload(){
        val app=ui.activity.application
        val previous=UpdateDownloads.state.value
        val owners=ViewModelStore()
        val file=File(app.filesDir,"updates/restored-test.apk").apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(1))}
        try{
            val release=AppRelease(AppVersion("9.0.0-alpha.1",9999),"","https://example.org/update.apk",1,"b".repeat(64),"c".repeat(64))
            lateinit var vm:SettingsViewModel
            ui.runOnUiThread{
                UpdateDownloads.update(UpdateTransfer(release,ready=file,message="安装包已下载，可继续安装"))
                vm=SettingsViewModel(app);owners.put("restored",vm)
                ui.activity.setContent{PlayerTheme{SettingsRoute(vm){}}}
            }
            ui.waitForIdle()
            ui.onNodeWithTag("settings-page").assertExists()
            assertFalse(vm.installing)
            assertEquals(0,vm.feedbackRevision)
            assertEquals(file,vm.ready)
        }finally{ui.runOnUiThread{owners.clear();UpdateDownloads.update(previous)};file.delete()}
    }
}
