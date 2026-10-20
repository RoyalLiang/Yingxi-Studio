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
internal class AppUpdates(private val context:Context,private val transport:(String)->HttpURLConnection=Companion::connect) {
    private val downloads=kotlinx.coroutines.sync.Mutex()
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

    suspend fun download(release:AppRelease,progress:suspend (Float)->Unit):File = downloads.withLock { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(release.version.code>installedVersion(context).code){"更新版本必须高于当前版本"}
        val directory=File(context.filesDir,"updates").apply{mkdirs()}
        // Another client or Android's installer may still be reading a published APK.
        val transfer=java.util.UUID.randomUUID().toString()
        val partial=File(directory,"$transfer.apk.part")
        val ready=File(directory,"yingxi-${release.version.code}-${release.sha256}-$transfer.apk")
        try {
            requireReleaseUrl(release.url)
            val connection=transport(release.url)
            try {
                if(connection.responseCode!=200)throw IOException("下载失败（HTTP ${connection.responseCode}）")
                val declared=connection.contentLengthLong
                if(declared>0 && declared!=release.size)throw IOException("安装包大小与发布信息不匹配")
                val digest=MessageDigest.getInstance("SHA-256")
                var count=0L;var lastReport=0L
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n=input.read(buffer);if(n<0)break
                        count+=n;if(count>release.size)throw IOException("安装包超出声明大小")
                        output.write(buffer,0,n);digest.update(buffer,0,n)
                        if(count-lastReport>=256*1024 || count==release.size){progress(count.toFloat()/release.size);lastReport=count}
                    }
                    output.fd.sync()
                } }
                if(count!=release.size || digest.digest().hex()!=release.sha256)throw IOException("安装包完整性校验失败，请重新下载")
            } finally { connection.disconnect() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            verifyArchive(context,partial,release)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(!partial.renameTo(ready))throw IOException("无法保存安装包")
            ready
        } catch(e:Exception) { partial.delete();throw e }
    } }

    private fun fetch(value:String,limit:Int):ByteArray {
            val connection=transport(value)
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
        private fun connect(value:String):HttpURLConnection {
            var url=URI(value)
            repeat(6) {
                val host=url.host ?: throw IOException("无效的更新地址")
                if(url.scheme!="https" || url.userInfo!=null || !(host=="github.com"||host=="api.github.com"||host.endsWith(".githubusercontent.com")))throw IOException("已阻止非官方更新下载地址")
                val connection=url.toURL().openConnection() as HttpURLConnection
                connection.useCaches=false;connection.setRequestProperty("Cache-Control","no-cache");connection.connectTimeout=10000;connection.readTimeout=20000;connection.instanceFollowRedirects=false
                connection.setRequestProperty("User-Agent","Yingxi-Android-Updater")
                connection.setRequestProperty("Accept","application/vnd.github+json")
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
