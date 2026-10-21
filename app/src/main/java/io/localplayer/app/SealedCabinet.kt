package io.localplayer.app

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
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

@Composable internal fun SealItemMenu(directory:Boolean,onSeal:()->Unit,modifier:Modifier=Modifier){
    var expanded by remember{mutableStateOf(false)}
    Box(modifier){
        IconButton(onClick={expanded=true},modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.MoreHoriz,if(directory)"目录操作"else"剧集操作",tint=Berry)}
        DropdownMenu(expanded,{expanded=false},containerColor=GroupedSurface){
            DropdownMenuItem(text={Text(if(directory)"封存目录（私密）"else"封存剧集（私密）")},leadingIcon={Icon(Icons.Rounded.Lock,null)},onClick={expanded=false;onSeal()})
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
                    Text(if(SealedMedia.damaged)"封存记录无法读取，原记录已保留。"else"封存的目录与剧集只在开柜后显示。",color=Ink,fontSize=16.sp)
                    Button(onClick={vm.requestPrivate()},enabled=!SealedMedia.damaged,modifier=Modifier.heightIn(min=48.dp).testTag("sealed-unlock")){Text("验证身份并开柜")}
                }
            }else{
                LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(SealedMedia.items().isEmpty())item{Text("柜里还没有封存的档案。",color=Muted)}
                    items(SealedMedia.items(),key={it.id}){item->
                        Surface(onClick={open(item)},color=GroupedSurface,shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().testTag("sealed-item:${item.id}")){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(item.media.title,fontSize=17.sp,fontWeight=FontWeight.Medium)
                                Text(item.media.sourceLabel+if(item.directory)" · 封存目录"else" · 封存剧集",color=Muted,fontSize=13.sp)
                                TextButton(onClick={SealedMedia.unseal(item.id)},modifier=Modifier.heightIn(min=48.dp)){Text("恢复公开")}
                            }
                        }
                    }
                    item{Text("封存只管理映汐内的显示与访问，原文件仍在原位置。离开放映站后，柜门会重新上锁。封存档案关闭在线海报与字幕匹配。",fontSize=13.sp,lineHeight=20.sp,color=Muted)}
                }
            }
            vm.error?.let{Text(it,Modifier.padding(20.dp),color=MaterialTheme.colorScheme.error)}
        }
    }
}
