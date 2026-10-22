package io.localplayer.app

import android.content.Context
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
import androidx.compose.ui.graphics.Color
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
internal data class WorldTerm(val id:String,val status:String,val category:WorldText,val name:WorldText,val summary:WorldText,val proposal:WorldText?)
internal data class StationWorld(val chapter:WorldText,val premise:WorldText,val boundary:WorldText,val places:List<WorldPlace>,val people:List<WorldPerson>,val edges:List<WorldEdge>,val chronicles:List<WorldChronicle>,val lexicon:List<WorldTerm>,val chapters:List<WorldChapter>) {
    companion object {
        fun load(context:Context):StationWorld {
            val root=JSONObject(context.resources.openRawResource(R.raw.world_atlas).bufferedReader().use{it.readText()})
            fun JSONObject.words(key:String)=getJSONObject(key).let{WorldText(it.getString("zh"),it.getString("en"))}
            fun JSONObject.objects(key:String)=getJSONArray(key).let{a->(0 until a.length()).map(a::getJSONObject)}
            return StationWorld(root.words("chapter"),root.words("premise"),root.words("boundary"),
                root.objects("nodes").map{WorldPlace(it.getString("id"),it.words("name"),it.words("kind"),it.words("description"),it.getDouble("x").toFloat(),it.getDouble("y").toFloat(),it.getBoolean("known"))},
                root.objects("characters").map{p->WorldPerson(p.getString("id"),p.words("name"),p.words("role"),p.words("description"),p.getString("home"),p.getString("portrait"),p.objects("quotes").map{WorldText(it.getString("zh"),it.getString("en"))})},
                root.objects("edges").map{WorldEdge(it.getString("from"),it.getString("to"),it.getBoolean("known"))},
                root.objects("chronicles").map{WorldChronicle(it.getString("version"),it.words("text"))},
                root.objects("lexicon").map{WorldTerm(it.getString("id"),it.getString("status"),it.words("category"),it.words("name"),it.words("summary"),if(it.has("proposal"))it.words("proposal")else null)},
                root.objects("chapters").map{c->WorldChapter(c.getString("id"),c.words("title"),c.words("introduction"),c.objects("letters").map{l->WorldLetter(l.getString("id"),l.getString("author"),l.getString("place"),l.words("title"),l.words("body"))})})
        }
    }
}

@Composable internal fun StationWorldPage(embedded:Boolean=false,back:()->Unit) {
    val context=LocalContext.current
    val world=remember(context){StationWorld.load(context)}
    val lang=LocalConfiguration.current.locales[0].language
    fun WorldText.say()=inLanguage(lang)
    var placeId by rememberSaveable{mutableStateOf("station")}
    var personId by rememberSaveable{mutableStateOf("axi")}
    var quote by rememberSaveable{mutableIntStateOf(0)}
    val person=world.people.first{it.id==personId}
    val place=world.places.first{it.id==placeId}
    Column(Modifier.fillMaxSize().background(Paper).then(if(embedded)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)).testTag("station-world")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(!embedded)IconButton(onClick=back,modifier=Modifier.testTag("archives-back")){Icon(Icons.AutoMirrored.Rounded.ArrowBack,if(lang=="zh")"返回" else "Back")}
            Text(if(embedded){if(lang=="zh")"探索"else"Explore"}else if(lang=="zh")"档案室"else"Station archives",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Ink)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=if(embedded)112.dp else 0.dp).testTag("world-scroll"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(!embedded) {
            if(embedded) StationChapterReader(world,lang) else Text(world.chapter.say(),fontSize=13.sp,color=Berry,fontWeight=FontWeight.Medium)
            Text(if(lang=="zh")"灯外，还有整个世界。" else "A world beyond the light.",fontFamily=FontFamily.Serif,fontSize=30.sp,lineHeight=39.sp,color=Ink)
            Text(world.premise.say(),fontSize=15.sp,lineHeight=25.sp,color=Muted)
            Text(if(lang=="zh")"把名字留在信上" else "Names on the letters",fontSize=20.sp,fontFamily=FontFamily.Serif,color=Ink)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                world.people.forEach { p ->
                    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Surface(onClick={personId=p.id;quote=0},color=Color.Transparent,
                            shape=CircleShape,border=androidx.compose.foundation.BorderStroke(
                                if(p.id==personId)2.dp else 1.dp,
                                if(p.id==personId)Berry else Muted.copy(alpha=.18f)),
                            modifier=Modifier.size(64.dp).testTag("world-person:${p.id}").semantics{selected=p.id==personId}) {
                            StationPortrait(p.portrait,p.name.say(),Modifier.fillMaxSize().padding(4.dp),false)
                        }
                        Text(p.name.say(),fontSize=13.sp,color=if(p.id==personId)Berry else Ink,
                            maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier=Modifier.testTag("world-person-name:${p.id}"))
                    }
                }
            }
            androidx.compose.animation.AnimatedContent(targetState=person.portrait,
                modifier=Modifier.align(Alignment.CenterHorizontally),label="character portrait") { kind ->
                Box(Modifier.size(160.dp).testTag("world-profile-art")) {
                    StationPortrait(kind,person.name.say(),Modifier.fillMaxSize())
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
                        Text(if(lang=="zh")"轻触信纸，读下一句" else "Tap the paper for another line",fontSize=11.sp,color=Muted)
                    }
                }
            }
            }
            if(embedded) {
            if(embedded) StationChapterReader(world,lang) else Text(world.chapter.say(),fontSize=13.sp,color=Berry,fontWeight=FontWeight.Medium)
            Text(if(lang=="zh")"沿着旧路标，看看长潮留下了什么。" else "Follow the old markers through the Long Tide.",fontFamily=FontFamily.Serif,fontSize=28.sp,lineHeight=37.sp,color=Ink)
            Text(world.premise.say(),fontSize=15.sp,lineHeight=25.sp,color=Muted)
            WorldMap(world,placeId,lang){placeId=it}
            Text(if(lang=="zh")"废土图谱 · 实线是往返信路，虚线是传闻" else "Wasteland atlas · Solid routes are known; dotted routes are rumors",fontSize=11.sp,lineHeight=18.sp,color=Muted)
            Column(Modifier.fillMaxWidth().testTag("world-place-detail"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(place.name.say()+" · "+place.kind.say(),fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Text(place.description.say(),fontFamily=FontFamily.Serif,fontSize=15.sp,lineHeight=26.sp,color=Ink)
            }
            HorizontalDivider(color=Muted.copy(alpha=.16f))
            WorldDictionary(world.lexicon,lang)
            Text(if(lang=="zh")"据点大事记" else "Station chronicles",fontSize=20.sp,fontFamily=FontFamily.Serif,color=Ink)
            world.chronicles.asReversed().forEach { entry -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(entry.version,fontSize=12.sp,color=Berry,fontWeight=FontWeight.Medium)
                Text(entry.text.say(),fontSize=14.sp,lineHeight=24.sp,color=Ink)
            } }
            }
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
                Box(Modifier.fillMaxWidth().heightIn(min=50.dp).padding(horizontal=5.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
                    Text(place.name.inLanguage(language),textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,modifier=Modifier.fillMaxWidth().testTag("world-node-label:${place.id}"))
                }
            }
        }
    }
}

/** Original portraits share the same art between the home entrance and character records. */
@Composable internal fun StationPortrait(kind:String,label:String,modifier:Modifier=Modifier,roundedCard:Boolean=true) {
    val art=when(kind){"keeper"->R.drawable.portrait_chideng;"wayfinder"->R.drawable.portrait_mitu;else->R.drawable.portrait_axi}
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(art),label,
        modifier.clip(if(roundedCard)RoundedCornerShape(22.dp) else CircleShape).testTag("character-art:$kind"),
        contentScale=androidx.compose.ui.layout.ContentScale.Crop)
}


@Composable
private fun WorldDictionary(terms:List<WorldTerm>,language:String) {
    var open by rememberSaveable{mutableStateOf(false)}
    val zh=language=="zh"
    TextButton(onClick={open=!open},modifier=Modifier.testTag("world-dictionary-toggle")) {
        Text(if(zh){if(open)"收起废土词典"else"废土词典 · ${terms.size} 条"}else{if(open)"Close wasteland dictionary"else"Wasteland dictionary · ${terms.size} entries"})
    }
    if(open) {
        Text(if(zh)"既有设定、传闻与演进提案分别标明。"else"Established facts, rumors, and proposals are labeled separately.",fontSize=12.sp,lineHeight=19.sp,color=Muted)
        terms.forEach { term ->
            Column(Modifier.fillMaxWidth().testTag("world-term:${term.id}"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                val status=if(term.status=="rumor"){if(zh)"传闻"else"Rumor"}else{if(zh)"已确认设定"else"Established"}
                Text(term.category.inLanguage(language)+" · "+status,fontSize=12.sp,color=Berry)
                Text(term.name.inLanguage(language),fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                Text(term.summary.inLanguage(language),fontSize=14.sp,lineHeight=24.sp,color=Ink)
                term.proposal?.let {
                    Text(if(zh)"演进提案"else"Proposal",fontSize=12.sp,color=Berry)
                    Text(it.inLanguage(language),fontSize=14.sp,lineHeight=24.sp,color=Muted)
                }
            }
        }
    }
}
