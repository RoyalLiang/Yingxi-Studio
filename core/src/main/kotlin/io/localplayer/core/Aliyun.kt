package io.localplayer.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Public application identifier. AppSecret is deliberately unsupported. */
object AliyunConfig {
    const val CLIENT_ID = "53a4ba437a8146819b5ebf05235e2762"
    const val SCOPE = "user:base,file:all:read"
}

class AliyunGrant private constructor(val verifier: String, val state: String) {
    // Match both first-party mobile SDKs: retain padding on the S256 challenge.
    // The independently generated verifier and state remain unpadded URL-safe strings.
    val challenge: String get() = Base64.getUrlEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    override fun toString() = "AliyunGrant([redacted])"
    companion object {
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun create(): AliyunGrant {
            val random = SecureRandom()
            return AliyunGrant(encode(ByteArray(32).also(random::nextBytes)), encode(ByteArray(32).also(random::nextBytes)))
        }
    }
}

class AliyunToken(val access: String, val refresh: String?, val expiresAt: Long) {
    init { require(access.isNotBlank()) }
    override fun toString() = "AliyunToken([redacted])"
}
class AliyunUnauthorized : IOException("阿里云盘授权已失效，请重新授权")
enum class AliyunOperation(val title: String) {
    AUTHORIZE("创建授权链接"), TOKEN("换取授权令牌"), ACCOUNT("读取账户"),
    DIRECTORY("读取目录"), FILE("读取文件"), DOWNLOAD("解析播放链接"), OTHER("访问网盘")
}
/** The only provider details allowed to cross the UI error boundary. */
class AliyunApiFailure(operation: AliyunOperation, status: Int, code: String? = null, requestId: String? = null) :
    IOException("阿里云盘${operation.title}失败（$status）" + details(code, requestId)) {
    companion object {
        private val knownCodes = setOf("AppNotExists", "InvalidClientSecret", "InvalidCode", "InvalidClientId",
            "InvalidGrantType", "InvalidParameter", "InvalidParameter.CodeChallenge", "Forbidden", "AccessTokenInvalid",
            "AccessTokenExpired", "InvalidAccessToken", "NotFound", "TooManyRequests", "PermissionDenied",
            "ForbiddenDriveLocked", "ForbiddenDriveNotValid")
        private fun details(code: String?, requestId: String?): String {
            val values = listOfNotNull(code?.takeIf { it in knownCodes },
                requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.let { "请求编号：$it" })
            return if (values.isEmpty()) "" else "\n" + values.joinToString("；")
        }
    }
}
data class AliyunDrive(val userId: String, val name: String, val driveId: String)
data class AliyunItem(val id: String, val name: String, val directory: Boolean, val size: Long, val createdAt: Long?)

/** No automatic redirects: API bearer credentials must remain at the API origin. */
class AliyunApi internal constructor(private val base: HttpUrl, private val client: OkHttpClient) {
    constructor() : this("https://openapi.alipan.com/".toHttpUrl(), OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build())

    private fun execute(request: Request, cancellation: Cancellation): JsonObject {
        cancellation.check()
        val call = client.newCall(request)
        val owner = java.io.Closeable { call.cancel() }
        cancellation.own(owner)
        try {
            call.execute().use { response ->
                if (response.code == 401) throw AliyunUnauthorized()
                if (!response.isSuccessful) throw apiFailure(request, response)
                val body = response.body ?: throw IOException("阿里云盘返回空内容")
                if (body.contentLength() > 4L * 1024 * 1024) throw IOException("阿里云盘响应过大")
                val bytes = body.byteStream().readNBytes(4 * 1024 * 1024 + 1)
                if (bytes.size > 4 * 1024 * 1024) throw IOException("阿里云盘响应过大")
                return try { JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject }
                catch (_: Exception) { throw IOException("阿里云盘响应格式无效") }
            }
        } finally { cancellation.release(owner) }
    }

    /** Only fixed operation names and allowlisted provider diagnostics reach the UI. */
    private fun apiFailure(request: Request, response: okhttp3.Response): AliyunApiFailure {
        val phase = when (request.url.encodedPath) {
            "/oauth/authorize" -> AliyunOperation.AUTHORIZE
            "/oauth/access_token" -> AliyunOperation.TOKEN
            "/adrive/v1.0/user/getDriveInfo" -> AliyunOperation.ACCOUNT
            "/adrive/v1.0/openFile/list" -> AliyunOperation.DIRECTORY
            "/adrive/v1.0/openFile/get" -> AliyunOperation.FILE
            "/adrive/v1.0/openFile/getDownloadUrl" -> AliyunOperation.DOWNLOAD
            else -> AliyunOperation.OTHER
        }
        val details = runCatching {
            val body = JsonParser.parseString(response.peekBody(16 * 1024).string()).asJsonObject
            val code = body.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            val requestId = body.get("requestId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            code to requestId
        }.getOrDefault(null to null)
        return AliyunApiFailure(phase, response.code, details.first, details.second)
    }
    private fun post(path: String, data: JsonObject, token: String?, c: Cancellation): JsonObject {
        val request = Request.Builder().url(base.resolve(path)!!)
            .post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (token != null) request.header("Authorization", "Bearer $token")
        return execute(request.build(), c)
    }
    fun authorization(grant: AliyunGrant, native: Boolean, cancellation: Cancellation): String {
        val url = base.resolve("oauth/authorize")!!.newBuilder()
            .addQueryParameter("client_id", AliyunConfig.CLIENT_ID).addQueryParameter("redirect_uri", "oob")
            .addQueryParameter("response_type", "code").addQueryParameter("scope", AliyunConfig.SCOPE)
            .addQueryParameter("bundle_id", "io.localplayer.app").addQueryParameter("source", if (native) "app" else "appLink")
            .addQueryParameter("code_challenge", grant.challenge).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", grant.state).build()
        val result = execute(Request.Builder().url(url).build(), cancellation).text("redirectUri")
        if (native) {
            val uri = java.net.URI(result)
            if (uri.scheme != "smartdrive") throw IOException("阿里云盘授权跳转地址无效")
            return result
        }
        val web = result.replace("alipan.com/applink/authorize", "alipan.com/o/oauth/authorize").toHttpUrl()
        if (web.scheme != "https" || web.host !in setOf("www.alipan.com", "alipan.com")) throw IOException("阿里云盘授权页面无效")
        return web.newBuilder().addQueryParameter("source", "app_link").addQueryParameter("deep_link", "true").build().toString()
    }
    fun exchange(code: String, verifier: String, c: Cancellation) = token(json("grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier), c)
    fun refresh(value: String, c: Cancellation) = token(json("grant_type" to "refresh_token", "refresh_token" to value), c)
    private fun token(data: JsonObject, c: Cancellation): AliyunToken {
        data.addProperty("client_id", AliyunConfig.CLIENT_ID)
        val result = post("oauth/access_token", data, null, c)
        val seconds = result.get("expires_in")?.asLong ?: throw IOException("授权有效期缺失")
        if (seconds !in 1..31_536_000) throw IOException("授权有效期无效")
        return AliyunToken(result.text("access_token"), result.get("refresh_token")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }, System.currentTimeMillis() + seconds * 1000)
    }
    fun drive(token: String, c: Cancellation): AliyunDrive {
        val result = post("adrive/v1.0/user/getDriveInfo", JsonObject(), token, c)
        return AliyunDrive(result.text("user_id"), result.get("name")?.takeUnless { it.isJsonNull }?.asString ?: "阿里云盘", result.text("default_drive_id"))
    }
    fun list(token: String, drive: String, parent: String, c: Cancellation): List<AliyunItem> {
        val items = ArrayList<AliyunItem>(); val markers = HashSet<String>(); var marker = ""
        do {
            c.check()
            val body = json("drive_id" to drive, "parent_file_id" to parent, "order_by" to "name", "order_direction" to "ASC", "marker" to marker)
            body.addProperty("limit", 100)
            val result = post("adrive/v1.0/openFile/list", body, token, c)
            val page = result.getAsJsonArray("items") ?: throw IOException("网盘列表缺失")
            page.forEach { items.add(item(it.asJsonObject)) }
            if (items.size > 10_000) throw IOException("当前目录超过 10000 项，请拆分目录")
            marker = result.get("next_marker")?.takeUnless { it.isJsonNull }?.asString ?: ""
            if (marker.isNotEmpty() && !markers.add(marker)) throw IOException("网盘分页标记重复")
        } while (marker.isNotEmpty())
        return items
    }
    fun file(token: String, drive: String, id: String, c: Cancellation) = item(post("adrive/v1.0/openFile/get", json("drive_id" to drive, "file_id" to id), token, c))
    fun download(token: String, drive: String, id: String, c: Cancellation): String {
        val result = post("adrive/v1.0/openFile/getDownloadUrl", json("drive_id" to drive, "file_id" to id), token, c).text("url")
        val url = result.toHttpUrl()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("下载地址不安全")
        return result
    }
    private fun item(o: JsonObject): AliyunItem {
        val id = o.text("file_id"); if (id.any { it == '/' || it == '\\' } || id in setOf(".", "..")) throw IOException("文件标识无效")
        val created = o.get("created_at")?.takeUnless { it.isJsonNull }?.asString?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return AliyunItem(id, o.text("name"), o.text("type") == "folder", o.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0, created)
    }
    private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: throw IOException("阿里云盘响应缺少 $key")
    private fun json(vararg entries: Pair<String, String>) = JsonObject().apply { entries.forEach { addProperty(it.first, it.second) } }
}

/** Tokens are provided only for API calls; the CDN client receives no API credentials. */
interface AliyunAccess { fun <T> authorized(cancellation: Cancellation, action: (String) -> T): T }
class AliyunConnector(private val api: AliyunApi, private val access: AliyunAccess, private val rememberName: (String, String) -> Unit = { _, _ -> }) : Connector {
    override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation): RemoteSession {
        require(source.protocol == Protocol.ALIYUN && source.domain.isNotBlank())
        cancellation.check()
        val sessionCancellation=Cancellation().also(cancellation::own)
        return object : RemoteSession {
            private fun id(path: String) = Paths.clean(path).substringAfterLast('/').ifEmpty { "root" }
            override fun list(path: String): List<Entry> = access.authorized(sessionCancellation) { token ->
                api.list(token, source.domain, id(path), sessionCancellation).map { item ->
                    if(item.directory)rememberName(item.id, item.name)
                    Entry(item.name, Paths.join(path, item.id), item.directory, item.size, item.createdAt)
                }.sortedWith(MediaOrdering.entries)
            }
            override fun open(path: String): RemoteFile {
                val file = access.authorized(sessionCancellation) { api.file(it, source.domain, id(path), sessionCancellation) }
                if (file.directory || file.size <= 0) throw IOException("该文件无法播放")
                return AliyunRangeFile(file.size, sessionCancellation) { handle -> access.authorized(handle) { api.download(it, source.domain, file.id, handle) } }
            }
            override fun close() { sessionCancellation.close();cancellation.release(sessionCancellation) }
        }
    }
}

internal class AliyunRangeFile(override val size: Long, private val parent: Cancellation, private val resolve: (Cancellation) -> String) : RemoteFile {
    private val cancellation = Cancellation().also(parent::own)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).readTimeout(30, TimeUnit.SECONDS).build()
    private var url: String? = null
    private var response: okhttp3.Response? = null
    private var stream: java.io.InputStream? = null
    private var cursor = -1L
    private var remaining = 0L
    private var closed = false
    @Volatile private var active: okhttp3.Call? = null
    private val transport = java.io.Closeable { active?.cancel(); response?.close() }.also(cancellation::own)
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length)
        cancellation.check(); if (closed) throw IOException("播放连接已关闭")
        if (length == 0) return 0
        if (position >= size) return -1
        if (position != cursor || remaining == 0L) {
            response?.close(); stream = null
            val end = minOf(size - 1, position + minOf(8L * 1024 * 1024 - 1, size - 1 - position))
            for (attempt in 0..1) {
                cancellation.check()
                if (url == null) url = resolve(cancellation)
                cancellation.check()
                val request = Request.Builder().url(url!!).header("Range", "bytes=$position-$end").header("Accept-Encoding", "identity").build()
                val call = client.newCall(request); active = call
                cancellation.check()
                val result = call.execute()
                if (result.code == 403 && attempt == 0) { result.close(); url = null; continue }
                val expected = "bytes $position-$end/$size"
                if (result.code != 206 || result.header("Content-Range") != expected || result.header("Content-Encoding").let { it != null && it != "identity" } || result.body == null || result.body!!.contentLength().let { it != -1L && it != end - position + 1 }) {
                    result.close(); throw IOException("网盘服务器未正确响应分段播放请求")
                }
                response = result; stream = result.body!!.byteStream(); cursor = position; remaining = end - position + 1; break
            }
        }
        val count = stream?.read(buffer, offset, minOf(length.toLong(), remaining).toInt()) ?: throw IOException("播放连接不可用")
        if (count < 0) throw IOException("视频分段提前结束")
        cursor += count; remaining -= count
        if (remaining == 0L) { response?.close(); stream = null; response = null }
        return count
    }
    override fun close() { cancellation.close(); synchronized(this) { closed = true; response?.close(); stream = null; parent.release(cancellation) } }
}
