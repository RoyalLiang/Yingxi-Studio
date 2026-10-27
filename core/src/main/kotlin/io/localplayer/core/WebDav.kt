package io.localplayer.core

import okhttp3.Call
import okhttp3.Credentials as HttpCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Standard WebDAV, including OpenList /dav. Credentials never leave the configured origin. */
class WebDavConnector(private val client:OkHttpClient=davClient) : Connector {
    override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation): RemoteSession {
        source.validate(); cancellation.check()
        return DavSession(source, credentials, cancellation,client).also { cancellation.own(it) }
    }
}

private val davClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(8,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()

private class DavSession(val source: Source, credentials: Credentials, val cancellation: Cancellation,val client:OkHttpClient) : RemoteSession {
    private val root = URI(if(source.secure) "https" else "http",null,source.host,source.port,source.root.trimEnd('/')+"/",null,null)
    private val auth = HttpCredentials.basic(source.username,credentials.password,Charsets.UTF_8)
    private val responses = ConcurrentHashMap<Response,Call>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)
    private fun check() { cancellation.check(); if(closed.get()) throw InterruptedIOException("来源已关闭") }
    private fun uri(path:String,directory:Boolean=false):URI {
        val clean=Paths.clean(path)
        return URI(root.scheme,null,root.host,root.port,root.path+clean+if(directory&&clean.isNotEmpty())"/" else "",null,null)
    }
    private fun sameOrigin(url:URI) = url.scheme.equals(root.scheme,true) && url.host.equals(root.host,true) && effectivePort(url)==effectivePort(root)
    private fun effectivePort(url:URI) = if(url.port>=0)url.port else if(url.scheme=="https")443 else 80

    private fun request(url:URI, method:String, range:String?=null, validator:Pair<String,String>?=null, ownCall:((Call)->Unit)?=null):Response {
        var target=url
        repeat(6) { hop ->
            check()
            val ownOrigin=sameOrigin(target)
            if(target.userInfo!=null || target.host==null || target.fragment!=null || target.scheme !in setOf("https","http")) throw IOException("无效的 WebDAV 重定向")
            if(target.scheme=="http" && (source.secure || !ownOrigin)) throw IOException("已阻止不安全的重定向")
            if(method!="GET" && !ownOrigin) throw IOException("目录请求不能重定向到其他服务器")
            val builder=Request.Builder().url(target.toASCIIString()).header("Accept-Encoding","identity")
                .header("User-Agent",source.userAgent.ifEmpty { "Yingxi-WebDAV" })
            if(source.referer.isNotEmpty()) builder.header("Referer",source.referer)
            if(ownOrigin && (source.username.isNotEmpty() || auth!="Basic Og==")) builder.header("Authorization",auth)
            if(range!=null)builder.header("Range",range)
            validator?.let { (name,value) -> builder.header(name,value) }
            if(method=="PROPFIND")builder.header("Depth","1").method(method,PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            val call=client.newCall(builder.build());calls.add(call)
            val response=try { ownCall?.invoke(call);check();call.execute() } catch(e:Exception) { calls.remove(call);call.cancel();throw e }
            responses[response]=call
            try { check() } catch(e:Exception) { release(response);throw e }
            if(response.code in setOf(301,302,303,307,308)) {
                val next=response.header("Location")
                release(response)
                if(next==null || hop==5)throw IOException("WebDAV 重定向次数过多或缺少地址")
                target=target.resolve(next)
            } else return response
        }
        throw IOException("WebDAV 重定向失败")
    }
    private fun release(response:Response?) { if(response==null)return;responses.remove(response)?.let{calls.remove(it)};response.close() }
    private fun status(response:Response, expected:Int) {
        if(response.code!=expected)throw IOException(when(response.code) {
            401,403 -> "WebDAV 登录失败或没有读取权限"
            404 -> "WebDAV 路径不存在；OpenList 通常使用 /dav/"
            412 -> "远程文件已变更，请重新打开"
            429 -> "服务请求过于频繁，请稍后重试"
            else -> "WebDAV 请求失败（HTTP ${response.code}）"
        })
    }

    override fun list(path:String):List<Entry> {
        val folder=uri(path,true)
        val response=request(folder,"PROPFIND")
        try {
            status(response,207)
            val input=response.body?.byteStream() ?: throw IOException("目录响应为空")
            val bytes=input.readNBytesCompat(4*1024*1024+1)
            if(bytes.size>4*1024*1024)throw IOException("目录过大，请缩小挂载目录")
            val xml=bytes.toString(Charsets.UTF_8)
            if(xml.contains("<!DOCTYPE",true)||xml.contains("<!ENTITY",true))throw IOException("不支持的 WebDAV XML")
            val factory=DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware=true;isExpandEntityReferences=false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities",false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities",false) }
            }
            val parser=factory.newDocumentBuilder().apply { setEntityResolver { _,_ -> InputSource(java.io.StringReader("")) } }
            val document=parser.parse(bytes.inputStream())
            val nodes=document.getElementsByTagNameNS("DAV:","response")
            return (0 until nodes.length).mapNotNull { index ->
                check()
                val node=nodes.item(index) as Element
                val href=node.text("href") ?: return@mapNotNull null
                val target=runCatching { folder.resolve(href) }.getOrNull() ?: return@mapNotNull null
                if(!sameOrigin(target) || Regex("%2f|%5c",RegexOption.IGNORE_CASE).containsMatchIn(target.rawPath ?: ""))return@mapNotNull null
                val prefix=folder.path.trimEnd('/')+"/"
                if(!target.path.startsWith(prefix))return@mapNotNull null
                val name=target.path.removePrefix(prefix).trimEnd('/')
                if(name.isEmpty() || '/' in name || '\\' in name || name in setOf(".",".."))return@mapNotNull null
                val stats=node.getElementsByTagNameNS("DAV:","propstat")
                val prop=(0 until stats.length).map { stats.item(it) as Element }.firstOrNull { it.text("status")?.contains(" 200 ")==true }
                    ?: return@mapNotNull null
                val directory=prop.getElementsByTagNameNS("DAV:","collection").length>0
                Entry(name,Paths.join(path,name),directory,prop.text("getcontentlength")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
            }.distinctBy { it.path }.sortedWith(MediaOrdering.entries)
        } finally { release(response) }
    }

    override fun open(path:String):RemoteFile = open(path,0)
    override fun open(path:String,position:Long):RemoteFile {
        require(position>=0)
        val url=uri(path)
        // Reuse metadata headers and body at the extractor's actual offset.
        // No discarded one-byte probe or prefix stream before a seek/rebuffer.
        val initialCall=java.util.concurrent.atomic.AtomicReference<Call?>()
        val initial=request(url,"GET","bytes=$position-",ownCall={initialCall.set(it)})
        val size=try { contentRange(initial,position).third } catch(e:Exception) { release(initial);throw e }
        val etag=initial.header("ETag")
        val modified=initial.header("Last-Modified")
        val validator=when {
            etag!=null && etag.startsWith('"') && etag.endsWith('"') -> "If-Match" to etag
            modified!=null -> "If-Unmodified-Since" to modified
            else -> null
        }
        return object:RemoteFile {
            override val size=size
            @Volatile private var response:Response?=initial
            private val currentCall=initialCall
            private var cursor=position
            private val done=AtomicBoolean(false)
            private fun ensureOpen(){check();if(done.get())throw InterruptedIOException("读取已关闭")}
            @Synchronized override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                require(position>=0 && offset>=0 && length>=0 && offset<=buffer.size && length<=buffer.size-offset)
                ensureOpen()
                if(length==0)return 0
                if(position>=size)return -1
                try {
                    var active=response
                    if(cursor!=position || active==null) {
                        release(active);response=null
                        val next=request(url,"GET","bytes=$position-",validator,ownCall={call->
                            currentCall.set(call)
                            if(done.get()){call.cancel();throw InterruptedIOException("读取已关闭")}
                        })
                        try {
                            ensureOpen()
                            if(contentRange(next,position).third!=size ||
                                etag!=null && next.header("ETag")!=etag ||
                                modified!=null && next.header("Last-Modified")!=modified)
                                throw IOException("远程文件已变更，请重新打开")
                        }
                        catch(e:Exception){release(next);throw e}
                        response=next;active=next;cursor=position
                    }
                    val requested=minOf(length.toLong(),size-position).toInt()
                    val input=active.body?.byteStream() ?: throw IOException("文件响应为空")
                    // RemoteFile permits short reads. Never wait for the whole read-ahead
                    // window: that blocks extractors and the first video frame on slow links.
                    val count=input.read(buffer,offset,requested)
                    if(count<0)throw IOException("远程文件读取中断")
                    cursor+=count;ensureOpen();return count
                } finally {
                    // close may run before request publishes its Response; release it here too.
                    if(done.get()){release(response);response=null}
                }
            }
            override fun close() {
                if(done.compareAndSet(false,true)) {
                    // Track the Call before execute, so waiting for headers is cancellable.
                    currentCall.getAndSet(null)?.cancel()
                    synchronized(this) { release(response);response=null }
                }
            }
        }
    }
    private fun contentRange(response:Response,position:Long):Triple<Long,Long,Long> {
        if(response.code==200)throw IOException("服务器未支持 Range 随机读取，请检查挂载服务的播放配置")
        status(response,206)
        val match=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range") ?: "") ?: throw IOException("无效的 Range 响应")
        val (start,end,total)=match.groupValues.drop(1).map{it.toLongOrNull() ?: throw IOException("无效的文件大小")}
        if(start!=position || end<start || total<=end || response.header("Content-Encoding")?.let{it!="identity"}==true)throw IOException("Range 响应位置不匹配")
        return Triple(start,end,total)
    }
    override fun close() { if(closed.compareAndSet(false,true)){calls.forEach{it.cancel()};responses.keys.toList().forEach(::release)} }
    private fun Element.text(name:String)=getElementsByTagNameNS("DAV:",name).item(0)?.textContent
}

private fun java.io.InputStream.readNBytesCompat(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(out.size()<limit){val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;out.write(buffer,0,n)}
    return out.toByteArray()
}
private const val PROPERTIES="""<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
