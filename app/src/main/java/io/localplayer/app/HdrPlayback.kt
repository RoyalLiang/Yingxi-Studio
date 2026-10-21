package io.localplayer.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Handler
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun hardwarePreferredSelector(delegate:MediaCodecSelector)=MediaCodecSelector { mime,secure,tunneling ->
    val codecs=delegate.getDecoderInfos(mime,secure,tunneling)
    if(MimeTypes.isVideo(mime))codecs.sortedByDescending{it.hardwareAccelerated} else codecs
}

internal fun nativeDolbyVisionAvailable(hdrTypes:IntArray,hardwareDecoderSupported:()->Boolean):Boolean =
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes&&hardwareDecoderSupported()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun supportsNativeDolbyVision(context:Context,format:Format):Boolean=runCatching {
    val display=context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    nativeDolbyVisionAvailable(display?.hdrCapabilities?.supportedHdrTypes?:intArrayOf()) {
        MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION,format.drmInitData!=null,false)
            .any{it.hardwareAccelerated&&it.isFormatSupported(format)}
    }
}.getOrDefault(false)

/** MediaCodec must receive HEVC color/profile data when trying a DV stream's HDR10 base layer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun decoderOutputFormat(format:Format,codecMimeType:String):Format =
    if(codecMimeType==MimeTypes.VIDEO_H265)dolbyVisionBaseFormat(format,false) else format

@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class HdrRenderersFactory(context:Context):androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(context:Context,extensionRendererMode:Int,selector:MediaCodecSelector,
        enableDecoderFallback:Boolean,eventHandler:Handler,eventListener:VideoRendererEventListener,
        allowedVideoJoiningTimeMs:Long,out:ArrayList<Renderer>) {
        val start=out.size
        super.buildVideoRenderers(context,extensionRendererMode,selector,enableDecoderFallback,eventHandler,
            eventListener,allowedVideoJoiningTimeMs,out)
        val index=(start until out.size).firstOrNull{out[it] is MediaCodecVideoRenderer}?:return
        out[index]=object:MediaCodecVideoRenderer(context,codecAdapterFactory,selector,allowedVideoJoiningTimeMs,
            enableDecoderFallback,eventHandler,eventListener,MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
            override fun getDecoderInfos(selector:MediaCodecSelector,format:Format,requiresSecureDecoder:Boolean):List<MediaCodecInfo> {
                val native=super.getDecoderInfos(selector,format,requiresSecureDecoder)
                val base=dolbyVisionBaseFormat(format,false)
                if(base===format)return native
                // Keep native Dolby Vision first; initialization fallback can then try compatible HEVC.
                return distinctDecoderConfigurations(native+super.getDecoderInfos(selector,base,requiresSecureDecoder))
            }

            override fun getMediaFormat(format:Format,codecMimeType:String,codecMaxValues:CodecMaxValues,
                codecOperatingRate:Float,deviceNeedsNoPostProcessWorkaround:Boolean,tunnelingAudioSessionId:Int):MediaFormat =
                super.getMediaFormat(decoderOutputFormat(format,codecMimeType),codecMimeType,codecMaxValues,
                    codecOperatingRate,deviceNeedsNoPostProcessWorkaround,tunnelingAudioSessionId)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun distinctDecoderConfigurations(codecs:List<MediaCodecInfo>):List<MediaCodecInfo> =
    codecs.distinctBy{it.name to it.codecMimeType}.sortedByDescending{it.hardwareAccelerated}
