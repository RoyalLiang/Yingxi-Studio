package io.localplayer.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.*

class AliyunTest {
    @Test fun pkceUsesIndependentSecureVerifiers() {
        val grants = (1..500).map { AliyunGrant.create() }
        assertEquals(500, grants.map { it.verifier }.toSet().size)
        grants.forEach { assertTrue(it.verifier.matches(Regex("[A-Za-z0-9_-]{43}"))); assertEquals(43, it.challenge.length); assertNotEquals(it.state, it.verifier) }
        assertFalse(grants.first().toString().contains(grants.first().verifier))
        assertFalse(AliyunToken("sensitive", null, 100).toString().contains("sensitive"))
    }
    @Test fun nativeRequestHasPkceReadOnlyScopeAndNoSecret() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"redirectUri":"smartdrive://authorize?opaque=value"}"""))
        val grant = AliyunGrant.create()
        assertTrue(api(server).authorization(grant, true, Cancellation()).startsWith("smartdrive:"))
        val request = server.takeRequest().requestUrl!!
        assertEquals(grant.challenge, request.queryParameter("code_challenge"))
        assertEquals("S256", request.queryParameter("code_challenge_method"))
        assertEquals("app", request.queryParameter("source"))
        assertEquals(AliyunConfig.SCOPE, request.queryParameter("scope"))
        assertNull(request.queryParameter("client_secret")); assertNull(request.queryParameter("code_verifier"))
    }
    @Test fun exchangeAcceptsMissingRefreshTokenAndNeverSendsSecret() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"access_token":"access-fixture","expires_in":3600}"""))
        val grant = AliyunGrant.create()
        val token = api(server).exchange("code-fixture", grant.verifier, Cancellation())
        assertEquals("access-fixture", token.access); assertNull(token.refresh)
        val request = server.takeRequest(); val body = request.body.readUtf8()
        assertTrue(body.contains(grant.verifier)); assertFalse(body.contains("client_secret")); assertNull(request.getHeader("Authorization"))
    }
    @Test fun listPaginatesAndKeepsStableIds() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"items":[{"file_id":"folder-a","name":"剧集","type":"folder"}],"next_marker":"next"}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"file_id":"file-b","name":"01.mp4","type":"file","size":42,"created_at":"2026-10-20T00:00:00Z"}],"next_marker":""}"""))
        val result = api(server).list("fixture-token", "drive", "root", Cancellation())
        assertEquals(listOf("folder-a", "file-b"), result.map { it.id }); assertEquals(42L, result.last().size)
        assertNotNull(result.last().createdAt)
        assertEquals("Bearer fixture-token", server.takeRequest().getHeader("Authorization"))
        assertTrue(server.takeRequest().body.readUtf8().contains("next"))
    }
    @Test fun repeatedPaginationAndApiRedirectsAreRejected() = MockWebServer().use { server ->
        repeat(2) { server.enqueue(MockResponse().setBody("""{"items":[],"next_marker":"repeat"}""")) }
        assertFailsWith<IOException> { api(server).list("fixture", "drive", "root", Cancellation()) }
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://unrelated.invalid/"))
        assertFailsWith<IOException> { api(server).drive("fixture", Cancellation()) }
        assertEquals(3, server.requestCount)
    }
    @Test fun connectorUsesStableIdsWithFolderFirstNaturalOrdering(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"items":[{"file_id":"ten","name":"Episode10.mp4","type":"file"},{"file_id":"folder","name":"剧集","type":"folder"},{"file_id":"two","name":"Episode2.mp4","type":"file"}]}"""))
        val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T)=action("fixture")}
        val source=Source(label="cloud",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
        AliyunConnector(api(server),access).connect(source,Credentials(""),Cancellation()).use { session ->
            val rows=session.list("parent")
            assertEquals(listOf("剧集","Episode2.mp4","Episode10.mp4"),rows.map{it.name})
            assertEquals(listOf("parent/folder","parent/two","parent/ten"),rows.map{it.path})
        }
    }
    @Test fun rangeReadsSeekAndRefreshForbiddenLinkOnceWithoutBearer() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-7/8").setBody("abcdefgh"))
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 5-7/8").setBody("fgh"))
        var resolutions = 0
        AliyunRangeFile(8, Cancellation()) { resolutions++; server.url("/signed-$resolutions").toString() }.use { file ->
            val bytes = ByteArray(3)
            assertEquals(3, file.readAt(0, bytes, 0, 3)); assertEquals("abc", bytes.toString(Charsets.UTF_8))
            assertEquals(3, file.readAt(5, bytes, 0, 3)); assertEquals("fgh", bytes.toString(Charsets.UTF_8))
            assertEquals(-1, file.readAt(8, bytes, 0, 3))
        }
        assertEquals(2, resolutions)
        repeat(3) { assertNull(server.takeRequest().getHeader("Authorization")) }
    }
    @Test fun rangeRejectsFullFileFallbackAndInvalidOffset(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("abcdefgh"))
        AliyunRangeFile(8, Cancellation()) { server.url("/").toString() }.use { file ->
            assertFailsWith<IOException> { file.readAt(5, ByteArray(3), 0, 3) }
        }
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-7/8").setBody("abcdefgh"))
        AliyunRangeFile(8, Cancellation()) { server.url("/").toString() }.use { file ->
            assertFailsWith<IOException> { file.readAt(5, ByteArray(3), 0, 3) }
        }
    }
    @Test fun cancelledCallsDoNotReachNetwork() = MockWebServer().use { server ->
        val c = Cancellation(); c.close()
        assertFailsWith<java.io.InterruptedIOException> { api(server).drive("fixture", c) }
        assertEquals(0, server.requestCount)
    }
    @Test fun closingHandleInterruptsAnInFlightSignedUrlResolution(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val parent=Cancellation()
        val file=AliyunRangeFile(8,parent){handle -> api(server).drive("fixture",handle);server.url("/cdn").toString()}
        val reader=Thread{try{file.readAt(0,ByteArray(1),0,1)}catch(t:Throwable){failure.set(t)}}
        reader.start();assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS))
        val start=System.nanoTime();file.close();val elapsed=(System.nanoTime()-start)/1_000_000
        reader.join(3000);assertFalse(reader.isAlive);assertTrue(elapsed<1500,"Handle close must cancel URL resolution promptly")
        assertTrue(failure.get() is IOException);assertEquals(1,server.requestCount)
        parent.check() // Closing one file must not cancel the reusable session.
    }
    @Test fun closingHandleDoesNotWaitForAnotherAccountsRefreshLock() {
        val lock=java.util.concurrent.locks.ReentrantLock();lock.lock()
        val entered=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val file=AliyunRangeFile(8,Cancellation()){handle -> entered.countDown();handle.withLock(lock){"https://cdn.example.invalid/video"}}
        val reader=Thread{try{file.readAt(0,ByteArray(1),0,1)}catch(t:Throwable){failure.set(t)}}
        try {
            reader.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            val start=System.nanoTime();file.close();assertTrue((System.nanoTime()-start)/1_000_000<1500)
            reader.join(3000);assertFalse(reader.isAlive);assertTrue(failure.get() is IOException)
            assertTrue(lock.isHeldByCurrentThread)
        }finally{lock.unlock();file.close();reader.join(3000)}
    }
    private fun api(server: MockWebServer) = AliyunApi(server.url("/"), OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
}
