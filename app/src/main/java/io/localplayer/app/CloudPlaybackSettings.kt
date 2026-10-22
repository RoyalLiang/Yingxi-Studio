package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CloudPlaybackSettings() {
    val context=LocalContext.current
    val prefs=remember(context){CloudReadPreferences(context)}
    var workers by remember{mutableIntStateOf(prefs.workers)}
    var caching by remember{mutableStateOf(prefs.cacheEnabled)}
    var next by remember{mutableStateOf(prefs.nextEpisode)}
    var clearing by remember{mutableStateOf(false)}
    var cleared by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    Surface(shape=RoundedCornerShape(22.dp),color=GroupedSurface,modifier=Modifier.testTag("cloud-playback-settings")) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("信号塔 · 网盘读取",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            Text("阿里云盘原画使用；下次打开影片时生效。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(0 to "自动",1 to "1 路",2 to "2 路",3 to "3 路").forEach{(value,label)->
                    FilterChip(selected=workers==value,onClick={workers=value;prefs.workers=value},label={Text(label)},modifier=Modifier.testTag("cloud-workers-$value"))
                }
            }
            Text("自动：非计费 Wi-Fi 使用 3 路，移动网络与其他网络使用 2 路。并行读取会预读后续片段，提前退出可能多用流量。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("保留已读片段",Modifier.weight(1f),color=Ink)
                SettingsSwitch(caching,{caching=it;prefs.cacheEnabled=it},Modifier.testTag("cloud-segment-cache"))
            }
            Text("原片片段仅存于应用私有缓存，最多 256 MiB；七天未使用的片段会在下次读取时清理。已有缓存不代表整部影片可离线观看。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("片尾预读下一集",Modifier.weight(1f),color=Ink)
                SettingsSwitch(next,{next=it;prefs.nextEpisode=it},Modifier.testTag("cloud-next-prefetch"),enabled=caching)
            }
            Text("仅非计费 Wi-Fi：看到约 80% 且当前缓冲充足时，预读队列中下一集的开头。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
            TextButton(enabled=!clearing,onClick={
                clearing=true
                scope.launch {
                    val bytes=withContext(Dispatchers.IO){val cache=CloudReadPreferences.cache(context);val before=cache.bytes();cache.clear();(before-cache.bytes()).coerceAtLeast(0)}
                    cleared="已释放 %.1f MiB；正在播放时，后续片段仍可能进入缓存。".format(java.util.Locale.ROOT,bytes/1048576.0)
                    clearing=false;StorageUsage.revision++
                }
            },modifier=Modifier.testTag("cloud-cache-clear")){Text(if(clearing)"正在清理片段…"else"回收片段 · 清理缓存")}
            cleared?.let{Text(it,fontSize=12.sp,lineHeight=17.sp,color=Muted)}
        }
    }
}
