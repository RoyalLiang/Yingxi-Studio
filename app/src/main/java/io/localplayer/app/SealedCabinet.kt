package io.localplayer.app

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import io.localplayer.core.mediaDisplayTitle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun PrivateScreenProtection(enabled:Boolean){
    val view=LocalView.current
    DisposableEffect(view,enabled){
        val window=generateSequence(view.context){(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val prior=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        if(enabled)window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose{if(enabled&&!prior)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
}

internal fun sealActionLabel(directory:Boolean)=if(directory)"封存片架（私密）"else"封存影像（私密）"

@Composable internal fun SealItemMenu(directory:Boolean,onSeal:()->Unit,modifier:Modifier=Modifier){
    var expanded by remember{mutableStateOf(false)}
    Box(modifier){
        IconButton(onClick={expanded=true},modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.MoreHoriz,if(directory)"目录操作"else"剧集操作",tint=Berry)}
        DropdownMenu(expanded,{expanded=false},containerColor=GroupedSurface){
            DropdownMenuItem(text={Text(sealActionLabel(directory))},leadingIcon={Icon(Icons.Rounded.Inventory2,null)},onClick={expanded=false;onSeal()})
        }
    }
}

@Composable internal fun SealedCabinet(vm:LibraryViewModel,close:()->Unit,open:(SealedItem)->Unit){
    BackHandler(onBack=close)
    PrivateScreenProtection(true)
    LaunchedEffect(SealedMedia.unlocked){if(!SealedMedia.unlocked)vm.error=null}
    Surface(color=Paper,modifier=Modifier.fillMaxSize().testTag("sealed-cabinet")){
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)){
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=close){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回")}
                Text("封存柜",Modifier.weight(1f),fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                if(SealedMedia.unlocked)IconButton(onClick={SealedMedia.lock()}){Icon(Icons.Rounded.Lock,"锁上封存柜")}
            }
            if(!SealedMedia.unlocked){
                Column(Modifier.fillMaxWidth().padding(28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                    Icon(Icons.Rounded.Lock,contentDescription=null,tint=Berry,modifier=Modifier.size(36.dp))
                    Text(if(SealedMedia.damaged)"封存记录无法读取，原记录已保留。"else"收好的片架与影像，只在开柜后显露。",color=Ink,fontSize=16.sp)
                    Button(onClick={vm.requestPrivate()},enabled=!SealedMedia.damaged,modifier=Modifier.heightIn(min=48.dp).testTag("sealed-unlock")){Text("验证身份并开柜")}
                }
            }else{
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val minWidth=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f)200.dp else 128.dp
                val columns=((maxWidth-40.dp+14.dp)/(minWidth+14.dp)).toInt().coerceAtLeast(1)
                val posterHeight=((maxWidth-40.dp-14.dp*(columns-1))/columns)*1.5f
                LazyVerticalGrid(columns=GridCells.Fixed(columns),contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(SealedMedia.items().isEmpty())item(span={GridItemSpan(maxLineSpan)}){Text("柜里还没有封存的档案。",color=Muted)}
                    items(SealedMedia.items(),key={it.id}) { item->
                        val touch=remember(item.id){MutableInteractionSource()}
            val pressed by touch.collectIsPressedAsState()
            val scale by animateFloatAsState(if(pressed).975f else 1f,tween(140),label="sealed-cover-press")
            Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                Surface(onClick={open(item)},interactionSource=touch,shape=RoundedCornerShape(14.dp),color=GroupedSurface,
                    modifier=Modifier.fillMaxWidth().height(posterHeight).graphicsLayer{scaleX=scale;scaleY=scale}.semantics{contentDescription=(if(item.directory)"片架："else"影集：")+item.media.title}.testTag("sealed-item:${item.id}")){
                    Box(Modifier.fillMaxSize()){
                        if(item.directory)Icon(Icons.Rounded.FolderOpen,null,Modifier.align(Alignment.Center).size(32.dp),tint=Berry.copy(alpha=.5f))
                        MediaThumbnail(item.media,Modifier.fillMaxSize())
                        PosterLabels(item.media,directory=item.directory)
                    }
                }
                Text(if(item.directory)item.media.title else mediaDisplayTitle(item.media.title),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={SealedMedia.unseal(item.id)},modifier=Modifier.heightIn(min=48.dp)){Text("放回片架")}
            }
        }
        item(span={GridItemSpan(maxLineSpan)}){Text("封存只收起站内的显示与访问，原片仍留在原处。退到后台后柜门会重新落锁；柜内档案可载入同目录轨道，不作互联网海报或轨道检索。",fontSize=13.sp,lineHeight=20.sp,color=Muted)}
                }
            }
        }
            vm.error?.let{Text(it,Modifier.padding(20.dp),color=MaterialTheme.colorScheme.error)}
        }
    }
}
