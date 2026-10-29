package io.localplayer.app
import android.content.ComponentName
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceAuthorityPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(action:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(action)};return result!!.getOrThrow()}
    @Test fun changingRootStopsBufferedPlaybackAndRejectsTheFinalOldCheckpoint(){
        val app=ui.activity.application;val source=Source(label="撤销回归",protocol=Protocol.SFTP,host="fixture.invalid",root="/",initialPath="movies");SourceStore(app).save(source,"",false)
        val h=HistoryStore(app);val ref=h.reference(MediaRef("${source.id}:movies/one.mp4","one.mp4",source.label,source.id,"movies/one.mp4",sizeBytes=100,location=mediaLocation(source),bindingEpoch=0))
        assertTrue(h.update(ref,h.token(ref),42000,130000,false))
        val sample=File(app.cacheDir,"beta-control.mp4");assertTrue(sample.isFile)
        val target=PlayTarget(Uri.fromFile(sample),ref.title,42000,ref,h.token(ref))
        val controller=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}.get(10,TimeUnit.SECONDS)
        try{
            main{controller.setMediaItems(PlaybackRequests.items(app,target),0,42000);controller.prepare();controller.play()}
            ui.waitUntil(10000){main{controller.playbackState==Player.STATE_READY&&controller.isPlaying&&controller.currentPosition>42300}}
            SourceStore(app).updatePresentation(source.id,source.label,"series","剧集")
            val atRevocation=h.entries().single{it.media.mediaId==ref.mediaId}.positionMs
            ui.waitUntil(5000){main{controller.mediaItemCount==0&&!controller.isPlaying}}
            assertTrue(atRevocation>=42000L);assertEquals(atRevocation,h.entries().single{it.media.mediaId==ref.mediaId}.positionMs)
        }finally{main{controller.stop();controller.clearMediaItems();controller.release()};target.release();h.remove(ref);SourceStore(app).delete(source.id)}
    }
}
