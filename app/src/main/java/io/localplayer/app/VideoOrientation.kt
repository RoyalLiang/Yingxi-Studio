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
