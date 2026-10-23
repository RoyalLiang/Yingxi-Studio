package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.localplayer.core.mediaDisplayTitle

@Composable internal fun HistoryActionMenu(entry:HistoryEntry,onRestart:()->Unit,onRemove:()->Unit,onSeal:(()->Unit)?=null){
    var menu by remember{mutableStateOf(false)}
    Box{
        IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreHoriz,"记录操作 ${mediaDisplayTitle(entry.media.title)}",tint=Muted,modifier=Modifier.size(20.dp))}
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
            DropdownMenuItem(text={Text("从头播放")},onClick={menu=false;onRestart()})
            if(onSeal!=null)DropdownMenuItem(text={Text(sealActionLabel(false))},onClick={menu=false;onSeal()})
            DropdownMenuItem(text={Text("移除此记录")},onClick={menu=false;onRemove()})
        }
    }
}
