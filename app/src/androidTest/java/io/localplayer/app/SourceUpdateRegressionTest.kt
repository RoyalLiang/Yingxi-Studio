package io.localplayer.app

import android.app.Application
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SourceUpdateRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val owners=ViewModelStore()
    private lateinit var vm:LibraryViewModel
    private fun mount(font:Float=1f){
        ui.runOnUiThread{
            vm=LibraryViewModel(ui.activity.application);owners.put("sources",vm)
            val files=LocalFilesViewModel(ui.activity.application);owners.put("files",files)
            ui.activity.setContent{PlayerTheme{val d=androidx.compose.ui.platform.LocalDensity.current;androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density,font)){androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredWidth(androidx.compose.ui.unit.Dp(320f))){AppShell(vm,files,{},{},{})}}}}
        }
    }
    @After fun close(){ui.runOnUiThread{owners.clear()}}
    @Test fun automaticScanIsAvailableBesideEveryManualProtocol(){
        mount();ui.runOnIdle{vm.add()}
        listOf("SMB","SFTP","WebDAV").forEach{protocol->
            ui.onNodeWithText(protocol).performClick()
            ui.onNodeWithTag("auto-scan-entry").assertIsDisplayed()
        }
        ui.onNodeWithTag("auto-scan-entry").performClick()
        ui.onNodeWithTag("source-discovery").assertIsDisplayed()
        listOf("SMB","SFTP","WebDAV").forEach { ui.onNodeWithText(it).assertIsNotSelected() }
        capture("world15-sources")
    }
    @Test fun newSourceSheetUsesDarkStatusIconsOnItsLightSurface(){
        mount();ui.runOnIdle{vm.add()};ui.waitForIdle()
        ui.waitUntil(5000) {
            var focused=false
            ui.runOnUiThread { focused=WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus()&&it!==ui.activity.window.decorView } }
            focused
        }
        ui.runOnIdle{
            val dialog=WindowInspector.getGlobalWindowViews().last{it.hasWindowFocus()&&it!==ui.activity.window.decorView}
            val appearance=dialog.windowInsetsController!!.systemBarsAppearance
            assertTrue("Light source sheet must keep dark status text",appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS!=0)
        }
    }
    @Test fun updateActionRemainsVisibleWithoutReadingAllReleaseNotes(){
        mount(1.8f);ui.onNodeWithTag("open-settings").performClick();ui.onNode(hasScrollAction()).performScrollToNode(hasTestTag("app-version"));ui.onNodeWithTag("app-version").performClick()
        ui.onNodeWithTag("check-updates").assertIsDisplayed();capture("world15-updates")
    }
    @Test fun separateChecksCannotReuseAnOldReleaseListCacheKey()=runBlocking{
        val urls=mutableListOf<String>()
        val name="1.9.0-alpha.1"
        val asset=JSONObject().put("name","yingxi-$name-199.apk").put("state","uploaded").put("size",100)
            .put("digest","sha256:"+"b".repeat(64))
            .put("browser_download_url","https://github.com/"+AppUpdates.REPOSITORY+"/releases/download/v$name/yingxi-$name-199.apk")
        val body=JSONArray().put(JSONObject().put("tag_name","v$name").put("prerelease",true).put("draft",false).put("body","fresh notes").put("assets",JSONArray().put(asset))).toString().toByteArray()
        val updates=AppUpdates(ui.activity.application){url->
            urls+=url
            object:HttpURLConnection(URL(url)){
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream()=body.inputStream()
            }
        }
        assertNotNull(updates.check());assertNotNull(updates.check())
        assertEquals(2,urls.size)
        assertNotEquals("Manual checks must not reuse a stale release-list cache entry",urls[0],urls[1])
    }
    private fun capture(name:String){
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap=i.uiAutomation.takeScreenshot()
        java.io.File(ui.activity.cacheDir,name+".png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun discoveryRecordsPreserveTlsAndPathWithoutImportingAdvertisedCredentials(){
        val kind=DiscoveryRecords.kinds.single{it.type=="_webdavs._tcp."}
        val candidate=DiscoveryRecords.candidate(kind,"NAS","nas.invalid",5006,mapOf("path" to "/dav/Movies".toByteArray(),"u" to "untrusted-user".toByteArray(),"p" to "never-import".toByteArray()))!!
        assertEquals(io.localplayer.core.Protocol.WEBDAV,candidate.protocol)
        assertTrue(candidate.secure);assertEquals("/dav/Movies",candidate.root)
        mount();ui.runOnIdle{vm.add(candidate)}
        assertTrue(vm.draft!!.webDavUrl.startsWith("https://nas.invalid:5006/dav/Movies"))
        assertEquals("",vm.draft!!.source.username);assertEquals("",vm.draft!!.password)
        val http=DiscoveryRecords.kinds.single{it.type=="_http._tcp."}
        assertNull(DiscoveryRecords.candidate(http,"ordinary website","web.invalid",80,emptyMap()))
        val dav=DiscoveryRecords.candidate(http,"DAV","web.invalid",80,mapOf("dav" to null))!!
        ui.runOnIdle{vm.add(dav)}
        assertFalse(vm.draft!!.allowHttp)
    }
    @Test fun releaseLetterKeepsParagraphsAndItsOwnLanguage(){
        val raw="# Title\n\n## 中文\n\n【拾荒者信笺】\n柜台收到新信。\n1. 自动扫描移到独立入口。\n\n## English\n\n[Scavenger letter]\nThe counter has a new letter.\n1. Discovery has its own entrance.\n"
        val chinese=displayReleaseNotes(raw,"zh")
        assertTrue(chinese.contains("柜台收到新信。\n1. 自动扫描"));assertFalse(chinese.contains("The counter"))
        val english=displayReleaseNotes(raw,"en")
        assertTrue(english.contains("The counter"));assertFalse(english.contains("柜台"))
        val legacy=raw.replace("\n\n## English","\n### 验证与限制\n- Old audit result.\n\n## English")
        assertFalse(displayReleaseNotes(legacy,"zh").contains("Old audit"))
    }
    @Test fun fetchedVersionAndLetterOpenTogetherWithDownloadStillReachable(){
        val version="1.9.0-alpha.1"
        val raw="## 中文\n\n【拾荒者信笺】\n"+(1..30).joinToString("\n"){"柜台信件 "+it+"：自动扫描的新路标已经安放。"}+
            "\n\n## English\n\n[Scavenger letter]\n"+(1..30).joinToString("\n"){"Letter "+it+": the new discovery signpost is in place."}
        val asset=JSONObject().put("name","yingxi-"+version+"-199.apk").put("state","uploaded").put("size",100)
            .put("digest","sha256:"+"b".repeat(64)).put("browser_download_url","https://github.com/"+AppUpdates.REPOSITORY+"/releases/download/v"+version+"/yingxi-"+version+"-199.apk")
        val bytes=JSONArray().put(JSONObject().put("tag_name","v"+version).put("prerelease",true).put("draft",false).put("body",raw).put("assets",JSONArray().put(asset))).toString().toByteArray()
        val updates=AppUpdates(ui.activity.application){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200;override fun getInputStream()=bytes.inputStream()
        }}
        lateinit var settings:SettingsViewModel
        ui.runOnUiThread{
            settings=SettingsViewModel(ui.activity.application,updates);owners.put("settings",settings)
            ui.activity.setContent{PlayerTheme{SettingsRoute(settings){}}}
        }
        ui.onNodeWithTag("app-version").performClick();ui.onNodeWithTag("check-updates").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithTag("release-letter").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("letter-version").assertTextEquals(version)
        ui.onNodeWithTag("confirm-update").assertIsDisplayed()
        capture("world15-letter")
        ui.onNodeWithText("稍后").performClick()
        ui.onNodeWithTag("available-version").assertTextEquals(version)
        ui.onNodeWithTag("download-update").assertIsDisplayed()
    }
}
