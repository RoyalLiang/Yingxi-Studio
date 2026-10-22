package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourceManagementTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private lateinit var vm: LibraryViewModel
    private val saved = mutableListOf<String>()
    private val requestedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val connector = object : Connector {
        override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation) = object : RemoteSession {
            override fun list(path: String): List<Entry> {
                requestedPaths += path
                return if (path.isEmpty()) listOf(Entry("影片", "films", true, 0), Entry("movie.mp4", "movie.mp4", false, 100)) else emptyList()
            }
            override fun open(path: String): RemoteFile = throw AssertionError("Folder selection must not play media")
            override fun close() {}
        }
    }
    private fun mount(protocol: Protocol = Protocol.ALIYUN, initialPath: String = "", fontScale: Float = 1f): Source {
        val source = Source(label = "阿里云盘 · 这是一个很长的私人家庭影音资料账户名称", protocol = protocol,
            host = "openapi.alipan.com", username = "fixture-account", domain = "fixture-drive", initialPath = initialPath)
        val app = ui.activity.application
        SourceStore(app).save(source, "local synthetic test credential", true); saved += source.id
        ui.runOnUiThread {
            vm = LibraryViewModel(app, connector)
            val files = LocalFilesViewModel(app)
            models.put("library", vm); models.put("files", files)
            ui.activity.setContent { PlayerTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { AppShell(vm, files, {}, {}, {}) }
            } }
        }
        ui.waitForIdle()
        return source
    }
    @After fun cleanup() {
        ui.runOnUiThread { models.clear() }
        saved.forEach { SourceStore(ui.activity.application).delete(it); SourceOrganization(ui.activity.application).removeSource(it) }
    }
    @Test fun homeContainsNoSavedSourceOrNetworkSourceEntry() {
        val source = mount()
        ui.onNodeWithTag("source-title:${source.id}",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithText("已保存的避难所").assertDoesNotExist()
        ui.onNodeWithText("网络避难所").assertDoesNotExist()
        ui.onNodeWithText("避难所").assertIsDisplayed()
    }
    @Test fun favoriteAndUnfavoriteHaveDistinctFeedback() {
        val source = mount()
        ui.runOnIdle { vm.putBookmark(SourceBookmark(source.id, "", source.label, favorite = true)) }
        ui.onNodeWithText("已收藏").assertIsDisplayed()
        ui.runOnIdle { assertTrue(vm.organization.bookmarks().first { it.sourceId == source.id }.favorite) }
        ui.runOnIdle { vm.putBookmark(SourceBookmark(source.id, "", source.label, favorite = false)) }
        ui.onNodeWithText("已取消路标标记").assertIsDisplayed()
        ui.runOnIdle { assertFalse(vm.organization.bookmarks().first { it.sourceId == source.id }.favorite) }
    }

    @Test fun repeatedFavoriteCallbacksReadTheStoredState() {
        val source = mount()
        val stale = SourceBookmark(source.id, "", source.label)
        ui.runOnIdle { vm.toggleFavorite(stale); vm.toggleFavorite(stale) }
        ui.runOnIdle { assertFalse(vm.organization.bookmarks().first { it.key == stale.key }.favorite) }
        ui.onNodeWithText("已取消路标标记").assertIsDisplayed()
    }

    @Test fun longCloudNameAndSettingsRemainReachableWithLargeText() {
        val source = mount(fontScale = 1.5f)
        ui.onNodeWithText("避难所").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("source-title:${source.id}", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Source name must wrap instead of being clipped", layouts.single().hasVisualOverflow)
        ui.onNodeWithContentDescription("修改配置 ${source.label}").assertDoesNotExist()
        ui.onNodeWithContentDescription("${source.stationKind}操作 ${source.label}").performClick()
        ui.onNodeWithText("${source.stationKind}设置").performClick()
        ui.onNodeWithTag("source-settings").assertIsDisplayed()
        ui.onNodeWithText("来源根目录").assertIsDisplayed()
        ui.runOnIdle { assertNull(vm.draft); assertNull(vm.openingSource); assertNull(vm.source) }
        capture("settings-large")
    }

    @Test fun selectedFolderPersistsWithoutChangingCredentialsOrSourceIdentity() {
        val source = mount(Protocol.SFTP)
        val store = SourceStore(ui.activity)
        val secretBefore = ui.activity.getSharedPreferences("secrets-v1", 0).getString(source.id, null)
        ui.runOnIdle { vm.putBookmark(SourceBookmark(source.id, "", source.label, favorite = true)) }
        ui.onNodeWithText("避难所").performClick()
        ui.onNodeWithContentDescription("${source.stationKind}操作 ${source.label}").performClick()
        ui.onNodeWithText("${source.stationKind}设置").performClick()
        ui.onNodeWithText("浏览并选择文件夹").performClick()
        ui.waitUntil(10_000) { vm.source != null && !vm.busy }
        ui.onNodeWithText("movie.mp4").assertDoesNotExist()
        ui.onNodeWithText("影片").performClick()
        ui.waitUntil(10_000) { vm.path == "films" && !vm.busy }
        ui.onNodeWithText("使用此文件夹").performClick()
        val updated = store.load().first { it.id == source.id }
        assertEquals("films", updated.initialPath)
        assertEquals("影片", store.startingFolderName(updated))
        assertEquals(mediaLocation(source), mediaLocation(updated))
        assertEquals(secretBefore, ui.activity.getSharedPreferences("secrets-v1", 0).getString(source.id, null))
        assertEquals("local synthetic test credential", store.password(source.id))
        assertTrue(vm.organization.bookmarks().first { it.sourceId == source.id && it.path == "films" }.favorite)
        ui.onNodeWithText("进入 影片").assertIsDisplayed()
        ui.runOnIdle { vm.open(updated) }
        ui.waitUntil(10_000) { vm.source != null && !vm.busy }
        assertEquals("films", requestedPaths.last())
    }

    @Test fun cancellingFolderSelectionPreservesExistingDefault() {
        val source = mount(Protocol.SFTP, initialPath = "previous")
        ui.onNodeWithText("避难所").performClick()
        ui.runOnIdle { vm.beginRootSelection(source) }
        ui.waitUntil(10_000) { vm.source != null && !vm.busy }
        ui.onNodeWithText("取消").performClick()
        assertEquals("previous", SourceStore(ui.activity).load().first { it.id == source.id }.initialPath)
        ui.runOnIdle { assertNull(vm.rootSelectionId); assertNull(vm.source) }
    }

    @Test fun cloudPresentationEditKeepsCredentialAndRejectsTraversal() {
        val source = mount()
        val store = SourceStore(ui.activity)
        val before = ui.activity.getSharedPreferences("secrets-v1", 0).getString(source.id, null)
        ui.runOnIdle { assertTrue(vm.updateSourcePresentation(source, "家庭片库", "opaque-folder-id", "电影")) }
        val updated = store.load().first { it.id == source.id }
        assertEquals("电影", store.startingFolderName(updated))
        assertEquals("家庭片库", updated.label)
        assertEquals(mediaLocation(source), mediaLocation(updated))
        assertEquals(before, ui.activity.getSharedPreferences("secrets-v1", 0).getString(source.id, null))
        ui.runOnIdle { assertFalse(vm.updateSourcePresentation(updated, "bad", "../escape", "bad")) }
        assertEquals(updated, store.load().first { it.id == source.id })
    }

    @Test fun successToastIsCompactAndAboveThePage() {
        mount()
        ui.runOnIdle { vm.notifySuccess("已取消路标标记") }
        ui.mainClock.advanceTimeBy(500)
        val toast = ui.onNodeWithTag("source-toast").getUnclippedBoundsInRoot()
        assertTrue("Toast should sit within the top safe area", toast.top.value < 100f)
        assertTrue("Success should be a compact capsule", (toast.right-toast.left).value < 240f)
        assertTrue("Success height: $toast", (toast.bottom-toast.top).value in 47.5f..64.5f)
        capture("toast")
    }

    @Test fun swipeOnlyRevealsActionsAndTapCollapsesTheRow() {
        val source = mount()
        ui.onNodeWithText("避难所").performClick()
        ui.onNodeWithTag("source-row:${source.id}").performTouchInput { swipeLeft() }
        ui.onNodeWithText("删除").assertIsDisplayed()
        ui.runOnIdle { assertNull(vm.source); assertNull(vm.openingSource); assertNull(vm.draft) }
        capture("swipe-left")
        ui.onNodeWithText(source.label).performClick()
        ui.onNodeWithText("删除").assertDoesNotExist()
        ui.runOnIdle { assertNull(vm.source); assertNull(vm.openingSource) }
        ui.onNodeWithTag("source-row:${source.id}").performTouchInput { swipeRight() }
        ui.onNodeWithText("私密访问").assertIsDisplayed()
        capture("swipe-right")
    }

    @Test fun cloudCredentialRenewalKeepsCustomNameAndDefaultFolder() {
        val source = mount()
        val store = SourceStore(ui.activity)
        ui.runOnIdle { vm.updateSourcePresentation(source, "我的电影", "films", "电影") }
        // A token refresh / reauthorization may still carry the original profile.
        store.save(source, "replacement synthetic credential", true, preservePresentation = true)
        val updated = store.load().first { it.id == source.id }
        assertEquals("我的电影", updated.label)
        assertEquals("films", updated.initialPath)
        assertEquals("电影", store.startingFolderName(updated))
        ui.runOnIdle { vm.cloudConnected(source) }
        ui.waitUntil(10_000) { vm.source != null && !vm.busy }
        assertEquals("films", requestedPaths.last())
        assertEquals("replacement synthetic credential", store.password(source.id))
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/management-$name.png").close()
    }
}
