package io.localplayer.app

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun posterVideoFormat(media:MediaRef):String? {
    val supported=setOf("mp4","mkv","m4v","mov","webm","avi","ts","m2ts","mpg","mpeg","wmv","flv","3gp")
    return listOfNotNull(media.title,media.path,media.localUri?.let{Uri.parse(it).lastPathSegment})
        .map{it.substringBefore('?').substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)}
        .firstOrNull{it in supported}?.uppercase(java.util.Locale.ROOT)
}

/** Only a real video extension is overlaid; directories and unknown formats stay unmarked. */
@Composable internal fun PosterLabels(media:MediaRef,directory:Boolean=false) {
    val format=if(directory)null else posterVideoFormat(media)
    if(format!=null)Text(format,Modifier.padding(8.dp).testTag("poster-labels:${media.key}"),
        color=Color.White,fontSize=11.sp,lineHeight=15.sp,fontWeight=FontWeight.Medium,maxLines=1)
}

@Composable internal fun MediaPoster(media:MediaRef,modifier:Modifier=Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).testTag("media-poster:${media.key}")) {
        MediaThumbnail(media,Modifier.fillMaxSize())
        PosterLabels(media)
    }
}
