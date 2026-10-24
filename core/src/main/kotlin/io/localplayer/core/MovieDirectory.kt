package io.localplayer.core

/** A folder click may select one unambiguous movie; never guess between cuts or episodes. */
fun singleMovieEntry(folderName:String,entries:List<Entry>,isVideo:(Entry)->Boolean={it.isVideo}):Entry? {
    val folder=folderName.trim().lowercase()
    if(folder in setOf("", "movies", "movie", "films", "film", "videos", "video", "电影", "影集", "合集", "剧集", "电视剧", "综艺", "媒体", "文件夹", "子文件夹", "新建文件夹"))return null
    val video=entries.filter{!it.directory&&isVideo(it)}.singleOrNull()?:return null
    val extras=Regex("(?i)(^|[ ._\\-])(sample|trailer|teaser|extras?|featurette)([ ._\\-]|$)|预告|花絮|样片")
    val seriesFolder=Regex("(?i)(^|[ ._\\-])(season[ ._\\-]*[0-9]+|s[0-9]{1,3})([ ._\\-]|$)|第[一二三四五六七八九十百0-9]+[季集]|全集|合集")
    val numberedEpisode=Regex("(?i)^([0-9]{1,3}|e[0-9]{1,3}|ep[ ._\\-]*[0-9]{1,3})$")
    if(seriesFolder.containsMatchIn(folderName)||numberedEpisode.matches(video.name.substringBeforeLast('.')))return null
    if(extras.containsMatchIn(video.name)||extras.containsMatchIn(folderName))return null
    if(ArchiveIdentity.parse(folderName).category!="电影"||ArchiveIdentity.parse(video.name,"$folderName/${video.name}").category!="电影")return null
    return video
}
