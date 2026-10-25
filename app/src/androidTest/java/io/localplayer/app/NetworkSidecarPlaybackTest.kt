package io.localplayer.app

import android.content.ComponentName
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkSidecarPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun authenticatedSftpP5LoadsSeparateTracksAndReleasesTheirLeases() {
        val context=ui.activity.applicationContext
        val prefs=EnrichmentPreferences(context);val online=prefs.subtitles;prefs.subtitles=false
        val unknown=Source(label="SFTP sidecar fixture55",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
        val fingerprint=try{SftpConnector().connect(unknown,Credentials(TestFixtures.password)).close();error("Expected key confirmation")}catch(e:HostKeyRequired){e.fingerprint}
        val source=unknown.copy(trustedKey=fingerprint,trustedAuthority=unknown.authority)
        val uri=PlaybackRegistry.add(source,Credentials(TestFixtures.password),"dv5-profile5.mp4","dv5-profile5.mp4")
        val ref=MediaRef("network-sidecar55","dv5-profile5.mp4",source.label,sourceId=source.id,path="dv5-profile5.mp4")
        val target=PlayTarget(uri,ref.title,0,ref)
        var controller:MediaController?=null;var activity:PlaybackActivity?=null
        var audioUri:android.net.Uri?=null
        try {
            main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
            ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val player=future.get(10,TimeUnit.SECONDS);controller=player
            ui.waitUntil(30000){main{player.playerError!=null||(player.isPlaying&&SidecarAudio.from(player.currentMediaItem!!).size==1&&player.currentTracks.groups.count{it.type==C.TRACK_TYPE_AUDIO}>=2)}}
            main{assertNull(player.playerError);assertTrue(player.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("sdr-compatibility"));audioUri=SidecarAudio.from(player.currentMediaItem!!).single().uri}
            ui.waitUntil(10000){main{MpvRuntime.load(context).get("sub-text")?.contains("SFTP attached")==true}}
            val dub=main{player.currentTracks.groups.last{it.type==C.TRACK_TYPE_AUDIO}}
            main{player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(dub.mediaTrackGroup,0)).build()}
            ui.waitUntil(5000){main{MpvRuntime.load(context).get("aid")==dub.getTrackFormat(0).id}}
            main{player.seekTo(6000);player.setPlaybackSpeed(1.5f);player.play()}
            ui.waitUntil(10000){main{player.currentPosition>6500&&player.isPlaying}}
            main{player.pause();assertNull(player.playerError);assertEquals(1.5f,player.playbackParameters.speed,.01f);player.stop();player.clearMediaItems()}
            ui.waitUntil(5000){runCatching{PlaybackRegistry.get(audioUri!!)}.isFailure}
        } finally {
            main{controller?.let{it.stop();it.clearMediaItems();it.release()};activity?.finish()}
            PlaybackRequests.remove(target.playId);PlaybackRegistry.remove(uri);HistoryStore(context).remove(ref.key);prefs.subtitles=online
        }
    }
}
