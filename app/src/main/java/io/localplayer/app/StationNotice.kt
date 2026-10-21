package io.localplayer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// finesse · register=product · component=station-notice · palette=paper+lamp+pine
@Composable internal fun StationNotice(eyebrow:String,title:String,body:String,modifier:Modifier=Modifier,onArchives:(()->Unit)?=null) {
    val lamp=Color(0xFFE6C78C)
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF293C34))) {
        Canvas(Modifier.matchParentSize()) {
            val slot=Size(9.dp.toPx(),3.dp.toPx())
            val gap=20.dp.toPx()
            var x=20.dp.toPx()
            while(x<size.width-20.dp.toPx()) {
                drawRoundRect(lamp.copy(alpha=.22f),Offset(x,size.height-10.dp.toPx()),slot,CornerRadius(1.dp.toPx()))
                x+=gap
            }
        }
        Column(Modifier.padding(start=20.dp,end=20.dp,top=18.dp,bottom=27.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Icon(Icons.Rounded.LocalMovies,null,tint=lamp,modifier=Modifier.size(16.dp))
                        Text(eyebrow,color=lamp,fontSize=12.sp,lineHeight=18.sp)
                    }
                    Text(title,color=Color(0xFFF9F2E4),fontSize=18.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold)
                }
                if(onArchives!=null)StationPortrait("scavenger","阿汐",Modifier.size(52.dp).testTag("home-admin-avatar"),false)
            }
            Text(body,color=Color(0xFFD7DED2),fontSize=if(onArchives!=null)16.sp else 14.sp,lineHeight=if(onArchives!=null)25.sp else 22.sp,modifier=if(onArchives!=null)Modifier.testTag("home-admin-quote")else Modifier)
            if(onArchives!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("阿汐 · 据点管理员",Modifier.weight(1f).padding(end=8.dp).testTag("home-admin-title"),fontSize=12.sp,lineHeight=18.sp,color=lamp)
                TextButton(onClick=onArchives,modifier=Modifier.heightIn(min=48.dp).testTag("home-archives")){
                    Text("档案室",color=Color(0xFFF9F2E4),fontSize=14.sp)
                    Icon(Icons.Rounded.ChevronRight,null,Modifier.size(19.dp),tint=lamp)
                }
            }
        }
    }
}
