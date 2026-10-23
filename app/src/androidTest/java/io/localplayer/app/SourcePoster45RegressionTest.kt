package io.localplayer.app

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
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SourcePoster45RegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun browserKeepsSourceHeadingHidesSidecarsAndMovesFileFactsIntoPoster() {
        val app=ui.activity.application
        val source=Source(id="poster45-${UUID.randomUUID()}",label="晚间信源",protocol=Protocol.WEBDAV,host="poster45.invalid")
        val folder=Entry("已确认剧集","show",true,0)
        val video=Entry("Example.S01E01.mkv","show/01.mkv",false,2L*1024*1024*1024)
        val sidecars=listOf(Entry("poster.jpg","show/poster.jpg",false,2048),Entry("episode.nfo","show/episode.nfo",false,40),Entry("02.mkv.part","show/02.mkv.part",false,1000))
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession {
                override fun list(path:String)=if(path.isEmpty())listOf(folder)else listOf(video)+sidecars
                override fun open(path:String):RemoteFile=error("No media read is required for metadata layout")
                override fun close(){}
            }
        }
        val owner=ViewModelStore();val prefs=EnrichmentPreferences(app);val previous=prefs.posters
        val station=StationPreferences(app);val discoveryBefore=station.cabinetDiscoverySeen;station.cabinetDiscoverySeen=true
        SourceStore(app).save(source,"",true)
        try {
            prefs.posters=false
            lateinit var vm:LibraryViewModel
            ui.runOnUiThread {
                vm=LibraryViewModel(app,connector);owner.put("library",vm)
                val files=LocalFilesViewModel(app);owner.put("files",files)
                ui.activity.setContent{PlayerTheme{AppShell(vm,files,{},{},{})}}
            }
            ui.onNodeWithTag("nav:Sources").performClick()
            ui.onNodeWithTag("open-sealed-cabinet").assertDoesNotExist()
            ui.runOnIdle{vm.open(source)}
            ui.waitUntil(5000){vm.source!=null&&!vm.busy}
            ui.onNodeWithTag("source-browser-title",true).assertTextEquals(source.label)
            ui.onNodeWithText("文件夹").assertDoesNotExist()
            ui.onNodeWithTag("shelter-entry:show").performClick()
            ui.waitUntil(5000){vm.path=="show"&&!vm.busy}
            ui.onNodeWithTag("source-browser-title",true).assertTextEquals(source.label)
            assertEquals(4,vm.entries.size) // Sidecars remain available to subtitle and metadata lookup.
            ui.onNodeWithText("1 个项目").assertExists()
            sidecars.forEach{ui.onNodeWithText(it.name).assertDoesNotExist()}
            ui.onNodeWithTag("shelter-info:${video.path}",true).assertDoesNotExist()
            val label="poster-labels:${source.id}:${video.path}"
            ui.onNodeWithTag(label,true).assertTextEquals("MKV")
            ui.onNode(hasText(source.label) and hasAnyAncestor(hasTestTag(label)),useUnmergedTree=true).assertDoesNotExist()
            ui.onNodeWithText("2.0 GB").assertDoesNotExist()
            capture("source-directory")
            ui.runOnIdle{vm.closeSource()}
            ui.onNodeWithTag("nav:History").performClick()
            ui.onNodeWithTag("open-sealed-cabinet").assertDoesNotExist()
            ui.onNodeWithTag("history-title").assertTextEquals("拾荒路线图")
            capture("history-single-list")
            ui.onNodeWithTag("nav:Home").performClick()
            capture("home-hidden-cabinet-entry")
            ui.onNodeWithTag("home-sealed-entry").assertHasClickAction().performClick()
            ui.runOnIdle{
                assertFalse(SealedMedia.unlocked)
                assertTrue(vm.privateRequest!=null||vm.error?.contains("锁屏密码")==true)
            }
            ui.onNodeWithTag("sealed-cabinet").assertDoesNotExist()
            ui.runOnIdle{vm.privateRequest?.let{vm.cancelPrivateAccess(it.id)}}
            ui.onNodeWithTag("home-sealed-entry").assertIsDisplayed()
        } finally {
            ui.runOnUiThread{owner.clear()};SourceStore(app).delete(source.id);SourceOrganization(app).removeSource(source.id);prefs.posters=previous;station.cabinetDiscoverySeen=discoveryBefore
        }
    }

    @Test fun collectionManagerUsesOneDialogAndOneEditorAtNarrowLargeText() {
        val app=ui.activity.application;val organization=SourceOrganization(app);val name="分册-${UUID.randomUUID().toString().take(8)}"
        organization.group(name);val group=organization.groups().first{it.name==name}
        val owner=ViewModelStore()
        try {
            ui.runOnUiThread {
                val vm=LibraryViewModel(app);owner.put("library",vm)
                ui.activity.setContent{PlayerTheme{val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)){
                        Box(Modifier.width(320.dp).fillMaxHeight()){SourcesCatalog(vm)}
                    }
                }}
            }
            ui.onNodeWithTag("manage-source-groups").performClick()
            ui.onAllNodes(isDialog()).assertCountEquals(1)
            ui.onNodeWithTag("source-group-dialog").assertExists()
            ui.onNodeWithTag("edit-source-group:${group.id}").performClick()
            ui.onAllNodes(isDialog()).assertCountEquals(1)
            ui.onNodeWithTag("edit-source-group:${group.id}").assertDoesNotExist()
            ui.onNodeWithText("分册名称").assertExists()
            ui.onNodeWithText("修改分册").assertExists()
            capture("group-editor-large")
            ui.onNodeWithText("返回").performClick()
            ui.onNodeWithTag("new-source-group").performScrollTo().performClick()
            ui.onNodeWithText("分册名称").assertExists()
            ui.onAllNodes(isDialog()).assertCountEquals(1)
            ui.onNodeWithText("返回").performClick()
            ui.onNodeWithText("取消").performClick()
            assertEquals(name,organization.groups().first{it.id==group.id}.name)
        } finally {ui.runOnUiThread{owner.clear()};organization.deleteGroup(group.id)}
    }

    @Test fun compactPosterFactsStayInsideTheImageAtLargeText() {
        val media=MediaRef("compact45","很长的剧集片名.mp4","很长的信源名称",sizeBytes=1024)
        val entry=HistoryEntry(media,1000,10000,0,false)
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)){
                Box(Modifier.width(320.dp)){ContinueCard(entry,false){}}
            }
        }}}
        val image=ui.onNodeWithTag("media-poster:${media.key}",true).getUnclippedBoundsInRoot()
        val labels=ui.onNodeWithTag("poster-labels:${media.key}",true).getUnclippedBoundsInRoot()
        assertTrue(labels.left>=image.left&&labels.right<=image.right&&labels.top>=image.top&&labels.bottom<=image.bottom)
        capture("compact-large")
    }

    @Test fun posterFormatUsesActualExtensionWithoutInventingIncompleteFileFormats() {
        assertEquals("MKV",posterVideoFormat(MediaRef("a","剧集.mkv","信源")))
        assertEquals("MP4",posterVideoFormat(MediaRef("b","片名","信源",path="/film.mp4")))
        assertNull(posterVideoFormat(MediaRef("c","剧集.mkv.part","信源",path="/film.mkv.part")))
        assertNull(posterVideoFormat(MediaRef("d","未知格式","信源")))
    }

    private fun capture(name:String) {
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.cacheDir,"poster45-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
