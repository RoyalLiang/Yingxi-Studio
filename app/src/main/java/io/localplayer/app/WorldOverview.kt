package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class WorldDiaryLine(val id:String,val author:String,val text:WorldText)
internal data class WorldAppName(val id:String,val name:WorldText,val meaning:WorldText)
internal data class WorldSurfaceNote(val id:String,val author:String,val eyebrow:WorldText,val title:WorldText,val body:WorldText)

@Composable internal fun LocalArchiveWorldNotice(modifier:Modifier=Modifier) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val world=remember(context) { StationWorld.load(context) }
    val lang=androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val note=world.surfaceNotes.first { it.id=="local_archive" }
    StationNotice(note.eyebrow.inLanguage(lang),note.title.inLanguage(lang),note.body.inLanguage(lang),modifier)
}

@Composable internal fun HistoryWorldNote(modifier:Modifier=Modifier) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val world=remember(context) { StationWorld.load(context) }
    val lang=androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val note=world.surfaceNotes.first { it.id=="history_empty" }
    val person=world.people.first { it.id==note.author }
    Surface(modifier.fillMaxWidth(),color=Paper,shape=androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                StationPortrait(person.portrait,person.name.inLanguage(lang),Modifier.size(52.dp),false)
                Text(person.name.inLanguage(lang),fontSize=12.sp,color=Berry)
            }
            Text(note.title.inLanguage(lang),fontSize=12.sp,color=Muted)
            Text(note.body.inLanguage(lang),fontSize=14.sp,lineHeight=24.sp,color=Ink)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun WorldOverview(world:StationWorld,language:String) {
    val zh=language=="zh"
    val counts=listOf(
        world.places.size to if(zh)"处地点线索"else"places",
        world.people.size to if(zh)"位住民"else"residents",
        world.chapters.size to if(zh)"章来信"else"chapters",
        world.people.sumOf { it.quotes.size } to if(zh)"条语录"else"quotes",
        world.lexicon.size to if(zh)"个词条"else"terms",
        world.dailyLines.size to if(zh)"则札记"else"journal notes"
    )
    Column(Modifier.fillMaxWidth().testTag("world-overview"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(if(zh)"废土档案概览"else"World archive",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=Ink)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            counts.forEach { (count,label) -> Text("$count $label",fontSize=13.sp,lineHeight=21.sp,color=Berry) }
        }
        Text(if(zh)"${world.places.count { it.known }} 处路标已核；${world.places.count { !it.known }} 处位置或路线待核。"else"${world.places.count { it.known }} surveyed places; ${world.places.count { !it.known }} with location or route unverified.",fontSize=12.sp,lineHeight=20.sp,color=Muted)
        Text(world.canonNote.inLanguage(language),fontSize=12.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.testTag("world-canon-boundary"))
    }
}

@Composable internal fun WorldApplicationGlossary(names:List<WorldAppName>,language:String) {
    var open by rememberSaveable { mutableStateOf(false) }
    val zh=language=="zh"
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick={open=!open},modifier=Modifier.testTag("world-app-glossary-toggle")) {
            Text(if(zh)"${if(open)"收起"else"查看"}站内用语 · ${names.size} 项"else"${if(open)"Close"else"Open"} station terms · ${names.size}",color=Berry)
        }
        if(open) names.forEach { item ->
            Column(Modifier.fillMaxWidth().testTag("world-app-name:${item.id}"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(item.name.inLanguage(language),fontSize=16.sp,fontWeight=FontWeight.Medium,color=Ink)
                Text(item.meaning.inLanguage(language),fontSize=14.sp,lineHeight=24.sp,color=Muted)
            }
        }
    }
}
