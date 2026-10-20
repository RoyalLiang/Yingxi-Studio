package io.localplayer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.localplayer.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Owns the app's cloud accounts. SourceStore encrypts credentials using Android Keystore. */
internal object AliyunCloud {
    private lateinit var context: Context
    private lateinit var store: SourceStore
    private val api = AliyunApi()
    private val names = ConcurrentHashMap<String, String>()
    private val accountLock = java.util.concurrent.locks.ReentrantLock(true)
    private data class Pending(val grant: AliyunGrant, val created: Long, val completion: CompletableDeferred<Source>, val cancellation: Cancellation, val claimed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false))
    private var pending: Pending? = null
    fun accountRemoved(sourceId: String) {
        val flow = synchronized(this) { pending.also { pending = null } }
        flow?.completion?.cancel(); flow?.cancellation?.close()
        names.keys.removeAll { it.startsWith(sourceId+":") }
    }
    fun initialize(app: Context) { context = app.applicationContext; store = SourceStore(context) }
    fun folderName(sourceId: String,id: String) = names[sourceId+":"+id] ?: "文件夹"

    suspend fun authorize(): Source {
        if (AndroidCloudApps(context).status(CloudProvider.ALIYUNPAN) != CloudAppStatus.AVAILABLE) throw IOException("未安装或无法打开阿里云盘，请先安装官方 App")
        val flow = Pending(AliyunGrant.create(), android.os.SystemClock.elapsedRealtime(), CompletableDeferred(), Cancellation())
        synchronized(this) { pending?.let { it.cancellation.close(); it.completion.cancel() }; pending = flow }
        try {
            val redirect = withContext(Dispatchers.IO) { api.authorization(flow.grant, true, flow.cancellation) }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(redirect)).setPackage(CloudProvider.ALIYUNPAN.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return withTimeout(10 * 60 * 1000L) { flow.completion.await() }
        } finally {
            synchronized(this) { if (pending === flow) pending = null }
            flow.cancellation.close()
        }
    }
    /** Native callbacks can be explicit Intents with code extras, per the official SDK. */
    fun callback(intent: Intent): Boolean {
        val flow = synchronized(this) { pending } ?: return false
        if (android.os.SystemClock.elapsedRealtime() - flow.created > 10 * 60 * 1000L) { flow.completion.completeExceptionally(IOException("授权已超时，请重试")); return false }
        val uri = intent.data
        if (uri != null && uri.scheme != "ypauth${AliyunConfig.CLIENT_ID}") return false
        fun field(key: String) = if (uri != null) uri.getQueryParameter(key) else intent.getStringExtra(key)
        val state = field("state")
        // Native SDK does not require/guarantee state in extras; the one-use verifier still binds the code.
        if (state != null && state != flow.grant.state) return false
        if (field("error") != null) { flow.completion.completeExceptionally(IOException("阿里云盘授权已取消或被拒绝")); return true }
        val code = field("code")?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: return false
        synchronized(this) { if (pending !== flow || !flow.claimed.compareAndSet(false, true)) return false }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val token = api.exchange(code, flow.grant.verifier, flow.cancellation)
                val drive = api.drive(token.access, flow.cancellation)
                val source = Source(id = "aliyun:${drive.userId}:${drive.driveId}", label = "阿里云盘 · ${drive.name}", protocol = Protocol.ALIYUN,
                    host = "openapi.alipan.com", root = "/", username = drive.userId, domain = drive.driveId)
                flow.cancellation.withLock(accountLock) {
                    flow.cancellation.check()
                    if (!flow.completion.isActive) throw IOException("授权已取消")
                    store.save(source, encode(token), true, preservePresentation = true) { synchronized(this@AliyunCloud) { pending === flow && flow.completion.isActive } }
                }
                flow.completion.complete(source)
            } catch (_: CancellationException) { flow.completion.cancel() }
            catch (e: Exception) { flow.completion.completeExceptionally(IOException(if (e is IOException) e.message else "网盘授权失败，请重试")) }
        }
        return true
    }
    private fun encode(token: AliyunToken) = JSONObject().put("access", token.access).put("refresh", token.refresh).put("expiresAt", token.expiresAt).toString()
    private fun decode(value: String): AliyunToken = try { JSONObject(value).let { AliyunToken(it.getString("access"), it.optString("refresh").takeIf { text -> text.isNotBlank() && text != "null" }, it.getLong("expiresAt")) } }
        catch (_: Exception) { throw AliyunUnauthorized() }
    fun connector(source: Source): Connector = AliyunConnector(api, object : AliyunAccess {
        override fun <T> authorized(cancellation: Cancellation, action: (String) -> T): T {
            fun token(force: Boolean): AliyunToken = cancellation.withLock(accountLock) {
                val encoded = store.password(source.id) ?: throw AliyunUnauthorized()
                val current = decode(encoded)
                if (!force && current.expiresAt > System.currentTimeMillis() + 60_000) return@withLock current
                val refresh = current.refresh ?: throw AliyunUnauthorized()
                val updated = try { api.refresh(refresh, cancellation) } catch (_: Exception) { cancellation.check(); throw AliyunUnauthorized() }
                // Source deletion or a newer login must not be undone by a late refresh.
                if (store.password(source.id) != encoded || store.load().none { it.id == source.id }) throw AliyunUnauthorized()
                store.save(source, encode(AliyunToken(updated.access, updated.refresh ?: current.refresh, updated.expiresAt)), true, preservePresentation = true) { store.password(source.id) == encoded && store.load().any { it.id == source.id } }
                updated
            }
            val current = token(false)
            return try { action(current.access) } catch (_: AliyunUnauthorized) { action(token(true).access) }
        }
    }) { id, name -> names[source.id+":"+id] = name }
}

internal class AppConnectors : Connector {
    override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation): RemoteSession =
        if (source.protocol == Protocol.ALIYUN) AliyunCloud.connector(source).connect(source, credentials, cancellation)
        else Protocols().connect(source, credentials, cancellation)
}
