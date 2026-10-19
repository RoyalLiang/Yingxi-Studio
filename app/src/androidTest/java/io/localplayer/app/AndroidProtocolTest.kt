package io.localplayer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the explicitly started host-loopback fixtures through the emulator alias. */
@RunWith(AndroidJUnit4::class)
class AndroidProtocolTest {
    private val credentials get() = Credentials(TestFixtures.password)

    @Test fun playbackLeaseReopensClosedTransportOnceAndStillHonorsCancellation(){
        val unknown=Source(label="SFTP recovery fixture",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
        val fingerprint=try{SftpConnector().connect(unknown,credentials).close();throw AssertionError("Expected host key")}
            catch(key:HostKeyRequired){key.fingerprint}
        val sources=listOf(Source(label="SMB recovery fixture",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username),unknown.copy(trustedKey=fingerprint,trustedAuthority=unknown.authority))
        for(source in sources){
            var last:RemoteSession?=null;var connections=0
            val connector=object:Connector{
                override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation):RemoteSession{
                    connections++;return Protocols().connect(source,credentials,cancellation).also{last=it}
                }
            }
            val marker="large-offset-marker".toByteArray()
            val lease=PlaybackLease(source,credentials,"影片/large.mp4",connector)
            try{
                lease.open().use{assertTrue(it.size>1L.shl(32))}
                last!!.close() // Controlled transport loss, while the play intent remains valid.
                lease.open().use{file->val bytes=ByteArray(marker.size);assertEquals(bytes.size,file.readAt(file.size-bytes.size,bytes,0,bytes.size));assertArrayEquals(marker,bytes)}
                assertEquals(2,connections)
            }finally{lease.close()}
            assertThrows(java.io.IOException::class.java){lease.open()}
            assertEquals(2,connections)
            connections=0
            PlaybackLease(source,credentials,"missing-${java.util.UUID.randomUUID()}",connector).use{missing->
                assertThrows(Exception::class.java){missing.open()}
                assertEquals(2,connections) // Permanent errors do not enter an unbounded retry loop.
            }
            val reconnecting=java.util.concurrent.CountDownLatch(1)
            val release=java.util.concurrent.CountDownLatch(1)
            var attempts=0
            val gated=object:Connector{
                override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation):RemoteSession{
                    attempts++
                    if(attempts==2){
                        cancellation.own(java.io.Closeable{release.countDown()})
                        reconnecting.countDown()
                        check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                        cancellation.check()
                    }
                    return Protocols().connect(source,credentials,cancellation).also{last=it}
                }
            }
            val race=PlaybackLease(source,credentials,"影片/large.mp4",gated)
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            try{
                race.open().close();last!!.close()
                val opening=pool.submit<RemoteFile>{race.open()}
                assertTrue(reconnecting.await(5,java.util.concurrent.TimeUnit.SECONDS))
                val closing=pool.submit{race.close()}
                val error=assertThrows(java.util.concurrent.ExecutionException::class.java){opening.get(5,java.util.concurrent.TimeUnit.SECONDS)}
                assertTrue(error.cause is java.io.InterruptedIOException)
                closing.get(5,java.util.concurrent.TimeUnit.SECONDS)
                assertThrows(java.io.IOException::class.java){race.open()}
                assertEquals(2,attempts)
            }finally{release.countDown();race.close();pool.shutdownNow()}
        }
    }

    @Test fun keystoreStillProtectsSavedCredentials() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SourceStore(context)
        val source=Source(label="temporary credential test",protocol=Protocol.SFTP,host="10.0.2.2",username=TestFixtures.username)
        try {
            store.save(source,credentials.password,true)
            assertEquals(credentials.password,store.password(source.id))
            val serialized=context.getSharedPreferences("secrets-v1",0).getString(source.id,"")!!
            assertFalse(serialized.contains(credentials.password))
        } finally { store.delete(source.id) }
    }

    @Test fun sftpIdentityAndRandomAccessOnAndroid() {
        val unknown = Source(label="Android fixture", protocol=Protocol.SFTP, host="10.0.2.2", port=TestFixtures.sftpPort, root="/", username=TestFixtures.username)
        val connector = SftpConnector()
        val fingerprint = try {
            connector.connect(unknown, credentials).close()
            fail("Unknown key was accepted"); ""
        } catch (required: HostKeyRequired) { required.fingerprint }
        assertTrue(fingerprint.startsWith("SHA256:"))
        verify(connector, unknown.copy(trustedKey=fingerprint, trustedAuthority=unknown.authority))
    }

    @Test fun smbAuthenticationAndRandomAccessOnAndroid() {
        verify(SmbConnector(), Source(label="Android fixture", protocol=Protocol.SMB, host="10.0.2.2", port=TestFixtures.smbPort, root="media", username=TestFixtures.username))
    }

    private fun verify(connector:Connector, source:Source) {
        connector.connect(source,credentials).use { session ->
            assertTrue(session.list("").any { it.name=="影片" && it.directory })
            if(source.protocol==Protocol.SMB) Thread.sleep(10_000)
            session.open("影片/large.mp4").use { file ->
                assertTrue(file.size > 1L.shl(32))
                val expected="large-offset-marker".toByteArray()
                val actual=ByteArray(expected.size)
                assertEquals(actual.size,file.readAt(file.size-actual.size,actual,0,actual.size))
                assertArrayEquals(expected,actual)
                assertEquals(4,file.readAt(0,actual,0,4))
                assertArrayEquals(ByteArray(4),actual.copyOf(4))
                assertEquals(-1,file.readAt(file.size,actual,0,actual.size))
            }
        }
    }
}
