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
            cancellation.own(connection); cancellation.check()
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
                        Entry(it.fileName, Paths.join(path, it.fileName), it.fileAttributes and 0x10L != 0L, it.endOfFile)
                    }.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
                }
                override fun open(path: String): RemoteFile {
                    cancellation.check()
                    val file = share.openFile(Paths.clean(path).replace('/', '\\'), EnumSet.of(AccessMask.FILE_READ_DATA),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.allOf(SMB2ShareAccess::class.java),
                        SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
                    try {
                        val length = file.fileInformation.standardInformation.endOfFile
                        return object : RemoteFile {
                            override val size = length
                            private val done = AtomicBoolean(false)
                            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                                require(position >= 0); cancellation.check()
                                if (done.get()) throw java.io.InterruptedIOException("读取已关闭")
                                if (length == 0) return 0
                                if (position >= size) return -1
                                val n = file.read(buffer, position, offset, length)
                                if (done.get()) throw java.io.InterruptedIOException("读取已关闭")
                                return if (n == 0) -1 else n
                            }
                            override fun close() { if (done.compareAndSet(false, true)) file.close() }
                        }
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
                    }.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
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
                            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                                require(position >= 0); cancellation.check()
                                if (done.get()) throw java.io.InterruptedIOException("读取已关闭")
                                if (length == 0) return 0
                                if (position >= size) return -1
                                val n = file.read(position, buffer, offset, length)
                                if (done.get()) throw java.io.InterruptedIOException("读取已关闭")
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
