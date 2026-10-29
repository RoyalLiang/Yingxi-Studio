package io.localplayer.app
import android.content.ComponentName
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LegacyUpgradePlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(action:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(action)};return result!!.getOrThrow()}
    @Test fun grantedLocalFileActuallyResumesAtTheMigratedCheckpoint(){
        val app=ui.activity.application;val receipt=JSONObject(File(app.noBackupFilesDir,"upgrade-receipt.json").readText());val uri=receipt.getString("localUri")
        val entry=HistoryStore(app).entries().single{it.media.localUri==uri};val id=entry.media.mediaId;assertEquals(42000L,entry.positionMs)
        val owner=ViewModelStore();val vm=main{LibraryViewModel(app).also{owner.put("upgrade",it)}}
        main{vm.resume(entry)}
        val target=runBlocking{withTimeout(8000){vm.plays.first()}};assertEquals(42000L,target.resume);assertEquals(id,target.media.mediaId)
        val controller=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}.get(10,TimeUnit.SECONDS)
        try{
            main{controller.setMediaItems(PlaybackRequests.items(app,target),0,target.resume);controller.prepare();controller.play()}
            ui.waitUntil(15000){main{controller.playbackState==Player.STATE_READY&&controller.isPlaying&&controller.currentPosition>42300}}
            main{assertNull(controller.playerError);assertTrue(controller.currentPosition<55000);assertEquals(1,controller.mediaItemCount)}
            assertEquals(id,HistoryStore(app).entries().single{it.media.localUri==uri}.media.mediaId)
        }finally{main{controller.stop();controller.clearMediaItems();controller.release();owner.clear()};target.release()}
    }
}
