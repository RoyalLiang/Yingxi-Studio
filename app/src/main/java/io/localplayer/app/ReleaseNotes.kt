package io.localplayer.app

/** Keep version identity outside prose and show only the reader's language. */
internal fun displayReleaseNotes(raw:String,language:String):String {
    val zh=Regex("(?m)^## 中文\\s*$").find(raw)
    val en=Regex("(?m)^## English\\s*$").find(raw)
    var text=when {
        zh!=null&&en!=null&&zh.range.first<en.range.first ->
            if(language=="zh")raw.substring(zh.range.last+1,en.range.first) else raw.substring(en.range.last+1)
        else -> raw
    }
    text=text.split(Regex("(?im)^### (?:验证与限制|Validation and limitations)\\s*$"),limit=2).first()
    text=text.split(Regex("(?m)^---\\s*\\n\\s*(?:版本 / Version|Version:)"),limit=2).first()
    return text.lineSequence().filterNot{it.trim().startsWith("# ")}
        .map{it.removePrefix("### ").removePrefix("## ").replace(Regex("^- "),"• ")}
        .joinToString("\n").trim().ifBlank{"这封信笺还没有附上正文。"}
}
