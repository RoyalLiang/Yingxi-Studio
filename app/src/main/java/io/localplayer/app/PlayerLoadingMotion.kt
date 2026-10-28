package io.localplayer.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** A shared loading indicator and transparent report receipt mark. */
@Composable internal fun PlayerLoadingMotion(modifier:Modifier=Modifier,description:String="正在接收画面",completed:Boolean=false){
    val completion=animateFloatAsState(if(completed)1f else 0f,tween(520,easing=FastOutSlowInEasing),label="receipt mark")
    if(!completed){
        Box(modifier.semantics{contentDescription=description},contentAlignment=Alignment.Center){
            CircularProgressIndicator(Modifier.size(48.dp),color=PlayerAccent,strokeWidth=3.dp,trackColor=PlayerAccent.copy(alpha=.15f))
        }
        return
    }
    Canvas(modifier.semantics{
        contentDescription=description
        liveRegion=LiveRegionMode.Polite
    }){
        val stroke=2.5.dp.toPx()
        if(completed){
            val p=completion.value
            val start=Offset(size.width*.25f,size.height*.51f)
            val corner=Offset(size.width*.43f,size.height*.68f)
            val end=Offset(size.width*.77f,size.height*.32f)
            val path=Path().apply{
                moveTo(start.x,start.y)
                val first=(p/.35f).coerceAtMost(1f)
                lineTo(start.x+(corner.x-start.x)*first,start.y+(corner.y-start.y)*first)
                if(p>.35f){val second=((p-.35f)/.65f).coerceAtMost(1f);lineTo(corner.x+(end.x-corner.x)*second,corner.y+(end.y-corner.y)*second)}
            }
            drawPath(path,Color.Black.copy(alpha=.4f),style=Stroke(stroke+2.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            drawPath(path,PlayerAccent,style=Stroke(stroke,cap=StrokeCap.Round,join=StrokeJoin.Round))
        }
    }
}
