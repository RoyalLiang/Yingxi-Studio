package io.localplayer.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** The station's receiving signal also resolves into the report receipt mark. */
@Composable internal fun PlayerSignalMotion(modifier:Modifier=Modifier,description:String="正在接收画面",completed:Boolean=false){
    val motion=if(completed)null else rememberInfiniteTransition(label="receiving signal").animateFloat(
        initialValue=0f,targetValue=1f,animationSpec=infiniteRepeatable(tween(1400,easing=LinearEasing)),label="signal waves")
    val completion=animateFloatAsState(if(completed)1f else 0f,tween(520,easing=FastOutSlowInEasing),label="receipt mark")
    Canvas(modifier.semantics{
        contentDescription=description
        liveRegion=LiveRegionMode.Polite
        if(!completed)progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
    }){
        val side=size.minDimension
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
        }else{
            val p=motion!!.value
            val origin=Offset(size.width/2,size.height*.72f)
            repeat(3){i->
                val wave=(p+i/3f)%1f
                val radius=side*(.18f+.24f*wave)
                val topLeft=Offset(origin.x-radius,origin.y-radius)
                val waveSize=Size(radius*2,radius*2)
                val alpha=(1f-wave)*.8f
                drawArc(Color.Black.copy(alpha=alpha*.5f),210f,120f,false,topLeft,waveSize,style=Stroke(stroke+2.dp.toPx(),cap=StrokeCap.Round))
                drawArc(PlayerAccent.copy(alpha=alpha),210f,120f,false,topLeft,waveSize,style=Stroke(stroke,cap=StrokeCap.Round))
            }
            drawCircle(PlayerAccent,stroke,origin)
        }
    }
}
