package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class EntryAndGroups53RegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun headerButtonsShareTitleCenterAtNarrowLargeText(){
        val owner=ViewModelStore()
        try{
            ui.runOnIdle{
                val vm=LibraryViewModel(ui.activity.application);owner.put("header",vm)
                ui.activity.setContent{PlayerTheme{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f)){Box(Modifier.width(320.dp)){SourcesCatalog(vm)}}}}
            }
            val title=ui.onNodeWithTag("source-catalog-title").getUnclippedBoundsInRoot()
            listOf("manage-source-groups","add-source").forEach{tag->
                val button=ui.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue(abs((title.top.value+title.bottom.value-button.top.value-button.bottom.value)/2)<1f)
                assertTrue(button.right-button.left>=47.5.dp&&button.bottom-button.top>=47.5.dp)
            }
        }finally{ui.runOnIdle{owner.clear()}}
    }
    @Test fun glassGroupCreateRenameCancelAndDeleteAtLargeText(){
        val owner=ViewModelStore();lateinit var vm:LibraryViewModel
        val old=SourceOrganization(ui.activity).groups().map{it.id}.toSet()
        var opening=0
        fun show(scale:Float=1.8f){val session=++opening;ui.runOnIdle{ui.activity.setContent{PlayerTheme{
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,scale)){
                var open by remember(session){mutableStateOf(true)}
                val groups=remember(vm.organizationRevision){vm.organization.groups()}
                if(open)SourceGroupDialog(vm,groups){open=false}
            }
        }}}}
        fun shot(name:String){ui.waitForIdle();File(ui.activity.cacheDir,name).outputStream().use{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        try{
            ui.runOnIdle{vm=LibraryViewModel(ui.activity.application);owner.put("groups",vm)}
            show();ui.onNodeWithTag("source-group-dialog").assertIsDisplayed()
            if(ui.onAllNodesWithTag("new-source-group").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("new-source-group").performScrollTo().performClick()
            ui.onNodeWithTag("save-source-group").assertIsNotEnabled()
            ui.onNodeWithTag("source-group-name").performTextInput("检修片架53")
            ui.onNodeWithTag("save-source-group").assertIsEnabled().performScrollTo().performClick()
            ui.onNodeWithTag("source-group-dialog").assertDoesNotExist()
            val id=vm.organization.groups().first{it.name=="检修片架53"}.id
            show();ui.onNodeWithTag("edit-source-group:$id").performScrollTo().performClick()
            ui.onNodeWithTag("source-group-name").performTextReplacement("未保存53")
            ui.onNodeWithText("返回").performClick()
            assertEquals("检修片架53",vm.organization.groups().first{it.id==id}.name)
            shot("entry53-glass-groups-large.png")
            ui.onNodeWithTag("edit-source-group:$id").performScrollTo().performClick()
            ui.onNodeWithTag("source-group-name").performTextReplacement("夜班片架53")
            ui.onNodeWithTag("save-source-group").performScrollTo().performClick()
            assertEquals("夜班片架53",vm.organization.groups().first{it.id==id}.name)
            show(1f);shot("entry53-glass-groups-normal.png")
            ui.onNodeWithTag("edit-source-group:$id").performScrollTo().performClick()
            ui.onNodeWithText("删除分册").performScrollTo().performClick()
            assertFalse(vm.organization.groups().any{it.id==id})
        }finally{ui.runOnIdle{vm.organization.groups().filter{it.id !in old}.forEach{vm.deleteGroup(it.id)};owner.clear()}}
    }
    @Test fun largeTextEditorStaysScrollableWhileRealKeyboardIsVisible(){
        val owner=ViewModelStore();lateinit var vm:LibraryViewModel
        val old=SourceOrganization(ui.activity).groups().map{it.id}.toSet()
        try{
            ui.runOnIdle{vm=LibraryViewModel(ui.activity.application);owner.put("ime-groups",vm)
                ui.activity.setContent{PlayerTheme{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,1.8f)){SourceGroupDialog(vm,emptyList()) {}}}}}
            ui.onNodeWithTag("source-group-name").performScrollTo().performClick()
            ui.waitUntil(5000){var shown=false;ui.runOnUiThread{shown=android.view.inspector.WindowInspector.getGlobalWindowViews().any{it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}};shown}
            ui.onNodeWithTag("source-group-name").performTextInput("窄屏键盘53")
            ui.onNodeWithTag("save-source-group").performScrollTo().assertIsDisplayed()
            File(ui.activity.cacheDir,"entry53-glass-narrow-ime.png").outputStream().use{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ui.onNodeWithTag("save-source-group").performClick()
            assertTrue(vm.organization.groups().any{it.name=="窄屏键盘53"})
        }finally{ui.runOnIdle{vm.organization.groups().filter{it.id !in old}.forEach{vm.deleteGroup(it.id)};owner.clear()}}
    }
    @Test fun sourcePageShowsGlassOverItsRecordedBackdropInBothAppearances(){
        val app=ui.activity.application;val owner=ViewModelStore();val appearance=AppearancePreferences(app);val previous=appearance.mode
        val source=Source(label="盐环旧片架53",protocol=Protocol.SMB,host="fixture.invalid",root="media")
        SourceStore(app).save(source,"",false)
        try{
            ui.runOnIdle{
                val vm=LibraryViewModel(app);val local=LocalFilesViewModel(app);owner.put("scene",vm);owner.put("local",local)
                ui.activity.setContent{PlayerTheme{AppShell(vm,local,{},{},{})}}
            }
            ui.onNodeWithTag("nav:Sources").performClick()
            ui.onNodeWithTag("manage-source-groups").performClick()
            for(mode in listOf(LibraryAppearance.DAY,LibraryAppearance.NIGHT)){
                ui.runOnIdle{appearance.mode=mode};ui.waitForIdle()
                ui.onNodeWithTag("source-group-dialog").assertIsDisplayed()
                File(ui.activity.cacheDir,"entry53-glass-scene-${mode.name}.png").outputStream().use{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
            ui.onNodeWithContentDescription("关闭分册整理").performClick()
            ui.onNodeWithTag("source-group-dialog").assertDoesNotExist()
            ui.onNodeWithTag("add-source").assertIsDisplayed()
        }finally{ui.runOnIdle{appearance.mode=previous;owner.clear();SourceStore(app).delete(source.id)}}
    }
    @Test fun galleryMovieFolderClickEmitsOriginalTargetButSeriesAndFileBrowseStayOpen(){
        val app=ui.activity.application
        val source=Source(label="电影目录53",protocol=Protocol.SFTP,host="fixture.invalid",root="/")
        val movie=Entry("旧城.2024.mkv","旧城 (2024)/旧城.2024.mkv",false,200)
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String)=when(path){
                ""->listOf(Entry("旧城 (2024)","旧城 (2024)",true,0),Entry("旧城 Season 1","旧城 Season 1",true,0))
                "旧城 (2024)"->listOf(movie,Entry("字幕.srt","旧城 (2024)/字幕.srt",false,10))
                else->listOf(Entry("旧城.S01E01.mkv","旧城 Season 1/旧城.S01E01.mkv",false,200))
            }
            override fun open(path:String):RemoteFile=error("Target selection must not read movie bytes")
            override fun close(){}
        }}
        val owner=ViewModelStore();lateinit var vm:LibraryViewModel;var target:PlayTarget?=null
        SourceStore(app).save(source,"",false)
        try{
            ui.runOnIdle{vm=LibraryViewModel(app,connector);owner.put("movie",vm);ui.activity.setContent{PlayerTheme{LibraryScreen(vm,{},{})}};vm.open(source)}
            ui.waitUntil(5000){vm.source?.id==source.id&&!vm.busy}
            ui.onNodeWithTag("shelter-entry:旧城 (2024)").performClick()
            target=runBlocking{withTimeout(5000){vm.plays.first()}}
            assertEquals(movie.path,target.media.path);assertEquals(source.id,target.media.sourceId)
            assertEquals(listOf(movie.path),target.queue.map{it.media.path})
            target.release();target=null
            ui.runOnIdle{vm.browse("")};ui.waitUntil(5000){vm.path==""&&!vm.busy}
            ui.onNodeWithTag("shelter-entry:旧城 Season 1").performClick()
            ui.waitUntil(5000){vm.path=="旧城 Season 1"&&!vm.busy}
            assertNull(runBlocking{withTimeoutOrNull(250){vm.plays.first()}})
            ui.runOnIdle{vm.browse("旧城 (2024)")};ui.waitUntil(5000){vm.path=="旧城 (2024)"&&!vm.busy}
            assertNull(runBlocking{withTimeoutOrNull(250){vm.plays.first()}})
        }finally{target?.release();ui.runOnIdle{owner.clear();SourceStore(app).delete(source.id)}}
    }
}
