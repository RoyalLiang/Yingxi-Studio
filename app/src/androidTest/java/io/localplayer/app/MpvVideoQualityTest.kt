package io.localplayer.app

import android.net.Uri
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MpvVideoQualityTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun softwareBackendListsAndSwitchesActualVideoTracks(){
        val file=File(ui.activity.cacheDir,"mpv-multi-cover.mp4").takeIf{it.isFile}?:File(ui.activity.cacheDir,"two-video-tracks.mp4");org.junit.Assume.assumeTrue(file.isFile)
        val player=main{MpvPlayer(ui.activity,Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(640).setHeight(360).build())}
        try{
            main{
                player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(file)),MediaItem.fromUri(Uri.fromFile(file))))
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("zh").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
                player.setPlaybackSpeed(1.25f)
                ui.activity.setContent{PlayerTheme{
                    var tracks by remember{mutableStateOf(player.currentTracks)};var parameters by remember{mutableStateOf(player.trackSelectionParameters)}
                    DisposableEffect(player){val listener=object:Player.Listener{override fun onEvents(p:Player,e:Player.Events){tracks=p.currentTracks;parameters=p.trackSelectionParameters}};player.addListener(listener);onDispose{player.removeListener(listener)}}
                    Column{
                        AndroidView(factory={SurfaceView(it).also(player::setVideoSurfaceView)},modifier=Modifier.height(180.dp).fillMaxWidth())
                        PlaybackQualityChoices(player,player.currentMediaItem,tracks,parameters)
                    }
                }}
                player.prepare();player.play()
            }
            ui.waitUntil(30000){main{player.playerError!=null||player.playbackState==Player.STATE_READY&&player.videoSize.width>0}}
            ui.waitUntil(10000){main{player.currentTracks.groups.count{it.type==C.TRACK_TYPE_VIDEO}==2}}
            val choice=main{
                assertNull(player.playerError);player.pause();player.seekTo(3000)
                File(ui.activity.getExternalFilesDir(null),"mpv-quality-track-list.json").writeText(MpvRuntime.load(ui.activity).get("track-list")?:"[]")
                val groups=player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_VIDEO}
                assertEquals("Software playback must expose both real video tracks",2,groups.size)
                val low=groups.indexOfFirst{it.getTrackFormat(0).width==320};assertTrue(low>=0)
                val native=org.json.JSONArray(MpvRuntime.load(ui.activity).get("track-list"))
                val lowRate=(0 until native.length()).map{native.getJSONObject(it)}.first{it.optInt("demux-w")==320}.getInt("demux-bitrate")
                assertEquals(lowRate,groups[low].getTrackFormat(0).averageBitrate)
                "video-quality-$low-0"
            }
            ui.onNodeWithTag(choice).performClick()
            ui.waitUntil(15000){main{player.playerError!=null||player.videoSize.width==320}}
            main{
                assertNull(player.playerError);assertEquals(320,player.videoSize.width)
                assertEquals(2,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex)
                assertTrue(player.currentPosition>=2900);assertFalse(player.playWhenReady);assertEquals(1.25f,player.playbackParameters.speed)
                assertEquals(listOf("zh"),player.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            }
            val high=main{player.currentTracks.groups.filter{it.type==C.TRACK_TYPE_VIDEO}.indexOfFirst{it.getTrackFormat(0).width==640}}
            ui.onNodeWithTag("video-quality-$high-0").performClick()
            ui.waitUntil(15000){main{player.videoSize.width==640}}
            ui.onNodeWithTag("video-quality-auto").performClick()
            main{assertTrue(player.trackSelectionParameters.overrides.keys.none{it.type==C.TRACK_TYPE_VIDEO});assertFalse(player.playWhenReady);assertNull(player.playerError)}
        }finally{main{player.release()}}
    }
    @Test fun actualPlayerSessionMapsQualityChoicesToTheCompatibilityVideoTrack(){
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"dual-p5.mp4");org.junit.Assume.assumeTrue("Two verified P5 tracks required",file.isFile)
        val uri=Uri.fromFile(file);val ref=MediaRef("dual-p5-${java.util.UUID.randomUUID()}","双视频轨 P5", "Local",localUri=uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,ref.title,500,ref)
        var activity:PlaybackActivity?=null
        main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
        ui.waitUntil(10000){activity=main{androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
        val future=main{androidx.media3.session.MediaController.Builder(context,androidx.media3.session.SessionToken(context,android.content.ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val controller=future.get(10,java.util.concurrent.TimeUnit.SECONDS)
        try{
            ui.waitUntil(30000){main{controller.playerError!=null||controller.playbackState==Player.STATE_READY&&controller.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")==true&&controller.currentTracks.groups.count{it.type==C.TRACK_TYPE_VIDEO}==2}}
            main{
                if(controller.playerError!=null){
                    val runtime=MpvRuntime.load(context)
                    val data=org.json.JSONObject()
                    for(key in listOf("vid","track-list","time-pos","pause","vo-configured","current-vo","video-out-params","video-frame-info","idle-active","eof-reached"))data.put(key,runCatching{runtime.get(key)}.getOrNull())
                    File(context.getExternalFilesDir(null),"dual-p5-failure.json").writeText(data.toString())
                }
                assertNull(controller.playerError?.stackTraceToString(),controller.playerError);assertTrue(controller.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("sdr-compatibility"))
                controller.pause();controller.seekTo(3000);controller.setPlaybackSpeed(1.25f)
                controller.trackSelectionParameters=controller.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("zh").setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
            }
            ui.onNodeWithTag("video-definition").performClick()
            ui.onNodeWithTag("video-quality-1-0").performScrollTo().performClick()
            ui.waitUntil(10000){main{MpvRuntime.load(context).get("vid")=="2"}}
            main{
                assertNull(controller.playerError);assertEquals(0,controller.currentMediaItemIndex)
                assertTrue(controller.currentPosition>=2900);assertFalse(controller.playWhenReady)
                assertEquals(1.25f,controller.playbackParameters.speed)
                assertEquals(listOf("zh"),controller.trackSelectionParameters.preferredAudioLanguages)
                assertTrue(controller.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            }
            ui.onNodeWithTag("video-quality-auto").performScrollTo().performClick()
            ui.waitUntil(10000){main{MpvRuntime.load(context).get("vid")=="1"}}
        }finally{main{controller.stop();controller.clearMediaItems();controller.release();activity?.finish()};PlaybackRequests.remove(target.playId)}
    }

    @Test fun qualityChoicesExcludeVideoDecodersNotEnabledInTheCompatibilityBackend(){
        val player=main{MpvPlayer(ui.activity,Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())}
        try{
            val tracks=main{
                val read=MpvPlayer::class.java.getDeclaredMethod("readTracks",String::class.java,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!).apply{isAccessible=true}
                read.invoke(player,"""[{"id":1,"type":"video","codec":"h264","selected":true,"demux-w":640,"demux-h":360},{"id":2,"type":"video","codec":"av1","selected":false,"demux-w":640,"demux-h":360}]""",640,360,0,1f,24f) as Tracks
            }
            assertTrue(tracks.groups[0].isTrackSupported(0))
            assertFalse("Disabled AV1 software decoding must not be offered as a playable quality",tracks.groups[1].isTrackSupported(0))
            main{ui.activity.setContent{PlayerTheme{PlaybackQualityChoices(player,null,tracks,player.trackSelectionParameters)}}}
            ui.onNodeWithTag("video-quality-1-0").assertDoesNotExist()
        }finally{main{player.release()}}
    }

}
