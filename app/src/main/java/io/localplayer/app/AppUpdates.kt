package io.localplayer.app

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

internal data class AppVersion(val name:String,val code:Long) {
    val isPrerelease:Boolean get()='-' in name.substringBefore('+')
}
internal fun installedVersion(context:Context):AppVersion = context.packageManager.getPackageInfo(context.packageName,0).let { AppVersion(it.versionName ?: "",it.longVersionCode) }

internal data class AppRelease(val version:AppVersion,val notes:String,val url:String,val size:Long,val sha256:String,val signer:String)

/** Uses GitHub's APK digest and the installed signing identity. */
internal class AppUpdates(private val context:Context,private val transport:((String)->HttpURLConnection)?=null) {
    suspend fun check():AppRelease? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val current=installedVersion(context)
        val installed=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val signer=installed.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()?.let{MessageDigest.getInstance("SHA-256").digest(it).hex()}
            ?: throw IOException("无法读取当前应用签名")
        var best:AppRelease?=null
        val checkId=java.util.UUID.randomUUID().toString()
        for(page in 1..10) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val releases=JSONArray(fetch("https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=$page&_yingxi_check=$checkId",2*1024*1024).toString(Charsets.UTF_8))
            for(index in 0 until releases.length()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val release=releases.getJSONObject(index)
                if(release.optBoolean("draft") || (release.optBoolean("prerelease") && !current.isPrerelease))continue
                val parsed=parseAssetRelease(release,signer,current.code) ?: continue
                if(parsed.version.code>current.code && parsed.version.code>(best?.version?.code ?: 0))best=parsed
            }
            if(releases.length()<100)return@withContext best
        }
        throw IOException("版本列表过大，未能完整检查，请到发布页查看")
    }

    suspend fun download(release:AppRelease,progress:suspend (Float)->Unit):File = downloadLock.withLock { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(release.version.code>installedVersion(context).code){"更新版本必须高于当前版本"}
        requireReleaseUrl(release.url)
        require(release.sha256.matches(Regex("[a-f0-9]{64}"))&&release.size in 1..MAX_APK){"无效的安装包校验信息"}
        val partial=partialFile(context,release)
        partial.parentFile!!.mkdirs()
        if(partial.length()>release.size)partial.delete()
        var count=partial.length()
        progress(count.toFloat()/release.size)
        if(count<release.size) {
            val headers=buildMap { put("Accept-Encoding","identity");if(count>0)put("Range","bytes=$count-") }
            val connection=open(release.url,headers)
            try {
                val status=connection.responseCode
                if(status==416){partial.delete();throw IOException("下载断点已失效，请重试")}
                val start=if(status==206) {
                    val range=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range")?:"")
                        ?:throw IOException("服务器返回了无效的下载断点")
                    val values=range.groupValues.drop(1).map{it.toLongOrNull()?:throw IOException("无效的下载范围")}
                    if(values[0]!=count||values[1]!=release.size-1||values[2]!=release.size)throw IOException("服务器下载范围与安装包不匹配")
                    count
                } else if(status==200)0L else throw IOException("下载失败（HTTP $status）")
                val encoding=connection.getHeaderField("Content-Encoding")
                if(encoding!=null&&!encoding.equals("identity",true))throw IOException("安装包传输编码不匹配")
                val declared=connection.contentLengthLong
                if(declared>=0&&declared!=release.size-start)throw IOException("安装包大小与发布信息不匹配")
                count=start
                progress(count.toFloat()/release.size)
                var lastReport=count
                connection.inputStream.use { input -> java.io.FileOutputStream(partial,start>0).use { output ->
                    try {
                        val buffer=ByteArray(64*1024)
                        while(true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n=input.read(buffer);if(n<0)break
                            if(n.toLong()>release.size-count){partial.delete();throw IOException("安装包超出声明大小")}
                            output.write(buffer,0,n);count+=n
                            if(count-lastReport>=256*1024||count==release.size){progress(count.toFloat()/release.size);lastReport=count}
                        }
                    } finally {output.fd.sync()}
                } }
                if(count!=release.size)throw IOException("下载连接中断，已保留进度，可继续下载")
            } finally {connection.disconnect()}
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val hash=partial.inputStream().use { input ->
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
            while(true){kotlinx.coroutines.currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
            digest.digest().hex()
        }
        if(partial.length()!=release.size||hash!=release.sha256){partial.delete();throw IOException("安装包完整性校验失败，请重新下载")}
        try {verifyArchive(context,partial,release)} catch(e:Exception){partial.delete();throw e}
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // Published installer paths remain immutable; only partial transfers use a stable identity.
        val ready=File(partial.parentFile,"yingxi-${release.version.code}-${release.sha256}-${java.util.UUID.randomUUID()}.apk")
        if(!partial.renameTo(ready))throw IOException("无法保存安装包")
        progress(1f)
        ready
    } }

    private fun open(value:String,headers:Map<String,String> = emptyMap()):HttpURLConnection =
        transport?.invoke(value)?.apply{headers.forEach{(key,value)->setRequestProperty(key,value)}}?:connect(value,headers)

    private fun fetch(value:String,limit:Int):ByteArray {
            val connection=open(value)
            try {
                if(connection.responseCode!=200)throw IOException(if(connection.responseCode in setOf(403,429))"更新服务暂时限制请求，请稍后重试" else "检查更新失败（HTTP ${connection.responseCode}）")
                return connection.inputStream.use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;if(output.size()+n>limit)throw IOException("更新信息超出大小限制");output.write(buffer,0,n)}
                    output.toByteArray()
                }
            } finally {connection.disconnect()}
        }

    companion object {
        private val downloadLock=kotlinx.coroutines.sync.Mutex()
        fun partialFile(context:Context,release:AppRelease)=File(context.filesDir,"updates/${release.version.code}-${release.sha256}.apk.part")

        fun parseAssetRelease(release:JSONObject,signer:String,minimumCode:Long=0):AppRelease? {
            val tag=release.optString("tag_name")
            if(!Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?").matches(tag))return null
            require(signer.matches(Regex("[a-f0-9]{64}"))){"当前签名信息无效"}
            val name=tag.removePrefix("v")
            val assets=release.getJSONArray("assets")
            val pattern=Regex("yingxi-${Regex.escape(name)}-([1-9][0-9]*)\\.apk")
            val matches=(0 until assets.length()).map{assets.getJSONObject(it)}.filter{pattern.matches(it.optString("name"))}
            if(matches.isEmpty())return null
            val codes=matches.map{pattern.matchEntire(it.getString("name"))!!.groupValues[1].toLongOrNull() ?: throw IOException("安装序号无效")}
            if(codes.all{it<=minimumCode})return null
            val asset=matches.singleOrNull() ?: throw IOException("未找到唯一的正式安装包，请前往发布页查看")
            val code=pattern.matchEntire(asset.getString("name"))!!.groupValues[1].toLongOrNull() ?: throw IOException("安装序号无效")
            require(code in 1..2_100_000_000){"安装序号无效"}
            val digest=asset.optString("digest")
            require(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)){"安装包缺少 SHA-256，请前往发布页手动更新"}
            val size=asset.getLong("size");require(size in 1..MAX_APK){"无效的安装包大小"}
            require(asset.optString("state")=="uploaded"){"安装包尚未上传完成"}
            val url=asset.getString("browser_download_url");requireReleaseUrl(url)
            require(URI(url).path.substringBeforeLast('/').substringAfterLast('/')==tag && URI(url).path.substringAfterLast('/')==asset.getString("name")){"安装包与版本不一致"}
            return AppRelease(AppVersion(name,code),release.optString("body").take(32000),url,size,digest.substringAfter(':').lowercase(),signer)
        }
        const val REPOSITORY="RoyalLiang/Yingxi-Studio"
        const val RELEASES_PAGE="https://github.com/$REPOSITORY/releases"
        fun parseManifest(manifest:JSONObject,release:JSONObject):AppRelease {
            require(manifest.getInt("formatVersion")==1){"不支持的更新信息格式"}
            val name=manifest.getString("versionName");val code=manifest.getLong("versionCode")
            require(name.length in 1..128 && code in 1..2100000000L && release.getString("tag_name")=="v$name"){"版本信息不一致"}
            val hash=manifest.getString("sha256").lowercase();val signer=manifest.getString("signerSha256").lowercase()
            require(hash.matches(Regex("[a-f0-9]{64}")) && signer.matches(Regex("[a-f0-9]{64}"))){"无效的安装包校验信息"}
            val size=manifest.getLong("sizeBytes");require(size in 1..MAX_APK){"无效的安装包大小"}
            val assets=release.getJSONArray("assets")
            val file=manifest.getString("fileName");require(file.endsWith(".apk") && '/' !in file && '\\' !in file){"无效的安装包名称"}
            val asset=(0 until assets.length()).map{assets.getJSONObject(it)}.singleOrNull{it.optString("name")==file} ?: throw IOException("未找到对应安装包")
            val url=asset.getString("browser_download_url");requireReleaseUrl(url)
            require(asset.getLong("size")==size && URI(url).path.substringBeforeLast('/').substringAfterLast('/')==release.getString("tag_name")){"安装包与版本不一致"}
            return AppRelease(AppVersion(name,code),release.optString("body").take(32000),url,size,hash,signer)
        }
        fun verifyArchive(context:Context,file:File,release:AppRelease) {
            val flags=PackageManager.GET_SIGNING_CERTIFICATES
            val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags) ?: throw IOException("安装包无法读取")
            val installed=context.packageManager.getPackageInfo(context.packageName,flags)
            require(archive.packageName==context.packageName && archive.longVersionCode==release.version.code && archive.versionName==release.version.name){"安装包版本或包名不匹配"}
            fun signers(info:android.content.pm.PackageInfo)=info.signingInfo?.apkContentsSigners?.map{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()}?.toSet() ?: emptySet()
            val actual=signers(archive)
            require(actual.isNotEmpty() && actual==signers(installed) && actual==setOf(release.signer)){"更新签名与当前应用不一致，无法覆盖安装"}
        }
        fun requireReleaseUrl(value:String) {
            val uri=URI(value)
            require(uri.scheme=="https" && uri.host=="github.com" && uri.userInfo==null && uri.port==-1 && uri.path.startsWith("/$REPOSITORY/releases/download/") && uri.fragment==null){"更新地址不属于官方发布仓库"}
        }
        private const val MAX_APK=350L*1024*1024
        private fun connect(value:String,headers:Map<String,String>):HttpURLConnection {
            var url=URI(value)
            repeat(6) {
                val host=url.host ?: throw IOException("无效的更新地址")
                if(url.scheme!="https" || url.userInfo!=null || !(host=="github.com"||host=="api.github.com"||host.endsWith(".githubusercontent.com")))throw IOException("已阻止非官方更新下载地址")
                val connection=url.toURL().openConnection() as HttpURLConnection
                connection.useCaches=false;connection.setRequestProperty("Cache-Control","no-cache");connection.connectTimeout=10000;connection.readTimeout=20000;connection.instanceFollowRedirects=false
                connection.setRequestProperty("User-Agent","Yingxi-Android-Updater")
                connection.setRequestProperty("Accept","application/vnd.github+json")
                headers.forEach{(key,value)->connection.setRequestProperty(key,value)}
                if(connection.responseCode in setOf(301,302,303,307,308)) {
                    val location=connection.getHeaderField("Location");connection.disconnect()
                    if(location==null)throw IOException("更新地址重定向失败")
                    url=url.resolve(location)
                } else return connection
            }
            throw IOException("更新下载重定向次数过多")
        }

    }
}

private fun ByteArray.hex()=joinToString(""){"%02x".format(it)}
