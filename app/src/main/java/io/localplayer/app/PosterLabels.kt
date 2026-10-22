package io.localplayer.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun posterVideoFormat(media:MediaRef):String? {
    val supported=setOf("mp4","mkv","m4v","mov","webm","avi","ts","m2ts","mpg","mpeg","wmv","flv","3gp")
    return listOfNotNull(media.title,media.path,media.localUri?.let{Uri.parse(it).lastPathSegment})
        .map{it.substringBefore('?').substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)}
        .firstOrNull{it in supported}?.uppercase(java.util.Locale.ROOT)
}

/** Real source/file facts only; folders never claim an aggregate size or video format. */
@Composable internal fun PosterLabels(media:MediaRef,directory:Boolean=false,reserveMenu:Boolean=true,compact:Boolean=false) {
    val details=if(directory)emptyList()else listOfNotNull(posterVideoFormat(media),
        media.sizeBytes?.takeIf{it>0&&!compact}?.let(::formatSize))
    Column(Modifier.fillMaxWidth().padding(start=8.dp,top=8.dp,end=if(reserveMenu)48.dp else 8.dp)
        .background(Color.Black.copy(alpha=.66f),RoundedCornerShape(7.dp)).padding(horizontal=6.dp,vertical=4.dp)
        .semantics(mergeDescendants=true){}.testTag("poster-labels:${media.key}"),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(media.sourceLabel,color=Color.White,fontSize=11.sp,lineHeight=15.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(details.isNotEmpty())Text(details.joinToString(" · "),color=Color.White.copy(alpha=.9f),fontSize=11.sp,lineHeight=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable internal fun MediaPoster(media:MediaRef,modifier:Modifier=Modifier,compact:Boolean=false,reserveMenu:Boolean=false) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).testTag("media-poster:${media.key}")) {
        MediaThumbnail(media,Modifier.fillMaxSize())
        PosterLabels(media,reserveMenu=reserveMenu,compact=compact)
    }
}
