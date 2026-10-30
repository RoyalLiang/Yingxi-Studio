package io.localplayer.app

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.common.DataReader
import androidx.media3.extractor.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackExtractorStartupTest {
    private class Probe:Extractor {
        var sniffed=false
        override fun sniff(input:ExtractorInput):Boolean {sniffed=true;val first=ByteArray(1);input.peekFully(first,0,1);return first[0]==1.toByte()}
        override fun init(output:ExtractorOutput){}
        override fun read(input:ExtractorInput,seekPosition:PositionHolder)=Extractor.RESULT_END_OF_INPUT
        override fun seek(position:Long,timeUs:Long){}
        override fun release(){}
    }
    private fun input(bytes:ByteArray,onRead:()->Unit={}):DefaultExtractorInput {
        var position=0
        return DefaultExtractorInput(DataReader{target,offset,length->
            onRead()
            if(position==bytes.size)-1 else minOf(length,bytes.size-position).also{bytes.copyInto(target,offset,position,position+it);position+=it}
        },0,bytes.size.toLong())
    }
    private fun factory(probe:Probe,onUri:(Uri,Map<String,List<String>>)->Unit={_,_->})=object:ExtractorsFactory {
        override fun createExtractors()=arrayOf<Extractor>(probe)
        override fun createExtractors(uri:Uri,responseHeaders:Map<String,List<String>>):Array<Extractor>{onUri(uri,responseHeaders);return createExtractors()}
    }
    @Test fun asfHeaderHandsOverBeforeScanningTheContainer(){
        val header=byteArrayOf(0x30,0x26,0xb2.toByte(),0x75,0x8e.toByte(),0x66,0xcf.toByte(),0x11,0xa6.toByte(),0xd9.toByte(),0,0xaa.toByte(),0,0x62,0xce.toByte(),0x6c)
        val probe=Probe();var reads=0;val input=input(header+ByteArray(65536)){reads++}
        try{PlaybackExtractors(delegate=factory(probe)).createExtractors().single().sniff(input);fail("ASF needs compatibility")}
        catch(expected:ParserException){assertFalse(expected.contentIsMalformed)}
        assertFalse(probe.sniffed);assertEquals(1,reads);assertEquals(0L,input.peekPosition)
    }
    @Test fun filenameIsOnlyAnOrderingHintAndShortNonAsfInputStillSniffs(){
        val probe=Probe();val uri=Uri.parse("content://fixture/document/opaque-id");val headers=mapOf("Content-Type" to listOf("application/octet-stream"))
        val extractors=PlaybackExtractors(delegate=factory(probe){hint,actualHeaders->assertEquals("clip.wmv",hint.lastPathSegment);assertEquals(headers,actualHeaders)},sourceName="clip.wmv")
        assertTrue(extractors.createExtractors(uri,headers).single().sniff(input(byteArrayOf(1,2,3))))
        assertTrue(probe.sniffed);assertEquals("opaque-id",uri.lastPathSegment)
    }
}
