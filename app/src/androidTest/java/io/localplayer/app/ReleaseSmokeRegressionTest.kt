package io.localplayer.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReleaseSmokeRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val context get() = ui.activity.applicationContext
    private val databaseName = "release-smoke-" + System.nanoTime() + ".db"
    private var posters = true

    @Before fun prepare() {
        posters = EnrichmentPreferences(context).posters
        EnrichmentPreferences(context).posters = false
    }

    @After fun restore() {
        EnrichmentPreferences(context).posters = posters
        context.deleteDatabase(databaseName)
    }

    private fun grant(uri: Uri) {
        context.startActivity(Intent().setClassName(instrumentation.context.packageName, GrantFixtureActivity::class.java.name)
            .putExtra("tree", uri.toString()).putExtra("target", context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            check(SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10)
        }
        instrumentation.waitForIdleSync()
    }

    private fun withChangingProvider(action: (MediaRef, Uri) -> Unit) {
        val tree = DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY, "root")
        grant(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "movies")
        val child = DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, "clip")
        val folder = MediaRef("sealed-" + databaseName, "目录", "本机", localUri = parent.toString())
        val film = MediaRef("clip-" + databaseName, "风过旧桥.mp4", "本机", localUri = child.toString())
        val unlocked = SealedMedia.unlocked
        context.contentResolver.call(tree, "fixture:set-clip-in-movies", "false", null)
        try {
            SealedMedia.unlock(); SealedMedia.seal(SealedItem(folder, true, tree.toString())); SealedMedia.lock()
            action(film, tree)
        } finally {
            context.contentResolver.call(tree, "fixture:set-clip-in-movies", "true", null)
            SealedMedia.unlock(); SealedMedia.unseal(folder.key)
            if (!unlocked) SealedMedia.lock()
        }
    }

    private fun catalog(media: MediaRef): Pair<ViewModelStore, String> {
        val id = FilmLibraryStore(context, databaseName).use { store ->
            store.addMoment(media, 12000)
            store.works().first { work -> work.films.any { it.media.key == media.key } }.id
        }
        val models = ViewModelStore()
        ui.runOnUiThread {
            val vm = LibraryViewModel(ui.activity.application as Application)
            models.put("library", vm)
            ui.activity.setContent { PlayerTheme { FilmLibraryScreen(vm, databaseName) {} } }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("film-work:" + id).fetchSemanticsNodes().isNotEmpty() }
        return models to id
    }

    @Test fun catalogRechecksProviderRelationshipAfterVisibilityWasCached() = withChangingProvider { media, tree ->
        FilmLibraryStore(context, databaseName).use { store ->
            store.addMoment(media, 12000)
            assertTrue(store.works().any { work -> work.films.any { it.media.key == media.key } })
            context.contentResolver.call(tree, "fixture:set-clip-in-movies", "true", null)
            assertFalse("A provider move must hide the newly protected film", store.works().any { work -> work.films.any { it.media.key == media.key } })
        }
    }

    @Test fun searchingRechecksProviderRelationship() = withChangingProvider { media, tree ->
        val (models, id) = catalog(media)
        try {
            context.contentResolver.call(tree, "fixture:set-clip-in-movies", "true", null)
            ui.onNodeWithTag("film-library-search").performTextInput("风过")
            ui.waitUntil(4000) { ui.onAllNodesWithTag("film-work:" + id).fetchSemanticsNodes().isEmpty() }
        } finally { ui.runOnUiThread { models.clear() } }
    }

    @Test fun leavingCatalogDoesNotWaitForBackgroundDatabaseLock() {
        val (models, _) = catalog(MediaRef("gate-" + databaseName, "旧桥.mp4", "本机", localUri = "content://library-fixture/gate"))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit { synchronized(MediaDataMigration.session(context).gate) { held.countDown(); release.await(3, TimeUnit.SECONDS) } }
            assertTrue(held.await(2, TimeUnit.SECONDS))
            val started = SystemClock.elapsedRealtime()
            ui.runOnUiThread { ui.activity.setContent { Text("已离开片匣") } }
            ui.onNodeWithText("已离开片匣").assertIsDisplayed()
            assertTrue("Leaving the catalog blocked the main thread", SystemClock.elapsedRealtime() - started < 1500)
            release.countDown(); pending.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow(); ui.runOnUiThread { models.clear() } }
    }

    @Test fun removingAnotherFilmRetriesAnInterruptedThumbnail() {
        val uri = DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, "thumbnail-revoke")
        grant(uri)
        val media = MediaRef("thumbnail-" + databaseName, "片段.mp4", "本机", localUri = uri.toString(), bindingEpoch = LocalBindingEpochs.current(context, uri))
        context.contentResolver.call(uri, "thumbnail-reset", null, null)
        try {
            ui.runOnUiThread { ui.activity.setContent { PlayerTheme { MediaThumbnail(media, Modifier.size(120.dp)) } } }
            ui.waitUntil(5000) { context.contentResolver.call(uri, "thumbnail-state", null, null)?.getBoolean("entered") == true }
            MediaThumbnails.remove(context, MediaRef("unrelated-" + databaseName, "另一部.mp4", "本机"))
            context.contentResolver.call(uri, "thumbnail-release", null, null)
            ui.waitUntil(6000) { ui.onAllNodesWithTag("media-thumbnail:" + media.key).fetchSemanticsNodes().isNotEmpty() }
        } finally { context.contentResolver.call(uri, "thumbnail-release", null, null); MediaThumbnails.remove(context, media) }
    }
}
