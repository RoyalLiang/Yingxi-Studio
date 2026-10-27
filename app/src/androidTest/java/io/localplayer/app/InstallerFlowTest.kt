package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Host confirms the genuine system dialog after this test hands off the verified payload. */
class InstallerFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun handOffHigherVersionToSystemInstaller() {
        val context=ui.activity.application
        val sample=File(context.getExternalFilesDir(null),"next-update.apk")
        check(sample.isFile)
        val packageInfo=context.packageManager.getPackageArchiveInfo(sample.path,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)!!
        val version=AppVersion(packageInfo.versionName!!,packageInfo.longVersionCode)
        assertTrue(version.code>installedVersion(context).code)
        val hash=MessageDigest.getInstance("SHA-256").digest(sample.readBytes()).joinToString(""){"%02x".format(it)}
        val name="yingxi-${version.name}-${version.code}.apk"
        val remote=JSONObject().put("tag_name","v${version.name}").put("prerelease",true).put("body","Installer fixture")
            .put("assets",JSONArray().put(JSONObject().put("name",name).put("size",sample.length()).put("state","uploaded")
                .put("digest","sha256:$hash").put("browser_download_url","https://github.com/${AppUpdates.REPOSITORY}/releases/download/v${version.name}/$name")))
        val updates=AppUpdates(context) { url ->
            val body=if(url.startsWith("https://api.github.com"))JSONArray().put(remote).toString().toByteArray() else sample.readBytes()
            object:HttpURLConnection(URL(url)) {
                override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
                override fun getResponseCode()=200;override fun getContentLengthLong()=body.size.toLong()
                override fun getInputStream()=body.inputStream()
            }
        }
        lateinit var vm:SettingsViewModel
        ui.runOnUiThread {
            vm=SettingsViewModel(context,updates)
            ui.activity.setContent { PlayerTheme { SettingsRoute(vm){} } }
        }
        ui.onNodeWithTag("app-version").performClick()
        ui.onNodeWithTag("check-updates").performClick()
        ui.waitUntil(10000){!vm.busy&&vm.release!=null}
        ui.onNodeWithTag("confirm-update").performClick()
        // Avoid waiting for Compose idleness once the external system installer owns the display.
        val deadline=android.os.SystemClock.elapsedRealtime()+15000
        while(vm.ready==null&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
        assertNotNull(vm.message,vm.ready)
        ui.waitForIdle()
        Thread.sleep(2500)
        assertFalse(vm.message,vm.error)
        ui.runOnUiThread{vm.install();vm.install()}
        val sessions=context.packageManager.packageInstaller.mySessions.filter{it.appPackageName==context.packageName}
        assertEquals("Repeated offers must share one install session",1,sessions.size)
        val activities=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_ACTIVITIES).activities.orEmpty()
        assertFalse("System confirmation must not pass through a second app page",activities.any{it.name.endsWith("UpdateInstallActivity")})

        if(InstrumentationRegistry.getArguments().getString("holdInstaller")=="true") {
            val until=android.os.SystemClock.elapsedRealtime()+45000
            while(android.os.SystemClock.elapsedRealtime()<until) { ui.waitForIdle();Thread.sleep(100) }
        }
    }
}
