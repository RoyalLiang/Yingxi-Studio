package io.localplayer.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SettingsAndUpdatesTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun settingsLivesOutsideBottomTabsAndVersionSurvivesRecreation() {
        ui.onNodeWithTag("open-settings").performClick()
        ui.onNodeWithTag("settings-page").assertIsDisplayed()
        ui.onAllNodes(hasText("首页") and hasClickAction()).assertCountEquals(0)
        ui.onNodeWithTag("app-version").performClick()
        val version=installedVersion(context)
        ui.onNodeWithText("映汐播放器 ${version.name}").assertIsDisplayed()
        ui.onNodeWithTag("check-updates").assertIsDisplayed().assertHasClickAction()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("映汐播放器 ${version.name}").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("app-version").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("open-settings").assertIsDisplayed()
    }

    @Test fun lightScreensKeepDarkSystemIconsAndRespectStatusBarInsets() {
        ui.runOnIdle {
            assertTrue(WindowCompat.getInsetsController(ui.activity.window,ui.activity.window.decorView).isAppearanceLightStatusBars)
            assertTrue(WindowCompat.getInsetsController(ui.activity.window,ui.activity.window.decorView).isAppearanceLightNavigationBars)
        }
        val status=ui.activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        assertTrue(ui.onNodeWithTag("open-settings").fetchSemanticsNode().boundsInRoot.top>=status)
        ui.onNodeWithTag("open-settings").performClick()
        assertTrue(ui.onNodeWithTag("app-version").fetchSemanticsNode().boundsInRoot.top>=status)
    }

    @Test fun manifestAndArchiveMustMatchRepositoryVersionAndInstalledSigner() {
        assertFalse(AppVersion("1.2.3",10).isPrerelease)
        assertFalse(AppVersion("1.2.3+build-dev",10).isPrerelease)
        assertTrue(AppVersion("1.2.3-alpha.4",10).isPrerelease)
        assertTrue(AppVersion("1.2.3-rc.1+build-dev",10).isPrerelease)
        val version=installedVersion(context)
        val apk=File(context.applicationInfo.sourceDir)
        val signer=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners.single().toByteArray().sha()
        val release=JSONObject().put("tag_name","v${version.name}").put("body","Test release notes").put("assets",JSONArray().put(JSONObject().put("name","player.apk").put("size",apk.length()).put("browser_download_url","https://github.com/${AppUpdates.REPOSITORY}/releases/download/v${version.name}/player.apk")))
        val manifest=JSONObject().put("formatVersion",1).put("versionName",version.name).put("versionCode",version.code).put("fileName","player.apk").put("sizeBytes",apk.length()).put("sha256","a".repeat(64)).put("signerSha256",signer)
        val candidate=AppUpdates.parseManifest(manifest,release)
        AppUpdates.verifyArchive(context,apk,candidate)
        fun rejected(block:()->Unit){var rejected=false;try{block()}catch(e:Exception){rejected=true};assertTrue(rejected)}
        rejected{AppUpdates.verifyArchive(context,apk,candidate.copy(signer="b".repeat(64)))}
        rejected{AppUpdates.verifyArchive(context,apk,candidate.copy(version=version.copy(code=version.code+1)))}
        rejected{AppUpdates.requireReleaseUrl("https://github.com/unrelated/app/releases/download/v1/player.apk")}
        rejected{AppUpdates.requireReleaseUrl("http://github.com/${AppUpdates.REPOSITORY}/releases/download/v1/player.apk")}
        manifest.put("sizeBytes",apk.length()+1)
        rejected{AppUpdates.parseManifest(manifest,release)}
    }
    private fun ByteArray.sha()=MessageDigest.getInstance("SHA-256").digest(this).joinToString(""){"%02x".format(it)}
}
