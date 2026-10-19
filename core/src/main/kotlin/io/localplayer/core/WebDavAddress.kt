package io.localplayer.core

import java.net.URI

val Source.webDavAddress:String get()=URI(if(secure)"https" else "http",null,host,port,root,null,null).toASCIIString()

fun Source.withWebDavAddress(address:String):Source {
    val uri=runCatching { URI(address.trim()) }.getOrElse { throw IllegalArgumentException("请填写完整的 WebDAV 地址") }
    require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) { "地址需以 https:// 或 http:// 开头，账号密码请单独填写" }
    val next=copy(protocol=Protocol.WEBDAV,host=uri.host.removeSurrounding("[","]"),port=if(uri.port>0)uri.port else if(uri.scheme=="https")443 else 80,
        secure=uri.scheme=="https",initialPath="",root=uri.path.ifEmpty{"/"}.trimEnd('/').ifEmpty{"/"})
    next.copy(label=next.label.ifBlank{next.host}).validate()
    return next
}
