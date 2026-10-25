package io.localplayer.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import io.localplayer.core.Cancellation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AutomaticSidecars(private val context:Context):Closeable {
    companion object { val status=MutableStateFlow<Pair<String,String>?>(null) }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var cancellation:Cancellation?=null
    private var attempted:String?=null
    private val registrations=mutableMapOf<String,List<Uri>>()
    private fun cancelRequest(){job?.cancel();cancellation?.let{old->scope.launch(Dispatchers.IO){old.close()}};cancellation=null}
    fun retain(items:List<MediaItem>){
        if(items.isEmpty()){cancelRequest();attempted=null}
        val used=items.flatMap{SidecarAudio.from(it)}.map{it.uri}.toSet()
        registrations.keys.toList().forEach { id ->
            val old=registrations.getValue(id)
            old.filter{it !in used}.forEach(PlaybackRegistry::remove)
            val live=old.filter{it in used}
            if(live.isEmpty())registrations.remove(id)else registrations[id]=live
        }
    }
    fun schedule(player:Player) {
        val item=player.currentMediaItem?:run{cancelRequest();return}
        val uri=item.localConfiguration?.uri?:return
        val media=item.mediaMetadata.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}?:return
        if(SealedMedia.protects(media)&&!SealedMedia.unlocked)return
        val key=item.mediaId+"\u0000"+item.mediaMetadata.extras?.getString("play-intent")
        if(attempted==key){if(job?.isActive!=true&&player.playbackState==Player.STATE_READY)OnlineSubtitles.schedule(context,player);return}
        attempted=key;cancelRequest()
        val c=Cancellation();cancellation=c
        job=scope.launch {
            var found:SidecarSubtitles.Discovery?=null
            var attached=false
            val deadline=EnrichmentDeadlines.timer.schedule({c.close()},8,java.util.concurrent.TimeUnit.SECONDS)
            val completion=coroutineContext.job.invokeOnCompletion{if(it!=null)c.close()}
            try {
                withContext(Dispatchers.IO) { found=SidecarSubtitles.discover(context,media,uri,c,OnlineSubtitles.provider,EnrichmentPreferences(context).bilingual) }
                ensureActive();c.check()
                val current=player.currentMediaItem?:return@launch
                if(current.mediaId!=item.mediaId||current.localConfiguration?.uri!=uri||attempted!=key)return@launch
                if(SealedMedia.protects(media)&&!SealedMedia.unlocked)return@launch
                val result=found?:return@launch
                val existing=current.localConfiguration?.subtitleConfigurations.orEmpty()
                val captions=result.subtitles.mapIndexed { i,file ->
                    val mime=when(file.extension){"ass","ssa"->MimeTypes.TEXT_SSA;"vtt"->MimeTypes.TEXT_VTT;else->MimeTypes.APPLICATION_SUBRIP}
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(mime).setLabel(result.subtitleNames[i])
                        .setLanguage(sidecarLanguage(result.subtitleNames[i]))
                        .setSelectionFlags(if(i==0&&existing.isEmpty())C.SELECTION_FLAG_DEFAULT else 0).build()
                }
                val previous=registrations[item.mediaId].orEmpty().toSet()
                val audio=(SidecarAudio.from(current).filter{it.uri !in previous}+result.audio).distinctBy{it.uri}.take(8)
                if(captions.isNotEmpty()||audio!=SidecarAudio.from(current)) {
                    val update=SidecarAudio.attach(current,audio).buildUpon().setSubtitleConfigurations((existing+captions).distinctBy{it.uri}).build()
                    player.replaceMediaItem(player.currentMediaItemIndex,update)
                    registrations.remove(item.mediaId)?.forEach(PlaybackRegistry::remove)
                    val used=audio.map{it.uri}.toSet()
                    result.registered.filter{it !in used}.forEach(PlaybackRegistry::remove)
                    registrations[item.mediaId]=result.registered.filter{it in used}
                    attached=true
                    status.value=item.mediaId to "同目录已载入：字幕 ${captions.size} · 音轨 ${result.audio.size}"
                }
            } catch(e:CancellationException){throw e}
            catch(_:Exception){if(player.currentMediaItem?.mediaId==item.mediaId)status.value=item.mediaId to "同目录轨道暂未载入，可手动选择文件"}
            finally {
                if(!attached)found?.registered?.forEach(PlaybackRegistry::remove)
                deadline.cancel(false);completion.dispose()
                withContext(NonCancellable+Dispatchers.IO){c.close()}
                if(cancellation===c)cancellation=null
                if(isActive&&player.playbackState==Player.STATE_READY&&player.currentMediaItem?.mediaId==item.mediaId)OnlineSubtitles.schedule(context,player)
            }
        }
    }
    override fun close(){job?.cancel();cancellation?.let{c->EnrichmentDeadlines.timer.execute{c.close()}};scope.cancel();registrations.values.flatten().forEach(PlaybackRegistry::remove);registrations.clear();status.value=null}
}
