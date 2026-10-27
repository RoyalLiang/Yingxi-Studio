package io.localplayer.app

import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.text.*
import java.util.concurrent.Executors

/** Stable text tracks let attachment changes reselect text without discarding A/V periods. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MutableSubtitleSource(private var item:MediaItem,private val data:DataSource.Factory,
    private val parsers:SubtitleParser.Factory):BaseMediaSource() {
    companion object {
        const val PREFIX="attached-text-"
        const val CAPACITY=32
        private val worker=Executors.newFixedThreadPool(2)
        private val closer=Executors.newCachedThreadPool()
        private val defaults=DefaultSubtitleParserFactory()
        fun input(c:MediaItem.SubtitleConfiguration)=Format.Builder().setSampleMimeType(c.mimeType).setLanguage(c.language)
            .setLabel(c.label).setSelectionFlags(c.selectionFlags).setRoleFlags(c.roleFlags).build()
        fun captions(item:MediaItem?)=item?.localConfiguration?.subtitleConfigurations.orEmpty().filter{defaults.supportsFormat(input(it))}
        fun index(c:MediaItem.SubtitleConfiguration,i:Int,parsers:SubtitleParser.Factory=defaults)=
            i*2+if(parsers.getCueReplacementBehavior(input(c))==Format.CUE_REPLACEMENT_BEHAVIOR_MERGE)0 else 1
    }
    private val initialCaptions=captions(item)
    private val group=TrackGroup(PREFIX+java.util.UUID.randomUUID(),*Array(CAPACITY*2){ i ->
        val initial=initialCaptions.getOrNull(i/2)
        val format=initial?.let(::input)?:Format.Builder().build()
        format.buildUpon().setId("$i").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCueReplacementBehavior(if(i%2==0)Format.CUE_REPLACEMENT_BEHAVIOR_MERGE else Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .setSubsampleOffsetUs(0).build()
    })
    private var handler:Handler?=null
    private var epoch=0
    private var snapshots=emptyList<List<CuesWithTiming>?>()
    private val pending=mutableListOf<Pair<java.util.concurrent.Future<*>,DataSource>>()
    override fun getMediaItem()=item
    override fun canUpdateMediaItem(mediaItem:MediaItem)=true
    override fun updateMediaItem(mediaItem:MediaItem){item=mediaItem;if(handler!=null)load()}
    override fun prepareSourceInternal(mediaTransferListener:TransferListener?){
        handler=Handler(Looper.myLooper()!!);load()
        refreshSourceInfo(SinglePeriodTimeline(C.TIME_UNSET,true,false,false,null,item))
    }
    private fun load(){
        val current=++epoch
        pending.forEach{(task,source)->task.cancel(true);closer.execute{runCatching{source.close()}}};pending.clear()
        val captions=item.localConfiguration?.subtitleConfigurations.orEmpty().filter{parsers.supportsFormat(input(it))}
        require(captions.size<=CAPACITY){"Too many subtitle tracks"}
        snapshots=List(captions.size){null}
        captions.forEachIndexed { i,caption ->
            val source=data.createDataSource()
            val task=worker.submit {
                val cues=runCatching {
                    val bytes=DataSourceInputStream(source,DataSpec(caption.uri)).use{it.readNBytes(8*1024*1024+1)}
                    require(bytes.size<=8*1024*1024){"Subtitle too large"}
                    val result=mutableListOf<CuesWithTiming>()
                    parsers.create(input(caption)).parse(bytes,SubtitleParser.OutputOptions.allCues()){result.add(it)}
                    result.sortedBy{it.startTimeUs}
                }.getOrDefault(emptyList())
                handler?.post{if(epoch==current)snapshots=snapshots.toMutableList().also{it[i]=cues}}
            }
            pending+=task to source
        }
    }
    override fun maybeThrowSourceInfoRefreshError(){}
    override fun createPeriod(id:MediaSource.MediaPeriodId,allocator:Allocator,startPositionUs:Long):MediaPeriod=Period()
    override fun releasePeriod(mediaPeriod:MediaPeriod){}
    override fun releaseSourceInternal(){epoch++;handler=null;pending.forEach{(task,source)->task.cancel(true);closer.execute{runCatching{source.close()}}};pending.clear();snapshots=emptyList()}
    private inner class Period:MediaPeriod {
        private val active=mutableListOf<Stream>()
        override fun prepare(callback:MediaPeriod.Callback,positionUs:Long){callback.onPrepared(this)}
        override fun maybeThrowPrepareError(){}
        override fun getTrackGroups()=TrackGroupArray(group)
        override fun selectTracks(selections:Array<out ExoTrackSelection?>,mayRetainStreamFlags:BooleanArray,
            streams:Array<SampleStream?>,streamResetFlags:BooleanArray,positionUs:Long):Long {
            for(i in selections.indices){
                val selected=selections[i]?.getIndexInTrackGroup(0)
                val old=streams[i] as? Stream
                if(old!=null&&(selected!=old.track||!mayRetainStreamFlags[i])){active.remove(old);streams[i]=null}
                if(selected!=null&&streams[i]==null){streams[i]=Stream(selected,positionUs).also(active::add);streamResetFlags[i]=true}
            }
            return positionUs
        }
        override fun discardBuffer(positionUs:Long,toKeyframe:Boolean){}
        override fun readDiscontinuity()=C.TIME_UNSET
        override fun seekToUs(positionUs:Long):Long{active.forEach{it.seek(positionUs)};return positionUs}
        override fun getAdjustedSeekPositionUs(positionUs:Long,seekParameters:SeekParameters)=positionUs
        override fun getBufferedPositionUs()=C.TIME_END_OF_SOURCE
        override fun getNextLoadPositionUs()=C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo:LoadingInfo)=false
        override fun isLoading()=false
        override fun reevaluateBuffer(positionUs:Long){}
    }
    private inner class Stream(val track:Int,private var position:Long):SampleStream {
        private var formatSent=false
        private var at=0
        private var initialized=false
        private val encoder=CueEncoder()
        fun seek(positionUs:Long){position=positionUs;at=0;initialized=false}
        private fun cues():List<CuesWithTiming>? = if(track/2>=snapshots.size)emptyList() else snapshots[track/2]
        private fun initialize(cues:List<CuesWithTiming>){
            if(!initialized){at=cues.indexOfFirst{it.endTimeUs>position}.let{if(it<0)cues.size else it};initialized=true}
        }
        override fun isReady()=cues()!=null
        override fun maybeThrowError(){}
        override fun readData(holder:FormatHolder,buffer:DecoderInputBuffer,readFlags:Int):Int {
            if(!formatSent||readFlags and SampleStream.FLAG_REQUIRE_FORMAT!=0){holder.format=group.getFormat(track);formatSent=true;return C.RESULT_FORMAT_READ}
            val cues=cues()?:return C.RESULT_NOTHING_READ
            initialize(cues)
            if(at>=cues.size){buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);return C.RESULT_BUFFER_READ}
            val cue=cues[at];buffer.timeUs=cue.startTimeUs;buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if(readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA==0){val bytes=encoder.encode(cue.cues,cue.durationUs);buffer.ensureSpaceForWrite(bytes.size);buffer.data!!.put(bytes)}
            if(readFlags and SampleStream.FLAG_PEEK==0)at++
            return C.RESULT_BUFFER_READ
        }
        override fun skipData(positionUs:Long):Int {val cues=cues()?:return 0;initialize(cues);val before=at;while(at<cues.size&&cues[at].endTimeUs<=positionUs)at++;return at-before}
    }
}
