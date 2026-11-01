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

    @Test fun extractionReportRecordsProgressWithoutRetainingTheSourceName(){
        PlaybackMetrics.reset()
        val extractor=PlaybackExtractors(delegate=factory(Probe()),sourceName="private-account-private-film.mkv").createExtractors().single()
        val source=input(byteArrayOf(1,2,3))
        assertTrue(extractor.sniff(source))
        source.resetPeekPosition()
        assertEquals(Extractor.RESULT_END_OF_INPUT,extractor.read(source,PositionHolder()))
        val report=org.json.JSONObject(playbackDiagnostic(null))
        assertEquals("MATROSKA",report.getString("containerHint"))
        assertEquals("OTHER",report.getString("extractor"))
        assertEquals("END",report.getString("extractorPhase"))
        assertEquals(0L,report.getLong("extractorPosition"))
        assertEquals(1L,report.getLong("extractorSniffCalls"))
        assertEquals(1L,report.getLong("extractorReadCalls"))
        assertFalse(report.toString().contains("private-"))
        PlaybackMetrics.reset()
        assertEquals("IDLE",PlaybackExtractionProbe.snapshot().phase)
        assertNull(PlaybackExtractionProbe.snapshot().position)
    }

    @Test fun sidecarExtractorCannotReplaceThePrimaryVideoProbe(){
        PlaybackMetrics.reset()
        val owner=Uri.parse("content://fixture/video")
        val extractors=PlaybackExtractors(delegate=factory(Probe()),sourceName="film.mkv",diagnosticUri=owner)
        assertTrue(extractors.createExtractors(Uri.parse("content://fixture/audio"),emptyMap()).single().sniff(input(byteArrayOf(1,2,3))))
        assertEquals("IDLE",PlaybackExtractionProbe.snapshot().phase)
        assertTrue(extractors.createExtractors(owner,emptyMap()).single().sniff(input(byteArrayOf(1,2,3))))
        assertEquals("MATROSKA",PlaybackExtractionProbe.snapshot().containerHint)
    }

    @Test fun queuedSourcesAndLateReadsCannotReplaceTheActiveGeneration(){
        val first=Uri.parse("content://fixture/first");val next=Uri.parse("content://fixture/next")
        PlaybackMetrics.reset(first)
        val entered=java.util.concurrent.CountDownLatch(1);val finish=java.util.concurrent.CountDownLatch(1)
        val blocked=object:Extractor by Probe(){
            override fun read(input:ExtractorInput,seekPosition:PositionHolder):Int{
                entered.countDown();check(finish.await(5,java.util.concurrent.TimeUnit.SECONDS));seekPosition.position=9000
                return Extractor.RESULT_SEEK
            }
        }
        val blockedFactory=object:ExtractorsFactory{override fun createExtractors()=arrayOf<Extractor>(blocked)}
        val active=PlaybackExtractors(delegate=blockedFactory,sourceName="first.mkv",diagnosticUri=first).createExtractors(first,emptyMap()).single()
        val queued=PlaybackExtractors(delegate=factory(Probe()),sourceName="next.mp4",diagnosticUri=next).createExtractors(next,emptyMap()).single()
        active.sniff(input(byteArrayOf(1,2,3)))
        queued.sniff(input(byteArrayOf(1,2,3)))
        assertEquals("MATROSKA",PlaybackExtractionProbe.snapshot().containerHint)
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            val oldRead=worker.submit<Int>{active.read(input(byteArrayOf(1,2,3)),PositionHolder())}
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            PlaybackMetrics.reset(next);queued.sniff(input(byteArrayOf(1,2,3)))
            val expected=PlaybackExtractionProbe.snapshot()
            finish.countDown();oldRead.get(2,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(expected,PlaybackExtractionProbe.snapshot())
        }finally{finish.countDown();worker.shutdownNow();PlaybackMetrics.reset()}
    }
}
