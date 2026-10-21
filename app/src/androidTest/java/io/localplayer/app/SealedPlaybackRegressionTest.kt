package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SealedPlaybackRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}

    @Test fun lockingCabinetStopsProtectedSessionAndClearsItsQueue(){
        val context=ui.activity.applicationContext
        val sample=File(context.cacheDir,"hevc-control.mp4");assertTrue(sample.isFile)
        val uri=Uri.fromFile(sample)
        val ref=MediaRef("sealed-playback-${System.nanoTime()}","封存播放回归","本机文件",localUri=uri.toString())
        val target=PlayTarget(uri,ref.title,0,ref)
        var controller:MediaController?=null;var activity:PlaybackActivity?=null
        try{
            main{SealedMedia.unlock();SealedMedia.seal(SealedItem(ref,false));ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
            ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val c=future.get(10,TimeUnit.SECONDS);controller=c
            ui.waitUntil(10000){main{c.playerError!=null||c.playbackState==Player.STATE_READY&&c.currentPosition>200}}
            main{assertNull(c.playerError);assertEquals("封存影像",c.mediaMetadata.title.toString());assertEquals(ref.title,playbackItemTitle(c.currentMediaItem,""));assertTrue(activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0);OnlineSubtitles.request(context,c)}
            ui.waitUntil(5000){OnlineSubtitles.status.value?.second=="封存档案仅使用内嵌或手动导入字幕"}
            main{SealedMedia.lock()}
            ui.waitUntil(5000){main{c.mediaItemCount==0&&!c.isPlaying}}
            main{assertNull(c.currentMediaItem);assertFalse(SealedMedia.unlocked);assertTrue(activity!!.isFinishing||activity!!.isDestroyed)}
        }finally{
            main{activity?.finish();controller?.release();SealedMedia.unlock();SealedMedia.unseal(ref.key);SealedMedia.lock()}
            PlaybackRequests.remove(target.playId);HistoryStore(context).remove(ref.key)
        }
    }
}
