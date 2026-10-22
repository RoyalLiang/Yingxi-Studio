package io.localplayer.app

import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcePresentationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val scope = ViewModelStore()
    private lateinit var vm: LibraryViewModel
    private fun mount(fontScale: Float = 1f) {
        ui.runOnUiThread {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
            vm = LibraryViewModel(app)
            val files = LocalFilesViewModel(app)
            scope.put("library", vm); scope.put("files", files)
            ui.activity.setContent {
                PlayerTheme {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)) {
                        AppShell(vm, files, {}, {}, {})
                    }
                }
            }
        }
        ui.waitForIdle()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        android.os.SystemClock.sleep(700)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { java.io.File(context.getExternalFilesDir(null), "source-ui-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    @After fun cleanup() { ui.runOnUiThread { scope.clear() } }

    @Test fun protocolSwitchKeepsTheSameDialogWindow() {
        mount()
        ui.runOnIdle { vm.add() }
        ui.waitForIdle()
        val windows = WindowInspector.getGlobalWindowViews().toSet()
        val top = ui.onNodeWithText("登记信源").getUnclippedBoundsInRoot().top
        listOf("SFTP", "WebDAV", "SMB", "WebDAV", "SFTP").forEach { protocol ->
            ui.onNodeWithText(protocol).performClick()
            ui.waitForIdle()
            assertEquals("Protocol switching must retain the sheet window", windows, WindowInspector.getGlobalWindowViews().toSet())
            assertEquals(top.value, ui.onNodeWithText("登记信源").getUnclippedBoundsInRoot().top.value, 2f)
        }
    }

    @Test fun manualWebDavReturnsToPickerWithoutReplacingWindow() {
        mount()
        ui.runOnIdle { vm.add() }
        ui.onNodeWithText("WebDAV").performClick()
        ui.waitForIdle()
        val windows = WindowInspector.getGlobalWindowViews().toSet()
        capture("cloud")
        ui.onNodeWithTag("manual-webdav").performClick()
        ui.onNodeWithText("WebDAV 地址").assertIsDisplayed()
        capture("webdav")
        assertEquals(windows, WindowInspector.getGlobalWindowViews().toSet())
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("cloud-provider-picker").assertIsDisplayed() }.isSuccess }
        assertEquals(windows, WindowInspector.getGlobalWindowViews().toSet())
    }

    @Test fun protocolContentCrossfadesInsideStableShell() {
        mount()
        ui.runOnIdle { vm.add() }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        ui.onNodeWithText("SFTP").performClick()
        ui.mainClock.advanceTimeBy(100)
        ui.onNode(hasText("服务器地址") and hasSetTextAction()).assertExists()
        ui.onNode(hasText("主机 IP") and hasSetTextAction()).assertExists()
        ui.onAllNodesWithText("登记信源").assertCountEquals(1)
        ui.mainClock.advanceTimeBy(300)
        ui.onNode(hasText("服务器地址") and hasSetTextAction()).assertDoesNotExist()
        ui.onNode(hasText("主机 IP") and hasSetTextAction()).assertIsDisplayed()
        ui.mainClock.autoAdvance = true
    }

    @Test fun addingAndScanningAreOnlyAvailableInsideSources() {
        mount()
        ui.onNodeWithContentDescription("添加避难所").assertDoesNotExist()
        ui.onNodeWithText("添加第一个避难所").assertDoesNotExist()
        ui.onNodeWithText("附近服务").assertDoesNotExist()
        ui.runOnIdle { assertFalse(vm.scanning) }
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithTag("auto-discovery").assertDoesNotExist()
        ui.onNodeWithText("附近设备 · 待验证").assertDoesNotExist()
        capture("sources")
        ui.onNodeWithContentDescription("添加避难所").performClick()
        capture("smb")
        ui.onNodeWithTag("auto-scan-entry").performClick()
        ui.onNodeWithTag("source-discovery").assertIsDisplayed()
        capture("discovery")
        ui.onNodeWithText("返回手动输入").performClick()
        ui.onNodeWithTag("source-discovery").assertDoesNotExist()
        ui.runOnIdle { assertFalse(vm.scanning); assertFalse(vm.discoveryPanelOpen) }
    }

    @Test fun repeatedErrorsRestartTimeoutWithoutMovingPage() {
        mount()
        ui.onNodeWithTag("nav:Sources").performClick()
        val page = ui.onNodeWithTag("source-list").getUnclippedBoundsInRoot()
        ui.mainClock.autoAdvance = false
        ui.runOnIdle { vm.error = "无法连接服务器，请检查地址或网络后重试" }
        ui.mainClock.advanceTimeBy(300)
        ui.onNodeWithTag("source-toast").assertIsDisplayed()
        assertEquals(page, ui.onNodeWithTag("source-list").getUnclippedBoundsInRoot())
        ui.mainClock.advanceTimeBy(3_000)
        var first = 0L
        ui.runOnIdle { first = vm.notice!!.id; vm.error = vm.error }
        ui.mainClock.advanceTimeBy(3_500)
        ui.runOnIdle { assertNotEquals(first, vm.notice?.id); assertNotNull(vm.error) }
        ui.onNodeWithTag("source-toast").assertIsDisplayed()
        ui.mainClock.advanceTimeBy(3_000)
        ui.runOnIdle { assertNull(vm.notice); assertNull(vm.error) }
        ui.onNodeWithTag("source-toast").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
    }

    @Test fun longErrorCanBeReadInDetailAndDismissed() {
        mount()
        val message = "服务器拒绝了访问。".repeat(20) + "请检查用户名、密码和目录权限。"
        ui.runOnIdle { vm.add(); vm.error = message }
        ui.onNodeWithTag("source-toast").assertIsDisplayed()
        capture("error-toast")
        ui.onNodeWithText("详情").performClick()
        ui.onNodeWithText("操作未完成").assertIsDisplayed()
        ui.onAllNodesWithText(message).onLast().assertIsDisplayed()
        ui.onNodeWithText("知道了").performClick()
        ui.onNodeWithContentDescription("关闭提示").performClick()
        ui.runOnIdle { assertNull(vm.notice); assertNull(vm.error); assertNotNull(vm.draft) }
    }

    @Test fun successfulOperationReplacesErrorAndExpires() {
        mount()
        val name = "避难所回归-${System.nanoTime()}"
        ui.mainClock.autoAdvance = false
        try {
            ui.runOnIdle { vm.error = "上次连接失败"; vm.saveGroup(name) }
            ui.mainClock.advanceTimeBy(300)
            ui.onNodeWithTag("source-toast").assertIsDisplayed()
            ui.onNodeWithText("已保存").assertIsDisplayed()
            ui.runOnIdle { assertNull(vm.error); assertFalse(vm.notice!!.isError) }
            ui.mainClock.advanceTimeBy(3_500)
            ui.runOnIdle { assertNull(vm.notice) }
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnIdle { vm.organization.groups().firstOrNull { it.name == name }?.let { vm.organization.deleteGroup(it.id) } }
        }
    }

    @Test fun largeTextKeepsProtocolAndPrimaryControlsReachable() {
        mount(fontScale = 1.5f)
        ui.onNodeWithTag("nav:Sources").performClick()
        ui.onNodeWithContentDescription("添加避难所").assertIsDisplayed().performClick()
        ui.onNodeWithText("SFTP").assertIsDisplayed().performClick()
        ui.onNode(hasText("主机 IP") and hasSetTextAction()).performTextInput("192.0.2.10")
        ui.onNodeWithText("SMB").performClick()
        ui.onNodeWithText("登记信源").assertIsDisplayed()
        ui.onNodeWithTag("connection-form").performScrollToNode(hasText("连接并浏览"))
        ui.onNodeWithText("连接并浏览").assertIsDisplayed()
        capture("large-text")
        ui.runOnIdle { assertEquals("192.0.2.10", vm.draft?.source?.host) }
    }
}
