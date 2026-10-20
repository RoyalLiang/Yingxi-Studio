package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class LetterKind { Paragraph, Heading, Repair, Quote }
internal data class LetterBlock(val kind:LetterKind,val text:String,val number:String="")
internal fun releaseLetterBlocks(raw:String,language:String):List<LetterBlock> {
    val body=displayReleaseNotes(raw,language)
    val blocks=mutableListOf<LetterBlock>()
    val paragraph=mutableListOf<String>()
    fun flush(){if(paragraph.isNotEmpty()){blocks+=LetterBlock(LetterKind.Paragraph,paragraph.joinToString("\n"));paragraph.clear()}}
    body.lineSequence().forEach { original ->
        val line=original.trim()
        val repair=Regex("^(\\d+)[.)]\\s+(.+)$").matchEntire(line)
        when {
            line.isBlank()->flush()
            line.startsWith(">")->{flush();blocks+=LetterBlock(LetterKind.Quote,line.removePrefix(">").trim())}
            (line.startsWith("【")&&line.endsWith("】"))||(line.startsWith("[")&&line.endsWith("]"))->{flush();blocks+=LetterBlock(LetterKind.Heading,line.drop(1).dropLast(1))}
            repair!=null->{flush();blocks+=LetterBlock(LetterKind.Repair,repair.groupValues[2],repair.groupValues[1])}
            else->paragraph+=line
        }
    }
    flush();return blocks
}

@Composable internal fun ReleaseLetterContent(raw:String,language:String,modifier:Modifier=Modifier) {
    Column(modifier.testTag("letter-paragraphs"),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        releaseLetterBlocks(raw,language).forEach { block ->
            when(block.kind) {
                LetterKind.Heading->Text(block.text,fontSize=12.sp,lineHeight=19.sp,fontWeight=FontWeight.SemiBold,color=Muted,modifier=Modifier.padding(top=7.dp))
                LetterKind.Repair->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(block.number.padStart(2,'0'),fontSize=12.sp,lineHeight=25.sp,color=Berry,fontWeight=FontWeight.Medium)
                    Text(block.text,fontSize=15.sp,lineHeight=26.sp,color=Ink,modifier=Modifier.weight(1f))
                }
                LetterKind.Quote->Text(block.text,fontFamily=FontFamily.Serif,fontSize=18.sp,lineHeight=30.sp,color=Berry,modifier=Modifier.padding(vertical=5.dp).testTag("letter-quote"))
                LetterKind.Paragraph->Text(block.text,fontFamily=FontFamily.Serif,fontSize=16.sp,lineHeight=28.sp,color=Ink)
            }
        }
    }
}
