package io.localplayer.app

import io.localplayer.core.mediaDisplayTitle

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.concurrent.ConcurrentHashMap

// No credentials or complete playlist cross an Activity's Binder transaction.
data class QueuedMedia(val uri:Uri,val media:MediaRef,val token:HistoryToken)
data class PlayTarget(val uri:Uri,val title:String,val resume:Long,val media:MediaRef,
    val historyToken:HistoryToken?=null,val playId:String=java.util.UUID.randomUUID().toString(),
    val queue:List<QueuedMedia> = emptyList(),val truncated:Boolean=false){
    fun release(){(queue.map{it.uri}+uri).distinct().forEach{PlaybackRegistry.remove(it)}}
}

/** Retains only an in-process request; process death requires reopening the source. */
object PlaybackRequests {
    const val MAX_ITEMS=200
    private val requests=ConcurrentHashMap<String,PlayTarget>()
    @Volatile private var activeIds=emptySet<String>()
    fun retainActive(ids:Set<String>){activeIds=ids}
    fun releaseUnused(ids:Collection<String>){ids.filterNot{it in activeIds}.forEach(::remove)}
    fun put(target:PlayTarget){requests[target.playId]=target}
    fun get(id:String?)=id?.let{requests[it]}
    fun remove(id:String?){id?.let{requests.remove(it)}?.release()}
    fun clear(){activeIds=emptySet();requests.keys.toList().forEach(::remove)}
    fun <T> window(items:List<T>,selected:T):List<T>{
        val index=items.indexOf(selected)
        if(index<0)return listOf(selected)
        val start=(index-MAX_ITEMS/2).coerceIn(0,(items.size-MAX_ITEMS).coerceAtLeast(0))
        return items.subList(start,(start+MAX_ITEMS).coerceAtMost(items.size))
    }
    fun items(context:Context,target:PlayTarget):List<MediaItem>{
        val history=HistoryStore(context)
        val queue=target.queue.ifEmpty{listOf(QueuedMedia(target.uri,target.media,target.historyToken ?: history.token(target.media.key)))}
        return queue.map{entry->
            val extras=Bundle().apply{
                putString("media-ref",entry.media.json());putLong("history-clear",entry.token.clearRevision)
                putLong("history-item",entry.token.itemRevision);putString("play-intent",target.playId)
                putBoolean("queue-truncated",target.truncated)
            }
            MediaItem.Builder().setUri(entry.uri).setMediaId(entry.uri.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(mediaDisplayTitle(entry.media.title)).setExtras(extras).build()).build()
        }
    }
}
