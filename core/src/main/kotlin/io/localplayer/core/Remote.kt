package io.localplayer.core

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class Protocol { SMB, SFTP, WEBDAV, ALIYUN }

data class Source(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val protocol: Protocol,
    val host: String,
    val port: Int = when(protocol) { Protocol.SMB -> 445; Protocol.SFTP -> 22; Protocol.WEBDAV, Protocol.ALIYUN -> 443 },
    val root: String = if (protocol == Protocol.SMB) "" else "/",
    val username: String = "",
    val domain: String = "",
    val trustedKey: String? = null,
    val trustedAuthority: String? = null,
    val initialPath: String = "",
    val secure: Boolean = true,
    val userAgent: String = "",
    val referer: String = "",
) {
    val authority: String get() = "${host.lowercase()}:$port"
    fun validate() {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '\\' || it == '\u0000' }) { "请填写主机名或 IP，不要包含协议或路径" }
        require(port in 1..65535) { "端口应为 1–65535" }
        require(label.isNotBlank()) { "请填写来源名称" }
        if (protocol == Protocol.SMB) require(root.isNotBlank() && root.none { it == '/' || it == '\\' }) { "SMB 请填写单个共享名" }
        else require(root.startsWith('/')) { "根目录必须以 / 开头" }
        Paths.clean(if (protocol != Protocol.SMB) root else ""); Paths.clean(initialPath)
        if(protocol == Protocol.WEBDAV) {
            require(':' !in username) { "WebDAV 用户名不能包含冒号" }
            require(listOf(userAgent,referer).none { '\r' in it || '\n' in it }) { "请求头不能包含换行" }
            require(referer.isEmpty() || runCatching { java.net.URI(referer).let { it.scheme == "https" && it.host != null && it.userInfo == null } }.getOrDefault(false)) { "Referer 应为 HTTPS 地址" }
        }
    }
}

// Never serialized with Source; the Android host is responsible for encrypted storage.
class Credentials(val password: String) {
    override fun toString(): String = "Credentials([redacted])"
}

data class Entry(val name: String, val path: String, val directory: Boolean, val size: Long, val createdAt: Long? = null) {
    val isVideo: Boolean get() = !directory && name.substringAfterLast('.', "").lowercase() in
        setOf("mp4", "mkv", "m4v", "mov", "webm", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "asf", "rm", "flv", "3gp")
}

object Paths {
    fun clean(path: String): String {
        require('\u0000' !in path) { "路径包含无效字符" }
        val pieces = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(pieces.none { it == ".." }) { "路径不能包含 .." }
        return pieces.joinToString("/")
    }
    fun join(parent: String, name: String): String {
        require(name.isNotEmpty() && name.none { it == '/' || it == '\\' } && name != "." && name != "..") { "无效文件名" }
        return clean("$parent/$name")
    }
    fun sftp(root: String, relative: String): String = "/" + listOf(clean(root), clean(relative)).filter { it.isNotEmpty() }.joinToString("/")
}

class HostKeyRequired(val source: Source, val fingerprint: String, val previous: String?) :
    java.io.IOException(if (previous == null) "首次连接：请核验服务器指纹" else "服务器身份发生变化，连接已阻止")

/** Invoke cancel off the UI thread. Transport deadlines bound blocking library calls too. */
class Cancellation : Closeable {
    private val cancelled = AtomicBoolean(false)
    private val resources = mutableListOf<Closeable>()
    fun release(resource: Closeable) { synchronized(resources) { resources.remove(resource) } }
    fun <T> withLock(lock: java.util.concurrent.locks.ReentrantLock, action: () -> T): T {
        while (true) {
            check()
            try { if (lock.tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) break }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("操作已取消") }
        }
        try { check(); return action() } finally { lock.unlock() }
    }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("操作已取消") }
    fun own(resource: Closeable) {
        synchronized(resources) {
            if (cancelled.get()) { runCatching { resource.close() }; throw InterruptedIOException("操作已取消") }
            resources += resource
        }
    }
    override fun close() {
        if (!cancelled.compareAndSet(false, true)) return
        val copy = synchronized(resources) { resources.toList().asReversed().also { resources.clear() } }
        copy.forEach { runCatching { it.close() } }
    }
}

interface RemoteFile : Closeable {
    val reusableCache:Boolean get()=false
    val readTelemetry: RemoteReadTelemetry? get() = null
    val size: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}
interface RemoteSession : Closeable {
    fun list(path: String): List<Entry>
    fun open(path: String): RemoteFile
    /** Initial read position is a hint; returned files retain random access. */
    fun open(path: String, position: Long): RemoteFile { require(position>=0);return open(path) }
}
interface Connector {
    fun connect(source: Source, credentials: Credentials, cancellation: Cancellation = Cancellation()): RemoteSession
}

class Protocols : Connector {
    override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation): RemoteSession {
        source.validate(); cancellation.check()
        return when (source.protocol) {
            Protocol.SMB -> SmbConnector().connect(source, credentials, cancellation)
            Protocol.SFTP -> SftpConnector().connect(source, credentials, cancellation)
            Protocol.WEBDAV -> WebDavConnector().connect(source, credentials, cancellation)
            Protocol.ALIYUN -> throw java.io.IOException("请通过映汐的阿里云盘账户连接")
        }
    }
}
