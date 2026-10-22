package io.localplayer.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Polish28Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String){
        ui.waitForIdle();android.os.SystemClock.sleep(400)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try{java.io.File(ui.activity.getExternalFilesDir(null),"polish28-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    @Test fun landscapeLargeTextKeepsUpdateActionsAndVersionInformationReachable(){
        ui.runOnUiThread{ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(5000){ui.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        val app=ui.activity.application
        val owner=androidx.lifecycle.ViewModelStore()
        var vm:SettingsViewModel?=null
        val payload=org.json.JSONArray().put(org.json.JSONObject().put("tag_name","v99.0.0-test").put("prerelease",true).put("body","Fixture letter").put("assets",org.json.JSONArray().put(org.json.JSONObject().put("name","yingxi-99.0.0-test-999999.apk").put("state","uploaded").put("digest","sha256:"+"1".repeat(64)).put("size",1000).put("browser_download_url","https://github.com/RoyalLiang/Yingxi-Studio/releases/download/v99.0.0-test/yingxi-99.0.0-test-999999.apk")))).toString().toByteArray()
        val updates=AppUpdates(app){url->object:java.net.HttpURLConnection(java.net.URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200;override fun getInputStream()=payload.inputStream()
        }}
        try {
            ui.runOnUiThread{vm=SettingsViewModel(app,updates);owner.put("settings",vm!!);vm!!.check()}
            ui.waitUntil(5000){vm?.release!=null&&!vm!!.busy}
            ui.runOnUiThread{
                vm!!.fail("连接暂时中断，已经保存下载进度。".repeat(12))
                ui.activity.setContent {
                    val density=androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,1.8f)){
                        PlayerTheme{VersionSettingsPage(vm!!,true,{},{},{})}
                    }
                }
            }
            ui.onNodeWithText("稍后").performClick()
            ui.onNodeWithTag("check-updates").assertIsDisplayed()
            ui.onNodeWithTag("download-update").assertIsDisplayed()
            ui.onNodeWithTag("open-world").performScrollTo().assertIsDisplayed()
            capture("landscape-large-text")
            ui.onNodeWithTag("update-status").performClick()
            ui.onNodeWithText("更新提示").assertIsDisplayed()
            ui.onNodeWithText("关闭").performClick()
        }finally{ui.runOnUiThread{owner.clear();ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}}
    }
    @Test fun registrationBubbleIsCompactAndAboveBottomNavigation(){
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.runOnIdle{androidx.lifecycle.ViewModelProvider(ui.activity)[LibraryViewModel::class.java].notifyRegistration()}
        val toast=ui.onNodeWithTag("source-toast").getUnclippedBoundsInRoot()
        val root=ui.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Bubble stays near the lower controls",toast.top.value>(root.bottom-root.top).value*.6f)
        assertTrue("Bubble stays above navigation",toast.bottom.value<root.bottom.value-85f)
        assertTrue("Registration height remains compact",(toast.bottom-toast.top).value in 48f..80f)
        capture("registration")
        ui.onNodeWithTag("source-toast").performClick().assertDoesNotExist()
    }
    @Test fun exploreRemainsWithinBottomNavigationAndKeepsItsReadingPosition(){
        ui.onNodeWithText("探索").performClick()
        ui.onNodeWithTag("station-world").assertIsDisplayed()
        ui.onNodeWithTag("world-node:salt").performClick()
        capture("explore")
        ui.onNodeWithText("放映站").performClick()
        ui.onNodeWithText("探索").performClick()
        ui.onNodeWithTag("world-node:salt").assertIsSelected()
        ui.onNodeWithText("拾荒记录").assertIsDisplayed()
    }
    @Test fun updateFooterStaysBelowScrollableContentAndMoreHasAnchoredActions(){
        ui.onNodeWithContentDescription("站务").performClick()
        val footer=ui.onNodeWithTag("update-footer").getUnclippedBoundsInRoot()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasText("角色问候"))
        assertEquals(footer,ui.onNodeWithTag("update-footer").getUnclippedBoundsInRoot())
        ui.onNodeWithTag("check-updates").assertIsDisplayed()
        capture("settings")
        ui.onNodeWithTag("settings-list").performScrollToIndex(0)
        ui.onNodeWithTag("app-version").performClick()
        ui.onNodeWithText("本版说明").assertDoesNotExist()
        val before=ui.onNodeWithTag("update-footer").getUnclippedBoundsInRoot()
        ui.onNodeWithTag("current-letter-scroll").performTouchInput{swipeUp()}
        assertEquals(before,ui.onNodeWithTag("update-footer").getUnclippedBoundsInRoot())
        capture("version")
        ui.onNodeWithTag("version-more").performClick()
        ui.onNodeWithText("阅读本版信笺").assertIsDisplayed()
        ui.onNodeWithText("前往发布页").assertIsDisplayed()
        capture("more")
    }
}
