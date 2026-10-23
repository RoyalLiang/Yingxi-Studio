package io.localplayer.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.localplayer.core.AliyunVideoInfo
import io.localplayer.core.Cancellation
import io.localplayer.core.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun playbackQuality(item:MediaItem?):String = item?.localConfiguration?.uri?.takeIf{it.scheme==CLOUD_STREAM_SCHEME}?.getQueryParameter("quality")?:"original"
internal fun cloudQualityContext(item:MediaItem?):PlaybackLease? = item?.localConfiguration?.uri?.takeIf{it.isPlayerNetworkUri()}?.let{runCatching{PlaybackRegistry.get(it)}.getOrNull()}?.takeIf{it.source.protocol==Protocol.ALIYUN}

/** Keep canonical IDs, metadata/history tokens, queue, speed and play intent across a source change. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun changePlaybackQuality(player:Player,selection:String) {
    require(selection in listOf("original","auto","LD","SD","HD","FHD","QHD"))
    val index=player.currentMediaItemIndex
    if(index !in 0 until player.mediaItemCount)return
    val current=player.getMediaItemAt(index)
    val canonical=Uri.parse(current.mediaId)
    require(canonical.scheme=="localplayer"&&cloudQualityContext(current)!=null)
    val uri=if(selection=="original")canonical else canonical.buildUpon().scheme(CLOUD_STREAM_SCHEME).clearQuery().appendQueryParameter("quality",selection).build()
    val replacement=current.buildUpon().setUri(uri).setMimeType(if(selection=="original")null else MimeTypes.APPLICATION_M3U8).build()
    reloadPlaybackSource(player,replacement)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun reloadPlaybackSource(player:Player,replacement:MediaItem?=null){
    val index=player.currentMediaItemIndex
    if(index !in 0 until player.mediaItemCount)return
    val queue=(0 until player.mediaItemCount).map{if(it==index&&replacement!=null)replacement else player.getMediaItemAt(it)}
    val position=player.currentPosition.coerceAtLeast(0)
    val playing=player.playWhenReady
    player.setMediaItems(queue,index,position);player.prepare();player.playWhenReady=playing
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CloudQualityChoices(player:Player,item:MediaItem?,loadInfo:(PlaybackLease,Cancellation)->AliyunVideoInfo={target,cancellation->AliyunCloud.videoInfo(target.source,target.path,cancellation)}) {
    val target=cloudQualityContext(item)?:return
    val scope=rememberCoroutineScope()
    val cancellation=remember(item?.mediaId){Cancellation()}
    var info by remember(item?.mediaId){mutableStateOf<AliyunVideoInfo?>(null)}
    var loading by remember{mutableStateOf(false)}
    var failure by remember(item?.mediaId){mutableStateOf<String?>(null)}
    val selected=playbackQuality(item)
    DisposableEffect(cancellation){onDispose{cancellation.close()}}
    Column(Modifier.fillMaxWidth().testTag("cloud-quality-options"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("云盘画质 · 本集",fontSize=17.sp)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            val choices=listOf("original" to "原画")+(if(info?.masterUrl!=null||info?.variants.orEmpty().size>=2)listOf("auto" to "自动")else emptyList())+info?.variants.orEmpty().map{it.template to it.template}
            choices.forEach{(value,label)->
                FilterChip(selected=selected==value,onClick={if(selected!=value)changePlaybackQuality(player,value)},label={Text(label)},modifier=Modifier.testTag("cloud-quality-$value"))
            }
        }
        if(selected!="original"&&info==null)Text("当前：${if(selected=="auto")"自动"else selected}",fontSize=13.sp,color=PlayerInk)
        TextButton(enabled=!loading,onClick={
            loading=true;failure=null
            scope.launch{
                try {
                    info=withContext(Dispatchers.IO){loadInfo(target,cancellation)}
                    if(info?.variants.isNullOrEmpty()&&info?.masterUrl==null)failure="平台暂无可用清晰度，可继续原画播放。"
                }catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(_:Exception){failure="暂时无法读取平台清晰度，可继续原画播放。"}
                finally{loading=false}
            }
        },modifier=Modifier.testTag("load-cloud-quality")){Text(if(loading)"读取中…"else"查看平台清晰度")}
        failure?.let{Text(it,fontSize=13.sp,lineHeight=19.sp,color=PlayerInk)}
    }
}

internal class CloudStreamRetryGate(private val now:()->Long=SystemClock::elapsedRealtime) {
    private var identity:String?=null
    private val attempts=ArrayDeque<Long>()
    fun allow(mediaId:String):Boolean {
        if(identity!=mediaId){identity=mediaId;attempts.clear()}
        val at=now();while(attempts.isNotEmpty()&&at-attempts.first()>=60_000)attempts.removeFirst()
        if(attempts.size>=2)return false
        attempts.addLast(at);return true
    }
}
/** Renew a complete HLS source on signed-CDN rejection; never splice signatures into old URLs. */
internal class CloudStreamRecovery(private val player:Player):java.io.Closeable {
    private val gate=CloudStreamRetryGate()
    private val handler=Handler(Looper.getMainLooper())
    private val listener=object:Player.Listener {
        override fun onPlayerError(error:PlaybackException){
            val item=player.currentMediaItem?:return
            if(item.localConfiguration?.uri?.scheme!=CLOUD_STREAM_SCHEME)return
            val status=generateSequence<Throwable>(error){it.cause}.take(12).filterIsInstance<CloudStreamReadFailure>().firstOrNull()?.status
            if(status !in listOf(401,403)||!gate.allow(item.mediaId))return
            val index=player.currentMediaItemIndex
            handler.post {
                if(player.currentMediaItem?.mediaId!=item.mediaId||player.currentMediaItemIndex!=index||player.currentMediaItem?.localConfiguration?.uri!=item.localConfiguration?.uri||player.playerError!==error)return@post
                reloadPlaybackSource(player)
            }
        }
    }
    init{player.addListener(listener)}
    override fun close(){handler.removeCallbacksAndMessages(null);player.removeListener(listener)}
}
