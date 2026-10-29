package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateFooterAppearanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun footerSharesPageColorAndCheckButtonStaysFilledAndFixed(){
        var pageColor=androidx.compose.ui.graphics.Color.Unspecified
        var accent=androidx.compose.ui.graphics.Color.Unspecified
        ui.runOnUiThread{
            val vm=SettingsViewModel(ui.activity.application)
            ui.activity.setContent{PlayerTheme{pageColor=Paper;accent=Berry;VersionSettingsPage(vm,true,{},{},{})}}
        }
        val footer=ui.onNodeWithTag("update-footer").captureToImage().toPixelMap()
        assertEquals("Footer must use the page background",pageColor.toArgb(),footer[4,4].toArgb())
        assertEquals("No separate separator band",pageColor.toArgb(),footer[footer.width/2,0].toArgb())
        val button=ui.onNodeWithTag("check-updates").assertIsDisplayed()
        val pixels=button.captureToImage().toPixelMap()
        assertEquals("Check button must retain its filled accent",accent.toArgb(),pixels[pixels.width/4,pixels.height/3].toArgb())
        val before=button.fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("current-letter-scroll").performTouchInput{swipeUp()}
        assertEquals(before,button.fetchSemanticsNode().boundsInRoot)
        val screenshot=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ui.activity.cacheDir,"pack34-update-footer.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun transferShowsOnePercentageButtonWithoutCapacityBelowIt(){
        val previous=UpdateDownloads.state.value
        val owners=androidx.lifecycle.ViewModelStore()
        try{
            ui.runOnUiThread{
                val vm=SettingsViewModel(ui.activity.application);owners.put("transfer-appearance",vm)
                ui.activity.setContent{PlayerTheme{VersionSettingsPage(vm,true,{},{},{})}}
                val release=AppRelease(AppVersion("9.0.0-alpha.1",9999),"","https://example.org/update.apk",30_000_000,"b".repeat(64),"c".repeat(64))
                UpdateDownloads.update(UpdateTransfer(release,busy=true,progress=.42f))
            }
            ui.onNodeWithTag("download-update").assertTextContains("暂停下载 42%")
            ui.onNodeWithTag("update-footer").onChildren().assertCountEquals(1)
            ui.onAllNodes(hasText("MB",substring=true) and hasAnyAncestor(hasTestTag("update-footer"))).assertCountEquals(0)
            val screenshot=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(ui.activity.cacheDir,"design-a-update-progress.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }finally{ui.runOnUiThread{owners.clear();UpdateDownloads.update(previous)}}
    }

}
