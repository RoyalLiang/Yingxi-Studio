package io.localplayer.app

import android.util.Base64
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HdrPlaybackTest {
    // VPS/SPS/PPS from a generated 320x180 Main10 test pattern, not a Dolby Vision movie.
    private val csd=Base64.decode("AAAAAUABDAH//wIgAAADAJAAAAMAAAMAPJWUCQAAAAFCAQECIAAAAwCQAAADAAADADygCggMHztllZUpMLwFqEiASCAAAAMAIAAAAwMBAAAAAUQBwHPBiQ==",Base64.DEFAULT)
    private fun pq()=ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .setHdrStaticInfo(ByteArray(25){it.toByte()}).build()
    private fun vision(profile:String="07",color:ColorInfo?=null)=Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.$profile.06")
        .setWidth(320).setHeight(180).setInitializationData(listOf(csd)).setColorInfo(color).build()

    @Test fun profile8PqBaseLayerRetainsHdrMetadataAndDimensions() {
        val source=vision("08",pq());val base=dolbyVisionBaseFormat(source,false)
        assertEquals(MimeTypes.VIDEO_H265,base.sampleMimeType)
        assertTrue(base.codecs!!.startsWith("hvc1.2."))
        assertEquals(source.colorInfo,base.colorInfo)
        assertArrayEquals(source.colorInfo!!.hdrStaticInfo,base.colorInfo!!.hdrStaticInfo)
        assertEquals(source.width,base.width);assertEquals(source.height,base.height)
        assertArrayEquals(csd,base.initializationData.single())
        assertSame(source,dolbyVisionBaseFormat(source,true))
    }

    @Test fun profile8RequiresExplicitBt2020Pq() {
        val hlg=vision("08",pq().buildUpon().setColorTransfer(C.COLOR_TRANSFER_HLG).build())
        val sdr=vision("08",pq().buildUpon().setColorTransfer(C.COLOR_TRANSFER_SDR).build())
        val bt709=vision("08",pq().buildUpon().setColorSpace(C.COLOR_SPACE_BT709).build())
        val untagged=vision("08")
        for(source in listOf(hlg,sdr,bt709,untagged))assertSame(source,dolbyVisionBaseFormat(source,false))
    }

    @Test fun incompatibleEncryptedAndUnreadableStreamsKeepTheirOriginalFormat() {
        val encrypted=vision().buildUpon().setDrmInitData(DrmInitData("cenc",emptyList<DrmInitData.SchemeData>())).build()
        for(source in listOf(vision("05",pq()),vision("09",pq()),encrypted,
            vision().buildUpon().setInitializationData(emptyList()).build(),
            vision().buildUpon().setInitializationData(listOf(byteArrayOf(0,0,0,1,66))).build())) {
            assertSame(source,dolbyVisionBaseFormat(source,false))
        }
    }

    @Test fun hdr10AndNativeDolbyVisionKeepTheirFormats() {
        val source=vision("08",pq())
        assertSame(source,decoderOutputFormat(source,MimeTypes.VIDEO_DOLBY_VISION))
        val base=decoderOutputFormat(source,MimeTypes.VIDEO_H265)
        assertEquals(MimeTypes.VIDEO_H265,base.sampleMimeType)
        assertSame(base,dolbyVisionBaseFormat(base,false))
        assertSame(base,decoderOutputFormat(base,MimeTypes.VIDEO_H265))
        val five=vision("05",pq());assertSame(five,decoderOutputFormat(five,MimeTypes.VIDEO_H265))
    }

    @Test fun unreadableHevcCodecMetadataUsesTheActualSpsForDecoderConfiguration() {
        val original=dolbyVisionBaseFormat(vision("08",pq()),false)
        val broken=original.buildUpon().setCodecs("hvc1.2.4.H1153.B0").build()
        val fixed=hevcDecoderFormat(broken)
        assertEquals(original.codecs,fixed.codecs)
        assertEquals(broken.colorInfo,fixed.colorInfo)
        assertEquals(broken.width,fixed.width);assertEquals(broken.height,fixed.height)
        assertArrayEquals(csd,fixed.initializationData.single())
        assertSame(original,hevcDecoderFormat(original))
        val unreadable=broken.buildUpon().setInitializationData(emptyList()).build()
        assertSame(unreadable,hevcDecoderFormat(unreadable))
    }

    @Test fun nativeVisionRequiresBothTheDisplayAndAHardwareDecoder() {
        var queried=false
        assertFalse(nativeDolbyVisionAvailable(intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10)){queried=true;true})
        assertFalse(queried)
        assertFalse(nativeDolbyVisionAvailable(intArrayOf()){true})
        assertFalse(nativeDolbyVisionAvailable(intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)){false})
        assertTrue(nativeDolbyVisionAvailable(intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)){true})
    }

    @Test fun hardwareIsPreferredWithoutDroppingSoftwareOrChangingAudioAndSecurityRequirements() {
        fun codec(name:String,hardware:Boolean)=MediaCodecInfo.newInstance(name,MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_H265,null,hardware,!hardware,hardware,false,false)
        val software=codec("software",false);val first=codec("hardware-first",true);val second=codec("hardware-second",true)
        val original=listOf(software,first,second)
        val requests=mutableListOf<Triple<String,Boolean,Boolean>>()
        val selector=hardwarePreferredSelector(MediaCodecSelector{mime,secure,tunnel->
            requests.add(Triple(mime,secure,tunnel));original
        })
        assertEquals(listOf(first,second,software),selector.getDecoderInfos(MimeTypes.VIDEO_H265,true,true))
        assertEquals(listOf(first,second,software),selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION,false,false))
        assertSame(original,selector.getDecoderInfos(MimeTypes.AUDIO_AAC,false,false))
        assertEquals(Triple(MimeTypes.VIDEO_H265,true,true),requests.first())
    }

    @Test fun aSharedCodecNameCanStillRetryWithHevcConfiguration() {
        val native=MediaCodecInfo.newInstance("shared.decoder",MimeTypes.VIDEO_DOLBY_VISION,
            MimeTypes.VIDEO_DOLBY_VISION,null,true,false,true,false,false)
        val hevc=MediaCodecInfo.newInstance("shared.decoder",MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_H265,null,true,false,true,false,false)
        assertEquals(listOf(native,hevc),distinctDecoderConfigurations(listOf(native,hevc,hevc)))
        val software=MediaCodecInfo.newInstance("software.dv",MimeTypes.VIDEO_DOLBY_VISION,
            MimeTypes.VIDEO_DOLBY_VISION,null,false,true,false,false,false)
        assertEquals(listOf(native,hevc,software),distinctDecoderConfigurations(listOf(native,software,hevc)))
    }
}
