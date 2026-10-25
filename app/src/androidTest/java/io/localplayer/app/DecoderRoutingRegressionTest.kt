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
        for(mime in listOf(MimeTypes.VIDEO_H264,MimeTypes.VIDEO_VP8,MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_MPEG2)) {
            val format=hevc.buildUpon().setSampleMimeType(mime).build()
            assertEquals(mime,C.FORMAT_HANDLED,RendererCapabilities.getFormatSupport(software.supportsFormat(format)))
            factory.forceSoftware=false
            assertEquals(mime,C.FORMAT_UNSUPPORTED_TYPE,RendererCapabilities.getFormatSupport(software.supportsFormat(format)))
            factory.forceSoftware=true
        }
        val hdr=hevc.buildUpon().setSampleMimeType(MimeTypes.VIDEO_AV1).setColorInfo(
            ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()
        assertFalse("Do not silently send HDR to the new SDR software paths",softwareVideoFormat(hdr))
        assertFalse(softwareVideoFormat(hevc.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        val encrypted=hevc.buildUpon().setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID,"video/mp4",byteArrayOf(1)))).build()
        assertFalse(softwareVideoFormat(encrypted))
        factory.forceSoftware = false
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(software.supportsFormat(hevc)))
    }
}
