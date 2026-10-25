package io.localplayer.core

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.zip.ZipInputStream

data class SubtitleResource(val id:String,val title:String,val page:String,val automatic:Boolean=false)
data class SubtitleContent(val bytes:ByteArray,val extension:String,val name:String)
class SubtitleDownloadUnavailable:IOException("字幕网站暂未提供可直接下载的地址")

/** Public search and prepare-download flow; website challenges stay on the website. */
class SubHdSubtitles(private val http:EnrichmentHttp=EnrichmentHttp(),base:String="https://subhd.tv/") {
    private val root=base.toHttpUrl()
    private val parser=ShooterSubtitles()
    fun search(identity:MediaIdentity,c:Cancellation):List<SubtitleResource> {
        if(identity.title.length<2||(identity.year==null&&identity.title.all{it.isDigit()}))return emptyList()
        val query=listOfNotNull(identity.title,identity.year?.toString(),identity.season?.let{"S%02d".format(it)}).joinToString(" ")
        val url=root.newBuilder().addPathSegment("search").addPathSegment(query).build()
        val html=http.get(url.toString(),c,2*1024*1024).toString(Charsets.UTF_8)
        fun words(value:String)=value.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter{it.isNotBlank()}
        val wanted=words(identity.title)
        if(wanted.isEmpty())return emptyList()
        return Regex("""(?is)<a\b[^>]*href\s*=\s*["']/a/([A-Za-z0-9]{1,30})["'][^>]*>(.*?)</a>""")
            .findAll(html).take(200).mapNotNull{m->
                val label=text(m.groupValues[2]).take(400)
                val parsed=releaseIdentity(label)
                val tokens=words(parsed.title)
                val at=tokens.windowed(wanted.size).indexOfFirst{it==wanted}
                if(at<0||
                    (identity.year!=null&&parsed.year!=null&&identity.year!=parsed.year)||
                    (identity.season!=null&&parsed.season!=null&&identity.season!=parsed.season)||
                    (identity.episode!=null&&parsed.episode!=null&&identity.episode!=parsed.episode))null
                else {
                    val extra=tokens.take(at)+tokens.drop(at+wanted.size)
                    val chinese=identity.title.any{it.code in 0x3400..0x9fff}
                    val aliasesOnly=extra.all{word->word.all{if(chinese)it in 'a'..'z' else it.code in 0x3400..0x9fff}}
                    val identified=if(identity.episode!=null)identity.season!=null&&parsed.season==identity.season
                        else identity.year!=null&&parsed.year==identity.year&&parsed.season==null&&parsed.episode==null
                    SubtitleResource(m.groupValues[1],label,root.resolve("/a/${m.groupValues[1]}")!!.toString(),aliasesOnly&&identified)
                }
            }.distinctBy{it.id}.sortedByDescending{candidate->
                val parsed=releaseIdentity(candidate.title)
                (if(identity.episode!=null&&parsed.episode==identity.episode)100 else 0)+
                    (if(identity.year!=null&&parsed.year==identity.year)20 else 0)
            }.take(6).toList()
    }
    fun download(resource:SubtitleResource,identity:MediaIdentity,c:Cancellation):List<SubtitleContent> {
        require(Regex("[A-Za-z0-9]{1,30}").matches(resource.id))
        val body="{\"sid\":\"${resource.id}\"}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val request=Request.Builder().url(root.resolve("/api/sub/prepare-download")!!)
            .header("Origin",root.newBuilder().encodedPath("/").build().toString().removeSuffix("/"))
            .header("Referer",root.resolve("/a/${resource.id}")!!.toString()).header("X-Requested-With","XMLHttpRequest").post(body).build()
        val result=JsonParser.parseString(http.bytes(request,c,32*1024).toString(Charsets.UTF_8)).asJsonObject
        val path=result.get("url")?.takeUnless{it.isJsonNull}?.asString
        if(result.get("success")?.asBoolean!=true||path==null||!path.startsWith("/down/")||path.contains('\\'))throw SubtitleDownloadUnavailable()
        val url=root.resolve(path)?:throw SubtitleDownloadUnavailable()
        require(url.host==root.host&&url.port==root.port&&url.scheme==root.scheme&&url.encodedPath.startsWith("/down/")){"字幕下载地址无效"}
        val data=http.get(url.toString(),c,16*1024*1024)
        val entries=if(data.size>=4&&data[0]==0x50.toByte()&&data[1]==0x4b.toByte())unzip(data,c)
            else listOf(SubtitleContent(data,extension(data),resource.title))
        val release=releaseIdentity(resource.title)
        return entries.filter{caption->
            c.check()
            val file=MediaIdentity.parse(caption.name)
            if(identity.episode==null)file.episode==null&&file.season==null&&release.episode==null&&release.season==null else
                identity.season!=null&&(file.season==null||file.season==identity.season)&&
                    (release.season==null||release.season==identity.season)&&
                    (file.season==identity.season||release.season==identity.season)&&
                    (file.episode==identity.episode||(file.episode==null&&release.episode==identity.episode&&entries.size==1))
        }.map{extension(it.bytes);it.copy(bytes=parser.normalizeSidecar(it.bytes,it.extension))}
    }
    private fun unzip(bytes:ByteArray,c:Cancellation):List<SubtitleContent> {
        val results=mutableListOf<SubtitleContent>();var total=0;var count=0
        ZipInputStream(bytes.inputStream(),java.nio.charset.Charset.forName("GB18030")).use{zip->
            while(true){
                c.check();val entry=zip.nextEntry?:break
                require(++count<=96){"字幕压缩包文件过多"}
                val data=zip.readNBytes(4*1024*1024+1);total+=data.size
                require(data.size<=4*1024*1024&&total<=32*1024*1024){"字幕压缩包超过大小限制"}
                val parts=entry.name.replace('\\','/').split('/')
                val ext=entry.name.substringAfterLast('.').lowercase()
                if(!entry.isDirectory&&parts.none{it==".."||it=="."||it=="__MACOSX"||it==".DS_Store"||it.startsWith("._")}&&ext in setOf("srt","ass","ssa","vtt"))
                    results+=SubtitleContent(data,ext,entry.name.take(400))
            }
        }
        return results
    }
    private fun extension(bytes:ByteArray):String {
        val text=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n')
        if(text.startsWith("<!doctype",true)||text.startsWith("<html",true))throw IOException("字幕网站需要在网页中完成验证")
        return when{ text.startsWith("WEBVTT")->"vtt";text.contains("[Events]",true)->"ass";text.contains("-->")->"srt"
            else->throw IOException("资源不是可自动载入的 SRT、ASS、SSA、VTT 或 ZIP 字幕") }
    }
    private fun releaseIdentity(value:String):MediaIdentity {
        val parsed=MediaIdentity.parse(value)
        if(parsed.season!=null)return parsed
        val marker=Regex("(?i)(?:^|[ ._\\-])(?:s|season\\s*)(\\d{1,2})(?=[ ._\\-]|$)|第\\s*(\\d{1,2})\\s*季").find(value)?:return parsed
        val season=marker.groupValues.drop(1).firstOrNull{it.isNotEmpty()}?.toIntOrNull()?:return parsed
        return parsed.copy(title=MediaIdentity.parse(value.take(marker.range.first)).title,season=season,series=true)
    }
    private fun text(html:String)=Regex("<[^>]+>").replace(html," ")
        .replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ")
        .replace(Regex("\\s+")," ").trim()
}
