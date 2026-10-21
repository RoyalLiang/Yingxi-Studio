package io.localplayer.app

import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.net.Uri
import android.view.Surface
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DolbyVisionCompatibilityTest {
    private val instrumentation get()=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun format():Format {
        val file=File(context.getExternalFilesDir(null),"dv-profile7.mp4");assertTrue("Seed the synthetic Main10/DV configuration fixture",file.isFile)
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val data=extractor.getTrackFormat(0).getByteBuffer("csd-0")!!
            val csd=ByteArray(data.remaining()).also{data.get(it)}
            return Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.06").setInitializationData(listOf(csd)).build()
        } finally {extractor.release()}
    }
    @Test fun onlyReadableProfile7Main10FallsBackAndNativeSupportIsPreserved() {
        val original=format();val fallback=profile7BaseFormat(original,false)
        assertEquals(MimeTypes.VIDEO_H265,fallback.sampleMimeType);assertTrue(fallback.codecs!!.startsWith("hvc1.2."))
        assertEquals(C.COLOR_TRANSFER_ST2084,fallback.colorInfo!!.colorTransfer)
        assertSame(original,profile7BaseFormat(original,true))
        val five=original.buildUpon().setCodecs("dvhe.05.06").build()
        assertSame(five,profile7BaseFormat(five,false))
        val broken=original.buildUpon().setInitializationData(emptyList()).build()
        assertSame(broken,profile7BaseFormat(broken,false))
        val nonHdr=original.buildUpon().setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).setColorTransfer(C.COLOR_TRANSFER_SDR).build()).build()
        assertSame(nonHdr,profile7BaseFormat(nonHdr,false))
    }
    @Test fun realExtractorPublishesHevcOnlyForTheCompatibilityPath() {
        val file=File(context.getExternalFilesDir(null),"dv-profile7.mp4")
        for(fallback in listOf(false,true)) {
            val done=CountDownLatch(1);var video:Format?=null
            lateinit var player:ExoPlayer
            instrumentation.runOnMainSync {
                val source=if(fallback)DefaultMediaSourceFactory(DefaultDataSource.Factory(context),PlaybackExtractors(supportsNative={false})) else DefaultMediaSourceFactory(context)
                player=ExoPlayer.Builder(context,playbackRenderers(context)).setMediaSourceFactory(source).build()
                player.addListener(object:Player.Listener {
                    override fun onTracksChanged(tracks:Tracks){
                        tracks.groups.firstOrNull{it.type==C.TRACK_TYPE_VIDEO}?.let{video=it.mediaTrackGroup.getFormat(0);done.countDown()}
                    }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare()
            }
            try {
                assertTrue(done.await(8,TimeUnit.SECONDS))
                assertEquals(if(fallback)MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_DOLBY_VISION,video!!.sampleMimeType)
                if(fallback)assertTrue(video!!.codecs!!.startsWith("hvc1.2.")) else assertEquals("dvhe.07.06",video!!.codecs)
            } finally {instrumentation.runOnMainSync{player.release()}}
        }
    }
    @Test fun capableDeviceRendersTheMain10Base() {
        val base=profile7BaseFormat(format(),false)
        val capable=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265,false,false).any{it.isFormatSupported(base)}
        org.junit.Assume.assumeTrue("Device does not advertise HEVC Main10; format routing is covered separately",capable)
        val file=File(context.getExternalFilesDir(null),"dv-profile7.mp4")
        val done=CountDownLatch(1);var error:PlaybackException?=null;var rendered=false
        lateinit var player:ExoPlayer;lateinit var texture:SurfaceTexture;lateinit var surface:Surface
        instrumentation.runOnMainSync {
            texture=SurfaceTexture(0);surface=Surface(texture)
            val source=DefaultMediaSourceFactory(DefaultDataSource.Factory(context),PlaybackExtractors(supportsNative={false}))
            player=ExoPlayer.Builder(context,playbackRenderers(context)).setMediaSourceFactory(source).build()
            player.addListener(object:Player.Listener {
                override fun onPlayerError(e:PlaybackException){error=e;done.countDown()}
                override fun onRenderedFirstFrame(){rendered=true;done.countDown()}
            })
            player.setVideoSurface(surface);player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));player.prepare();player.play()
        }
        try {assertTrue(done.await(12,TimeUnit.SECONDS));assertNull(error);assertTrue(rendered)}
        finally {instrumentation.runOnMainSync{player.release();surface.release();texture.release()}}
    }
}
