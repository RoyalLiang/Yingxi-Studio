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
import android.view.TextureView
import android.graphics.SurfaceTexture
import androidx.media3.common.*
import androidx.media3.common.util.Size
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import java.util.concurrent.Executors

private val compatibilityVideoDecoders="hevc,h264,vp8,vp9,mpeg1video,mpeg2video,mpeg4,h263,wmv1,wmv2,wmv3,vc1,rv10,rv20,rv30,rv40,theora,mjpeg".split(',')

/** Compatibility decoding preserves Dolby Vision RPU and uses BT.709 SDR output. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MpvPlayer(private val context:Context,private val sourceFormat:Format,private val openInput:(Context,android.net.Uri)->MpvInput={c,u->MpvInput.open(c,u)}):SimpleBasePlayer(Looper.getMainLooper()) {
    private val handler=Handler(Looper.getMainLooper())
    private var runtime:MpvRuntime?=null
    private class Engine(val runtime:MpvRuntime){var alive=true;var surface:Surface?=null}
    private var engine:Engine?=null
    private var pendingLoad:(()->Unit)?=null
    private fun enqueue(action:(MpvRuntime)->Unit){
        val owner=engine?:return
        worker.execute { if(owner.alive)try{action(owner.runtime)}catch(error:Exception){handler.post{
            if(!released&&engine===owner){failure=PlaybackException("SDR 兼容播放中断",error,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false;syncPlay();invalidateState()}
        }} }
    }
    private class Texture(val texture:SurfaceTexture){
        val surface=Surface(texture)
        @Volatile var retired=false
        private var detached=false
        private var destroyed=false
        @Synchronized fun destroyed():Boolean {if(detached)return true;destroyed=true;return false}
        @Synchronized fun release(){surface.release();detached=true;if(destroyed)texture.release()}
    }
    private var texture:Texture?=null
    private var pollGeneration:Int?=null
    private var sampleRevision=0
    private var fillVideo=false
    private var subtitleMarginX=25
    private var subtitleMarginY=22
    internal fun setVideoLayout(fill:Boolean,marginX:Int,marginY:Int) {
        fillVideo=fill;subtitleMarginX=marginX;subtitleMarginY=marginY
        enqueue { it.set("panscan",if(fill)"1"else"0");it.set("sub-margin-x",marginX.toString());it.set("sub-margin-y",marginY.toString()) }
    }

    private var input:MpvInput?=null
    private class Opening {
        var engine:MpvRuntime?=null
        var input:MpvInput?=null
        var disposed=false
        fun close(){if(!disposed){disposed=true;input?.close();runCatching{engine?.destroy()}}}
    }
    private var opening:Opening?=null
    private val externalInputs=mutableMapOf<Pair<String,android.net.Uri>,MpvInput>()
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
    private var tracks=readTracks("[]",sourceFormat.width,sourceFormat.height)
    private val initialVideo=when {
        sourceFormat.width<=0||sourceFormat.height<=0->VideoSize.UNKNOWN
        sourceFormat.rotationDegrees%180!=0->VideoSize(sourceFormat.height,sourceFormat.width,1f/sourceFormat.pixelWidthHeightRatio)
        else->VideoSize(sourceFormat.width,sourceFormat.height,sourceFormat.pixelWidthHeightRatio)
    }
    private var video=initialVideo
    private var decodedWidth=0
    private var decodedHeight=0
    private var nextMetadataPoll=0L
    private var trackSignature:String?=null
    private var pauseApplied:Boolean?=null
    private var subtitleRequested=false
    private var firstFrame=false
    private var firstFrameEvent=false
    private var output:Any?=null
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
    private val retiring=java.util.IdentityHashMap<SurfaceTexture,Texture>()
    private fun textureCallback(view:TextureView)=object:TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(value:SurfaceTexture,width:Int,height:Int){
            if(output!==view)return
            retireTexture();texture=Texture(value);attach(width,height)
        }
        override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,width:Int,height:Int){if(output===view&&texture?.texture===value)attach(width,height)}
        override fun onSurfaceTextureUpdated(value:SurfaceTexture){}
        override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean {
            val owned=texture?.takeIf{it.texture===value}?:retiring.remove(value)?:return true
            val releaseByView=owned.destroyed()
            if(texture===owned){retireTexture();surfaceSize=Size.ZERO;invalidateState()}
            return releaseByView
        }
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
        stopRuntime();items=mediaItems.map{softwareSdrItem(it,true)};index=startIndex.coerceIn(0,(items.size-1).coerceAtLeast(0));position=startPositionMs.takeIf{it!=C.TIME_UNSET}?.coerceAtLeast(0)?:0
        duration=C.TIME_UNSET;buffered=position;failure=null;state=Player.STATE_IDLE;tracks=readTracks("[]",sourceFormat.width,sourceFormat.height);video=initialVideo;firstFrame=false
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
                    "keepaspect" to "yes","panscan" to if(fillVideo)"1"else"0","blend-subtitles" to "no",
                    "sub-use-margins" to "yes","sub-ass-force-margins" to "yes","sub-margin-x" to subtitleMarginX.toString(),"sub-margin-y" to subtitleMarginY.toString(),
                    "vd" to (compatibilityVideoDecoders.joinToString(",")+",-"),
                    "hwdec" to "no","target-prim" to "bt.709","target-trc" to "srgb","target-peak" to "100",
                    "idle" to "yes","keep-open" to "yes","pause" to "yes","force-window" to "no",
                    "cache" to "yes","demuxer-max-bytes" to "67108864","demuxer-readahead-secs" to "15",
                    "access-references" to "no","audio-fallback-to-null" to "no","msg-level" to "all=no").forEach{(k,v)->engine.option(k,v)}
                if(sourceFormat.sampleMimeType==MimeTypes.VIDEO_DOLBY_VISION) {
                    // Two decoded frames overlap HEVC work with GPU mapping without changing RPU or scaling.
                    engine.option("vd-queue-enable","yes")
                    engine.option("vd-queue-max-samples","2")
                    engine.option("vd-queue-max-bytes","64MiB")
                }
                engine.init()
                handler.post {
                    if(released||generation!=current)return@post
                    runtime=engine;this.engine=Engine(engine);input=opened;opening=null;openingSince=android.os.SystemClock.elapsedRealtime()
                    pendingLoad={
                        val start=position;val rate=speed.speed;val volume=gain
                        enqueue{it.command("loadfile",opened.path,"replace","-1","start=${start/1000.0}");it.set("speed",rate.toString());it.set("volume",(volume*100).toString())}
                        handleSetTrackSelectionParameters(selection);handler.post(poll)
                    }
                    attach(surfaceSize.width,surfaceSize.height)
                }
            }catch(e:Throwable){
                resources.close()
                handler.post{if(!released&&generation==current){opening=null;failure=PlaybackException("SDR 兼容播放无法打开此文件",e,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false;syncPlay();invalidateState()}}
            }
        }
        return done()
    }
    internal fun updateAttachments(mediaItems:List<MediaItem>) {
        items=mediaItems.map{softwareSdrItem(it,true)}
        runtime?.let { engine ->
            sampleRevision++
            val item=items.getOrNull(index)
            val keep=item?.localConfiguration?.subtitleConfigurations.orEmpty().map{ "sub-add" to it.uri }.toSet()+
                item?.let{SidecarAudio.from(it).map{audio->"audio-add" to audio.uri}}.orEmpty()
            val removed=externalInputs.filterKeys{it !in keep}
            removed.keys.forEach(externalInputs::remove)
            enqueue { m ->
                val list=runCatching{JSONArray(m.get("track-list")?:"[]")}.getOrDefault(JSONArray())
                removed.forEach { (key,input) ->
                    for(i in 0 until list.length()) {
                        val track=list.getJSONObject(i)
                        if(track.optBoolean("external")&&track.optString("external-filename")==input.path)
                            m.command(if(key.first=="sub-add")"sub-remove"else"audio-remove",track.optInt("id").toString())
                    }
                    input.close()
                }
            }
            subtitleRequested=false;nextMetadataPoll=0
        }
        invalidateState()
    }
    private fun loadAttachments(item:MediaItem,engine:MpvRuntime,current:Int){
        data class Extra(val uri:android.net.Uri,val command:String,val select:String,val label:String,val language:String)
        val captions=item.localConfiguration?.subtitleConfigurations.orEmpty().mapIndexed { i,c ->
            Extra(c.uri,"sub-add",if(i==0)"select"else"auto",c.label?:"",c.language?:"")
        }
        val files=(captions+SidecarAudio.from(item).map { Extra(it.uri,"audio-add","auto",it.name,sidecarLanguage(it.name)?:"") }).filter{(it.command to it.uri) !in externalInputs}
        if(files.isEmpty())return
        attachments.execute {
            files.forEach { file -> try {
                val opened=MpvInput.open(context,file.uri)
                handler.post {
                    if(released||generation!=current||runtime!==engine){worker.execute{opened.close()};return@post}
                    val item=items.getOrNull(index)
                    val retained=if(file.command=="sub-add")item?.localConfiguration?.subtitleConfigurations.orEmpty().any{it.uri==file.uri}else item?.let{SidecarAudio.from(it).any{it.uri==file.uri}}==true
                    if(!retained){worker.execute{opened.close()};return@post}
                    externalInputs[file.command to file.uri]=opened;nextMetadataPoll=0
                    enqueue{it.command(file.command,opened.path,file.select,file.label,file.language)}
                }
            } catch(_:Exception) { handler.post {
                if(!released&&generation==current)AutomaticSidecars.status.value=item.mediaId to "外挂轨道无法读取，可重新选择文件"
            } } }
        }
    }
    private fun refresh(){
        val m=runtime?:return
        if(pollGeneration!=null)return
        val current=generation
        val revision=sampleRevision
        val now=android.os.SystemClock.elapsedRealtime()
        val metadata=decodedWidth==0||now>=nextMetadataPoll
        if(metadata)nextMetadataPoll=now+1000
        val sourceFailure=input?.failure
        val keys=mutableListOf("time-pos","demuxer-cache-time","paused-for-cache","eof-reached","idle-active")
        if(metadata)keys+=listOf("duration","video-out-params","track-list","container-fps","frame-drop-count","decoder-frame-drop-count","avsync")
        if(!firstFrame)keys+=listOf("video-frame-info/picture-type","vo-configured")
        pollGeneration=current
        // Synchronous mpv getters can wait on its core; keep them off the application looper.
        worker.execute {
            val start=android.os.SystemClock.elapsedRealtimeNanos()
            val snapshot=runCatching {
                sourceFailure?.let{throw java.io.IOException("远程文件读取中断",it)}
                keys.associateWith{m.get(it)}
            }
            val elapsed=android.os.SystemClock.elapsedRealtimeNanos()-start
            handler.post {
                if(pollGeneration==current)pollGeneration=null
                if(released||generation!=current||runtime!==m||sampleRevision!=revision)return@post
                applySnapshot(m,snapshot,metadata,elapsed)
            }
        }
    }
    private fun applySnapshot(m:MpvRuntime,snapshot:Result<Map<String,String?>>,metadata:Boolean,readNanos:Long){
        val applyStart=android.os.SystemClock.elapsedRealtimeNanos()
        try {
            val values=snapshot.getOrThrow()
            val now=android.os.SystemClock.elapsedRealtime()
            if(!firstFrame&&openingSince>0&&now-openingSince>30000)
                throw java.io.IOException("SDR 兼容解码未能生成画面")
            values["time-pos"]?.toDoubleOrNull()?.let{position=(it*1000).toLong().coerceAtLeast(0)}
            buffered=values["demuxer-cache-time"]?.toDoubleOrNull()?.let{(it*1000).toLong()}?:position
            // Container/track metadata does not need a synchronous JNI roundtrip every UI tick.
            if(metadata){
                duration=values["duration"]?.toDoubleOrNull()?.let{(it*1000).toLong()}?:duration
                val params=runCatching{org.json.JSONObject(values["video-out-params"]?:"{}")}.getOrNull()
                val width=params?.optInt("w")?:0
                val height=params?.optInt("h")?:0
                if(width>0&&height>0){
                    val dw=params?.optInt("dw")?.takeIf{it>0}?:width
                    val dh=params?.optInt("dh")?.takeIf{it>0}?:height
                    val rotation=params?.optInt("rotate")?:0
                    video=if(Math.floorMod(rotation,180)==90)VideoSize(dh,dw)else VideoSize(dw,dh)
                    val json=values["track-list"]?:"[]"
                    if(json!=trackSignature||width!=decodedWidth||height!=decodedHeight){
                        tracks=readTracks(json,width,height,rotation,params?.optDouble("par",1.0)?.toFloat()?:1f,values["container-fps"]?.toFloatOrNull()?:sourceFormat.frameRate);trackSignature=json
                    }
                    decodedWidth=width;decodedHeight=height
                }
                PlaybackMetrics.sdrOutput(values["frame-drop-count"]?.toIntOrNull()?:0,
                    values["decoder-frame-drop-count"]?.toIntOrNull(),
                    values["avsync"]?.toDoubleOrNull()?.let{(it*1000).toLong()})
            }
            if(!firstFrame&&!values["video-frame-info/picture-type"].isNullOrBlank()&&values["vo-configured"]=="yes"){
                firstFrame=true;firstFrameEvent=true;nextMetadataPoll=0;PlaybackMetrics.sdrFrame()
            }
            if(firstFrame&&!subtitleRequested){subtitleRequested=true;items.getOrNull(index)?.let{loadAttachments(it,m,generation)}}
            state=if(!firstFrame||values["paused-for-cache"]=="yes")Player.STATE_BUFFERING else Player.STATE_READY
            if(values["eof-reached"]=="yes"){
                if(!firstFrame)throw java.io.IOException("SDR 兼容解码未能生成画面")
                state=Player.STATE_ENDED;wanted=false
            }
            if(decodedWidth==0&&values["idle-active"]=="yes"){
                failure=PlaybackException("SDR 兼容解码未能打开视频画面",null,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false
            }
            syncPlay();invalidateState()
        }catch(e:Exception){failure=PlaybackException("SDR 兼容播放中断",e,PlaybackException.ERROR_CODE_DECODING_FAILED);state=Player.STATE_IDLE;wanted=false;syncPlay();invalidateState()}
        finally{PlaybackMetrics.sdrPoll(readNanos,android.os.SystemClock.elapsedRealtimeNanos()-applyStart)}
    }
    private fun readTracks(json:String,width:Int,height:Int,rotation:Int=sourceFormat.rotationDegrees,pixelRatio:Float=sourceFormat.pixelWidthHeightRatio,frameRate:Float=sourceFormat.frameRate):Tracks {
        val groups=mutableListOf<Tracks.Group>()
        val list=runCatching{JSONArray(json)}.getOrDefault(JSONArray())
        val videos=(0 until list.length()).map{list.getJSONObject(it)}.filter{it.optString("type")=="video"&&it.optInt("id")>0&&!it.optBoolean("image")&&!it.optBoolean("albumart")&&!it.optBoolean("dependent")}
        for(t in videos){
            val id=t.getInt("id");val selected=t.optBoolean("selected")
            val codec=t.optString("codec")
            val mime=if(t.optInt("dolby-vision-profile")>0)MimeTypes.VIDEO_DOLBY_VISION else when(codec){
                "h264"->MimeTypes.VIDEO_H264;"hevc"->MimeTypes.VIDEO_H265;"av1"->MimeTypes.VIDEO_AV1
                "vp8"->MimeTypes.VIDEO_VP8;"vp9"->MimeTypes.VIDEO_VP9;"mpeg4"->MimeTypes.VIDEO_MP4V
                "mpeg1video"->MimeTypes.VIDEO_MPEG;"mpeg2video"->MimeTypes.VIDEO_MPEG2;"h263"->MimeTypes.VIDEO_H263
                "vc1"->"video/wvc1";"wmv3"->"video/x-ms-wmv";"mjpeg"->"video/mjpeg";"theora"->"video/theora"
                else->"video/x-unknown"
            }
            val builder=if(videos.size==1)sourceFormat.buildUpon()else Format.Builder().setSampleMimeType(mime)
            val rate=t.optInt("demux-bitrate",-1).takeIf{it>0}?:if(videos.size==1)sourceFormat.averageBitrate else -1
            val format=builder.setId(id.toString()).setAverageBitrate(rate)
                .setWidth(t.optInt("demux-w",if(selected)width else -1)).setHeight(t.optInt("demux-h",if(selected)height else -1))
                .setRotationDegrees(t.optInt("demux-rotation",if(selected)rotation else 0))
                .setPixelWidthHeightRatio(t.optDouble("demux-par",if(selected)pixelRatio.toDouble()else 1.0).toFloat())
                .setFrameRate(t.optDouble("demux-fps",if(selected)frameRate.toDouble()else -1.0).toFloat()).build()
            val support=if(codec in compatibilityVideoDecoders)C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
            groups+=Tracks.Group(TrackGroup("mpv-video-$id",format),false,intArrayOf(support),booleanArrayOf(selected))
        }
        if(videos.isEmpty())groups+=Tracks.Group(TrackGroup("mpv-video",sourceFormat.buildUpon().setWidth(width).setHeight(height).setRotationDegrees(rotation).setPixelWidthHeightRatio(pixelRatio).setFrameRate(frameRate).build()),false,intArrayOf(C.FORMAT_HANDLED),booleanArrayOf(true))
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
        selection=parameters;nextMetadataPoll=0
        val available=tracks
        enqueue{m->
            m.set("sub-visibility",if(parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))"no" else "yes")
            m.set("alang",parameters.preferredAudioLanguages.joinToString(","));m.set("slang",parameters.preferredTextLanguages.joinToString(","))
            val video=parameters.overrides.values.firstOrNull{override->override.type==C.TRACK_TYPE_VIDEO&&available.groups.any{it.mediaTrackGroup==override.mediaTrackGroup&&it.isTrackSupported(0)}}?.mediaTrackGroup?.id?.takeIf{it.startsWith("mpv-video-")}?.removePrefix("mpv-video-")
            m.set("vid",video?:"auto")
            for((type,property,prefix) in listOf(Triple(C.TRACK_TYPE_AUDIO,"aid","mpv-audio-"),Triple(C.TRACK_TYPE_TEXT,"sid","mpv-sub-"))) {
                val id=parameters.overrides.values.firstOrNull{it.mediaTrackGroup.id.startsWith(prefix)}?.mediaTrackGroup?.id?.removePrefix(prefix)
                m.set(property,if(type in parameters.disabledTrackTypes)"no"else id?:"auto")
            }
        }
        return done()
    }
    override fun handleSetPlayWhenReady(playWhenReady:Boolean):ListenableFuture<*>{wanted=playWhenReady;syncPlay();return done()}
    private fun syncPlay(){
        val active=wanted&&state in setOf(Player.STATE_READY,Player.STATE_BUFFERING)
        if(active&&!focusHeld){focusHeld=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;suppressed=!focusHeld}
        if(!active&&focusHeld){audio.abandonAudioFocusRequest(focus);focusHeld=false;suppressed=false}
        val playing=wanted&&!suppressed&&state!=Player.STATE_IDLE&&state!=Player.STATE_ENDED
        val pause=!(playing&&firstFrame)
        if(pauseApplied!=pause&&runtime!=null){enqueue{it.set("pause",if(pause)"yes"else"no")};pauseApplied=pause}
        if(playing&&!wake.isHeld)wake.acquire(30*60*1000L)else if(!playing&&wake.isHeld)wake.release()
    }
    override fun handleSetPlaybackParameters(playbackParameters:PlaybackParameters):ListenableFuture<*>{speed=playbackParameters;enqueue{it.set("speed",playbackParameters.speed.toString())};return done()}
    override fun handleSetVolume(volume:Float):ListenableFuture<*>{gain=volume;enqueue{it.set("volume",(volume*100).toString())};return done()}
    override fun handleSeek(mediaItemIndex:Int,positionMs:Long,seekCommand:Int):ListenableFuture<*>{
        if(mediaItemIndex==index){sampleRevision++;nextMetadataPoll=0;position=positionMs.coerceAtLeast(0);enqueue{it.command("seek",(positionMs.coerceAtLeast(0)/1000.0).toString(),"absolute+exact")};if(state==Player.STATE_ENDED)state=Player.STATE_READY}
        return done()
    }
    override fun handleSetVideoOutput(videoOutput:Any):ListenableFuture<*> {
        // Only TextureView can transfer destruction ownership without blocking the UI thread.
        require(videoOutput is TextureView){"SDR compatibility requires TextureView"}
        clearOutput();output=videoOutput
        val callback=textureCallback(videoOutput);videoOutput.surfaceTextureListener=callback
        if(videoOutput.isAvailable)videoOutput.surfaceTexture?.let{callback.onSurfaceTextureAvailable(it,videoOutput.width,videoOutput.height)}
        return done()
    }
    override fun handleClearVideoOutput(videoOutput:Any?):ListenableFuture<*>{if(videoOutput==null||videoOutput===output)clearOutput();return done()}
    private fun attach(width:Int,height:Int){
        val owned=texture?:return
        surfaceSize=Size(width.coerceAtLeast(1),height.coerceAtLeast(1));nextMetadataPoll=0
        val owner=engine?:return
        val size=surfaceSize
        worker.execute {
            if(!owner.alive||owned.retired)return@execute
            val m=owner.runtime
            if(owner.surface!==owned.surface){
                if(owner.surface!=null){m.set("vo","null");m.detach()}
                m.attach(owned.surface);owner.surface=owned.surface
                m.set("vo","gpu-next");m.set("force-window","yes")
            }
            m.set("android-surface-size","${size.width}x${size.height}")
        }
        pendingLoad?.also{pendingLoad=null}?.invoke()
    }
    private fun retireTexture(){
        val owned=texture?:return;texture=null;owned.retired=true
        retiring[owned.texture]=owned
        val owner=engine
        worker.execute {
            try { if(owner?.alive==true&&owner.surface===owned.surface){owner.runtime.set("vo","null");owner.runtime.detach();owner.surface=null} }
            finally {owned.release();handler.post{retiring.remove(owned.texture)}}
        }
    }
    private fun clearOutput(){
        val view=output as? TextureView;val listener=view?.surfaceTextureListener
        output=null;retireTexture()
        // Keep the listener until native detaches; a disappearing view transfers its texture here.
        worker.execute {handler.post{if(view!=null&&view.surfaceTextureListener===listener&&output!==view){view.surfaceTextureListener=null}}}
    }
    internal fun afterVideoDetached(callback:()->Unit){worker.execute{handler.post(callback)}}
    override fun handleStop():ListenableFuture<*>{stopRuntime();state=Player.STATE_IDLE;syncPlay();return done()}
    override fun handleRelease():ListenableFuture<*>{released=true;clearOutput();stopRuntime();wanted=false;syncPlay();runCatching{context.unregisterReceiver(noisy)};return done()}
    private fun stopRuntime(){
        pendingLoad=null
        generation++;handler.removeCallbacks(poll);pollGeneration=null;decodedWidth=0;decodedHeight=0;nextMetadataPoll=0;trackSignature=null;pauseApplied=null
        val owner=engine;engine=null
        firstFrame=false;firstFrameEvent=false;subtitleRequested=false
        val m=runtime;val source=input;val pending=opening;val captions=externalInputs.values.toList();externalInputs.clear();runtime=null;input=null;opening=null
        closer.execute{pending?.input?.close();source?.close();captions.forEach{it.close()}}
        worker.execute{pending?.close();source?.close();captions.forEach{it.close()};runCatching{
            if(owner?.alive==true){if(owner.surface!=null){m?.set("vo","null");m?.detach();owner.surface=null};m?.destroy();owner.alive=false}
        }}
    }
    private fun done():ListenableFuture<*> = Futures.immediateVoidFuture()
    companion object {
        private val worker=Executors.newSingleThreadExecutor()
        private val attachments=Executors.newSingleThreadExecutor()
        private val closer=Executors.newCachedThreadPool()
    }
}

/** Keep backend viewport information across MediaSession's track-group ID remapping. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun softwareSdrItem(item:MediaItem,enabled:Boolean):MediaItem {
    val extras=android.os.Bundle(item.mediaMetadata.extras?:android.os.Bundle())
    if(enabled)extras.putBoolean("sdr-compatibility",true)else extras.remove("sdr-compatibility")
    return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
}
