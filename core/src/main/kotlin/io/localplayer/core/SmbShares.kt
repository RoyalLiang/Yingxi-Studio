package io.localplayer.core

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.rapid7.client.dcerpc.mssrvs.messages.NetrShareEnumRequest.NetShareEnumRequest1
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Authenticated candidates; opening the selected share must still verify readability. */
object SmbShares {
    fun list(source: Source, credentials: Credentials, cancellation: Cancellation): List<String> {
        source.copy(root = "placeholder").validate()
        val client = SMBClient(SmbConfig.builder().withTimeout(8, TimeUnit.SECONDS).withSoTimeout(0, TimeUnit.SECONDS).build())
        cancellation.own(client)
        return client.use {
            cancellation.check()
            client.connect(source.host, source.port).use { connection ->
                cancellation.own(connection)
                val auth = if (source.username.isBlank()) AuthenticationContext.anonymous() else AuthenticationContext(source.username, credentials.password.toCharArray(), source.domain)
                connection.authenticate(auth).use { session ->
                    val transport = SMBTransportFactories.SRVSVC.getTransport(session)
                    collectPages(cancellation) { cursor ->
                        val response = transport.call(NetShareEnumRequest1(1024L * 1024, cursor))
                        val status=response.returnValue
                        if(status!=0&&status!=234)throw IOException("共享枚举失败（$status）")
                        SharePage(response.shareEnumStruct?.shareInfoContainer?.buffer?.mapNotNull { it.netName?.value }.orEmpty(), status, response.resumeHandle)
                    }
                }
            }
        }
    }

    internal data class SharePage(val names: List<String>, val status: Int, val resume: Long?)
    internal fun collectPages(cancellation: Cancellation, fetch: (Long?) -> SharePage): List<String> {
        val names = linkedSetOf<String>(); val cursors = mutableSetOf(0L); var cursor: Long? = 0L
        repeat(128) {
            cancellation.check()
            val page = fetch(cursor)
            cancellation.check()
            if (page.status != 0 && page.status != 234) throw IOException("共享枚举失败（${page.status}）")
            names += page.names.map { it.trimEnd('\u0000') }.filter { it.isNotBlank() && !it.equals("IPC$", true) }
            if (names.size > 10000) throw IOException("共享列表过大，请输入共享名")
            if (page.status == 0) return names.sortedWith(MediaOrdering::compareNames)
            val next = page.resume ?: throw IOException("共享枚举缺少下一页游标")
            if (!cursors.add(next)) throw IOException("共享枚举游标未前进，请输入共享名")
            cursor = next // opaque absolute handle; never add it to the old value.
        }
        throw IOException("共享枚举页数过多，请输入共享名")
    }
}
