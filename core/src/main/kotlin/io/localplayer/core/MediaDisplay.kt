package io.localplayer.core

/** Presentation only. Never use the result as a path, lookup key or history identity. */
fun mediaDisplayTitle(filename:String):String {
    val stem=filename.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("(?i)\\.(mkv|mp4|avi|mov|m4v|ts|m2ts|mts|webm|mpg|mpeg|wmv|flv|3gp|vob|iso)$"),"")
    val technical=Regex("(?i)(?:^|[ ._\\[(])(?:\\d{3,4}[xX]\\d{3,4}|2160p|1080[pi]|720p|480p|WEB[- .]?DL|WEBRip|BluRay|BDRip|HDRip|REMUX|x26[45]|[hH][ .]?26[45]|HEVC|AVC)(?=$|[ ._\\])+-])")
        .find(stem)?.range?.first
    val title=(technical?.takeIf{it>0}?.let{stem.take(it)}?:stem)
        .replace(Regex("[._]+")," ").replace(Regex("\\s+")," ").trim(' ','-')
    return title.ifBlank{stem.ifBlank{filename}}
}
