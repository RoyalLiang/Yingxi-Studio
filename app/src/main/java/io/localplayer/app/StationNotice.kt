package io.localplayer.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// Existing paper, lamp and pine palette; a single quiet entrance sweep settles at rest.
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun StationNotice(eyebrow:String,title:String,body:String,modifier:Modifier=Modifier,onArchives:(()->Unit)?=null,person:WorldPerson?=null,onRefresh:(()->Unit)?=null) {
    val lamp=Color(0xFFE6C78C)
    val motionEnabled=ValueAnimator.areAnimatorsEnabled()
    val light=remember { Animatable(if(motionEnabled) 0f else 1f) }
    LaunchedEffect(motionEnabled) {
        if(motionEnabled) light.animateTo(1f,tween(1600,easing=LinearOutSlowInEasing)) else light.snapTo(1f)
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF293C34))) {
        Canvas(Modifier.matchParentSize().testTag("station-film-perforation")) {
            val slot=Size(9.dp.toPx(),3.dp.toPx())
            val gap=20.dp.toPx()
            val center=(light.value*1.6f-.3f)*size.width
            var x=20.dp.toPx()
            while(x<size.width-20.dp.toPx()) {
                val glow=(1f-abs(x-center)/(size.width*.22f)).coerceIn(0f,1f)
                drawRoundRect(lamp.copy(alpha=.20f+.32f*glow),Offset(x,size.height-10.dp.toPx()),slot,CornerRadius(1.dp.toPx()))
                x+=gap
            }
        }
        Column(Modifier.padding(start=20.dp,end=20.dp,top=18.dp,bottom=if(onArchives!=null)12.dp else 25.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.LocalMovies,null,tint=lamp,modifier=Modifier.size(16.dp))

                Text(eyebrow,color=lamp,fontSize=12.sp,lineHeight=18.sp)
            }
            Text(title,color=Color(0xFFF9F2E4),fontSize=18.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold)
                }
                if(onArchives!=null) {
                    val portrait=person?.portrait?:"scavenger"
                    val name=person?.name?.inLanguage(androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language)?:"阿汐"
                    if(onRefresh!=null) IconButton(onClick=onRefresh,modifier=Modifier.size(52.dp).testTag("home-diary-refresh")) {
                        Box(Modifier.size(52.dp).testTag("home-admin-avatar")) {StationPortrait(portrait,"$name，换一则札记",Modifier.fillMaxSize(),false)}
                    } else Box(Modifier.size(52.dp).testTag("home-admin-avatar")) {StationPortrait(portrait,name,Modifier.fillMaxSize(),false)}
                }
            }
            Text(body,color=Color(0xFFD7DED2),fontSize=if(onArchives!=null)16.sp else 14.sp,lineHeight=if(onArchives!=null)25.sp else 22.sp,modifier=if(onArchives!=null)Modifier.testTag("home-admin-quote")else Modifier)
            if(onArchives!=null) {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color=lamp.copy(alpha=.16f))
                    Row(Modifier.fillMaxWidth()
                        .clickable(onClickLabel="打开档案室",role=Role.Button,onClick=onArchives)
                        .padding(vertical=2.dp).testTag("home-archives"),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.MenuBook,null,tint=lamp,modifier=Modifier.size(18.dp))
                        Text("档案室",Modifier.weight(1f),color=Color(0xFFF9F2E4),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium)
                        Icon(Icons.Rounded.ChevronRight,null,Modifier.size(18.dp),tint=lamp)
                    }
                }
            }
        }
    }
}

/** Hidden cabinet entry on the home masthead; authentication still owns access. */
@Composable
internal fun StationCabinetName(name:String,color:Color,onOpen:()->Unit,modifier:Modifier=Modifier,fontSize:androidx.compose.ui.unit.TextUnit=32.sp){
    val original=LocalViewConfiguration.current
    val holdConfiguration=remember(original){object:ViewConfiguration by original { override val longPressTimeoutMillis:Long=2000L }}
    val open by rememberUpdatedState(onOpen)
    val haptic=LocalHapticFeedback.current
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration){
        Text(name,modifier.heightIn(min=48.dp).wrapContentHeight(Alignment.CenterVertically)
            .semantics{onLongClick(label="长按2秒，验证进入封存柜"){open();true}}
            .focusable()
            .pointerInput(Unit){detectTapGestures(onLongPress={haptic.performHapticFeedback(HapticFeedbackType.LongPress);open()})}
            .testTag("home-sealed-entry"),color=color,fontSize=fontSize,lineHeight=40.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.7).sp,maxLines=1)
    }
}
