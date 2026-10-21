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

    @Test fun initializationFallbackConfiguresHevcWithoutADolbyProfile() {
        val attempted=java.util.Collections.synchronizedList(mutableListOf<android.media.MediaFormat>())
        val adapter=androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory { configuration ->
            attempted.add(android.media.MediaFormat(configuration.mediaFormat))
            throw java.io.IOException("Intentional decoder initialization failure")
        }
        val selector=androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime,_,_ ->
            if(mime==MimeTypes.VIDEO_DOLBY_VISION||mime==MimeTypes.VIDEO_H265)
                listOf(androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
                    "yingxi.test.decoder",mime,mime,null,true,false,true,false,false)) else emptyList()
        }
        val done=CountDownLatch(1);lateinit var player:ExoPlayer
        instrumentation.runOnMainSync {
            val renderers=object:HdrRenderersFactory(context){override fun getCodecAdapterFactory()=adapter}
                .setMediaCodecSelector(selector).setEnableDecoderFallback(true)
            val source=DefaultMediaSourceFactory(DefaultDataSource.Factory(context),PlaybackExtractors(supportsNative={true}))
            player=ExoPlayer.Builder(context,renderers).setMediaSourceFactory(source).build()
            player.addListener(object:Player.Listener{override fun onPlayerError(e:PlaybackException){done.countDown()}})
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(context.getExternalFilesDir(null),"dv-profile7.mp4"))))
            player.prepare();player.play()
        }
        try {
            assertTrue("Both initialization attempts completed",done.await(8,TimeUnit.SECONDS))
            assertEquals(listOf(MimeTypes.VIDEO_DOLBY_VISION,MimeTypes.VIDEO_H265),attempted.map{it.getString(android.media.MediaFormat.KEY_MIME)})
            assertTrue(attempted[0].containsKey(android.media.MediaFormat.KEY_PROFILE))
            assertFalse(attempted[1].containsKey(android.media.MediaFormat.KEY_PROFILE))
            assertEquals(C.COLOR_TRANSFER_ST2084,attempted[1].getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER))
            assertEquals(C.COLOR_SPACE_BT2020,attempted[1].getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD))
        } finally {instrumentation.runOnMainSync{player.release()}}
    }

    @Test fun nativeDecoderInitializationFailureFallsBackToHdr10BaseLayer() {
        val base=dolbyVisionBaseFormat(format(),false)
        val real=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
        org.junit.Assume.assumeTrue("Device HEVC Main10 support",real.getDecoderInfos(MimeTypes.VIDEO_H265,false,false).any{it.isFormatSupported(context,base)})
        val selector=androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime,secure,tunnel ->
            if(mime==MimeTypes.VIDEO_DOLBY_VISION)listOf(androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
                "c2.yingxi.unavailable.dolby.decoder",mime,mime,null,true,false,true,false,false))
            else real.getDecoderInfos(mime,secure,tunnel)
        }
        for(enabled in listOf(false,true)) {
            val done=CountDownLatch(1);var error:PlaybackException?=null;var rendered=false
            lateinit var player:ExoPlayer;lateinit var texture:SurfaceTexture;lateinit var surface:Surface
            instrumentation.runOnMainSync {
                texture=SurfaceTexture(0);surface=Surface(texture)
                val source=DefaultMediaSourceFactory(DefaultDataSource.Factory(context),PlaybackExtractors(supportsNative={true}))
                player=ExoPlayer.Builder(context,playbackRenderers(context,selector).setEnableDecoderFallback(enabled))
                    .setMediaSourceFactory(source).build()
                player.addListener(object:Player.Listener {
                    override fun onPlayerError(e:PlaybackException){error=e;done.countDown()}
                    override fun onRenderedFirstFrame(){rendered=true;done.countDown()}
                })
                player.setVideoSurface(surface)
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(context.getExternalFilesDir(null),"dv-profile7.mp4"))))
                player.prepare();player.play()
            }
            try {
                assertTrue("Decoder attempt completed",done.await(12,TimeUnit.SECONDS))
                if(enabled){assertNull(error);assertTrue(rendered)} else {assertNotNull(error);assertFalse(rendered)}
            } finally {instrumentation.runOnMainSync{player.release();surface.release();texture.release()}}
        }
    }
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
        val original=format();val fallback=dolbyVisionBaseFormat(original,false)
        assertEquals(MimeTypes.VIDEO_H265,fallback.sampleMimeType);assertTrue(fallback.codecs!!.startsWith("hvc1.2."))
        assertEquals(C.COLOR_TRANSFER_ST2084,fallback.colorInfo!!.colorTransfer)
        assertSame(original,dolbyVisionBaseFormat(original,true))
        val five=original.buildUpon().setCodecs("dvhe.05.06").build()
        assertSame(five,dolbyVisionBaseFormat(five,false))
        val broken=original.buildUpon().setInitializationData(emptyList()).build()
        assertSame(broken,dolbyVisionBaseFormat(broken,false))
        val nonHdr=original.buildUpon().setColorInfo(ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).setColorTransfer(C.COLOR_TRANSFER_SDR).build()).build()
        assertSame(nonHdr,dolbyVisionBaseFormat(nonHdr,false))
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
        val base=dolbyVisionBaseFormat(format(),false)
        val capable=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265,false,false).any{it.isFormatSupported(context,base)}
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
