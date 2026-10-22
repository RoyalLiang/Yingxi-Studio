package io.localplayer.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A synthetic child scopes the artwork lookup to the folder; it is never opened as media. */
internal fun shelterPosterRef(source: Source, entry: Entry, parentName: String? = null) = MediaRef(
    key = if (entry.directory) "directory:${source.id}:${entry.path}" else "${source.id}:${entry.path}",
    title = entry.name, sourceLabel = source.label, sourceId = source.id,
    path = if (entry.directory) Paths.join(entry.path, ".yingxi-folder") else entry.path,
    sizeBytes = if (entry.directory) null else entry.size, location = mediaLocation(source),
    posterPathHint = listOfNotNull(parentName?.takeIf{it.isNotBlank()}, entry.name,
        if(entry.directory) ".yingxi-folder" else null).joinToString("/"))

@Composable internal fun ShelterDirectory(vm:LibraryViewModel,source:Source,selectedPath:String?,open:(Entry)->Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){MediaPosters.retryMissing()}
    key(source.id,vm.path) {
        SourceDirectory(vm.entries,source,selectedPath,vm.busy,vm.currentDirectoryBookmark()?.label,open){vm.sealRemote(source,it)}
    }
}

@Composable internal fun SourceDirectory(entries:List<Entry>,source:Source,selectedPath:String?,busy:Boolean,
    parentName:String?,open:(Entry)->Unit,seal:(Entry)->Unit) {
    val minWidth=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f)200.dp else 128.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns=((maxWidth-40.dp+14.dp)/(minWidth+14.dp)).toInt().coerceAtLeast(1)
        // Adaptive columns may differ by one physical pixel; keep the image baseline common.
        val posterHeight=((maxWidth-40.dp-14.dp*(columns-1))/columns)*1.5f
        LazyVerticalGrid(columns=GridCells.Fixed(columns),modifier=Modifier.fillMaxWidth().testTag("shelter-poster-wall"),
            contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=112.dp),
            horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(entries,key={it.path},contentType={if(!it.directory&&!it.isVideo)"file"else"poster"},
                span={if(!it.directory&&!it.isVideo)GridItemSpan(maxLineSpan)else GridItemSpan(1)}) { entry->
                if(!entry.directory&&!entry.isVideo) DirectoryFileRow(entry)
                else {
                    val selected=selectedPath==entry.path
                    Surface(color=Color.Transparent,modifier=Modifier.fillMaxWidth()
                        .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,enabled=!busy||!selected){open(entry)}
                        .testTag("shelter-entry:${entry.path}").semantics{this.selected=selected}) {
                        Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.fillMaxWidth().height(posterHeight).clip(RoundedCornerShape(14.dp))
                                .background(GroupedSurface).border(2.dp,if(selected)Berry else Color.Transparent,RoundedCornerShape(14.dp))) {
                                ShelterPoster(shelterPosterRef(source,entry,parentName),entry.directory)
                                SealItemMenu(entry.directory,{seal(entry)},Modifier.align(Alignment.TopEnd))
                                if(entry.directory)Surface(color=Paper.copy(alpha=.94f),shape=RoundedCornerShape(8.dp),
                                    modifier=Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                    Icon(Icons.Rounded.FolderOpen,"打开目录",Modifier.padding(6.dp).size(16.dp),tint=Berry)
                                }
                                if(selected&&busy)CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp),strokeWidth=2.dp)
                            }
                            Text(if(entry.isVideo)mediaDisplayTitle(entry.name)else entry.name,
                                modifier=Modifier.fillMaxWidth().testTag("shelter-title:${entry.path}"),color=Ink,fontSize=14.sp,lineHeight=20.sp,
                                fontWeight=FontWeight.Medium,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(if(entry.directory)"文件夹"else formatSize(entry.size),
                                modifier=Modifier.testTag("shelter-info:${entry.path}"),color=Muted,fontSize=11.sp,lineHeight=16.sp,maxLines=1)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun DirectoryFileRow(entry:Entry) {
    // Sidecars and incomplete downloads remain visible, without implying a playable item.
    Surface(modifier=Modifier.fillMaxWidth().testTag("directory-file:${entry.path}"),color=GroupedSurface,shape=RoundedCornerShape(12.dp)) {
        Row(Modifier.heightIn(min=72.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Description,null,Modifier.size(28.dp),tint=Muted)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(entry.name,fontSize=15.sp,lineHeight=21.sp,color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
                val extension=entry.name.substringAfterLast('.',"").takeIf{it.length in 1..8}?.uppercase(java.util.Locale.ROOT)?:"文件"
                Text("$extension · ${formatSize(entry.size)}",fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=1)
            }
        }
    }
}

@Composable private fun ShelterPoster(media: MediaRef, directory: Boolean) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val changes by MediaPosters.changes.collectAsState()
    val bitmap by produceState<android.graphics.Bitmap?>(null, media, changes, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = MediaPosters.load(context, media, localOnly=true)
            MediaPosters.load(context, media)?.let { value=it }
        }
    }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val art=bitmap
        if(art!=null)Image(art.asImageBitmap(),null,Modifier.fillMaxSize().testTag("shelter-poster:${media.key}"),contentScale=ContentScale.Crop)
        else Icon(if(directory)Icons.Rounded.FolderOpen else Icons.Rounded.Movie,null,Modifier.size(30.dp),tint=Berry.copy(alpha=.5f))
    }
}
