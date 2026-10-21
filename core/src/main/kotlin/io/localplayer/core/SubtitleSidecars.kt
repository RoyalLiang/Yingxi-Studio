package io.localplayer.core

import java.util.Locale

/** Match the full video basename; never guess between different cuts, episodes or resolutions. */
object SubtitleSidecars {
    fun candidates(video:String,names:List<String>,bilingual:Boolean=false):List<String> {
        val base=video.substringBeforeLast('.').lowercase(Locale.ROOT)
        val suffix=Regex("(?i)^(?:[ ._-](?:zh|cn|chs|cht|chi|zho|en|eng|zh-cn|zh-tw|简体|繁体|中文|双语|bilingual))*$")
        return names.filter { name->
            val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT)
            val stem=name.substringBeforeLast('.').lowercase(Locale.ROOT)
            ext in setOf("srt","ass","ssa","vtt")&&stem.startsWith(base)&&suffix.matches(stem.removePrefix(base))
        }.sortedWith(compareByDescending<String> { name->
            val s=name.lowercase(Locale.ROOT)
            when { bilingual&&listOf("双语","bilingual").any{s.contains(it)}->4
                listOf("chs","zh-cn","简体").any{s.contains(it)}->3
                listOf("zh","chi","zho","中文").any{s.contains(it)}->2
                name.substringBeforeLast('.').equals(video.substringBeforeLast('.'),true)->1
                else->0 }
        }.thenBy{it.lowercase(Locale.ROOT)})
    }
}
