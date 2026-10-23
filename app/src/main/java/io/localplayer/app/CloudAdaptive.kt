package io.localplayer.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import io.localplayer.core.AliyunVideoInfo
import java.io.IOException

/** Session-only IDs, not URLs or guessed bitrates. The provider owns the actual alternatives. */
internal class CloudAdaptiveState(private val now:()->Long=SystemClock::elapsedRealtime) {
    private var templates=emptyList<String>()
    private var selected:String?=null
    private var master=false
    private var lastDown:Long?=null
    @Volatile var loaded=false;private set
    @Synchronized fun resolve(info:AliyunVideoInfo):String {
        loaded=true
        info.masterUrl?.let{master=true;templates=emptyList();selected=null;return it}
        master=false
        templates=info.variants.map{it.template}
        if(templates.isEmpty())throw IOException("平台暂无完整播放档位，可选择原画")
        if(selected !in templates){
            // Prefer a provider's middle tier; do not infer its pixel count or bandwidth.
            val previous=selected
            val order=listOf("QHD","FHD","HD","SD","LD")
            selected=if(previous==null)templates.firstOrNull{it=="HD"}?:templates.first()
                else templates.firstOrNull{order.indexOf(it)>=order.indexOf(previous)}?:templates.last()
        }
        return info.resolve(selected!!)
    }
    @Synchronized fun canStepDown()=!master&&templates.indexOf(selected).let{it>=0&&it<templates.lastIndex}
    @Synchronized fun stepDown():Boolean {
        if(!canStepDown())return false
        val at=now();if(lastDown?.let{at-it<15_000}==true)return false
        selected=templates[templates.indexOf(selected)+1];lastDown=at;return true
    }
    @Synchronized fun currentTemplate()=if(master)"master"else selected
}

/** Variant-only sources degrade after observed buffering; a real master stays with Media3 ABR. */
internal class CloudAdaptiveController(private val player:Player):java.io.Closeable {
    private val handler=Handler(Looper.getMainLooper())
    private var mediaId:String?=null
    private var rendered=false
    private val check=object:Runnable{
        override fun run(){
            val item=player.currentMediaItem?:return
            if(item.mediaId!=mediaId||!player.playWhenReady||player.playbackState!=Player.STATE_BUFFERING||playbackQuality(item)!="auto")return
            val state=cloudQualityContext(item)?.adaptive?:return
            if(state.stepDown()){
                reloadPlaybackSource(player)
                schedule()
            }else if(!state.loaded||state.canStepDown())handler.postDelayed(this,3000)
        }
    }
    private fun schedule(){
        handler.removeCallbacks(check)
        if(player.playWhenReady&&player.playbackState==Player.STATE_BUFFERING&&playbackQuality(player.currentMediaItem)=="auto")handler.postDelayed(check,if(rendered)3000 else 8000)
    }
    private val listener=object:Player.Listener{
        override fun onRenderedFirstFrame(){rendered=true}
        override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int){if(mediaId!=item?.mediaId){mediaId=item?.mediaId;rendered=false};schedule()}
        override fun onPlaybackStateChanged(state:Int){schedule()}
        override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){schedule()}
    }
    init{player.addListener(listener)}
    override fun close(){handler.removeCallbacksAndMessages(null);player.removeListener(listener)}
}
