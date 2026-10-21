package io.localplayer.app

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.cos
import kotlin.math.sin

/** Draw-only buffering mark: never reads media, opens decoders, or schedules preview IO. */
@Composable internal fun StationDecodingMark(){
    val context=LocalContext.current
    fun reduced()=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
    var still by remember{mutableStateOf(reduced())}
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){still=reduced()}
    val angle:Float
    val pulse:Float
    if(still){angle=0f;pulse=.55f}else{
        val transition=rememberInfiniteTransition(label="station-signal")
        angle=transition.animateFloat(0f,360f,infiniteRepeatable(tween(3600,easing=LinearEasing)),label="reel").value
        pulse=transition.animateFloat(.2f,.8f,infiniteRepeatable(tween(850,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="signal").value
    }
    Canvas(Modifier.size(66.dp)){
        val center=Offset(size.width/2,size.height/2)
        val radius=size.minDimension*.27f
        drawCircle(Color.White,radius,center,style=Stroke(2.dp.toPx()))
        repeat(3){index->
            val rad=Math.toRadians((angle+index*120).toDouble())
            drawCircle(Color.White,radius*.19f,center+Offset(cos(rad).toFloat(),sin(rad).toFloat())*(radius*.54f))
        }
        drawCircle(Color.White,radius*.08f,center)
        drawArc(Color(0xFFE6C78C).copy(alpha=pulse),angle,110f,false,Offset(3.dp.toPx(),3.dp.toPx()),androidx.compose.ui.geometry.Size(size.width-6.dp.toPx(),size.height-6.dp.toPx()),style=Stroke(1.5.dp.toPx()))
    }
}
