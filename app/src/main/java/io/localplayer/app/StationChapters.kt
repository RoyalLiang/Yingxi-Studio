package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class WorldLetter(val id:String,val author:String,val place:String,val title:WorldText,val body:WorldText)
internal data class WorldChapter(val id:String,val title:WorldText,val introduction:WorldText,val letters:List<WorldLetter>)

/** Store stable content IDs so adding letters never silently shifts a reader's position. */
internal class ChapterReading(context:android.content.Context,name:String="station-reading-v1") {
    private val p=MigratingPreferences.open(context,name)
    fun chapter(chapters:List<WorldChapter>)=chapters.firstOrNull { it.id==p.getString("chapter",null) } ?: chapters.first()
    fun letter(chapter:WorldChapter)=chapter.letters.firstOrNull { it.id==p.getString("letter:${chapter.id}",null) } ?: chapter.letters.first()
    fun select(chapter:WorldChapter,letter:WorldLetter) {
        require(letter in chapter.letters)
        p.edit().putString("chapter",chapter.id).putString("letter:${chapter.id}",letter.id).apply()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun StationChapterReader(world:StationWorld,language:String,onTurn:()->Unit) {
    val context=LocalContext.current
    val reading=remember(context) { ChapterReading(context) }
    var chapterId by remember { mutableStateOf(reading.chapter(world.chapters).id) }
    val chapter=world.chapters.first { it.id==chapterId }
    var letterId by remember(chapterId) { mutableStateOf(reading.letter(chapter).id) }
    val index=chapter.letters.indexOfFirst { it.id==letterId }.coerceAtLeast(0)
    val letter=chapter.letters[index]
    fun select(target:WorldLetter) { letterId=target.id;reading.select(chapter,target);onTurn() }
    fun WorldText.say()=inLanguage(language)
    FlowRow(Modifier.fillMaxWidth().testTag("world-chapters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        world.chapters.forEachIndexed { number,item ->
            FilterChip(selected=item.id==chapter.id,onClick={chapterId=item.id;reading.select(item,reading.letter(item));onTurn()},
                label={Text(if(language=="zh")"第${if(number==0)"一"else"二"}章" else "Chapter ${number+1}")},
                modifier=Modifier.heightIn(min=48.dp).testTag("world-chapter:${item.id}"))
        }
    }
    Text(chapter.title.say(),fontSize=15.sp,color=Berry,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("chapter-title"))
    Text(chapter.introduction.say(),fontSize=14.sp,lineHeight=24.sp,color=Muted)
    Surface(color=GroupedSurface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().testTag("chapter-letter:${letter.id}")) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(letter.title.say(),fontSize=22.sp,lineHeight=31.sp,fontFamily=FontFamily.Serif,color=Ink)
            val person=world.people.first { it.id==letter.author }
            val place=world.places.first { it.id==letter.place }
            Text(person.name.say()+" · "+place.name.say(),fontSize=12.sp,lineHeight=19.sp,color=Berry)
            Text(letter.body.say(),fontSize=16.sp,lineHeight=28.sp,color=Ink,modifier=Modifier.testTag("chapter-letter-body"))
            Text(if(language=="zh")"第 ${index+1} / ${chapter.letters.size} 封" else "Letter ${index+1} / ${chapter.letters.size}",fontSize=12.sp,color=Muted,modifier=Modifier.testTag("chapter-position"))
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={select(chapter.letters[index-1])},enabled=index>0,modifier=Modifier.testTag("chapter-previous")) {
                    Text(if(language=="zh")"上一封" else "Previous letter")
                }
                Button(onClick={select(chapter.letters[index+1])},enabled=index<chapter.letters.lastIndex,modifier=Modifier.testTag("chapter-next")) {
                    Text(if(language=="zh")"下一封" else "Next letter")
                }
            }
        }
    }
}
