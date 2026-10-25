package io.localplayer.app

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SettingsPolish56RegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String){
        ui.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->
            java.io.File(ui.activity.cacheDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    @Test fun reportConsentIsExplicitAndPreviewRemainsLocal() {
        ui.activity.setContent{PlayerTheme{PlaybackReportDialog(null){}}}
        ui.onNodeWithTag("report-continue").assertIsNotEnabled()
        ui.onNodeWithTag("report-consent").assertIsOff()
        ui.onNodeWithTag("report-preview").performClick()
        ui.onNodeWithTag("report-payload").assertTextContains("yingxi-playback-v1",substring=true)
        ui.onNodeWithTag("report-consent").performScrollTo().performClick()
        ui.onNodeWithTag("report-continue").assertIsEnabled()
        capture("report-dialog56.png")
        ui.onNodeWithTag("report-consent").performClick()
        ui.onNodeWithTag("report-continue").assertIsNotEnabled()
    }
    @Test fun narrowLargeTypeKeepsBothSettingsInRowsAndPersistsChoices() {
        val context=ui.activity
        val appearance=AppearancePreferences(context);val oldMode=appearance.mode
        val audio=PlaybackPreferences(context);val oldLanguage=audio.audioLanguage
        try {
            ui.activity.setContent{PlayerTheme{CompositionLocalProvider(LocalDensity provides Density(1f,1.8f)){Column(Modifier.width(320.dp)){NightWatchSettings();StationAudioSettings()}}}}
            ui.onNodeWithTag("appearance-selector").assertIsDisplayed().performClick()
            ui.onNodeWithTag("appearance-night").performClick()
            assertEquals(LibraryAppearance.NIGHT,appearance.mode)
            ui.onNodeWithTag("audio-language-selector").assertIsDisplayed().performClick()
            ui.onNodeWithTag("audio-language-zh").performClick()
            assertEquals("zh",audio.audioLanguage)
            ui.onNodeWithText("听音频段").assertIsDisplayed()
            capture("settings-inline56.png")
        } finally {appearance.mode=oldMode;audio.audioLanguage=oldLanguage}
    }
    @Test fun updateCheckingAndResultNeverGrowTheFooterAboveTheButton() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val updates=AppUpdates(ui.activity){value->object:HttpURLConnection(URL(value)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getInputStream():java.io.InputStream{entered.countDown();check(release.await(5,TimeUnit.SECONDS));return ByteArrayInputStream("[]".toByteArray())}
        }}
        val vm=SettingsViewModel(ui.activity.application,updates)
        val store=androidx.lifecycle.ViewModelStore().apply{put("settings56",vm)}
        try {
            ui.activity.setContent{PlayerTheme{VersionSettingsPage(vm,false,{},{},{})}}
            val before=ui.onNodeWithTag("update-footer").fetchSemanticsNode().boundsInRoot
            ui.onNodeWithTag("check-updates").performClick();assertTrue(entered.await(5,TimeUnit.SECONDS))
            ui.onNodeWithText("正在检查…").assertIsDisplayed()
            val checking=ui.onNodeWithTag("update-footer").fetchSemanticsNode().boundsInRoot
            assertEquals(before.top,checking.top,1f);assertEquals(before.height,checking.height,1f)
            release.countDown();ui.waitUntil(5000){!vm.busy}
            ui.onNodeWithText("已是最新版本").assertIsDisplayed()
            val done=ui.onNodeWithTag("update-footer").fetchSemanticsNode().boundsInRoot
            assertEquals(before.top,done.top,1f);assertEquals(before.height,done.height,1f)
        } finally {release.countDown();ui.runOnUiThread{store.clear()}}
    }
}
