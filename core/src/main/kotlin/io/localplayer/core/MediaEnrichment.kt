package io.localplayer.core

import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Shared limits for independent, credential-free metadata requests. */
class EnrichmentHttp(private val client:OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(6,TimeUnit.SECONDS).callTimeout(18,TimeUnit.SECONDS).build(),private val permitHttpForTests:Boolean=false){
    fun get(url:String,c:Cancellation,limit:Int):ByteArray = bytes(Request.Builder().url(url).build(),c,limit)
    fun bytes(request:Request,c:Cancellation,limit:Int):ByteArray {
        var next=request
        repeat(5){
            c.check()
            val u=next.url
            require((u.isHttps||permitHttpForTests)&&u.username.isEmpty()&&u.password.isEmpty()){"不安全的资料地址"}
            if(!permitHttpForTests)require(u.host!="localhost"&&!u.host.contains(':')&&!u.host.matches(Regex("[0-9.]+"))){"不支持的资料地址"}
            val call=client.newCall(next.newBuilder().header("User-Agent","Yingxi/0.2 (https://github.com/RoyalLiang/Yingxi-Studio)").build())
            val owner=java.io.Closeable{call.cancel()};c.own(owner)
            try{call.execute().use{response->
                if(response.code in listOf(301,302,303,307,308)){
                    val location=response.header("Location")?.let{u.resolve(it)}?:throw IOException("资料重定向无效")
                    next=next.newBuilder().url(location).apply{if(response.code==303)get()}.build()
                }else{
                    if(!response.isSuccessful)throw IOException("资料服务暂不可用（${response.code}）")
                    val body=response.body?:throw IOException("资料响应为空")
                    if(body.contentLength()>limit)throw IOException("资料超过大小限制")
                    val data=body.byteStream().readNBytes(limit+1)
                    if(data.size>limit)throw IOException("资料超过大小限制")
                    c.check();return data
                }
            }}finally{c.release(owner)}
        }
        throw IOException("资料重定向过多")
    }
}

data class MediaIdentity(val title:String,val year:Int?=null,val season:Int?=null,val episode:Int?=null,val series:Boolean=false){
    companion object{
        fun parse(filename:String):MediaIdentity {
            val base=filename.substringAfterLast('/').substringAfterLast('\\').replace(Regex("(?i)\\.(mkv|mp4|avi|mov|m4v|ts|webm)$"),"")
            val episode=Regex("(?i)S(\\d{1,2})E(\\d{1,3})").find(base)
            val year=Regex("(?<![0-9])(?:19|20)[0-9]{2}(?![0-9pPiIxX])").findAll(base).firstOrNull { it.range.first > 0 }
            val cut=listOfNotNull(episode?.range?.first,year?.range?.first,
                Regex("(?i)(?:^|[ ._\\[(-])(?:[0-9]{3,4}[xX][0-9]{3,4}|2160p|1080p|720p|480p|WEB[- .]?DL|BluRay|BDRip|HDRip|x26[45]|H[ .]?26[45])").find(base)?.range?.first).minOrNull()?:base.length
            val title=base.take(cut).replace(Regex("\\[[^]]*]")," ").replace(Regex("[._]+")," ").trim(' ','-','(',')','[',']')
            return MediaIdentity(title.take(150),year?.value?.toIntOrNull(),episode?.groupValues?.get(1)?.toInt(),episode?.groupValues?.get(2)?.toInt())
        }
        /** Only anonymous episode names inherit their containing show's identity. */
        fun forPoster(filename:String,path:String?):MediaIdentity {
            val own=parse(filename)
            val anonymous=own.title.isBlank() || Regex("(?i)^(?:第\\s*)?\\d{1,3}(?:\\s*[集期话話])?$|^(?:e|ep)\\s*\\d{1,3}$").matches(own.title)
            if(!anonymous||path.isNullOrBlank())return own
            val folders=path.replace('\\','/').substringBeforeLast('/',"").split('/').filter{it.isNotBlank()}
            var season=own.season
            for(folder in folders.asReversed()) {
                val seasonFolder=Regex("(?i)^(?:season|s)\\s*(\\d{1,2})$|^第\\s*(\\d{1,2})\\s*季$").matchEntire(folder)
                if(seasonFolder!=null){season=seasonFolder.groupValues.drop(1).firstOrNull{it.isNotBlank()}?.toIntOrNull();continue}
                if(folder.lowercase() in setOf("tv","shows","series","电视剧","電視劇","综艺","綜藝","剧集","视频","videos","movies","电影"))return own
                val show=parse(folder)
                if(show.title.length>=2&&!show.title.all{it.isDigit()})return show.copy(season=season?:show.season,episode=own.episode?:own.title.filter{it.isDigit()}.toIntOrNull(),series=true)
                return own
            }
            return own
        }
    }
    fun normalized()=normalize(title)
}
private fun normalize(s:String)=s.lowercase().replace(Regex("\\s*\\([^)]*(?:film|电影|電影|年)[^)]*\\)"),"").filter{it.isLetterOrDigit()}
data class PosterMatch(val imageUrl:String,val sourceUrl:String,val credit:String,val confidence:Double=.98)
/** Reject uncertain identity rather than showing a plausible but unrelated image. */
class PosterLookup(private val http:EnrichmentHttp=EnrichmentHttp(), private val tvBase:String="https://api.tvmaze.com/", private val wikiBase:String?=null) {
    fun find(id:MediaIdentity,c:Cancellation):PosterMatch? {
        if(id.title.length<2)return null
        if(id.season!=null||id.series) {
            val tv=runCatching {
                val url=tvBase.toHttpUrl().resolve("search/shows")!!.newBuilder().addQueryParameter("q",id.title).build()
                val result=JsonParser.parseString(String(http.bytes(Request.Builder().url(url).build(),c,1024*1024),Charsets.UTF_8)).asJsonArray
                val candidates=result.map{it.asJsonObject.getAsJsonObject("show")}.filter { show ->
                    normalize(show.get("name").asString)==id.normalized() &&
                        (id.year==null||show.get("premiered")?.takeUnless{it.isJsonNull}?.asString?.take(4)?.toIntOrNull()==id.year)
                }
                val show=candidates.singleOrNull()?:return@runCatching null
                val image=show.getAsJsonObject("image")?.get("medium")?.takeUnless{it.isJsonNull}?.asString?:return@runCatching null
                val source=show.get("url").asString.toHttpUrl()
                if(!image.toHttpUrl().isHttps||!source.isHttps)return@runCatching null
                PosterMatch(image,source.toString(),"TVmaze CC BY-SA")
            }.onFailure{c.check()}.getOrNull()
            if(tv!=null)return tv
        }
        val languages=if(id.title.any{it.code in 0x3400..0x9fff})listOf("zh","en")else listOf("en","zh")
        for(lang in languages) {
            val match=runCatching {
                val series=id.series||id.season!=null
                val titles=if(series)listOf("${id.title} (TV series)","${id.title} (电视剧)","${id.title} (电视节目)",id.title)
                    else listOfNotNull(id.year?.let{"${id.title} ($it film)"},id.year?.let{"${id.title} (${it}年电影)"},"${id.title} (film)","${id.title} (电影)",id.title)
                val url=(wikiBase?:"https://$lang.wikipedia.org/w/api.php").toHttpUrl().newBuilder()
                    .addQueryParameter("action","query").addQueryParameter("format","json").addQueryParameter("redirects","1")
                    .addQueryParameter("converttitles","1").addQueryParameter("prop","pageimages|pageprops|categories")
                    .addQueryParameter("piprop","thumbnail|name").addQueryParameter("pilicense","any")
                    .addQueryParameter("cllimit","50").addQueryParameter("pithumbsize","640").addQueryParameter("titles",titles.joinToString("|")).build()
                val result=JsonParser.parseString(String(http.bytes(Request.Builder().url(url).build(),c,1024*1024),Charsets.UTF_8)).asJsonObject
                val query=result.getAsJsonObject("query")?:return@runCatching null
                // Follow only conversions and redirects returned for the exact requested titles.
                val accepted=titles.map(::normalize).toMutableSet()
                val links=listOf("normalized","converted","redirects").flatMap{query.getAsJsonArray(it)?.toList()?:emptyList()}
                repeat(links.size+1) { links.forEach { value ->
                    val link=value.asJsonObject
                    if(normalize(link.get("from").asString) in accepted)accepted+=normalize(link.get("to").asString)
                } }
                val pages=query.getAsJsonObject("pages")?:return@runCatching null
                val matches=pages.entrySet().map{it.value.asJsonObject}.filter { page ->
                    if(page.get("pageid")?.asLong?.let{it>0}!=true||!page.has("thumbnail")||page.getAsJsonObject("pageprops")?.has("disambiguation")==true)return@filter false
                    val title=page.get("title").asString
                    val categories=page.getAsJsonArray("categories")?.map{it.asJsonObject.get("title").asString}?:emptyList()
                    val filmQualifier=Regex("(?i)\\([^)]*(?:\\bfilm\\b|电影|電影)[^)]*\\)").containsMatchIn(title)
                    val explicitYears=Regex("[（(]((?:19|20)[0-9]{2})(?:\\s+film|年(?:电影|電影))").findAll(title).map{it.groupValues[1].toInt()}.toSet()
                    val releaseYears=categories.mapNotNull { category ->
                        Regex("(?i)^Category:((?:19|20)[0-9]{2})(?:年(?!代|背景|设定|設定)[^:]*?(?:電影|电影)(?:作品)?$|\\s+films$)")
                            .find(category)?.groupValues?.get(1)?.toInt()
                    }.toSet()
                    val isFilm=filmQualifier||releaseYears.isNotEmpty()||categories.any{Regex("(?i)\\bfilms\\b|電影作品|电影作品|(?:电影|電影|片)$").containsMatchIn(it)}
                    val years=explicitYears.ifEmpty{releaseYears}
                    val isSeries=Regex("(?i)\\([^)]*(?:TV series|television|电视剧|電視劇|电视节目|電視節目)[^)]*\\)").containsMatchIn(title)||categories.any{Regex("(?i)television series|television shows|reality shows|电视剧|電視劇|电视节目|電視節目|综艺|綜藝").containsMatchIn(it)}
                    val seriesYears=categories.mapNotNull{Regex("(?i)((?:19|20)[0-9]{2})(?:年.*(?:开播|開播|首播)|.*television series debuts)").find(it)?.groupValues?.get(1)?.toIntOrNull()}
                    normalize(title) in accepted && (if(series)isSeries else isFilm) && (id.year==null||id.year in if(series)seriesYears else years)
                }.distinctBy{it.get("pageid").asLong}
                val page=matches.singleOrNull()?:return@runCatching null
                val image=page.getAsJsonObject("thumbnail").get("source").asString.toHttpUrl()
                if(!image.isHttps||image.host!="upload.wikimedia.org")return@runCatching null
                val title=page.get("title").asString
                PosterMatch(image.toString(),"https://$lang.wikipedia.org/wiki/"+java.net.URLEncoder.encode(title.replace(' ','_'),"UTF-8"),"Wikipedia / Wikimedia")
            }.onFailure{c.check()}.getOrNull()
            if(match!=null)return match
        }
        return null
    }
}

/** A shared folder cover is safe only when every video belongs to the same title. */
fun posterSidecarNames(filename:String,siblings:List<String>,path:String?=null):List<String> {
    val stem=filename.substringBeforeLast('.',filename)
    val specific=listOf("$stem-poster.jpg","$stem.jpg","$stem-poster.png","$stem.png")
    val identity=MediaIdentity.forPoster(filename,path)
    val videos=siblings.filter{Regex("(?i)\\.(mkv|mp4|avi|mov|m4v|ts|webm)$").containsMatchIn(it)}
    val sameTitle=videos.isNotEmpty()&&videos.all { name ->
        val candidate=MediaIdentity.forPoster(name,path?.substringBeforeLast('/',"")?.let{"$it/$name"})
        candidate.normalized()==identity.normalized()&&candidate.year==identity.year&&(candidate.season==null)==(identity.season==null)
    }
    return specific + if(sameTitle)listOf("poster.jpg","folder.jpg","cover.jpg","poster.png","folder.png","cover.png")else emptyList()
}

data class SubtitleMatch(val url:String,val extension:String,val label:String,val delayMs:Long)
class ShooterSubtitles(private val http:EnrichmentHttp=EnrichmentHttp(),private val endpoint:String="https://www.shooter.cn/api/subapi.php"){
    fun fingerprint(file:RemoteFile,c:Cancellation):String {
        require(file.size>=12288){"影片太小，无法计算字幕指纹"}
        return listOf(4096L,file.size/3*2,file.size/3,file.size-8192).joinToString(";"){at->
            val data=ByteArray(4096);var n=0
            while(n<data.size){c.check();val count=file.readAt(at+n,data,n,data.size-n);if(count<=0)throw IOException("影片指纹读取中断");n+=count}
            MessageDigest.getInstance("MD5").digest(data).joinToString(""){"%02x".format(it)}
        }
    }
    fun search(file:RemoteFile,title:String,bilingual:Boolean,c:Cancellation):List<SubtitleMatch>{
        val body=FormBody.Builder().add("filehash",fingerprint(file,c)).add("pathinfo",title.substringAfterLast('/').substringAfterLast('\\'))
            .add("format","json").add("lang","chn").build()
        val bytes=http.bytes(Request.Builder().url(endpoint).post(body).build(),c,1024*1024)
        if(bytes.size==1&&bytes[0]==(-1).toByte())return emptyList()
        val array=JsonParser.parseString(String(bytes,Charsets.UTF_8)).asJsonArray
        val matches=mutableListOf<SubtitleMatch>()
        array.take(20).forEach{item->val o=item.asJsonObject;val label=o.get("Desc")?.takeUnless{it.isJsonNull}?.asString?:"射手字幕"
            val delay=o.get("Delay")?.takeUnless{it.isJsonNull}?.asLong?:0
            if(delay !in -600_000L..600_000L)return@forEach
            o.getAsJsonArray("Files")?.take(8)?.forEach{value->val f=value.asJsonObject;val ext=f.get("Ext")?.asString?.lowercase()?.removePrefix(".")
                val link=f.get("Link")?.asString?.let{runCatching{it.toHttpUrl()}.getOrNull()}
                if(ext in setOf("srt","ass","ssa","vtt")&&link?.isHttps==true)matches+=SubtitleMatch(link.toString(),ext!!,label.take(120),delay)
            }
        }
        return matches.distinctBy{it.url}.sortedByDescending{
            val dual=it.label.contains("双语")||it.label.contains("雙語")
            val simplified=Regex("(?i)简|簡|\\bchs\\b|zh-cn|simplified").containsMatchIn(it.label)
            val traditional=Regex("(?i)繁|\\bcht\\b|zh-tw|traditional").containsMatchIn(it.label)
            (if(bilingual&&dual)200 else 0)+(if(simplified)100 else 0)+(if(dual)10 else 0)-(if(traditional)10 else 0)+(if(it.extension=="srt")2 else 0)
        }
    }
    fun download(match:SubtitleMatch,c:Cancellation):ByteArray {
        var data=http.bytes(Request.Builder().url(match.url).build(),c,4*1024*1024)
        if(data.size>2&&data[0]==0x1f.toByte()&&data[1]==0x8b.toByte())data=java.util.zip.GZIPInputStream(data.inputStream()).use{it.readNBytes(4*1024*1024+1)}
        require(data.size<=4*1024*1024){"字幕文件超过4MB"}
        val text=decode(data)
        require(!text.trimStart().startsWith("<html",true)&&!text.contains("\u0000")){"字幕内容无效"}
        val valid=when(match.extension){"ass","ssa"->text.contains("[Events]",true)&&text.contains("Dialogue:",true);else->text.contains("-->")}
        require(valid){"字幕格式无效"}
        val shifted=if(match.delayMs==0L)text else shift(text,match.extension,match.delayMs)
        return shifted.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8)
    }
    fun normalizeSidecar(data:ByteArray,extension:String):ByteArray {
        require(data.size<=4*1024*1024){"字幕文件超过4MB"}
        val text=decode(data).removePrefix("\uFEFF")
        require(!text.contains("\u0000")&&!text.trimStart().startsWith("<html",true)){"字幕内容无效"}
        require(if(extension in setOf("ass","ssa"))text.contains("[Events]",true)&&text.contains("Dialogue:",true)else text.contains("-->")){"字幕格式无效"}
        return text.toByteArray(Charsets.UTF_8)
    }
    private fun decode(data:ByteArray):String {
        fun strict(charset:java.nio.charset.Charset,bytes:ByteArray=data)=charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        if(data.size>=2&&data[0]==0xff.toByte()&&data[1]==0xfe.toByte())return strict(Charsets.UTF_16LE,data.copyOfRange(2,data.size))
        if(data.size>=2&&data[0]==0xfe.toByte()&&data[1]==0xff.toByte())return strict(Charsets.UTF_16BE,data.copyOfRange(2,data.size))
        try{return strict(Charsets.UTF_8)}catch(_:java.nio.charset.CharacterCodingException){}
        val common="的一是在不了有和人這这中國国來来時时會会我你他她們们說说個个為为以要對对都上到也就那能過过著着還还去看好".toSet()
        return listOf("GB18030","Big5").map{String(data,java.nio.charset.Charset.forName(it))}
            .filter{it.count{c->c=='\uFFFD'}*1000<=it.length}
            .maxByOrNull { text ->
                val chinese=text.count{it.code in 0x3400..0x9fff}
                text.count{it in common}*1000/chinese.coerceAtLeast(1)-text.count{it=='\uFFFD'}*10
            }?:throw IOException("字幕编码无法识别")
    }

    /** Rank dialogue because the service often omits language labels. */
    fun preferenceScore(data:ByteArray,bilingual:Boolean):Int? {
        val text=data.toString(Charsets.UTF_8)
        val ass=text.contains("[Events]",true)
        val dialogue=text.lineSequence().filter{if(ass)it.startsWith("Dialogue:",true) else !it.contains("-->")&&!it.trim().matches(Regex("[0-9]+"))}
            .map{line->if(ass){var rest=line;repeat(9){rest=rest.substringAfter(',',"")};rest}else line}
            .joinToString("\n").replace(Regex("<[^>]*>|\\{[^}]*}"),"")
        val chinese=dialogue.count{it.code in 0x3400..0x9fff}
        val latin=dialogue.count{it in 'a'..'z'||it in 'A'..'Z'}
        if(chinese<2||chinese*25<latin)return null
        val simple="这会国来对过时开里发后与无们汉语简体网艺爱车云风见观听欢龙从长乐万东书门声动学还让该处关线转画仅页买质显传读将为说经实点别当样个种于".toSet()
        val traditional="這會國來對過時開裡發後與無們漢語簡體網藝愛車雲風見觀聽歡龍從長樂萬東書門聲動學還讓該處關線轉畫僅頁買質顯傳讀將為說經實點別當樣個種於".toSet()
        val s=dialogue.count{it in simple};val t=dialogue.count{it in traditional}
        val common=s+t+dialogue.count{it in "的一是在不了有和人我你他她要都上到也就那能去看好"}
        if(chinese>=30&&common*40<chinese)return null
        val script=(s-t)*100/(s+t).coerceAtLeast(1)
        val dual=latin>=12&&latin>=chinese/4
        return 100+script+(if(dual&&bilingual)200 else if(dual)10 else 0)
    }

    private fun shift(text:String,ext:String,delay:Long):String{
        val ass=ext=="ass"||ext=="ssa"
        val pattern=if(ass)Regex("(?m)(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})(?=,)")else Regex("(\\d{1,3}):(\\d{2}):(\\d{2})([,.])(\\d{3})")
        return pattern.replace(text){m->val g=m.groupValues;val fraction=if(ass)g[4].toInt()*10 else g[5].toInt()
            val ms=((g[1].toLong()*3600+g[2].toInt()*60+g[3].toInt())*1000+fraction+delay).coerceAtLeast(0)
            if(ass)"%d:%02d:%02d.%02d".format(ms/3600000,ms/60000%60,ms/1000%60,ms%1000/10)
            else "%02d:%02d:%02d%s%03d".format(ms/3600000,ms/60000%60,ms/1000%60,g[4],ms%1000)
        }
    }
}
