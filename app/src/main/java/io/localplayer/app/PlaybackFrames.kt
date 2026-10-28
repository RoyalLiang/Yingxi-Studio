package io.localplayer.app

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Transient UI receipt; never persisted or included in diagnostic reports. */
internal object PlaybackFrames {
    data class Frame(val sequence:Long=0,val key:String="")
    private val rendered=MutableStateFlow(Frame())
    val last=rendered.asStateFlow()
    fun rendered(item:MediaItem?){rendered.value=Frame(rendered.value.sequence+1,key(item))}
    fun key(item:MediaItem?):String=item?.mediaMetadata?.extras?.getString("play-intent")
        ?:item?.mediaId?.takeIf{it.isNotEmpty()}?:item?.localConfiguration?.uri?.toString().orEmpty()
}
