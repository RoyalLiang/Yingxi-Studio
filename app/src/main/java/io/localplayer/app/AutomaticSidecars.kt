package io.localplayer.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable

/** Same-directory tracks are prepared by the media source before playback starts. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AutomaticSidecars(private val context:Context):Closeable {
    companion object {
        val status=MutableStateFlow<Pair<String,String>?>(null)
        private val registrations=java.util.concurrent.ConcurrentHashMap<String,List<android.net.Uri>>()
        private fun owner(item:MediaItem)=item.mediaId+"\u0000"+item.mediaMetadata.extras?.getString("play-intent")
        fun register(item:MediaItem,uris:List<android.net.Uri>){registrations.compute(owner(item)){_,old->old.orEmpty()+uris}}
    }
    private var owners=emptySet<String>()
    fun retain(items:List<MediaItem>){
        val live=items.map(::owner).toSet()
        (owners-live).forEach{registrations.remove(it)?.forEach(PlaybackRegistry::remove)}
        owners=live
        if(items.isEmpty())status.value=null
    }
    fun schedule(player:Player){if(player.playbackState==Player.STATE_READY)OnlineSubtitles.schedule(context,player)}
    override fun close(){owners.forEach{registrations.remove(it)?.forEach(PlaybackRegistry::remove)};owners=emptySet();status.value=null}
}
