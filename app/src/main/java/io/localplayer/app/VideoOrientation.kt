package io.localplayer.app

import android.content.pm.ActivityInfo
import androidx.media3.common.VideoSize

/** Display sizes already include rotation; container metadata must apply it explicitly. */
internal fun videoOrientation(size:VideoSize):Int? = videoOrientation(size.width,size.height,0,size.pixelWidthHeightRatio)

internal fun videoOrientation(width:Int,height:Int,rotation:Int,pixelRatio:Float):Int? {
    if(width<=0||height<=0||!pixelRatio.isFinite()||pixelRatio<=0)return null
    var displayWidth=width.toDouble()*pixelRatio
    var displayHeight=height.toDouble()
    if(rotation%180!=0){val swap=displayWidth;displayWidth=displayHeight;displayHeight=swap}
    return if(displayWidth>displayHeight)ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}

/** Container dimensions are available before a decoder produces its first frame. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoOrientation(player:androidx.media3.common.Player):Int? {
    return videoOrientation(player.currentTracks)?:videoOrientation(player.videoSize)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun videoOrientation(tracks:androidx.media3.common.Tracks):Int? {
    val groups=tracks.groups.filter{it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}.sortedByDescending{it.isSelected}
    for(group in groups)for(i in (0 until group.length).sortedByDescending{group.isTrackSelected(it)}) {
        val format=group.getTrackFormat(i)
        videoOrientation(format.width,format.height,format.rotationDegrees,format.pixelWidthHeightRatio)?.let{return it}
    }
    return null
}

/** Direction decision separated from Activity side effects so stale transition data can be tested. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackOrientationState {
    private var mediaId:String?=null
    private var direction:Int?=null
    var hasMetadata=false;private set
    fun restoreSnapshot(player:androidx.media3.common.Player){
        mediaId=player.currentMediaItem?.mediaId
        direction=videoOrientation(player.currentTracks)
        hasMetadata=direction!=null
    }
    fun update(player:androidx.media3.common.Player,events:androidx.media3.common.Player.Events?,hint:Int?):Int? {
        val current=player.currentMediaItem?.mediaId
        if(current!=mediaId){mediaId=current;direction=hint;hasMetadata=false}
        else if(direction==null)direction=hint
        // A transition can still expose the previous clip's size until fresh metadata arrives.
        val freshSize=if(events?.contains(androidx.media3.common.Player.EVENT_VIDEO_SIZE_CHANGED)==true)videoOrientation(player.videoSize)else null
        val freshTracks=if(events?.contains(androidx.media3.common.Player.EVENT_TRACKS_CHANGED)==true)videoOrientation(player.currentTracks)else null
        if(freshSize!=null||freshTracks!=null)hasMetadata=true
        direction=freshSize?:freshTracks?:direction
        return direction
    }
}
