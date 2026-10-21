package io.localplayer.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.RendererCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DecoderRoutingRegressionTest {
    @Test fun softwareCannotWinInitialTrackSelectionEvenWhenCapabilitiesUnderreportHardware() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = playbackRenderers(context, androidx.media3.exoplayer.mediacodec.MediaCodecSelector { _,_,_-> emptyList() })
        val renderers = factory.createRenderers(Handler(Looper.getMainLooper()),
            object : androidx.media3.exoplayer.video.VideoRendererEventListener {},
            object : androidx.media3.exoplayer.audio.AudioRendererEventListener {}, {}, {})
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(1920).setHeight(1080).setFrameRate(30f).build()
        val software = renderers[factory.softwareVideoIndex].capabilities
        assertEquals("FFmpeg is recovery only: capability estimates must not divert normal playback",
            C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(software.supportsFormat(hevc)))
        factory.forceSoftware = true
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(software.supportsFormat(hevc)))
        val avc = hevc.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(software.supportsFormat(avc)))
        factory.forceSoftware = false
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(software.supportsFormat(hevc)))
    }
}
