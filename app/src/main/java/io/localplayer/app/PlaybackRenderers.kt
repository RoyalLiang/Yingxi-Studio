package io.localplayer.app

import android.content.Context
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Keep hardware/HDR output; try the next available decoder when initialization fails. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackRenderers(context:Context,selector:MediaCodecSelector=MediaCodecSelector.DEFAULT,preferPlatform:((androidx.media3.common.Format)->Boolean)?=null)=
    HdrRenderersFactory(context,preferPlatform).apply {setMediaCodecSelector(hardwarePreferredSelector(selector));setEnableDecoderFallback(true)}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackFailure(error:androidx.media3.common.PlaybackException):String {
    val codecErrors=setOf(
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
    if(error.errorCode in codecErrors){
        val format=(error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat
        val codec=when(format?.sampleMimeType){
            "video/hevc"->"HEVC";"video/avc"->"H.264";"video/dolby-vision"->"杜比视界"
            else->format?.sampleMimeType?.removePrefix("video/")?:"当前编码"
        }
        return "视频解码失败（$codec），请尝试其他编码版本的片源"
    }
    return "播放失败（${error.errorCodeName}），请返回重试"
}
