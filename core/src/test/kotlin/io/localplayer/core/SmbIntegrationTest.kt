package io.localplayer.core

import kotlin.test.*

class SmbIntegrationTest {
    @Test fun authenticatedShareEnumeration(){
  val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
  org.junit.Assume.assumeTrue("Explicit loopback fixture required",port!=null)
  val source=Source(label="Shares",protocol=Protocol.SMB,host="127.0.0.1",port=port!!,root="",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
  Cancellation().use { cancellation ->
   assertTrue(SmbShares.list(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD"))),cancellation).any { it.equals("media",true) })
  }
 }
 @Test fun idlePlaybackConnectionCanSeekAgain(){
        val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
        org.junit.Assume.assumeTrue("Set PLAYER_SMB_FIXTURE_PORT for the loopback fixture",port!=null)
        val source=Source(label="SMB idle fixture",protocol=Protocol.SMB,host="127.0.0.1",port=port!!,root="media",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
        SmbConnector().connect(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD")){"Explicit loopback fixture password required"})).use{session ->
            session.open("影片/large.mp4").use{assertEquals(4,it.readAt(0,ByteArray(4),0,4))}
            Thread.sleep(10_000) // Playback can buffer or pause longer than a request deadline.
            session.open("影片/large.mp4").use{file ->
                val marker="large-offset-marker".toByteArray();val actual=ByteArray(marker.size)
                assertEquals(actual.size,file.readAt(file.size-actual.size,actual,0,actual.size))
                assertContentEquals(marker,actual)
            }
        }
    }
    @Test fun realSmbAuthenticationBrowsingAndLargeOffset(){
        val port=System.getenv("PLAYER_SMB_FIXTURE_PORT")?.toInt()
        org.junit.Assume.assumeTrue("Set PLAYER_SMB_FIXTURE_PORT for the isolated loopback server",port!=null)
        val source=Source(label="SMB loopback",protocol=Protocol.SMB,host="127.0.0.1",port=port!!,root="media",username=System.getenv("PLAYER_TEST_USERNAME")?:"test-user")
        val connector=SmbConnector()
        connector.connect(source,Credentials(requireNotNull(System.getenv("PLAYER_TEST_PASSWORD")){"Explicit loopback fixture password required"})).use{session ->
            assertTrue(session.list("").any{it.name=="影片"&&it.directory})
            val entry=session.list("影片").first{it.name=="large.mp4"}
            val marker="large-offset-marker".toByteArray()
            assertTrue(entry.size>(1L shl 32))
            session.open(entry.path).use{file ->
                val bytes=ByteArray(marker.size)
                assertEquals(marker.size,file.readAt(file.size-marker.size,bytes,0,bytes.size));assertContentEquals(marker,bytes)
                assertEquals(-1,file.readAt(file.size,bytes,0,bytes.size))
                assertEquals(4,file.readAt(0,bytes,0,4));assertContentEquals(ByteArray(4),bytes.copyOf(4))
            }
        }
        assertFails{connector.connect(source,Credentials("wrong-fixture-password"))}
    }
}
