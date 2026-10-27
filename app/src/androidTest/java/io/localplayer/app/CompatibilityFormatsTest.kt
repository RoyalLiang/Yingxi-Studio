package io.localplayer.app

import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.setContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CompatibilityFormatsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(block:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
    @Test fun unsafeAv1SoftwareRouteStaysClosed() {
        val format=Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build()
        assertFalse(needsCompatibility(format,Tracks.EMPTY,PlaybackException("fixture",null,PlaybackException.ERROR_CODE_DECODING_FAILED)))
    }
    @Test fun unknownContainerCannotBypassUnsafeDecoderBlock() {
        val file=File(ui.activity.cacheDir,"compat-av1-10.ivf");assertTrue(file.isFile)
        val player=main{CompatibilityPlayer(ui.activity,ExoPlayer.Builder(ui.activity,playbackRenderers(ui.activity)).build())}
        // Compatibility preparation waits for the same real output used by the player window.
        var frames=0
        main{player.addListener(object:Player.Listener{override fun onRenderedFirstFrame(){frames++}})}
        lateinit var surface:TextureView
        main{ui.activity.setContent{AndroidView(factory={TextureView(it).also{view->surface=view;player.setVideoTextureView(view)}})}}
        ui.waitForIdle()
        try {
            main{player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();player.play()}
            ui.waitUntil(35000){main{player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")==true&&player.playerError!=null}}
            // Demux dimensions can exist without decoding; assert that no video frame is rendered.
            main{assertNotNull(player.playerError);assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED,player.playerError?.errorCode);assertEquals(0,frames)}
        }finally{main{player.release();(surface.parent as? android.view.ViewGroup)?.removeView(surface)}}
    }
    @Test fun asfWmv()=play("compat-wmv.wmv")
    @Test fun realVideo()=play("compat-real.rm")
    @Test fun mpeg1()=play("software-mpeg1.mkv",true)
    @Test fun mpeg4()=play("compat-mpeg4.avi",true)
    @Test fun h263()=play("compat-h263.avi",true)
    @Test fun mjpeg()=play("compat-mjpeg.mov",true)

    @Test fun transportAndRightsFailuresDoNotChangeBackend() {
        val format=Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build()
        for(code in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)) {
            assertFalse(needsCompatibility(format,Tracks.EMPTY,PlaybackException("private",null,code)))
        }
        val drm=format.buildUpon().setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID,"video/mp4",byteArrayOf(1)))).build()
        assertFalse(needsCompatibility(drm,Tracks.EMPTY,PlaybackException("private",null,PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertFalse(softwareVideoFormat(format))
    }
    private fun play(name:String,breakNative:Boolean=false) {
        val file=File(ui.activity.cacheDir,name);assertTrue(file.isFile)
        val platform=DefaultMediaCodecAdapterFactory(ui.activity)
        val factory=object:HdrRenderersFactory(ui.activity) {
            override fun getCodecAdapterFactory()=MediaCodecAdapter.Factory { config ->
                if(breakNative&&config.codecInfo.mimeType.startsWith("video/"))throw java.io.IOException("Intentional platform failure")
                platform.createAdapter(config)
            }
        }.apply {if(breakNative)setMediaCodecSelector(MediaCodecSelector { mime,secure,tunnel ->
            if(mime.startsWith("video/"))listOf(MediaCodecInfo.newInstance("yingxi.test.decoder",mime,mime,null,true,false,true,false,false))
            else MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)
        })}
        val native=main{ExoPlayer.Builder(ui.activity,factory).build()}
        val player=main{CompatibilityPlayer(ui.activity,native)}
        lateinit var surface:TextureView
        main{ui.activity.setContent{AndroidView(factory={TextureView(it).also{view->surface=view;player.setVideoTextureView(view)}})}}
        ui.waitForIdle()
        try {
            main{player.volume=0f;player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();player.play()}
            ui.waitUntil(30000){main{player.playerError!=null||(player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")==true&&player.currentPosition>700&&player.videoSize.width>0)}}
            main{assertNull("$name ${playbackDiagnostic(player)} cause=${player.playerError?.cause}",player.playerError);assertTrue(player.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("sdr-compatibility"));player.pause()}
            val image=main{surface.getBitmap(160,90)}!!
            val pixels=IntArray(160*90);image.getPixels(pixels,0,160,0,0,160,90);image.recycle();assertTrue(pixels.toSet().size>24)
            main{player.seekTo(1500);player.setPlaybackSpeed(1.5f);player.play()}
            ui.waitUntil(15000){main{player.playerError!=null||player.currentPosition>2300}}
            main{assertNull(player.playerError);assertEquals(1.5f,player.playbackParameters.speed)}
            println("COMPATIBILITY_VIDEO sample=$name position=${main{player.currentPosition}}")
        } finally {main{player.release();(surface.parent as? android.view.ViewGroup)?.removeView(surface)}}
    }
}
