package io.localplayer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
@Composable internal fun StationNotice(eyebrow:String,title:String,body:String,modifier:Modifier=Modifier) {
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
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.LocalMovies,null,tint=lamp,modifier=Modifier.size(16.dp))
                Text(eyebrow,color=lamp,fontSize=12.sp,lineHeight=18.sp)
            }
            Text(title,color=Color(0xFFF9F2E4),fontSize=18.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold)
            Text(body,color=Color(0xFFD7DED2),fontSize=14.sp,lineHeight=22.sp)
        }
    }
}
