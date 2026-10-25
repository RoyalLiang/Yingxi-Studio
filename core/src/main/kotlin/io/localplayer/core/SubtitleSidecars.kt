package io.localplayer.core

import java.util.Locale

/** Match the full video basename; never guess between different cuts, episodes or resolutions. */
object SubtitleSidecars {
    val audioExtensions=setOf("mka","m4a","aac","ac3","eac3","mp3","flac","ogg","opus","wav")
    fun candidates(video:String,names:List<String>,bilingual:Boolean=false):List<String> {
        val base=video.substringBeforeLast('.').lowercase(Locale.ROOT)
        val suffix=Regex("(?i)^(?:[ ._-](?:zh-hans|zh-hant|zh-cn|zh-tw|en-us|en-gb|zh|cn|chs|cht|chi|zho|en|eng|简体|繁体|中文|双语|bilingual|forced|sdh|cc|default|简中|繁中))*$")
        return names.filter { name->
            val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT)
            val stem=name.substringBeforeLast('.').lowercase(Locale.ROOT)
            ext in setOf("srt","ass","ssa","vtt")&&stem.startsWith(base)&&suffix.matches(stem.removePrefix(base))
        }.sortedWith(compareByDescending<String> { name->
            val s=name.lowercase(Locale.ROOT)
            when { bilingual&&listOf("双语","bilingual").any{s.contains(it)}->4
                listOf("chs","zh-cn","zh-hans","简体","简中").any{s.contains(it)}->3
                listOf("zh","chi","zho","中文").any{s.contains(it)}->2
                name.substringBeforeLast('.').equals(video.substringBeforeLast('.'),true)->1
                else->0 }
        }.thenBy{it.lowercase(Locale.ROOT)})
    }
    fun audioCandidates(video:String,names:List<String>):List<String> {
        val base=video.substringBeforeLast('.').lowercase(Locale.ROOT)
        val suffix=Regex("(?i)^(?:[ ._-](?:zh-hans|zh-hant|zh-cn|zh-tw|zh|cn|chs|cht|chi|zho|en|eng|ja|jpn|ko|kor|国语配音|国语|国配|粤语|普通话|中文|英语|日语|韩语|mandarin|cantonese|english|japanese|korean|双语|国粤|commentary|default|aac|ac3|eac3|flac|dts|2[._]0|5[._]1|7[._]1))*$")
        return names.filter { name ->
            val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT)
            val stem=name.substringBeforeLast('.').lowercase(Locale.ROOT)
            ext in audioExtensions&&stem.startsWith(base)&&suffix.matches(stem.removePrefix(base))
        }.sortedBy{it.lowercase(Locale.ROOT)}
    }

}
