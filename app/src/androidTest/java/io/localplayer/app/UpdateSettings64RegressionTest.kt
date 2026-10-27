package io.localplayer.app

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class UpdateSettings64RegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val owners=ViewModelStore()
    private val context get()=ui.activity.application
    @After fun clear(){ui.runOnUiThread{owners.clear();OnlineSubtitles.cancel()}}
    private fun mount(updates:AppUpdates):SettingsViewModel {
        lateinit var vm:SettingsViewModel
        ui.runOnUiThread{vm=SettingsViewModel(context,updates);owners.put("settings",vm);ui.activity.setContent{PlayerTheme{SettingsRoute(vm){}}}}
        return vm
    }
    private fun capture(name:String){
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        File(context.cacheDir,"update64-$name.png").outputStream().use{i.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun checkFailureExplainsItselfAtTheTopAndButtonRetries(){
        val vm=mount(AppUpdates(context){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode():Int=throw java.io.IOException("网络暂不可达，请稍后重试")
        }})
        ui.onNodeWithTag("check-updates").performClick()
        ui.waitUntil(5000){vm.error&&!vm.busy}
        ui.onNodeWithTag("update-toast").assertTextEquals(vm.message!!).assertIsDisplayed()
        ui.onNodeWithText("查看更新提示").assertDoesNotExist()
        ui.onNodeWithTag("check-updates").assertHasClickAction()
        val bounds=ui.onNodeWithTag("update-toast").fetchSemanticsNode().boundsInRoot
        val screen=ui.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.bottom<screen.height/3);assertEquals(screen.center.x,bounds.center.x,2f)
        capture("toast")
        val previous=vm.message
        ui.onNodeWithTag("check-updates").performClick();ui.waitUntil(5000){!vm.busy}
        assertEquals(previous,vm.message)
    }
    @Test fun backgroundDownloadFailuresAlsoExplainThemselves(){
        val previous=UpdateDownloads.state.value
        val release=AppRelease(AppVersion("9.0.0-alpha.1",9999),"","https://example.org/update.apk",100,"b".repeat(64),"c".repeat(64))
        try{
            ui.runOnUiThread{
                val vm=SettingsViewModel(context);owners.put("transfer",vm)
                ui.activity.setContent{PlayerTheme{SettingsRoute(vm){}}}
                UpdateDownloads.update(UpdateTransfer(release,error=true,message="下载中断，已保存进度"))
            }
            ui.onNodeWithTag("update-toast").assertTextEquals("下载中断，已保存进度").assertIsDisplayed()
            ui.onNodeWithTag("check-updates").assertDoesNotExist()
        }finally{ui.runOnUiThread{UpdateDownloads.update(previous)}}
    }
    @Test fun availableAndDownloadingUpdateHaveOnePrimaryAction(){
        val finish=AtomicBoolean(false)
        val name="yingxi-9.0.0-alpha.1-9999.apk"
        val json=JSONArray().put(JSONObject().put("tag_name","v9.0.0-alpha.1").put("prerelease",true).put("body","【拾荒者信笺】\n修缮柜台。")
            .put("assets",JSONArray().put(JSONObject().put("name",name).put("state","uploaded").put("size",100).put("digest","sha256:"+"b".repeat(64))
                .put("browser_download_url","https://github.com/${AppUpdates.REPOSITORY}/releases/download/v9.0.0-alpha.1/$name")))).toString().toByteArray()
        val vm=mount(AppUpdates(context){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getContentLengthLong()=if(url.startsWith("https://api.github.com"))json.size.toLong()else 100L
            override fun getInputStream():InputStream=if(url.startsWith("https://api.github.com"))json.inputStream()else object:InputStream(){override fun read():Int{while(!finish.get())Thread.sleep(10);return -1}}
        }})
        try{
            ui.onNodeWithTag("check-updates").performClick();ui.waitUntil(5000){vm.release!=null&&!vm.busy}
            ui.onNodeWithText("稍后").performClick()
            ui.onNodeWithTag("check-updates").assertDoesNotExist();ui.onNodeWithTag("download-update").assertIsDisplayed()
            ui.runOnUiThread{vm.download()};ui.waitUntil(5000){vm.busy&&vm.progress!=null}
            ui.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("update-footer"))).assertCountEquals(1)
            ui.onNodeWithText("暂停下载 0%").assertIsDisplayed();capture("download")
            ui.onNodeWithTag("download-update").performClick();ui.waitUntil(5000){!vm.busy}
        }finally{finish.set(true);ui.runOnUiThread{vm.cancel()};vm.release?.let{AppUpdates.partialFile(context,it).delete()}}
    }
    @Test fun customSubtitleSearchCanBeSavedAtNarrowWidthAndLargeText(){
        val raw=context.getSharedPreferences("media-enrichment-v1",0)
        val oldSource=raw.getString("subtitle-source",null);val oldSearch=raw.getString("custom-subtitle-search",null)
        try{
            raw.edit().putString("subtitle-source","subhd").remove("custom-subtitle-search").commit()
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f)){Column(Modifier.width(320.dp)){EnrichmentSettings()}}}}}
            ui.onNodeWithText("字幕驿站").assertIsDisplayed()
            ui.onNodeWithTag("subtitle-source-selector").performClick();ui.onNodeWithTag("subtitle-source-custom").performClick()
            ui.onNodeWithTag("subtitle-search-url").performTextInput("javascript:{query}")
            ui.onNodeWithTag("save-subtitle-search").performClick()
            assertEquals("",EnrichmentPreferences(context).customSubtitleSearch)
            ui.onNodeWithTag("subtitle-search-url").performTextReplacement("https://example.org/search/{query}")
            ui.onNodeWithTag("save-subtitle-search").performClick()
            assertEquals("custom",EnrichmentPreferences(context).subtitleSource)
            assertEquals("https://example.org/search/{query}",EnrichmentPreferences(context).customSubtitleSearch)
            ui.onNodeWithTag("save-subtitle-search").assertIsNotEnabled()
            capture("custom-large")
        }finally{raw.edit().putString("subtitle-source",oldSource).putString("custom-subtitle-search",oldSearch).commit()}
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun customSourceOnlyPreparesAnEncodedBrowserLink(){
        val raw=context.getSharedPreferences("media-enrichment-v1",0)
        val oldSource=raw.getString("subtitle-source",null);val oldSearch=raw.getString("custom-subtitle-search",null)
        lateinit var player:androidx.media3.exoplayer.ExoPlayer
        try{
            EnrichmentPreferences(context).apply{customSubtitleSearch="https://example.org/?q={query}";subtitleSource="custom"}
            val ref=MediaRef("custom64","映汐 (2026).mkv","Local")
            ui.runOnUiThread{
                player=androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
                val extras=android.os.Bundle().apply{putString("media-ref",ref.json())}
                player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(ref.key).setUri(Uri.fromFile(File(context.cacheDir,"not-opened.mkv")))
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setExtras(extras).build()).build())
                OnlineSubtitles.request(context,player)
            }
            ui.waitUntil(3000){OnlineSubtitles.resources.value?.first==ref.key}
            val resource=OnlineSubtitles.resources.value!!.second.single()
            assertEquals("custom",resource.id);assertTrue(resource.page.contains("%E6%98%A0%E6%B1%90%202026"));assertFalse(resource.automatic)
            ui.runOnIdle{assertEquals(androidx.media3.common.Player.STATE_IDLE,player.playbackState)}
        }finally{ui.runOnUiThread{OnlineSubtitles.cancel();runCatching{player}.getOrNull()?.release()};raw.edit().putString("subtitle-source",oldSource).putString("custom-subtitle-search",oldSearch).commit()}
    }
    @Test fun unknownNetworkStartsImmediatelyAndKnownPortraitWins():Unit=runBlocking {
        val file=File(context.cacheDir,"direction-portrait.mp4");check(file.isFile)
        DavFixture(file,2,2500L).use{server->
            val source=io.localplayer.core.Source(label="方向预算",protocol=io.localplayer.core.Protocol.WEBDAV,host="127.0.0.1",port=server.port,root="/dav",username=server.username,secure=false)
            val path=server.names.first();val uri=PlaybackRegistry.add(source,io.localplayer.core.Credentials(server.password),path,"film")
            val ref=MediaRef("launch64-${java.util.UUID.randomUUID()}","film.mp4",source.label,source.id,path,sizeBytes=file.length(),location=mediaLocation(source))
            val target=PlayTarget(uri,ref.title,0,ref)
            try{
                val begin=android.os.SystemClock.elapsedRealtime()
                assertNull(PlaybackOrientationProbe.initial(context,target))
                val elapsed=android.os.SystemClock.elapsedRealtime()-begin
                println("YINGXI64 network orientation decision: $elapsed ms")
                assertTrue("Network entry must not wait for a metadata connection: $elapsed ms",elapsed<300)
                PlaybackOrientationProbe.remember(context,ref,ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,PlaybackOrientationProbe.initial(context,target))
            }finally{target.release()}
        }
    }
}
