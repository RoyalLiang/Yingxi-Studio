package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.localplayer.core.Protocol
import io.localplayer.core.Source

class StationExperienceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun audioPreferenceIsSeparateFromRememberedManualLanguage(){
        val name="audio-test-${UUID.randomUUID()}"
        try {
            val prefs=PlaybackPreferences(context,name)
            prefs.audioLanguage="zh";prefs.rememberedAudioLanguage="en"
            assertEquals("en",PlaybackPreferences(context,name).effectiveAudioLanguage)
            prefs.rememberManual=false
            assertEquals("zh",PlaybackPreferences(context,name).effectiveAudioLanguage)
            assertNull(PlaybackPreferences(context,name).rememberedAudioLanguage)
            prefs.rememberManual=true
            prefs.audioLanguage="ja"
            assertEquals("ja",PlaybackPreferences(context,name).effectiveAudioLanguage)
            assertNull(prefs.rememberedAudioLanguage)
        } finally {context.deleteSharedPreferences(name)}
    }
    @Test fun greetingIsOncePerDayAndSoundRequiresOptIn() {
        val name="station-test-${UUID.randomUUID()}"
        val raw=context.getSharedPreferences(name,0)
        try {
            raw.edit().putString("first-day",LocalDate.now().minusDays(2).toString()).commit()
            val prefs=StationPreferences(context,name)
            assertEquals(3L,prefs.day);assertFalse(prefs.gearSound)
            assertTrue(prefs.claimGreeting());assertFalse(StationPreferences(context,name).claimGreeting())
            prefs.greetings=false
            raw.edit().remove("greeted").commit()
            assertFalse(prefs.claimGreeting())
            prefs.gearSound=true;assertTrue(StationPreferences(context,name).gearSound)
        }finally{context.deleteSharedPreferences(name)}
    }
    @Test fun inventoryPreservesFirstSeenAndRemovesDeletedSources() {
        val name="inventory-test-${UUID.randomUUID()}"
        try {
            val inventory=StationInventory(context,name)
            val ref=MediaRef("source:film","Film.mkv","Fixture",sourceId="source",path="Film.mkv",sizeBytes=100,location="identity")
            assertEquals(1,inventory.observe(listOf(ref),1000))
            assertEquals(0,inventory.observe(listOf(ref.copy(title="Film 2020.mkv")),2000))
            assertEquals(1000L,inventory.entries().single().firstSeen)
            assertEquals("Film 2020.mkv",inventory.entries().single().media.title)
            inventory.removeSource("source");assertTrue(inventory.entries().isEmpty())
        }finally{context.deleteSharedPreferences(name)}
    }
    @Test fun diaryAndOptInSettingsAreReachable() {
        ui.onNodeWithTag("station-diary").assertIsDisplayed()
        ui.onNodeWithTag("open-settings").performClick()
        ui.onNodeWithText("站务").assertIsDisplayed()
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("station-gear-sound"))
        ui.onNodeWithTag("station-gear-sound").assertIsOff()
        ui.onNodeWithTag("station-gear-sound").performClick()
        ui.onNodeWithTag("station-gear-sound").assertIsOn()
        ui.onNodeWithTag("station-gear-sound").performClick()
    }
    @Test fun bufferingMessageIsCenteredAndDisappearsWhenReady() {
        val buffering=mutableStateOf(true)
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{StationBuffering(buffering.value,false)}}}
        ui.onNodeWithTag("station-decoding").assertIsDisplayed()
        ui.onNodeWithText("正在缓冲，稍候继续放映").assertDoesNotExist()
        ui.onNodeWithTag("station-buffering-bubble").assertDoesNotExist()
        ui.runOnIdle{buffering.value=false}
        ui.onNodeWithTag("station-decoding").assertDoesNotExist()
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yingxi-world24-$name.png").use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}
    }

    @Test fun actualScreensAndSeriesResumeUseTheObservedFileIdentity() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val enrichment=EnrichmentPreferences(context);val oldPosters=enrichment.posters;enrichment.posters=false
        val tree=DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"series")
        context.startActivity(Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",tree.toString()).putExtra("target",context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.waitUntil(10000){context.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lateinit var files:LocalFilesViewModel
        lateinit var player:LibraryViewModel
        ui.runOnIdle {
            files=ViewModelProvider(ui.activity)[LocalFilesViewModel::class.java]
            player=ViewModelProvider(ui.activity)[LibraryViewModel::class.java]
            val uri=DocumentsContract.buildDocumentUriUsingTree(tree,"episode6").toString()
            val ref=MediaRef(uri,"漫长的季节.S01E06.mkv","漫长的季节",localUri=uri)
            HistoryStore(context).update(ref,HistoryStore(context).token(ref.key),1398000,2700000,false)
            player.refreshHistory()
        }
        try {
            capture("home")
            ui.onNodeWithTag("open-settings").performClick();capture("settings")
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("station-gear-sound"));capture("atmosphere")
            ui.runOnIdle{ui.activity.onBackPressedDispatcher.onBackPressed()}
            ui.runOnIdle{player.draft=Draft(Source(label="西区仓库",protocol=Protocol.WEBDAV,host="media.example.com",root="/dav/"))}
            ui.onNodeWithTag("connection-title").assertTextEquals("登记信源").assertIsDisplayed()
            ui.onNodeWithTag("source-category-tower").assertIsSelected();ui.onNodeWithText("网盘连接").assertDoesNotExist();capture("source")
            ui.runOnIdle{player.dismissDraft()}
            ui.onNodeWithContentDescription("打开视频").performClick()
            ui.runOnIdle{files.add(tree)}
            ui.waitUntil(10000){files.active!=null&&!files.busy}
            ui.onNodeWithTag("series-resume").assertIsDisplayed()
            ui.onNodeWithText("继续第 6 集 · 23:18",substring=true).assertIsDisplayed()
            capture("archive")
            ui.runOnIdle{files.root()}
            ui.onNodeWithText("我的片架").assertExists()
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNodeWithText("今日拾获").assertExists()
            capture("home")
        }finally{
            ui.runOnIdle { files.folders.firstOrNull{it.tree==tree}?.let{files.remove(it)};files.root();player.clearHistory() }
            enrichment.posters=oldPosters
        }
    }

    @Test fun characterBubbleIsCompactAndDoesNotCrossTheCenteredStatus() {
        ui.runOnUiThread{ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        ui.waitUntil(10000){context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
        ui.activityRule.scenario.recreate()
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{Box(Modifier.fillMaxSize().background(Color(0xFF101113))){StationBuffering(true,true)}}}}
        ui.onNodeWithTag("station-decoding").assertIsDisplayed()
        android.util.Log.d("WorldUi24","bounds="+ui.onRoot().fetchSemanticsNode().boundsInRoot+" density="+context.resources.displayMetrics.density+" font="+context.resources.configuration.fontScale+" greeting="+StationPreferences(context).greetings)
        capture("buffering-initial")
        ui.waitUntil(8000){ui.onAllNodesWithTag("station-buffering-bubble").fetchSemanticsNodes().isNotEmpty()}
        val status=ui.onNodeWithTag("station-decoding").fetchSemanticsNode().boundsInRoot
        val bubble=ui.onNodeWithTag("station-buffering-bubble").fetchSemanticsNode().boundsInRoot
        assertTrue(bubble.left>status.right)
        assertTrue(bubble.width>bubble.height*2)
        capture("buffering")
    }
}
