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
        ui.runOnUiThread{
            val vm=SettingsViewModel(ui.activity.application)
            ui.activity.setContent{PlayerTheme{VersionSettingsPage(vm,true,{},{},{})}}
        }
        val footer=ui.onNodeWithTag("update-footer").captureToImage().toPixelMap()
        assertEquals("Footer must use the page background",Paper.toArgb(),footer[4,4].toArgb())
        assertEquals("No separate separator band",Paper.toArgb(),footer[footer.width/2,0].toArgb())
        val button=ui.onNodeWithTag("check-updates").assertIsDisplayed()
        val pixels=button.captureToImage().toPixelMap()
        assertEquals("Check button must retain its filled accent",Berry.toArgb(),pixels[pixels.width/4,pixels.height/3].toArgb())
        val before=button.fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("current-letter-scroll").performTouchInput{swipeUp()}
        assertEquals(before,button.fetchSemanticsNode().boundsInRoot)
        val screenshot=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ui.activity.cacheDir,"pack34-update-footer.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
}
