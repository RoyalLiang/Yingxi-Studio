package io.localplayer.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.NalUnitUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.extractor.*

/** Profile 7 UHD-BD carries an HDR10 base layer. Never relabel profile 5's incompatible colors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun profile7BaseFormat(format:Format,nativeSupported:Boolean):Format {
    if(nativeSupported||format.drmInitData!=null||format.sampleMimeType!=MimeTypes.VIDEO_DOLBY_VISION||
        !Regex("^(?:dvhe|dvh1)\\.07\\.\\d{2}$").matches(format.codecs.orEmpty()))return format
    val base=runCatching{NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData)}.getOrNull()?:return format
    // Require a readable Main10 base SPS, rather than assuming every deprecated DV profile is compatible.
    if(!Regex("^hvc1\\.[ABC]?2\\..*").matches(base))return format
    val color=format.colorInfo
    if(color!=null&&((color.colorSpace!=Format.NO_VALUE&&color.colorSpace!=C.COLOR_SPACE_BT2020)||
        (color.colorTransfer!=Format.NO_VALUE&&color.colorTransfer!=C.COLOR_TRANSFER_ST2084)))return format
    return format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(base)
        .setColorInfo((color?:ColorInfo.Builder().build()).buildUpon().setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorRange(if(color?.colorRange==C.COLOR_RANGE_FULL)C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED).build()).build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackExtractors(
    private val delegate:ExtractorsFactory=DefaultExtractorsFactory(),
    private val supportsNative:(Format)->Boolean={format->runCatching {
        MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION,false,false).any{it.isFormatSupported(format)}
    }.getOrDefault(false)}
):ExtractorsFactory by delegate {
    override fun createExtractors():Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()
    override fun createExtractors(uri:Uri,responseHeaders:Map<String,List<String>>):Array<Extractor> =
        delegate.createExtractors(uri,responseHeaders).map(::wrap).toTypedArray()
    private fun wrap(extractor:Extractor)=object:Extractor by extractor {
        override fun init(output:ExtractorOutput) {
            extractor.init(object:ExtractorOutput by output {
                override fun track(id:Int,type:Int):TrackOutput {
                    val track=output.track(id,type)
                    return object:TrackOutput by track {
                        override fun format(format:Format) {
                            track.format(if(type==C.TRACK_TYPE_VIDEO&&format.sampleMimeType==MimeTypes.VIDEO_DOLBY_VISION)
                                profile7BaseFormat(format,supportsNative(format)) else format)
                        }
                    }
                }
            })
        }
    }
}
