package io.localplayer.core

import java.net.URI
import java.net.URLEncoder

/** Build a browser search link; the custom site is never fetched automatically. */
fun subtitleSearchUrl(template:String,query:String):String {
    val value=template.trim()
    require(value.length<=2048&&value.split("{query}").size==2){"搜寻地址需包含一个 {query} 片名占位符"}
    require(query.isNotBlank()){"片名为空，无法搜寻字幕"}
    val url=value.replace("{query}",URLEncoder.encode(query,"UTF-8").replace("+","%20"))
    val uri=runCatching{URI(url)}.getOrNull()
    require(uri!=null&&uri.scheme?.lowercase() in setOf("https","http")&&!uri.host.isNullOrBlank()&&uri.rawUserInfo==null){"请输入不含账号密码的 HTTP 或 HTTPS 搜寻地址"}
    return url
}
