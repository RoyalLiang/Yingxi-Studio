package io.localplayer.core

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProtocolIntegrationTest {
    @Test fun sftpIdentityAuthenticationDirectoryAndLargeOffsetRead() {
        val root=Files.createTempDirectory("player-sftp-")
        val attempts=AtomicInteger()
        val password=java.util.UUID.randomUUID().toString()
        val server=SshServer.setUpDefaultServer().apply {
            host="127.0.0.1";port=0
            keyPairProvider=SimpleGeneratorHostKeyProvider(root.resolve("server-key"))
            passwordAuthenticator=org.apache.sshd.server.auth.password.PasswordAuthenticator { u,p,_ -> attempts.incrementAndGet();u=="viewer"&&p==password }
            subsystemFactories=listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory=VirtualFileSystemFactory(root)
        }
        val large=(1L shl 32)+8192
        val expected="large-offset-marker".toByteArray()
        Files.createDirectories(root.resolve("影片"))
        RandomAccessFile(root.resolve("影片/large.mp4").toFile(),"rw").use{it.setLength(large);it.seek(large-expected.size);it.write(expected)}
        server.start()
        try {
            val unknown=Source(label="loopback fixture",protocol=Protocol.SFTP,host="127.0.0.1",port=server.port,root="/",username="viewer")
            val connector=SftpConnector();val secret=Credentials(password)
            val key=assertFailsWith<HostKeyRequired>{connector.connect(unknown,secret)}
            assertTrue(key.fingerprint.startsWith("SHA256:"));assertEquals(0,attempts.get(),"Unknown host key must not receive credentials")
            val trusted=unknown.copy(trustedKey=key.fingerprint,trustedAuthority=unknown.authority)
            connector.connect(trusted,secret).use{session ->
                assertTrue(session.list("").any{it.name=="影片"&&it.directory})
                val entries=session.list("影片");assertEquals(large,entries.single().size)
                session.open("影片/large.mp4").use{file ->
                    assertEquals(large,file.size)
                    val bytes=ByteArray(expected.size)
                    assertEquals(expected.size,file.readAt(large-expected.size,bytes,0,bytes.size))
                    assertContentEquals(expected,bytes)
                    assertEquals(-1,file.readAt(large,bytes,0,bytes.size))
                    assertEquals(4,file.readAt(0,bytes,0,4))
                    assertContentEquals(ByteArray(4),bytes.copyOf(4))
                }
            }
            val before=attempts.get()
            assertFailsWith<HostKeyRequired>{connector.connect(trusted.copy(trustedKey="SHA256:changed"),secret)}
            assertEquals(before,attempts.get(),"Changed host key must not receive credentials")
            assertFails{connector.connect(trusted,Credentials("wrong-fixture-password"))}
            val cancellation=Cancellation()
            connector.connect(trusted,secret,cancellation).use{session ->
                cancellation.close()
                assertFailsWith<java.io.InterruptedIOException>{session.list("")}
            }
        } finally {server.stop(true);root.toFile().deleteRecursively()}
    }

    @Test fun pathsAndSourceIdentityHaveExplicitBoundaries(){
        assertEquals("目录/片.mp4",Paths.join("目录","片.mp4"))
        assertEquals("/media/目录/片.mp4",Paths.sftp("/media","目录/片.mp4"))
        assertFailsWith<IllegalArgumentException>{Paths.clean("a/../b")}
        assertFailsWith<IllegalArgumentException>{Paths.join("a","../../b")}
        assertFailsWith<IllegalArgumentException>{Source(label="x",protocol=Protocol.SFTP,host="sftp://server").validate()}
        assertFailsWith<IllegalArgumentException>{Source(label="x",protocol=Protocol.SMB,host="server",root="share/path").validate()}
        assertFailsWith<IllegalArgumentException>{Source(label="x",protocol=Protocol.SFTP,host="server",port=0).validate()}
        val source=Source(label="x",protocol=Protocol.SFTP,host="Server",port=2222)
        assertEquals("server:2222",source.authority)
        assertFalse(Credentials("sensitive-test-value").toString().contains("sensitive"))
    }
}
