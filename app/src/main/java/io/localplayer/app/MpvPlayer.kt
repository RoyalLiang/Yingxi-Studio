package io.localplayer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.*
import androidx.media3.common.util.Size
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import java.util.concurrent.Executors

/** DV profile 5 only: software RPU decoding + libplacebo reshaping and BT.709 SDR output. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MpvPlayer(private val context:Context,private val sourceFormat:Format,private val openInput:(Context,android.net.Uri)->MpvInput={c,u->MpvInput.open(c,u)}):SimpleBasePlayer(Looper.getMainLooper()) {
    private val handler=Handler(Looper.getMainLooper())
    private var runtime:MpvRuntime?=null
    private var input:MpvInput?=null
    private class Opening {
        var engine:MpvRuntime?=null
        var input:MpvInput?=null
        var disposed=false
        fun close(){if(!disposed){disposed=true;input?.close();runCatching{engine?.destroy()}}}
    }
    private var opening:Opening?=null
    private val subtitleInputs=mutableListOf<MpvInput>()
    private var generation=0
    private var released=false
    private var items=listOf<MediaItem>()
    private var index=0
    private var position=0L
    private var openingSince=0L
    private var duration=C.TIME_UNSET
    private var buffered=0L
    private var wanted=false
    private var state=Player.STATE_IDLE
    private var failure:PlaybackException?=null
    private var speed=PlaybackParameters.DEFAULT
    private var gain=1f
    private var selection=TrackSelectionParameters.Builder(context).build()
    private var tracks=Tracks.EMPTY
    private var video=VideoSize.UNKNOWN
    private var subtitleRequested=false
    private var firstFrame=false
    private var firstFrameEvent=false
    private var output:Any?=null
    private var surface:Surface?=null
    private var attachedSurface:Surface?=null
    private var surfaceSize=Size.UNKNOWN
    private var suppressed=false
    private var focusHeld=false
    private val audio=context.getSystemService(AudioManager::class.java)
    private val wake=context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"yingxi:dv5-sdr").apply{setReferenceCounted(false)}
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({change->
            when(change){
                AudioManager.AUDIOFOCUS_GAIN->{suppressed=false;syncPlay();invalidateState()}
                AudioManager.AUDIOFOCUS_LOSS->{wanted=false;focusHeld=false;suppressed=false;syncPlay();invalidateState()}
                else->{suppressed=true;syncPlay();invalidateState()}
            }
        },handler).build()
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){if(i?.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY)pause()}}
    init {context.registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED)}
    private val surfaceCallback=object:SurfaceHolder.Callback {
        override fun surfaceCreated(holder:SurfaceHolder){attach(holder.surface,holder.surfaceFrame.width(),holder.surfaceFrame.height())}
        override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){attach(holder.surface,width,height)}
        override fun surfaceDestroyed(holder:SurfaceHolder){detach();surfaceSize=Size.ZERO;invalidateState()}
    }
    private val poll=object:Runnable {override fun run(){if(released||runtime==null)return;refresh();handler.postDelayed(this,150)}}

    override fun getState():State {
        val playlist=items.mapIndexed { i,item->MediaItemData.Builder(item.mediaId.ifEmpty{"mpv-$i"})
            .setMediaItem(item).setIsSeekable(true).setDurationUs(if(i==index&&duration!=C.TIME_UNSET)duration*1000 else C.TIME_UNSET)
            .setTracks(if(i==index)tracks else Tracks.EMPTY).build() }
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,Player.COMMAND_PREPARE,Player.COMMAND_STOP,Player.COMMAND_RELEASE,
            Player.COMMAND_SET_MEDIA_ITEM,Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TRACKS,Player.COMMAND_GET_TEXT,Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_GET_VOLUME,Player.COMMAND_SET_VOLUME,Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT,Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        ).build()).setPlaylist(playlist).setCurrentMediaItemIndex(if(items.isEmpty())C.INDEX_UNSET else index)
            .setPlaybackState(state).setPlayWhenReady(wanted,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(if(suppressed)Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setPlayerError(failure).setIsLoading(state==Player.STATE_BUFFERING)
            .setContentPositionMs(position).setContentBufferedPositionMs(PositionSupplier.getConstant(maxOf(position,buffered)))
            .setTotalBufferedDurationMs(PositionSupplier.getConstant((buffered-position).coerceAtLeast(0)))
            .setPlaybackParameters(speed).setVolume(gain).setTrackSelectionParameters(selection)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build())
            .setVideoSize(video).setSurfaceSize(surfaceSize).setNewlyRenderedFirstFrame(firstFrameEvent.also{firstFrameEvent=false})
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build()
    }
    override fun handleSetMediaItems(mediaItems:List<MediaItem>,startIndex:Int,startPositionMs:Long):ListenableFuture<*> {
        stopRuntime();items=mediaItems;index=startIndex.coerceIn(0,(items.size-1).coerceAtLeast(0));position=startPositionMs.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        duration=C.TIME_UNSET;buffered=position;failure=null;state=Player.STATE_IDLE;tracks=Tracks.EMPTY;video=VideoSize.UNKNOWN;firstFrame=false
        return done()
    }
    override fun handlePrepare():ListenableFuture<*> {
        if(items.isEmpty()||runtime!=null||opening!=null)return done()
        val item=items[index];val uri=item.localConfiguration?.uri?:return done()
        val current=++generation;state=Player.STATE_BUFFERING;failure=null
        val resources=Opening();opening=resources
        worker.execute {
            try {
                val opened=openInput(context,uri).also{resources.input=it}
                val engine=MpvRuntime.load(context);engine.create(context);resources.engine=engine
                mapOf("config" to "no","vo" to "gpu-next","gpu-context" to "android","gpu-api" to "opengl","opengl-es" to "yes",
                    "hwdec" to "no","target-prim" to "bt.709","target-trc" to "srgb","target-peak" to "100",
                    "idle" to "yes","keep-open" to "yes","pause" to "yes","force-window" to "no",
                    "cache" to "yes","demuxer-max-bytes" to "67108864","demuxer-readahead-secs" to "15",
                    "audio-fallback-to-null" to "no","msg-level" to "all=no").forEach{(k,v)->engine.option(k,v)}
                engine.init()
                handler.post {
                    if(released||generation!=current)return@post
                    runtime=engine;input=opened;opening=null;openingSince=android.os.SystemClock.elapsedRealtime()
                    surface?.takeIf{it.isValid}?.let{attach(it,surfaceSize.width,surfaceSize.height)}
                    engine.command("loadfile",opened.path,"replace","-1","start=${position/1000.0}")
                    engine.set("speed",speed.speed.toString());engine.set("volume",(gain*100).toString());handleSetTrackSelectionParameters(selection)
                    handler.post(poll)
                }
            }catch(e:Throwable){
                resources.close()
                handler.post{if(!released&&generation==current){opening=null;failure=PlaybackException("SDR 兼容播放无法打开此文件",e,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false;syncPlay();invalidateState()}}
            }
        }
        return done()
    }
    private fun loadSubtitles(item:MediaItem,engine:MpvRuntime,current:Int){
        val captions=item.localConfiguration?.subtitleConfigurations.orEmpty()
        if(captions.isEmpty())return
        worker.execute {
            captions.forEachIndexed{index,caption->
                try {
                    val opened=MpvInput.open(context,caption.uri)
                    handler.post {
                        if(released||generation!=current||runtime!==engine){worker.execute{opened.close()};return@post}
                        subtitleInputs+=opened
                        engine.command("sub-add",opened.path,if(index==0)"select" else "auto",caption.label?:"",caption.language?:"")
                    }
                }catch(_:Exception){handler.post{if(!released&&generation==current)OnlineSubtitles.status.value=item.mediaId to "字幕文件无法读取"}}
            }
        }
    }
    private fun refresh(){
        val m=runtime?:return
        try {
            input?.failure?.let{throw java.io.IOException("远程文件读取中断",it)}
            if(!firstFrame&&openingSince>0&&android.os.SystemClock.elapsedRealtime()-openingSince>30000)
                throw java.io.IOException("SDR 兼容解码未能生成画面")
            val seconds=m.get("time-pos")?.toDoubleOrNull()
            if(seconds!=null)position=(seconds*1000).toLong().coerceAtLeast(0)
            duration=m.get("duration")?.toDoubleOrNull()?.let{(it*1000).toLong()}?:duration
            buffered=m.get("demuxer-cache-time")?.toDoubleOrNull()?.let{(it*1000).toLong()}?:position
            val width=m.get("video-out-params/w")?.toIntOrNull()?:0
            val height=m.get("video-out-params/h")?.toIntOrNull()?:0
            if(width>0&&height>0){
                video=VideoSize(width,height)
                tracks=readTracks(m,width,height)
                val frame=m.get("video-frame-info/picture-type")
                if(!frame.isNullOrBlank()&&m.get("vo-configured")=="yes"&&!firstFrame){firstFrame=true;firstFrameEvent=true;PlaybackMetrics.sdrFrame()}
                if(firstFrame&&!subtitleRequested){
                    subtitleRequested=true
                    items.getOrNull(index)?.let{loadSubtitles(it,m,generation)}
                }
                state=if(!firstFrame||m.get("paused-for-cache")=="yes")Player.STATE_BUFFERING else Player.STATE_READY
            }
            if(m.get("eof-reached")=="yes"){
                if(!firstFrame)throw java.io.IOException("SDR 兼容解码未能生成画面")
                state=Player.STATE_ENDED;wanted=false
            }
            if(m.get("idle-active")=="yes"&&width==0){failure=PlaybackException("SDR 兼容解码未能打开视频画面",null,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false}
            PlaybackMetrics.sdrOutput(m.get("frame-drop-count")?.toIntOrNull()?:0)
            syncPlay();invalidateState()
        }catch(e:Exception){failure=PlaybackException("SDR 兼容播放中断",e,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false;syncPlay();invalidateState()}
    }
    private fun readTracks(m:MpvRuntime,width:Int,height:Int):Tracks {
        val groups=mutableListOf<Tracks.Group>()
        groups+=Tracks.Group(TrackGroup("mpv-video",sourceFormat.buildUpon().setWidth(width).setHeight(height).build()),false,intArrayOf(C.FORMAT_HANDLED),booleanArrayOf(true))
        val list=runCatching{JSONArray(m.get("track-list")?:"[]")}.getOrDefault(JSONArray())
        for(i in 0 until list.length()){
            val t=list.getJSONObject(i);val type=t.optString("type")
            if(type !in setOf("audio","sub"))continue
            val id=t.optInt("id");val mime=if(type=="audio")MimeTypes.AUDIO_RAW else MimeTypes.APPLICATION_SUBRIP
            val format=Format.Builder().setId(id.toString()).setSampleMimeType(mime).setLanguage(t.optString("lang").ifBlank{null}).setLabel(t.optString("title").ifBlank{null}).build()
            groups+=Tracks.Group(TrackGroup("mpv-$type-$id",format),false,intArrayOf(C.FORMAT_HANDLED),booleanArrayOf(t.optBoolean("selected")))
        }
        return Tracks(ImmutableList.copyOf(groups))
    }
    override fun handleSetTrackSelectionParameters(parameters:TrackSelectionParameters):ListenableFuture<*> {
        selection=parameters
        runtime?.let{m->
            m.set("sub-visibility",if(parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))"no" else "yes")
            parameters.overrides.values.forEach{o->
                val id=o.mediaTrackGroup.id
                if(id.startsWith("mpv-audio-"))m.set("aid",id.removePrefix("mpv-audio-"))
                if(id.startsWith("mpv-sub-"))m.set("sid",id.removePrefix("mpv-sub-"))
            }
            m.set("alang",parameters.preferredAudioLanguages.joinToString(","));m.set("slang",parameters.preferredTextLanguages.joinToString(","))
        }
        return done()
    }
    override fun handleSetPlayWhenReady(playWhenReady:Boolean):ListenableFuture<*>{wanted=playWhenReady;syncPlay();return done()}
    private fun syncPlay(){
        val active=wanted&&state in setOf(Player.STATE_READY,Player.STATE_BUFFERING)
        if(active&&!focusHeld){focusHeld=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;suppressed=!focusHeld}
        if(!active&&focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false;suppressed=false}
        val playing=wanted&&!suppressed&&state!=Player.STATE_IDLE&&state!=Player.STATE_ENDED
        runtime?.set("pause",if(playing&&firstFrame)"no" else "yes")
        if(playing&&!wake.isHeld)wake.acquire(30*60*1000L)else if(!playing&&wake.isHeld)wake.release()
    }
    override fun handleSetPlaybackParameters(playbackParameters:PlaybackParameters):ListenableFuture<*>{speed=playbackParameters;runtime?.set("speed",speed.speed.toString());return done()}
    override fun handleSetVolume(volume:Float):ListenableFuture<*>{gain=volume;runtime?.set("volume",(gain*100).toString());return done()}
    override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*>{
        if(mediaItemIndex==index){position=positionMs.coerceAtLeast(0);runtime?.command("seek",(position/1000.0).toString(),"absolute+exact");if(state==Player.STATE_ENDED)state=Player.STATE_READY}
        return done()
    }
    override fun handleSetVideoOutput(videoOutput:Any):ListenableFuture<*> {
        clearOutput();output=videoOutput
        when(videoOutput){
            is SurfaceView->{videoOutput.holder.addCallback(surfaceCallback);attach(videoOutput.holder.surface,videoOutput.width,videoOutput.height)}
            is SurfaceHolder->{videoOutput.addCallback(surfaceCallback);attach(videoOutput.surface,videoOutput.surfaceFrame.width(),videoOutput.surfaceFrame.height())}
            is Surface->attach(videoOutput,1,1)
        }
        return done()
    }
    override fun handleClearVideoOutput(videoOutput:Any?):ListenableFuture<*>{if(videoOutput==null||videoOutput===output)clearOutput();return done()}
    private fun attach(value:Surface,width:Int,height:Int){
        if(!value.isValid)return
        val changed=attachedSurface!==value
        if(changed&&attachedSurface!=null)detach()
        surface=value;surfaceSize=Size(width.coerceAtLeast(1),height.coerceAtLeast(1))
        runtime?.let{
            if(changed){it.attach(value);attachedSurface=value}
            it.set("android-surface-size","${surfaceSize.width}x${surfaceSize.height}")
            if(changed){it.set("vo","gpu-next");it.set("force-window","yes")}
        }
    }
    private fun detach(){runtime?.let{it.set("vo","null");if(attachedSurface!=null)it.detach()};attachedSurface=null;surface=null}
    private fun clearOutput(){when(val value=output){is SurfaceView->value.holder.removeCallback(surfaceCallback);is SurfaceHolder->value.removeCallback(surfaceCallback)};output=null;detach()}
    override fun handleStop():ListenableFuture<*>{stopRuntime();state=Player.STATE_IDLE;syncPlay();return done()}
    override fun handleRelease():ListenableFuture<*>{released=true;clearOutput();stopRuntime();wanted=false;syncPlay();runCatching{context.unregisterReceiver(noisy)};return done()}
    private fun stopRuntime(){
        generation++;handler.removeCallbacks(poll)
        if(attachedSurface!=null){runtime?.let{it.set("vo","null");it.detach()}}
        attachedSurface=null;firstFrame=false;firstFrameEvent=false;subtitleRequested=false
        val m=runtime;val source=input;val pending=opening;val captions=subtitleInputs.toList();subtitleInputs.clear();runtime=null;input=null;opening=null
        worker.execute{pending?.close();source?.close();captions.forEach{it.close()};runCatching{m?.destroy()}}
    }
    private fun done():ListenableFuture<*> = Futures.immediateVoidFuture()
    companion object {private val worker=Executors.newSingleThreadExecutor()}
}
