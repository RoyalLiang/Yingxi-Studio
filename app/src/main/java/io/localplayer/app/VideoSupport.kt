package io.localplayer.app

import androidx.media3.common.C
import androidx.media3.common.Tracks

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun unsupportedVideo(tracks:Tracks):Boolean {
    val video=tracks.groups.filter{it.type==C.TRACK_TYPE_VIDEO}
    // Media3 may select a track above advertised performance limits; only actual decoder errors can reject it.
    return video.isNotEmpty()&&video.none{it.isSupported(true)}&&video.none{it.isSelected}
}
