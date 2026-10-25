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

internal class VideoOutputStateException(cause:IllegalStateException):IllegalStateException(cause)

// Kotlin interface delegation inherits Java default methods instead of forwarding them.
// Renderer defaults include speed and wake-up scheduling, so forward those explicitly.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class ForwardingPlaybackRenderer(private val delegate:Renderer):Renderer by delegate {
    override fun setPlaybackSpeed(currentPlaybackSpeed:Float,targetPlaybackSpeed:Float)=delegate.setPlaybackSpeed(currentPlaybackSpeed,targetPlaybackSpeed)
    override fun getDurationToProgressUs(positionUs:Long,elapsedRealtimeUs:Long)=delegate.getDurationToProgressUs(positionUs,elapsedRealtimeUs)
    override fun supportsResetPositionWithoutKeyFrameReset(positionUs:Long)=delegate.supportsResetPositionWithoutKeyFrameReset(positionUs)
    override fun enableMayRenderStartOfStream()=delegate.enableMayRenderStartOfStream()
    override fun release()=delegate.release()
}

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
            .any{it.hardwareAccelerated&&it.isFormatSupported(context,format)}
    }
}.getOrDefault(false)

/** MediaCodec must receive HEVC color/profile data when trying a DV stream's HDR10 base layer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun decoderOutputFormat(format:Format,codecMimeType:String):Format =
    if(codecMimeType==MimeTypes.VIDEO_H265)hevcDecoderFormat(dolbyVisionBaseFormat(format,false)) else format

/** Repair an unreadable advertised HEVC profile only from the actual initialization bitstream. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun hevcDecoderFormat(format:Format):Format {
    if(format.sampleMimeType!=MimeTypes.VIDEO_H265||format.drmInitData!=null||
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(format)!=null)return format
    val codecs=runCatching{androidx.media3.container.NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData)}.getOrNull()?:return format
    return format.buildUpon().setCodecs(codecs).build()
}

@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class HdrRenderersFactory(context:Context):androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    @Volatile internal var forceSoftware=false
    @Volatile private var videoOutput:Any?=null
    private var softwareRenderer:Renderer?=null
    internal var nativeVideoIndex=-1;private set
    internal var softwareVideoIndex=-1;private set
    internal fun nativeGate(renderer:Renderer):Renderer {
        val capabilities=object:androidx.media3.exoplayer.RendererCapabilities by renderer.capabilities {
            override fun supportsFormat(format:Format):Int =
                if(forceSoftware&&format.sampleMimeType==MimeTypes.VIDEO_H265&&format.drmInitData==null)androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE
                else renderer.capabilities.supportsFormat(format)
        }
        return object:ForwardingPlaybackRenderer(renderer) {
            override fun getCapabilities()=capabilities
            override fun handleMessage(messageType:Int,message:Any?) {
                if(messageType==Renderer.MSG_SET_VIDEO_OUTPUT)videoOutput=message
                renderer.handleMessage(messageType,message)
            }
        }
    }
    internal fun reapplySoftwareOutput(player:androidx.media3.exoplayer.ExoPlayer) {
        softwareRenderer?.let{player.createMessage(it).setType(Renderer.MSG_SET_VIDEO_OUTPUT).setPayload(videoOutput).send()}
    }
    override fun buildAudioRenderers(context:Context,extensionRendererMode:Int,selector:MediaCodecSelector,
        enableDecoderFallback:Boolean,audioSink:androidx.media3.exoplayer.audio.AudioSink,eventHandler:Handler,
        eventListener:androidx.media3.exoplayer.audio.AudioRendererEventListener,out:ArrayList<Renderer>) {
        super.buildAudioRenderers(context,extensionRendererMode,selector,enableDecoderFallback,audioSink,eventHandler,eventListener,out)
        out.add(io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer(eventHandler,eventListener,audioSink))
    }
    override fun buildVideoRenderers(context:Context,extensionRendererMode:Int,selector:MediaCodecSelector,
        enableDecoderFallback:Boolean,eventHandler:Handler,eventListener:VideoRendererEventListener,
        allowedVideoJoiningTimeMs:Long,out:ArrayList<Renderer>) {
        val start=out.size
        super.buildVideoRenderers(context,extensionRendererMode,selector,enableDecoderFallback,eventHandler,
            eventListener,allowedVideoJoiningTimeMs,out)
        val index=(start until out.size).firstOrNull{out[it] is MediaCodecVideoRenderer}?:return
        nativeVideoIndex=index
        val software=io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer(allowedVideoJoiningTimeMs,eventHandler,eventListener,MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        softwareRenderer=software
        softwareVideoIndex=out.size
        val capabilities=object:androidx.media3.exoplayer.RendererCapabilities by software.capabilities {
            override fun supportsFormat(format:Format):Int {
                if(format.sampleMimeType!=MimeTypes.VIDEO_H265||format.drmInitData!=null)return androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE
                // Capability tables can underreport level/profile support. Try platform decoding first;
                // only VideoDecoderRecovery may enable the CPU renderer after an actual failure.
                if(!forceSoftware)return androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE
                return software.capabilities.supportsFormat(format)
            }
        }
        out.add(object:ForwardingPlaybackRenderer(software) {
            override fun getCapabilities()=capabilities
            override fun handleMessage(messageType:Int,message:Any?) {
                if(messageType==Renderer.MSG_SET_VIDEO_OUTPUT)videoOutput=message
                software.handleMessage(messageType,message)
            }
        })
        out[index]=nativeGate(object:MediaCodecVideoRenderer(context,codecAdapterFactory,selector,allowedVideoJoiningTimeMs,
            enableDecoderFallback,eventHandler,eventListener,MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
            private var codecFailed=false
            override fun onCodecError(codecError:Exception) {
                codecFailed=true
                super.onCodecError(codecError)
            }
            override fun onCodecInitialized(name:String,configuration:androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration,
                initializedTimestampMs:Long,initializationDurationMs:Long) {
                codecFailed=false
                super.onCodecInitialized(name,configuration,initializedTimestampMs,initializationDurationMs)
            }
            override fun handleMessage(messageType:Int,message:Any?) {
                // A surface change can arrive between a codec failure and the main-thread recovery.
                // Clear the failed adapter before Media3 updates the output, retaining the new surface.
                if(messageType==Renderer.MSG_SET_VIDEO_OUTPUT&&(codecFailed||forceSoftware))releaseCodec()
                try { super.handleMessage(messageType,message) }
                catch(e:IllegalStateException) {
                    // Asynchronous codec errors may precede their callback. Recover only the
                    // framework codec's output-state failure, not unrelated renderer invariants.
                    if(messageType!=Renderer.MSG_SET_VIDEO_OUTPUT||e.stackTrace.none{it.className=="android.media.MediaCodec"})throw e
                    val format=codecInputFormat
                    releaseCodec()
                    // Reopening the codec alone discards queued samples until the next keyframe.
                    // Let the recovery listener reload from the current position instead.
                    throw createRendererException(VideoOutputStateException(e),format,
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                }
            }
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
        })
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun distinctDecoderConfigurations(codecs:List<MediaCodecInfo>):List<MediaCodecInfo> =
    codecs.distinctBy{it.name to it.codecMimeType}.sortedByDescending{it.hardwareAccelerated}
