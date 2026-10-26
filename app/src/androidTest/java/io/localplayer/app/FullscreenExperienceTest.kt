package io.localplayer.app

import android.content.ComponentName
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FullscreenExperienceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/ux-$name.png").use { java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
    }
    private fun source(protocol: Protocol): Source {
        val s = Source(label = "Fullscreen fixture", protocol = protocol, host = "10.0.2.2", port = if (protocol == Protocol.SMB) TestFixtures.smbPort else TestFixtures.sftpPort, root = if (protocol == Protocol.SMB) "media" else "/", username = TestFixtures.username)
        if (protocol == Protocol.SMB) return s
        val fingerprint = try { SftpConnector().connect(s, Credentials(TestFixtures.password)).close(); error("Expected host verification") } catch (e: HostKeyRequired) { e.fingerprint }
        return s.copy(trustedKey = fingerprint, trustedAuthority = s.authority)
    }
    @Test fun smbShareSelectionBookmarksAndCredentialsSurviveReopen() {
        val vm = main { LibraryViewModel(context.applicationContext as android.app.Application) }
        val owner = ViewModelStore().apply { main { put("ux", vm) } }
        val s = source(Protocol.SMB)
        var group: SourceGroup? = null
        try {
            main { vm.draft = Draft(s.copy(host = "smb://${s.host}:${s.port}", root = ""), password = TestFixtures.password); vm.connectDraft() }
            await("SMB share enumeration: ${vm.error}") { main { !vm.busy && (vm.shareChoices != null || vm.error != null) } }
            main { assertNull(vm.error); assertTrue(vm.shareChoices!!.any { it.equals("media",true) }); vm.chooseShare(vm.shareChoices!!.first { it.equals("media",true) }) }
            await("Selected share unreadable") { main { !vm.busy && (vm.source != null || vm.error != null) } }
            main { assertNull(vm.error); assertTrue(vm.source!!.root.equals("media",true)) }
            assertEquals(TestFixtures.password, SourceStore(context).password(s.id))
            main { vm.saveGroup("UX-${System.nanoTime()}") }
            group = vm.organization.groups().last()
            val bookmark = SourceBookmark(s.id, "QueueTest", "测试播放列表", group.id, true)
            main { vm.putBookmark(bookmark); vm.closeSource(); vm.openLocation(bookmark) }
            await("Bookmark did not open") { main { !vm.busy && (vm.source != null || vm.error != null) } }
            main { assertNull(vm.error); assertEquals("QueueTest", vm.path); assertEquals("", SourceStore(context).load().first { it.id == s.id }.initialPath) }
            assertEquals(bookmark, SourceOrganization(context).bookmarks().first { it.key == bookmark.key })
            main { vm.deleteGroup(group.id) }
            val ungrouped = SourceOrganization(context).bookmarks().first { it.key == bookmark.key }
            assertNull(ungrouped.groupId); assertTrue(ungrouped.favorite)
            assertEquals(TestFixtures.password, SourceStore(context).password(s.id))
        } finally {
            main { group?.let { vm.deleteGroup(it.id) }; vm.organization.removeSource(s.id); SourceStore(context).delete(s.id); owner.clear() }
        }
    }
    @Test fun invalidSmbInputIsAnInlineError() {
        val vm = main { LibraryViewModel(context.applicationContext as android.app.Application) }
        val owner = ViewModelStore().apply { main { put("invalid-input", vm) } }
        try { main { vm.draft = Draft(source(Protocol.SMB).copy(host = "smb://nas/Movies/invalid%")); vm.connectDraft(); assertNotNull(vm.error); assertFalse(vm.busy) } }
        finally { main { owner.clear() } }
    }
    @Test fun smbFullscreenControlsGesturesAndRealFrames() = playback(Protocol.SMB)
    @Test fun sftpFullscreenControlsGesturesAndRealFrames() = playback(Protocol.SFTP)
    private fun playback(protocol: Protocol) {
        val vm = main { LibraryViewModel(context.applicationContext as android.app.Application) }
        val owner = ViewModelStore().apply { main { put("fullscreen", vm) } }
        var scenario: ActivityScenario<PlaybackActivity>? = null
        var controller: MediaController? = null
        var target: PlayTarget? = null
        val s = source(protocol)
        val audio=context.getSystemService(android.media.AudioManager::class.java)
        val originalVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        try {
            main { vm.draft = Draft(s, password = TestFixtures.password, remember = false); vm.connectDraft() }
            await("$protocol source") { main { !vm.busy && (vm.source != null || vm.error != null) } }
            main { assertNull(vm.error); vm.browse("QueueTest") }
            await("$protocol directory") { main { !vm.busy } }
            target = runBlocking {
                val next = async(Dispatchers.Default) { withTimeout(10000) { vm.plays.first() } }
                main { vm.play(vm.entries.first { it.name == "01-first.mp4" }) }; next.await()
            }
            scenario = ActivityScenario.launch(PlaybackActivity.intent(context, target))
            val future = main { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val c = future.get(10, TimeUnit.SECONDS); controller = c
            await("$protocol first frame") { ui.waitForIdle(); main { c.isPlaying && c.videoSize.width > 0 } }
            scenario.onActivity { assertEquals(Configuration.ORIENTATION_LANDSCAPE, it.resources.configuration.orientation) }
            main { c.pause() }
            println("UX_METRICS $protocol ${PlaybackMetrics.snapshot()}")
            ui.onNodeWithTag("video-definition").assertIsDisplayed()
        ui.onNodeWithTag("video-definition").assertHasClickAction()
        capture("${protocol.name.lowercase()}-controls")
            // Paused controls remain visible, so this test also catches the old non-clickable label.
            ui.waitForIdle()
            for (rate in listOf(.75f, 1f, 1.25f, 1.5f, 2f, 4f)) {
                ui.onNodeWithTag("playback-speed").performClick()
            if(rate==.75f)capture("${protocol.name.lowercase()}-speeds")
                ui.onNodeWithTag("speed-option-$rate", useUnmergedTree = true).performClick()
                await("Speed $rate did not apply") { main { kotlin.math.abs(c.playbackParameters.speed-rate)<.001f } }
            }
            main { c.setPlaybackSpeed(1f) }
            ui.onNodeWithText("选集 1/2").performClick()
            ui.onNodeWithTag("playback-queue-drawer").assertIsDisplayed()
            capture("${protocol.name.lowercase()}-queue")
        val bounds = ui.onNodeWithTag("playback-queue-drawer").fetchSemanticsNode().boundsInRoot
            assertTrue("Drawer should be to the right", bounds.left > 0)
            ui.onNodeWithText("02-second.mp4").performClick()
            await("Episode selection") { main { c.currentMediaItemIndex == 1 && c.playbackState == Player.STATE_READY } }
            main { c.pause(); c.seekTo(3000) }
            Thread.sleep(500)
            var lightBefore = 0f
            scenario.onActivity { lightBefore = it.window.attributes.screenBrightness }
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput { swipe(Offset(width * .2f, height * .65f), Offset(width * .2f, height * .35f), 500) }
            scenario.onActivity { assertTrue("Brightness gesture", it.window.attributes.screenBrightness >= 0 && (lightBefore < 0 || it.window.attributes.screenBrightness > lightBefore)) }
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput { down(Offset(width * .3f, height * .34f)); moveTo(Offset(width * .35f, height * .34f),700) }
            ui.waitUntil(20000){ui.onAllNodesWithContentDescription("进度预览").fetchSemanticsNodes().isNotEmpty()}
            capture("${protocol.name.lowercase()}-seek")
        // A second seek bucket must not blank the already decoded image.
        ui.onNodeWithTag("playback-gesture-surface").performTouchInput { moveBy(Offset(width*.18f,0f),100) }
        ui.mainClock.advanceTimeBy(32);ui.waitForIdle()
        assertTrue("Retain the last real preview while the latest position loads",ui.onAllNodesWithContentDescription("进度预览").fetchSemanticsNodes().isNotEmpty())

            ui.onNodeWithTag("playback-gesture-surface").performTouchInput { up() }
            await("Horizontal gesture did not seek") { main { c.currentPosition > 7000 } }
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,2,0)
            ui.onNodeWithTag("playback-gesture-surface").performTouchInput { swipe(Offset(width*.8f,height*.65f),Offset(width*.8f,height*.3f),500) }
            assertTrue("Volume gesture",audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)>2)
            val loader = PreviewFrames(context)
            try {
                val uri = target.queue[1].uri
                val bitmap = runBlocking { withTimeout(20000) { loader.get(uri, 10000) } }
                assertNotNull("$protocol must yield an actual video thumbnail", bitmap)
                assertTrue(bitmap!!.width <= 320 && bitmap.height <= 180)
                runBlocking {
                    val cancelled = launch { loader.get(uri, 20000) }
                    delay(5); cancelled.cancelAndJoin()
                    assertNotNull(withTimeout(20000) { loader.get(uri, 5000) })
                }
            } finally { loader.close() }
            ui.onNodeWithContentDescription("锁定方向与控件").performClick()
            ui.onNodeWithContentDescription("解锁方向与控件").assertIsDisplayed().performClick()
            // Portrait remains operable after an explicit rotation.
            ui.onNodeWithContentDescription("旋转屏幕").performClick()
            await("Explicit portrait rotation") { var portrait = false; scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }; portrait }
            ui.onNodeWithContentDescription("旋转屏幕").assertIsDisplayed()
        } finally {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,originalVolume,0)
            scenario?.close(); main { controller?.release(); owner.clear(); vm.closeSource(); SourceStore(context).delete(s.id) }
            target?.let { PlaybackRequests.remove(it.playId) }
        }
    }
}
