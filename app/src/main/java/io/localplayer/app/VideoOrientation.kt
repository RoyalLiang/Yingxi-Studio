package io.localplayer.app

import android.content.pm.ActivityInfo
import androidx.media3.common.VideoSize

/** Use display dimensions, Media3 already applies the stream rotation; include anamorphic pixels. */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoOrientation(size:VideoSize):Int? {
    if(size.width<=0||size.height<=0||!size.pixelWidthHeightRatio.isFinite()||size.pixelWidthHeightRatio<=0)return null
    var width=size.width.toDouble()*size.pixelWidthHeightRatio
    var height=size.height.toDouble()
    if(size.unappliedRotationDegrees%180!=0){val swap=width;width=height;height=swap}
    return if(width>height)ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}

/** Container dimensions are available before a decoder produces its first frame. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoOrientation(player:androidx.media3.common.Player):Int? {
    return videoOrientation(player.currentTracks)?:videoOrientation(player.videoSize)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoOrientation(tracks:androidx.media3.common.Tracks):Int? {
    val groups=tracks.groups.filter{it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}.sortedByDescending{it.isSelected}
    for(group in groups)for(i in 0 until group.length) {
        val format=group.getTrackFormat(i)
        videoOrientation(VideoSize(format.width,format.height,format.rotationDegrees,format.pixelWidthHeightRatio))?.let{return it}
    }
    return null
}

/** Direction decision separated from Activity side effects so stale transition data can be tested. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackOrientationState {
    private var mediaId:String?=null
    private var direction:Int?=null
    fun restoreSnapshot(player:androidx.media3.common.Player){
        mediaId=player.currentMediaItem?.mediaId
        direction=videoOrientation(player.currentTracks)
    }
    fun update(player:androidx.media3.common.Player,events:androidx.media3.common.Player.Events?,hint:Int?):Int? {
        val current=player.currentMediaItem?.mediaId
        if(current!=mediaId){mediaId=current;direction=hint}
        else if(direction==null)direction=hint
        // A transition can still expose the previous clip's size until fresh metadata arrives.
        val freshSize=if(events?.contains(androidx.media3.common.Player.EVENT_VIDEO_SIZE_CHANGED)==true)videoOrientation(player.videoSize)else null
        val freshTracks=if(events?.contains(androidx.media3.common.Player.EVENT_TRACKS_CHANGED)==true)videoOrientation(player.currentTracks)else null
        direction=freshSize?:freshTracks?:direction
        return direction
    }
}
