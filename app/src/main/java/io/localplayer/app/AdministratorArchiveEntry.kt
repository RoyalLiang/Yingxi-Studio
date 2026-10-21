package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AdministratorArchiveEntry(line:String,onArchives:()->Unit) {
    val largeText=LocalDensity.current.fontScale>1.3f
    Surface(onClick=onArchives,color=GroupedSurface,shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().testTag("home-archives")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                StationPortrait("scavenger","阿汐",Modifier.size(40.dp),false)
                Text("阿汐 · 据点管理员",Modifier.weight(1f).testTag("home-admin-title"),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,
                    maxLines=if(largeText)2 else 1,overflow=TextOverflow.Ellipsis)
                if(!largeText)Text("档案室",color=Muted,fontSize=11.sp)
                Icon(Icons.Rounded.ChevronRight,if(largeText)"档案室"else null,tint=Muted,modifier=Modifier.size(16.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val measurer=rememberTextMeasurer()
                val density=LocalDensity.current
                val style=LocalTextStyle.current.copy(color=Muted,lineHeight=18.sp)
                val available=constraints.maxWidth
                val size=remember(line,available,density.fontScale,density.density,style,measurer) {
                    if(largeText)13.sp else listOf(13.sp,12.5.sp,12.sp,11.5.sp).firstOrNull {
                        !measurer.measure(line,style.copy(fontSize=it),maxLines=1,softWrap=false,
                            constraints=Constraints(maxWidth=available)).hasVisualOverflow
                    }?:11.5.sp
                }
                Text(line,Modifier.fillMaxWidth().testTag("home-admin-quote"),style=style,fontSize=size,
                    maxLines=if(largeText)3 else 1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}
