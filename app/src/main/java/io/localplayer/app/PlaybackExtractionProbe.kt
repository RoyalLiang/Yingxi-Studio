package io.localplayer.app

import java.util.Locale
import android.net.Uri

/** Reports expose fixed vocabulary and byte offsets, never source identities or payloads. */
internal object PlaybackExtractionProbe {
    enum class Kind { UNKNOWN,OTHER,MATROSKA,MP4,MPEG_TS,MPEG_PS,AVI,FLV,OGG,FLAC,WAV,MP3,AAC,AC3,AC4,AMR }
    data class Snapshot(val containerHint:String="UNKNOWN",val extractor:String="UNKNOWN",
        val phase:String="IDLE",val position:Long?=null,val sniffCalls:Long=0,
        val readCalls:Long=0,val seeks:Long=0)
    private var state=Snapshot()
    private var generation=0L
    private var owner:Uri?=null
    @Synchronized fun reset(active:Uri?=null){generation++;owner=active;state=Snapshot()}
    @Synchronized fun snapshot()=state
    @Synchronized fun sniff(kind:Kind,sourceName:String?,position:Long,source:Uri?=null):Long? = start(kind,sourceName,position,source,true)
    @Synchronized fun reading(kind:Kind,sourceName:String?,position:Long,source:Uri?=null):Long? = start(kind,sourceName,position,source,false)
    private fun start(kind:Kind,sourceName:String?,position:Long,source:Uri?,sniff:Boolean):Long?{
        if(owner!=null&&owner!=source)return null
        val hint=when(sourceName?.substringAfterLast('.',"")?.lowercase(Locale.ROOT)){
            "mkv","webm"->"MATROSKA";"mp4","m4v","mov"->"MP4"
            "ts","m2ts","mts"->"MPEG_TS";"avi"->"AVI";"wmv","asf"->"ASF"
            else->"UNKNOWN"
        }
        state=state.copy(containerHint=hint,extractor=kind.name,phase=if(sniff)"SNIFF"else"READ",position=position,
            sniffCalls=state.sniffCalls+if(sniff)1 else 0,readCalls=state.readCalls+if(sniff)0 else 1)
        return generation
    }
    @Synchronized fun read(token:Long?,position:Long,seek:Boolean,end:Boolean){
        if(token==null||token!=generation)return
        state=state.copy(phase=if(seek)"SEEK"else if(end)"END"else"READ",position=position,seeks=state.seeks+if(seek)1 else 0)
    }
}
