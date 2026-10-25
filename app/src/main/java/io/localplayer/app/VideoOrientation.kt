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
    val groups=player.currentTracks.groups.filter{it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}.sortedByDescending{it.isSelected}
    for(group in groups)for(i in 0 until group.length) {
        val format=group.getTrackFormat(i)
        videoOrientation(VideoSize(format.width,format.height,format.rotationDegrees,format.pixelWidthHeightRatio))?.let{return it}
    }
    return videoOrientation(player.videoSize)
}
