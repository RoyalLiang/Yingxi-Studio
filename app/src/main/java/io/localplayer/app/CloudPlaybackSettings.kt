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
    Surface(shape=RoundedCornerShape(22.dp),color=GroupedSurface,modifier=Modifier.fillMaxWidth().testTag("cloud-playback-settings")) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("信号采集频段",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(0 to "自动",1 to "1 路",2 to "2 路",3 to "3 路").forEach{(value,label)->
                    FilterChip(selected=workers==value,onClick={workers=value;prefs.workers=value},label={Text(label)},modifier=Modifier.testTag("cloud-workers-$value"))
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("保留已读片段",Modifier.weight(1f),color=Ink)
                SettingsSwitch(caching,{caching=it;prefs.cacheEnabled=it},Modifier.testTag("cloud-segment-cache"))
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("片尾预读下一集",Modifier.weight(1f),color=Ink)
                SettingsSwitch(next,{next=it;prefs.nextEpisode=it},Modifier.testTag("cloud-next-prefetch"),enabled=caching)
            }

        }
    }
}
