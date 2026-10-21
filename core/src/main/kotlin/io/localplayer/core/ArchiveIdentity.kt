package io.localplayer.core

/** Conservative filename/directory hints. These never rename files or define playback identity. */
data class ArchiveIdentity(val title:String,val category:String,val season:Int?,val episode:Int?) {
    companion object {
        fun parse(name:String,path:String=name):ArchiveIdentity {
            val id=MediaIdentity.forPoster(name,path)
            val period=Regex("第\\s*(\\d{1,3})\\s*期").find(name)
            val chinese=Regex("第\\s*(\\d{1,3})\\s*[集话話]").find(name)
            val ep=id.episode ?: chinese?.groupValues?.get(1)?.toIntOrNull() ?: period?.groupValues?.get(1)?.toIntOrNull()
            val variety=period!=null||path.split('/').any{it in setOf("综艺","綜藝")}
            val category=when { variety->"综艺";ep!=null||id.series||id.season!=null->"剧集";else->"电影" }
            val marker=chinese?:period
            val title=if(marker!=null&&marker.range.first>0)id.title.substringBefore(marker.value).trim(' ','.','_','-')else id.title
            return ArchiveIdentity(title.ifBlank{mediaDisplayTitle(name)},category,id.season,ep)
        }
    }
}
