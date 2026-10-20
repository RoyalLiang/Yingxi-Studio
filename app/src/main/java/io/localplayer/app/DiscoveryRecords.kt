package io.localplayer.app

import io.localplayer.core.Paths
import io.localplayer.core.Protocol

internal data class DiscoveryKind(val type:String,val protocol:Protocol,val secure:Boolean=true,val requireDavAttribute:Boolean=false)
internal object DiscoveryRecords {
    val kinds=listOf(
        DiscoveryKind("_smb._tcp.",Protocol.SMB),
        DiscoveryKind("_sftp-ssh._tcp.",Protocol.SFTP),
        DiscoveryKind("_ssh._tcp.",Protocol.SFTP),
        DiscoveryKind("_webdav._tcp.",Protocol.WEBDAV,false),
        DiscoveryKind("_webdavs._tcp.",Protocol.WEBDAV,true),
        DiscoveryKind("_http._tcp.",Protocol.WEBDAV,false,true),
        DiscoveryKind("_https._tcp.",Protocol.WEBDAV,true,true)
    )
    fun candidate(kind:DiscoveryKind,name:String,host:String,port:Int,attributes:Map<String,ByteArray?>):Candidate?{
        if(host.isBlank()||port !in 1..65535)return null
        val txt=attributes.mapKeys{it.key.lowercase()}.mapValues{it.value?.toString(Charsets.UTF_8).orEmpty()}
        if(kind.requireDavAttribute&&!txt.any{(key,value)->
            key in setOf("dav","webdav")&&value.lowercase() !in setOf("0","false","no")
        })return null
        val root=if(kind.protocol==Protocol.WEBDAV){
            val path=txt["path"]?:"/"
            if(path.length>2048||path.contains("://")||path.contains('?')||path.contains('#'))return null
            runCatching{"/"+Paths.clean(path)}.getOrNull()?:return null
        }else if(kind.protocol==Protocol.SFTP)"/" else ""
        val hint=when{
            kind.type=="_ssh._tcp."->"SSH 广播候选 · SFTP 仍需连接验证"
            kind.protocol==Protocol.WEBDAV->"WebDAV 广播候选 · "+if(kind.secure)"HTTPS" else "HTTP"
            else->"服务广播候选 · 连接后可浏览"
        }
        return Candidate(name.take(150).ifBlank{host},host,port,kind.protocol,hint,kind.secure,root)
    }
}
