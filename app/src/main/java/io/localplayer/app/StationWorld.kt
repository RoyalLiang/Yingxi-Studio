package io.localplayer.app

import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal data class WorldText(val zh:String,val en:String) { fun inLanguage(language:String)=if(language=="zh")zh else en }
internal data class WorldPlace(val id:String,val name:WorldText,val kind:WorldText,val description:WorldText,val x:Float,val y:Float,val known:Boolean)
internal data class WorldPerson(val id:String,val name:WorldText,val role:WorldText,val description:WorldText,val home:String,val portrait:String,val quotes:List<WorldText>)
internal data class WorldEdge(val from:String,val to:String,val known:Boolean)
internal data class WorldChronicle(val version:String,val text:WorldText)
internal data class StationWorld(val chapter:WorldText,val premise:WorldText,val boundary:WorldText,val places:List<WorldPlace>,val people:List<WorldPerson>,val edges:List<WorldEdge>,val chronicles:List<WorldChronicle>) {
    companion object {
        fun load(context:Context):StationWorld {
            val root=JSONObject(context.resources.openRawResource(R.raw.world_atlas).bufferedReader().use{it.readText()})
            fun JSONObject.words(key:String)=getJSONObject(key).let{WorldText(it.getString("zh"),it.getString("en"))}
            fun JSONObject.objects(key:String)=getJSONArray(key).let{a->(0 until a.length()).map(a::getJSONObject)}
            return StationWorld(root.words("chapter"),root.words("premise"),root.words("boundary"),
                root.objects("nodes").map{WorldPlace(it.getString("id"),it.words("name"),it.words("kind"),it.words("description"),it.getDouble("x").toFloat(),it.getDouble("y").toFloat(),it.getBoolean("known"))},
                root.objects("characters").map{p->WorldPerson(p.getString("id"),p.words("name"),p.words("role"),p.words("description"),p.getString("home"),p.getString("portrait"),p.objects("quotes").map{WorldText(it.getString("zh"),it.getString("en"))})},
                root.objects("edges").map{WorldEdge(it.getString("from"),it.getString("to"),it.getBoolean("known"))},
                root.objects("chronicles").map{WorldChronicle(it.getString("version"),it.words("text"))})
        }
    }
}

@Composable internal fun StationWorldPage(back:()->Unit) {
    val context=LocalContext.current
    val world=remember(context){StationWorld.load(context)}
    val lang=LocalConfiguration.current.locales[0].language
    fun WorldText.say()=inLanguage(lang)
    var placeId by rememberSaveable{mutableStateOf("station")}
    var personId by rememberSaveable{mutableStateOf("axi")}
    var quote by rememberSaveable{mutableIntStateOf(0)}
    val person=world.people.first{it.id==personId}
    val place=world.places.first{it.id==placeId}
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("station-world")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,if(lang=="zh")"返回" else "Back")}
            Text(if(lang=="zh")"放映站档案" else "Station archives",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Ink)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).testTag("world-scroll"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(world.chapter.say(),fontSize=13.sp,color=Berry,fontWeight=FontWeight.Medium)
            Text(if(lang=="zh")"灯外，还有整个世界。" else "A world beyond the light.",fontFamily=FontFamily.Serif,fontSize=30.sp,lineHeight=39.sp,color=Ink)
            Text(world.premise.say(),fontSize=15.sp,lineHeight=25.sp,color=Muted)
            WorldMap(world,placeId,lang){placeId=it}
            Text(if(lang=="zh")"点选地点阅读档案 · 实线是往返信路，虚线是传闻" else "Tap a place to read · Solid routes are known; dotted routes are rumors",fontSize=11.sp,lineHeight=18.sp,color=Muted)
            Column(Modifier.fillMaxWidth().testTag("world-place-detail"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(place.name.say()+" · "+place.kind.say(),fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Text(place.description.say(),fontFamily=FontFamily.Serif,fontSize=15.sp,lineHeight=26.sp,color=Ink)
            }
            HorizontalDivider(color=Muted.copy(alpha=.16f))
            Text(if(lang=="zh")"把名字留在信上" else "Names on the letters",fontSize=20.sp,fontFamily=FontFamily.Serif,color=Ink)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                world.people.forEach { p ->
                    Surface(onClick={personId=p.id;quote=0},color=if(p.id==personId)Berry.copy(alpha=.08f) else Color.Transparent,
                        shape=RoundedCornerShape(18.dp),modifier=Modifier.weight(1f).testTag("world-person:${p.id}").semantics{selected=p.id==personId}) {
                        Column(Modifier.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            StationPortrait(p.portrait,p.name.say(),Modifier.size(76.dp),p.id==personId)
                            Text(p.name.say(),fontSize=14.sp,color=if(p.id==personId)Berry else Ink,modifier=Modifier.padding(top=9.dp))
                        }
                    }
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text(person.name.say()+" / "+person.role.say(),fontSize=16.sp,fontWeight=FontWeight.Medium,color=Ink)
                Text(world.places.first{it.id==person.home}.name.say(),fontSize=12.sp,color=Berry)
                Text(person.description.say(),fontSize=14.sp,lineHeight=24.sp,color=Muted)
                Surface(onClick={quote=(quote+1)%person.quotes.size},color=GroupedSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().testTag("world-quote")) {
                    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("“"+person.quotes[quote%person.quotes.size].say()+"”",fontFamily=FontFamily.Serif,fontSize=19.sp,lineHeight=30.sp,color=Ink)
                        Text("— "+person.name.say(),fontSize=13.sp,color=Berry)
                        Text(if(lang=="zh")"轻触信纸，听下一句" else "Tap the paper for another line",fontSize=11.sp,color=Muted)
                    }
                }
            }
            Text(if(lang=="zh")"信笺走过的路" else "Along the letter route",fontSize=20.sp,fontFamily=FontFamily.Serif,color=Ink)
            world.chronicles.asReversed().forEach { entry -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(entry.version,fontSize=12.sp,color=Berry,fontWeight=FontWeight.Medium)
                Text(entry.text.say(),fontSize=14.sp,lineHeight=24.sp,color=Ink)
            } }
            Text(world.boundary.say(),fontSize=11.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(bottom=28.dp))
        }
    }
}

@Composable private fun WorldMap(world:StationWorld,selectedId:String,language:String,select:(String)->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height((340*androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f,1.8f)).dp).clip(RoundedCornerShape(24.dp)).background(GroupedSurface).testTag("world-map")) {
        val width=maxWidth;val height=maxHeight
        Canvas(Modifier.fillMaxSize()) {
            // Faint survey grid; solid routes are known, dotted routes remain story leads.
            for(i in 1..6)drawLine(Muted.copy(alpha=.06f),Offset(size.width*i/7,0f),Offset(size.width*i/7,size.height),1.dp.toPx())
            for(i in 1..5)drawLine(Muted.copy(alpha=.06f),Offset(0f,size.height*i/6),Offset(size.width,size.height*i/6),1.dp.toPx())
            world.edges.forEach { edge ->
                val from=world.places.first{it.id==edge.from};val to=world.places.first{it.id==edge.to}
                drawLine(if(edge.known)Berry.copy(alpha=.5f) else Muted.copy(alpha=.35f),Offset(from.x*size.width,from.y*size.height),Offset(to.x*size.width,to.y*size.height),1.2.dp.toPx(),pathEffect=if(edge.known)null else PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),5.dp.toPx())))
            }
        }
        world.places.forEach { place ->
            Surface(onClick={select(place.id)},color=if(place.id==selectedId)Berry else Paper,contentColor=if(place.id==selectedId)Color.White else Ink,
                shape=RoundedCornerShape(14.dp),modifier=Modifier.offset(x=(width*place.x-56.dp).coerceIn(6.dp,(width-118.dp).coerceAtLeast(6.dp)),y=height*place.y-25.dp).width(112.dp).heightIn(min=50.dp)
                    .testTag("world-node:${place.id}").semantics{selected=place.id==selectedId}) {
                Column(Modifier.padding(horizontal=5.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(place.name.inLanguage(language),fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium)
                }
            }
        }
    }
}

/** Small native illustrations: worn goggles, repaired headphones and a wayfinder's red pencil. */
@Composable internal fun StationPortrait(kind:String,label:String,modifier:Modifier=Modifier,animate:Boolean=true) {
    val enabled=remember{ValueAnimator.areAnimatorsEnabled()}
    val blink=if(animate&&enabled) {
        val transition=rememberInfiniteTransition(label="station-blink")
        transition.animateFloat(1f,1f,infiniteRepeatable(keyframes{durationMillis=5200;1f at 0;1f at 4780;.12f at 4860;1f at 4960},RepeatMode.Restart),label="eyes").value
    } else 1f
    Canvas(modifier.clip(CircleShape).background(if(kind=="keeper")Color(0xFFE3E7E8) else Color(0xFFF0E2DB)).semantics{contentDescription=label}) {
        fun p(x:Float,y:Float)=Offset(size.width*x,size.height*y)
        fun oval(color:Color,x:Float,y:Float,w:Float,h:Float)=drawOval(color,p(x,y),Size(size.width*w,size.height*h))
        val coat=if(kind=="keeper")Color(0xFF526069) else if(kind=="wayfinder")Color(0xFF7D8267) else Color(0xFF79665A)
        oval(coat,.11f,.63f,.78f,.58f)
        oval(Color(0xFFDBB69A),.29f,.24f,.42f,.49f)
        val hair=if(kind=="keeper")Color(0xFFB1B2AB) else Color(0xFF37332F)
        val cap=Path().apply {moveTo(size.width*.25f,size.height*.4f);cubicTo(size.width*.16f,size.height*.02f,size.width*.82f,size.height*.02f,size.width*.76f,size.height*.4f);lineTo(size.width*.64f,size.height*.3f);lineTo(size.width*.35f,size.height*.3f);close()}
        drawPath(cap,hair)
        if(kind=="scavenger") {
            drawLine(coat,p(.2f,.28f),p(.79f,.28f),size.width*.09f)
            oval(Color(0xFFE0BD75),.29f,.2f,.18f,.14f);oval(Color(0xFFE0BD75),.53f,.2f,.18f,.14f)
            oval(Color(0xFF455257),.32f,.225f,.12f,.085f);oval(Color(0xFF455257),.56f,.225f,.12f,.085f)
        }
        if(kind=="keeper") {
            drawArc(coat,190f,160f,false,p(.2f,.18f),Size(size.width*.6f,size.height*.6f),style=Stroke(size.width*.06f))
            oval(coat,.2f,.39f,.1f,.22f);oval(coat,.7f,.39f,.1f,.22f)
            drawLine(Color(0xFFDBBD83),p(.2f,.48f),p(.29f,.51f),size.width*.025f)
        }
        if(kind=="wayfinder")drawLine(Berry,p(.68f,.1f),p(.78f,.39f),size.width*.04f)
        oval(Ink,.37f,.465f,.045f,.045f*blink);oval(Ink,.58f,.465f,.045f,.045f*blink)
        drawLine(Color(0xFF996953),p(.46f,.61f),p(.55f,.61f),size.width*.018f)
        val scarf=Path().apply{moveTo(size.width*.25f,size.height*.69f);lineTo(size.width*.73f,size.height*.67f);lineTo(size.width*.67f,size.height*.8f);lineTo(size.width*.33f,size.height*.77f);close()}
        drawPath(scarf,if(kind=="keeper")Color(0xFFC9B58D) else Berry)
    }
}
