package io.localplayer.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Dv5SdrPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    private fun view(root:View):PlayerView? {
        if(root is PlayerView)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)view(root.getChildAt(i))?.let{return it}
        return null
    }
    private fun pixels(surface:TextureView,name:String):IntArray {
        val image=main{surface.getBitmap(320,180)}!!
        val pixels=IntArray(320*180);image.getPixels(pixels,0,320,0,0,320,180)
        assertTrue("Decoded video must contain visible scene detail",pixels.toSet().size>100)
        File(ui.activity.cacheDir,"dv5-sdr-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle();return pixels
    }
    @Test fun fallbackIsRestrictedToUnsupportedUnencryptedProfile5(){
        fun format(codecs:String)=Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).build()
        val unsupported=Tracks(com.google.common.collect.ImmutableList.of(Tracks.Group(TrackGroup(format("dvhe.05.06")),false,intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),booleanArrayOf(false))))
        assertTrue(needsDv5Sdr(format("dvhe.05.06"),unsupported,null))
        assertFalse(needsDv5Sdr(format("dvhe.08.06"),unsupported,PlaybackException("decoder",null,PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertFalse(needsDv5Sdr(format("dvhe.07.06"),unsupported,PlaybackException("decoder",null,PlaybackException.ERROR_CODE_DECODING_FAILED)))
        assertFalse(needsDv5Sdr(format("dvhe.05.06"),Tracks.EMPTY,null))
        assertFalse(needsDv5Sdr(format("dvhe.05.06"),unsupported,PlaybackException("network",null,PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
    }
    @Test fun openingSeekAndExternalSubtitleActuallyReachDecoder(){
        val context=ui.activity.applicationContext
        val sample=File(context.cacheDir,"dv5-profile5.mp4")
        org.junit.Assume.assumeTrue("Provide the documented official DV5 fixture",sample.isFile)
        val caption=File(context.cacheDir,"dv5-fixture.srt").apply{writeText("1\n00:00:00,000 --> 00:00:14,000\nDV5 caption fixture\n")}
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        val format=Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.05.05").build()
        val engine=main{MpvPlayer(context,format){c,u->entered.countDown();check(unblock.await(10,TimeUnit.SECONDS));MpvInput.open(c,u)}}
        val surface=main{TextureView(ui.activity).also{ui.activity.addContentView(it,ViewGroup.LayoutParams(-1,-1));engine.setVideoTextureView(it)}}
        try{
            main{
                val sub=MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(caption)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").setLabel("DV5 external caption").build()
                engine.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(sample)).setSubtitleConfigurations(listOf(sub)).build())
                engine.prepare()
            }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            main{engine.seekTo(6000)};unblock.countDown()
            ui.waitUntil(20000){main{engine.playerError!=null||engine.playbackState==Player.STATE_READY}}
            main{assertNull(engine.playerError);assertTrue("Seek during asynchronous opening must survive",engine.currentPosition in 5800..6500)}
            ui.waitUntil(10000){MpvRuntime.load(context).get("sub-text")?.contains("DV5 caption fixture")==true}
            pixels(surface,"caption")
            main{engine.stop();engine.prepare();engine.play()}
            ui.waitUntil(20000){main{engine.playerError!=null||engine.playbackState==Player.STATE_READY}}
            main{assertNull(engine.playerError);engine.pause()}
            pixels(surface,"restarted")
            assertEquals("gpu-next",MpvRuntime.load(context).get("current-vo"))
            val rotated=File(context.cacheDir,"dv5-profile5-rotated.mp4")
            if(rotated.isFile){
                main{engine.setMediaItem(MediaItem.fromUri(Uri.fromFile(rotated)));engine.prepare()}
                ui.waitUntil(20000){main{engine.playerError!=null||(engine.playbackState==Player.STATE_READY&&engine.videoSize.width==1080&&engine.videoSize.height==1920)}}
                main{assertNull(engine.playerError)}
                pixels(surface,"rotated")
            }
            println("DV5_OPENING_SEEK_EXTERNAL_SUBTITLE_RESTART_AND_ROTATION_PASS")
        }finally{unblock.countDown();main{engine.release();(surface.parent as? ViewGroup)?.removeView(surface)}}
    }
    @Test fun sessionFallbackShowsRealFramesAndKeepsSeekPauseSpeedAndSurface(){
        val context=ui.activity.applicationContext
        val file=File(context.cacheDir,"dv5-profile5.mp4")
        org.junit.Assume.assumeTrue("Provide the documented official DV5 fixture",file.isFile)
        val network=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("dv5_source")=="sftp"
        val source=if(network){
            val unknown=io.localplayer.core.Source(label="DV5 SFTP fixture",protocol=io.localplayer.core.Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
            val key=try{io.localplayer.core.SftpConnector().connect(unknown,io.localplayer.core.Credentials(TestFixtures.password)).close();error("Host key confirmation required")}
                catch(e:io.localplayer.core.HostKeyRequired){e.fingerprint}
            unknown.copy(trustedKey=key,trustedAuthority=unknown.authority)
        }else null
        if(source!=null&&androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("dv5_save_source")=="true"){
            val store=SourceStore(context)
            store.load().filter{it.label==source.label}.forEach{store.delete(it.id)}
            store.save(source,TestFixtures.password,true)
        }
        val uri=if(source==null)Uri.fromFile(file) else PlaybackRegistry.add(source,io.localplayer.core.Credentials(TestFixtures.password),"dv5-profile5.mp4","dv5-profile5.mp4")
        val ref=MediaRef("dv5-sdr","DV5 SDR fixture",source?.label?:"本机文件",sourceId=source?.id,path=if(network)"dv5-profile5.mp4" else null,localUri=if(network)null else uri.toString(),sizeBytes=file.length())
        val target=PlayTarget(uri,ref.title,400,ref)
        main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
        var activity:PlaybackActivity?=null
        ui.waitUntil(10000){activity=main{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};activity!=null}
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val c=future.get(10,TimeUnit.SECONDS)
        try {
            try{ui.waitUntil(30000){main{c.playerError!=null||(c.videoSize.width==1920&&PlaybackMetrics.snapshot().firstFrameMs!=null)}}}
            catch(e:Throwable){println(main{"DV5_TIMEOUT state=${c.playbackState} tracks=${c.currentTracks.groups.map{it.type to it.isSelected}} size=${c.videoSize} position=${c.currentPosition} error=${c.playerError} metrics=${PlaybackMetrics.snapshot()}"});throw e}
            main{assertNull(c.playerError);c.pause();c.seekTo(500)}
            ui.waitUntil(5000){main{c.currentPosition in 450..700}}
            Thread.sleep(600)
            val surface=main{view(activity!!.window.decorView)!!.videoSurfaceView as TextureView}
            val first=pixels(surface,"first")
            main{c.setPlaybackSpeed(1.5f);c.seekTo(1300);c.play()}
            ui.waitUntil(10000){main{c.playerError!=null||c.currentPosition>=1550}}
            main{assertNull(c.playerError);c.pause();assertEquals(1.5f,c.playbackParameters.speed,0.01f)}
            var second=pixels(surface,"seek")
            main{c.play()}
            ui.waitUntil(8000){
                Thread.sleep(150)
                second=pixels(surface,"seek")
                !first.contentEquals(second)
            }
            main{c.pause()}
            println("DV5_ENGINE "+listOf("time-pos","pause","seeking","eof-reached","estimated-frame-number").associateWith{MpvRuntime.load(context).get(it)})
            assertFalse("Real video pixels must change after seeking",first.contentEquals(second))
            val version=io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary.getVersion()
            assertTrue("Original NextLib must still be usable",!version.isNullOrBlank())
            println("DV5_SDR_REAL_FRAMES coexist=$version metrics=${PlaybackMetrics.snapshot()}")
        }finally{
            listOf("time-pos","pause","current-vo","vo","vid","video-frame-info","video-out-params/colormatrix").forEach{key->println("DV5_ENGINE_$key="+runCatching{MpvRuntime.load(context).get(key)}.getOrNull())}
            main{c.stop();activity?.finish();c.release()};PlaybackRequests.remove(target.playId)}
    }    @Test fun selectedDubSurvivesCaptionChangesAndCanReturnToAutomatic() {
        val context=ui.activity.applicationContext
        val sample=File(context.cacheDir,"dv5-profile5.mp4");check(sample.isFile)
        val audio=File(context.cacheDir,"sidecar-dts.mka");check(audio.isFile)
        val caption=File(context.cacheDir,"dub-retain55.srt").apply{writeText("1\n00:00:00,000 --> 00:00:14,000\nRetained dub\n")}
        val engine=main{MpvPlayer(context,Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.05.05").build())}
        val surface=main{TextureView(ui.activity).also{ui.activity.addContentView(it,ViewGroup.LayoutParams(-1,-1));engine.setVideoTextureView(it)}}
        val original=MediaItem.fromUri(Uri.fromFile(sample))
        val dubbed=SidecarAudio.attach(original,listOf(SidecarAudio(Uri.fromFile(audio),"Film.zh.mka")))
        try {
            main{engine.setMediaItem(dubbed);engine.volume=0f;engine.prepare();engine.play()}
            ui.waitUntil(20000){main{engine.currentTracks.groups.count{it.type==C.TRACK_TYPE_AUDIO}>=2}}
            val runtime=MpvRuntime.load(context)
            val external=main{engine.currentTracks.groups.last{it.type==C.TRACK_TYPE_AUDIO}}
            val id=external.mediaTrackGroup.id.removePrefix("mpv-audio-")
            main{engine.trackSelectionParameters=engine.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(external.mediaTrackGroup,0)).build()}
            ui.waitUntil(5000){runtime.get("aid")==id}
            val config=MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(caption)).setMimeType(MimeTypes.APPLICATION_SUBRIP).build()
            main{engine.updateAttachments(listOf(dubbed.buildUpon().setSubtitleConfigurations(listOf(config)).build()))}
            ui.waitUntil(5000){main{engine.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT}}}
            assertEquals("Adding a caption retains selected audio ID",id,runtime.get("aid"));main{assertNull(engine.playerError)}
            main{engine.trackSelectionParameters=engine.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,true).build()}
            ui.waitUntil(5000){runtime.get("aid")=="no"}
            main{engine.trackSelectionParameters=engine.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).build()}
            ui.waitUntil(5000){runtime.get("aid")!="no"}
            main{assertTrue(engine.trackSelectionParameters.overrides.values.none{it.type==C.TRACK_TYPE_AUDIO});assertNull(engine.playerError)}
        } finally {main{engine.release();(surface.parent as? ViewGroup)?.removeView(surface)};caption.delete()}
    }

}
