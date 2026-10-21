package io.localplayer.app

import androidx.compose.foundation.Image
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

@Composable internal fun ShelterDirectory(vm: LibraryViewModel, source: Source,
    selectedPath: String?, open: (Entry) -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){MediaPosters.retryMissing()}
    key(source.id, vm.path) {
        LazyVerticalGrid(columns = GridCells.Adaptive(128.dp), modifier = Modifier.fillMaxWidth()
            .testTag("shelter-poster-wall"), contentPadding = PaddingValues(start=20.dp,end=20.dp,bottom=112.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(vm.entries, key = { it.path },span={if(!it.directory&&!it.isVideo)GridItemSpan(maxLineSpan)else GridItemSpan(1)}) { entry ->
                if(!entry.directory&&!entry.isVideo) {
                    MediaRow(entry.name,"非视频文件",Icons.Rounded.Description){open(entry)}
                } else {
                val selected = selectedPath == entry.path
                Surface(onClick = { open(entry) }, enabled = !vm.busy || !selected, color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().testTag("shelter-entry:${entry.path}")
                        .semantics { this.selected = selected }) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(RoundedCornerShape(14.dp))
                            .background(GroupedSurface)) {
                            ShelterPoster(shelterPosterRef(source, entry, vm.currentDirectoryBookmark()?.label), entry.directory)
                            if(entry.directory)Surface(color=Paper.copy(alpha=.94f),shape=RoundedCornerShape(8.dp),
                                modifier=Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                Icon(Icons.Rounded.FolderOpen,"打开目录",Modifier.padding(6.dp).size(16.dp),tint=Berry)
                            }
                            if(selected && vm.busy)CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp),strokeWidth=2.dp)
                        }
                        Text(if(entry.isVideo)mediaDisplayTitle(entry.name)else entry.name,
                            color=Ink,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,
                            maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(!entry.directory)Text(formatSize(entry.size),color=Muted,fontSize=11.sp)
                    }
                }
                }
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
