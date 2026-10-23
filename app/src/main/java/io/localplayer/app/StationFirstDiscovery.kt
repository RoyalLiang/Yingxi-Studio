package io.localplayer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A first-visit paper discovery; continuing always returns to device authentication. */
@Composable internal fun StationFirstDiscovery(onDismiss:()->Unit,onContinue:()->Unit) {
    val context=LocalContext.current
    val language=LocalConfiguration.current.locales[0].language
    val chinese=language=="zh"
    val note=remember(context){StationWorld.load(context).surfaceNotes.single{it.id=="first_light"}}
    var revealed by rememberSaveable{mutableStateOf(false)}
    var light by rememberSaveable{mutableFloatStateOf(.18f)}
    var found by rememberSaveable{mutableStateOf(false)}
    fun move(value:Float){light=value.coerceIn(0f,1f);if(light in 0.42f..0.58f)found=true}
    fun finishMove(){if(found)revealed=true}
    val lamp=Color(0xFFE6C78C)
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=400.dp).fillMaxWidth().heightIn(max=maxHeight).testTag("station-first-discovery"),
                color=Paper,shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text(if(chinese)"第一次发现"else"A first discovery",fontSize=12.sp,color=Berry)
                    Text(if(chinese)"有人在胶片背面写了字"else"Someone wrote on the back of this film",
                        fontSize=24.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold)
                    Text(if(chinese)"沿着旧胶片移动光斑，看看被留下的小字。"else"Move the light along the old film to find the words left behind.",
                        fontSize=14.sp,lineHeight=22.sp,color=Muted)
                    if(revealed) {
                        Surface(color=Color(0xFFE9DFC4),shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().testTag("station-discovery-note")) {
                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    StationPortrait("scavenger",if(chinese)"阿汐"else"Axi",Modifier.size(36.dp),false)
                                    Text(if(chinese)"阿汐的短笺"else"A note from Axi",fontSize=13.sp,color=Color(0xFF525E4F))
                                }
                                Text(note.title.inLanguage(language),fontSize=21.sp,lineHeight=28.sp,fontWeight=FontWeight.Medium,color=Color(0xFF293C34))
                                Text(note.body.inLanguage(language),fontSize=16.sp,lineHeight=26.sp,color=Color(0xFF374234))
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF202C26))
                            .testTag("station-film-light").pointerInput(Unit){
                                detectDragGestures(onDragStart={move(it.x/size.width)},onDragEnd={finishMove()},onDrag={change,_->change.consume();move(change.position.x/size.width)})
                            }) {
                            Canvas(Modifier.fillMaxSize()) {
                                val center=Offset(light*size.width,size.height/2)
                                val radius=minOf(size.width*.29f,size.height*.52f)
                                drawCircle(lamp.copy(alpha=.10f),radius,center)
                                drawCircle(lamp.copy(alpha=.45f),radius,center,style=Stroke(1.dp.toPx()))
                                var x=10.dp.toPx()
                                while(x<size.width-10.dp.toPx()) {
                                    for(y in listOf(10.dp.toPx(),size.height-15.dp.toPx()))drawRoundRect(lamp.copy(alpha=.45f),Offset(x,y),Size(9.dp.toPx(),5.dp.toPx()),CornerRadius(1.dp.toPx()))
                                    x+=20.dp.toPx()
                                }
                            }
                            Text(note.eyebrow.inLanguage(language),Modifier.fillMaxSize().drawWithContent {
                                val radius=minOf(size.width*.29f,size.height*.52f)
                                val center=Offset(light*size.width,size.height/2)
                                clipPath(Path().apply{addOval(Rect(center-Offset(radius,radius),Size(radius*2,radius*2)))}){this@drawWithContent.drawContent()}
                            }.wrapContentSize(Alignment.Center),color=lamp,fontSize=26.sp,lineHeight=35.sp,textAlign=TextAlign.Center)
                        }
                        Slider(value=light,onValueChange={move(it)},onValueChangeFinished={finishMove()},
                            modifier=Modifier.fillMaxWidth().testTag("station-light-slider").semantics{contentDescription=if(chinese)"移动光斑"else"Move the light"})
                    }
                    Button(onClick={if(revealed)onContinue()else revealed=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("station-discovery-continue"),shape=RoundedCornerShape(14.dp)) {
                        Text(if(revealed){if(chinese)"收好短笺，继续身份校验"else"Keep the note and verify identity"}else{if(chinese)"直接读笺"else"Read the note directly"},textAlign=TextAlign.Center)
                    }
                    TextButton(onClick=onContinue,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("station-discovery-skip")) {
                        Text(if(chinese)"跳过，继续身份校验"else"Skip and verify identity",textAlign=TextAlign.Center)
                    }
                    Text(if(chinese)"这份惊喜只在第一次发现时出现。完成或跳过后，站名会直接带你进入身份校验。"else"This surprise appears only on your first discovery. Complete or skip it to go directly to identity verification next time.",
                        fontSize=12.sp,lineHeight=18.sp,color=Muted,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
                }
            }
        }
    }
}
