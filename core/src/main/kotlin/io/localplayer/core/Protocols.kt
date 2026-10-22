package io.localplayer.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.OpenMode
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SmbConnector : Connector {
    override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation): RemoteSession {
        source.validate(); cancellation.check()
        // SMBJ's socket reader also runs while playback is buffered/paused. An
        // SO_TIMEOUT would close a healthy idle session; bound requests instead.
        val client = SMBClient(SmbConfig.builder().withTimeout(8, TimeUnit.SECONDS).withSoTimeout(0, TimeUnit.SECONDS).build())
        cancellation.own(client)
        try {
            val connection = client.connect(source.host, source.port)
            // Cancellation must close the transport immediately; graceful session
            // logoff can wait another network round trip while a read is stuck.
            cancellation.own(Closeable{connection.close(true)}); cancellation.check()
            val auth = if (source.username.isBlank()) AuthenticationContext.anonymous() else
                AuthenticationContext(source.username, credentials.password.toCharArray(), source.domain)
            val session = connection.authenticate(auth)
            cancellation.check()
            val share = session.connectShare(source.root) as? DiskShare ?: throw java.io.IOException("该共享不是文件目录")
            val closed = AtomicBoolean(false)
            return object : RemoteSession {
                override fun list(path: String): List<Entry> {
                    cancellation.check()
                    return share.list(Paths.clean(path).replace('/', '\\')).filter { it.fileName != "." && it.fileName != ".." }.map {
                        Entry(it.fileName, Paths.join(path, it.fileName), it.fileAttributes and 0x10L != 0L, it.endOfFile, it.creationTime.toEpochMillis().takeIf { time -> time > 0 })
                    }.sortedWith(MediaOrdering.entries)
                }
                override fun open(path: String): RemoteFile {
                    cancellation.check()
                    val file = share.openFile(Paths.clean(path).replace('/', '\\'), EnumSet.of(AccessMask.FILE_READ_DATA),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.allOf(SMB2ShareAccess::class.java),
                        SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
                    try {
                        val length = file.fileInformation.standardInformation.endOfFile
                        return SmbReadFile(file,length,cancellation,connection.negotiatedProtocol.maxReadSize)
                } catch (e: Exception) { runCatching { file.close() }; throw e }
                }
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        runCatching { share.close() }; runCatching { session.close() }; runCatching { connection.close() }; runCatching { client.close() }
                    }
                }
            }
        } catch (e: Exception) { runCatching { client.close() }; throw e }
    }
}

class SftpConnector : Connector {
    override fun connect(source: Source, credentials: Credentials, cancellation: Cancellation): RemoteSession {
        source.validate(); cancellation.check()
        // Android initializes the bundled BC provider once in PlayerApplication.
        // Keep SSHJ key parsing and signature verification on the same provider.
        val ssh = SSHClient()
        ssh.connectTimeout = 6000; ssh.timeout = 8000
        cancellation.own(Closeable { ssh.disconnect() })
        var rejected: HostKeyRequired? = null
        ssh.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
            val expected = source.trustedKey.takeIf { source.trustedAuthority == source.authority }
            return (expected == fingerprint).also { valid -> if (!valid) rejected = HostKeyRequired(source, fingerprint, expected) }
            }
        })
        try {
            ssh.connect(source.host, source.port)
            cancellation.check()
            // Host verification happened during connect; credentials are never sent to an untrusted key.
            ssh.authPassword(source.username, credentials.password)
            cancellation.check()
            val sftp = ssh.newSFTPClient()
            val closed = AtomicBoolean(false)
            return object : RemoteSession {
                override fun list(path: String): List<Entry> {
                    cancellation.check()
                    return sftp.ls(Paths.sftp(source.root, path)).filter { it.name != "." && it.name != ".." }.map {
                        Entry(it.name, Paths.join(path, it.name), it.isDirectory, it.attributes.size)
                    }.sortedWith(MediaOrdering.entries)
                }
                override fun open(path: String): RemoteFile {
                    cancellation.check()
                    val full = Paths.sftp(source.root, path)
                    val file = sftp.open(full, EnumSet.of(OpenMode.READ))
                    try {
                        val length = sftp.stat(full).size
                        return object : RemoteFile {
                            override val size = length
                            private val done = AtomicBoolean(false)
                    private var cursor=-1L
                    private var warmed=false
                    private var stream:java.io.InputStream?=null
                    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        require(position>=0 && offset>=0 && length>=0 && offset<=buffer.size && length<=buffer.size-offset);cancellation.check()
                        if(done.get()) throw java.io.InterruptedIOException("读取已关闭")
                        if(length==0)return 0
                        if(position>=size)return -1
                        if(stream==null || cursor!=position){
                            // Bounded SFTP request window, not a full-file download. A seek
                            // starts at its own offset instead of waiting for old prefetch.
                            stream=file.ReadAheadRemoteFileInputStream(0,position,size-position)
                            cursor=position;warmed=false
                        }else if(!warmed){
                            // Only expand after the caller consumes the first block.
                            // Metadata probes and discontinuous seeks stay small.
                            stream=file.ReadAheadRemoteFileInputStream(15,position,size-position)
                            warmed=true
                        }
                        // Keep packets small even when the outer cache asks for 1 MiB.
                        // This avoids oversized reads being truncated by common servers.
                        val n=stream!!.read(buffer,offset,minOf(length,32768,(size-position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                        if(done.get()) throw java.io.InterruptedIOException("读取已关闭")
                        if(n>0)cursor+=n
                        return n
                    }
                    override fun close() { if (done.compareAndSet(false, true)) file.close() }
                        }
                    } catch (e: Exception) { runCatching { file.close() }; throw e }
                }
                override fun close() {
                    if (closed.compareAndSet(false, true)) { runCatching { sftp.close() }; runCatching { ssh.disconnect() }; runCatching { ssh.close() } }
                }
            }
        } catch (e: Exception) { runCatching { ssh.disconnect() }; runCatching { ssh.close() }; throw rejected ?: e }
    }
}
