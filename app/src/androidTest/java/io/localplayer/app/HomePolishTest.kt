package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

class HomePolishTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private lateinit var vm: LibraryViewModel
    private val savedMedia = mutableListOf<android.net.Uri>()
    private val savedGroups = mutableListOf<String>()
    private val bookmarkSourceId="home-polish-${java.util.UUID.randomUUID()}"
    private fun seedHistory() {
        val app=ui.activity.application
        val bytes=java.io.File(app.cacheDir,"seek-performance.mp4").readBytes()
        val resolver=app.contentResolver
        for(name in listOf("旷野来信", "城市微光", "深蓝航线", "远山来客", "山海之间 · 这是一段很长的家庭影音影片名称")) {
            val uri=resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"home-${System.nanoTime()}.mp4")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Movies/YingxiHomeTest")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
            })!!
            savedMedia+=uri
            resolver.openOutputStream(uri)!!.use{it.write(bytes)}
            resolver.update(uri,android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            val media=MediaRef(uri.toString(),name,"本机文件",localUri=uri.toString(),sizeBytes=bytes.size.toLong())
            HistoryStore(app).let{it.update(media,it.token(media.key),5000,20000,false)}
        }
    }
    private fun mount(groups: Boolean = false, fontScale: Float = 1.8f, width: Int = 320) {
        ui.runOnUiThread {
            val app = ui.activity.application
            vm = LibraryViewModel(app); models.put("library", vm)
            val files = LocalFilesViewModel(app); models.put("files", files)
            ui.activity.setContent { PlayerTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width.dp).fillMaxHeight()) {
                        if (groups) BookmarkOrganizer(vm, SourceBookmark(bookmarkSourceId, "", "很长的家庭电影文件夹名称")) {}
                        else AppShell(vm, files, {}, {}, {})
                    }
                }
            } }
        }
        ui.waitForIdle()
    }
    @After fun cleanup() {
        ui.runOnUiThread { models.clear() }
        val app=ui.activity.application
        savedMedia.forEach{uri->
            val media=HistoryStore(app).entries().firstOrNull{it.media.key==uri.toString()}?.media
            HistoryStore(app).remove(uri.toString())
            media?.let{MediaThumbnails.remove(app,it)}
            app.contentResolver.delete(uri,null,null)
        }
        savedGroups.forEach{SourceOrganization(app).deleteGroup(it)}
        SourceOrganization(app).removeSource(bookmarkSourceId)
    }
    @Test fun homeHasOneOpenActionWithoutRepeatedLaunchSection() {
        mount()
        ui.onNodeWithText("从这里打开").assertDoesNotExist()
        ui.onNodeWithContentDescription("打开视频").assertIsDisplayed()
        ui.onNodeWithContentDescription("设置").assertIsDisplayed()
    }
    @Test fun groupChoiceExposesSelectedStateInsteadOfAStringPrefix() {
        mount(groups = true)
        ui.onNodeWithText("未分组").assertIsSelected()
        ui.onNodeWithText("✓ 未分组").assertDoesNotExist()
    }
    @Test fun galleryHasOneContinueItemAndRecentTilesWithoutSourceOrLaunchRows() {
        seedHistory();mount(fontScale=1f,width=411)
        ui.onNodeWithTag("continue-player").assertIsDisplayed()
        ui.onAllNodesWithContentDescription("打开视频").assertCountEquals(1)
        ui.onNodeWithText("从这里打开").assertDoesNotExist()
        ui.onNodeWithText("网络避难所").assertDoesNotExist()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("最近播放"))
        ui.onNodeWithText("最近播放").assertIsDisplayed()
        capture("gallery")
        ui.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-film:${savedMedia[0]}"))
        ui.onNodeWithContentDescription("记录操作 旷野来信").performClick()
        ui.onNodeWithText("从头播放").assertIsDisplayed()
        ui.runOnIdle{assertNull(vm.preparingKey)}
        capture("gallery-menu")
    }

    @Test fun localHeaderRemainsOneLineAtNarrowWidthAndLargeFont() {
        mount()
        ui.onNodeWithContentDescription("打开视频").performClick()
        val layouts=mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("local-browser-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals(1,layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        ui.onNodeWithContentDescription("返回").assertIsDisplayed()
        ui.onNodeWithContentDescription("添加授权文件夹").assertIsDisplayed()
        capture("local-large")
    }

    @Test fun selectingAnotherGroupMovesTheSemanticAndVisibleCheck() {
        val app=ui.activity.application
        val organization=SourceOrganization(app)
        val name="家庭影集-${System.nanoTime()}"
        organization.group(name);savedGroups+=organization.groups().first{it.name==name}.id
        mount(groups=true)
        ui.onNodeWithText(name).performScrollTo().performClick().assertIsSelected()
        ui.onNodeWithText("未分组").assertIsNotSelected()
        ui.onNodeWithText("保存").performClick()
        ui.runOnIdle{assertEquals(savedGroups.single(),vm.organization.bookmarks().first{it.sourceId==bookmarkSourceId}.groupId)}
    }

    @Test fun wholeFileRowShowsSelectionWithoutChangingItsGeometry() {
        ui.runOnUiThread { ui.activity.setContent { PlayerTheme {
            var selected by remember{mutableStateOf(false)}
            Box(Modifier.width(320.dp)) {
                MediaRow("很长的家庭电影名称与文件扩展名.mp4","20 MB",Icons.Rounded.Folder,selected=selected){selected=!selected}
            }
        } } }
        val row=ui.onNodeWithText("很长的家庭电影名称与文件扩展名.mp4")
        val before=row.getUnclippedBoundsInRoot()
        row.assertIsNotSelected().performClick().assertIsSelected()
        assertEquals(before,row.getUnclippedBoundsInRoot())
        ui.onNodeWithContentDescription("已选择文件").assertExists()
    }

    @Test fun inAppVersionNotesUseTheCurrentChangeOnlyCopy() {
        mount(fontScale=1f,width=411)
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithTag("app-version").performClick()
        capture("updates")
        ui.onNodeWithText("本版说明").performClick()
        ui.onNodeWithTag("release-letter-body").assertIsDisplayed()
        ui.onNode(hasTestTag("letter-paragraphs") and hasAnyAncestor(hasTestTag("release-letter-body")),useUnmergedTree=true).assertExists()
        capture("release-letter")
        assertTrue(BuildConfig.VERSION_CHANGES.contains("拾荒者信笺"))
        assertFalse(BuildConfig.VERSION_CHANGES.contains("验证与限制"))
    }

    @Test fun largerTextUsesOneGalleryColumn() {
        seedHistory();mount(fontScale=1.8f,width=320)
        val recent=vm.history.drop(1)
        ui.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-film:${recent[0].media.key}"))
        val one=ui.onNodeWithTag("home-film:${recent[0].media.key}").getUnclippedBoundsInRoot()
        val two=ui.onNodeWithTag("home-film:${recent[1].media.key}").getUnclippedBoundsInRoot()
        assertEquals(one.left.value,two.left.value,.5f)
        capture("gallery-large")
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/home-polish-$name.png").use{
            java.io.FileInputStream(it.fileDescriptor).use{stream->stream.readBytes()}
        }
    }

}
