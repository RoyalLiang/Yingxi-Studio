package io.localplayer.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.*

class AliyunTest {
    @Test fun parentCancellationStillStopsDirectorySession() = MockWebServer().use { server ->
        val parent=Cancellation()
        val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T):T=action("synthetic-token")}
        val source=Source(label="Cloud",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
        val session=AliyunConnector(api(server),access).connect(source,Credentials(""),parent)
        parent.close()
        assertFailsWith<java.io.InterruptedIOException>{session.list("")}
        session.close()
        assertEquals(0,server.requestCount)
    }

    @Test fun closingDirectorySessionDoesNotCancelHistoryReplay() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"file_id":"video","name":"01.mp4","type":"file","size":42}],"next_marker":""}"""))
            val parent=Cancellation()
            val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T):T=action("synthetic-token")}
            val source=Source(label="Cloud",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
            val files=AliyunConnector(api(server),access).connect(source,Credentials(""),parent).use{it.list("")}
            parent.check()
            assertEquals("video",files.single().path)
            parent.close()
        }
    }

    @Test fun pkceUsesIndependentSecureVerifiers() {
        val grants = (1..500).map { AliyunGrant.create() }
        assertEquals(500, grants.map { it.verifier }.toSet().size)
        grants.forEach {
            assertTrue(it.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
            // Both first-party Aliyunpan mobile SDKs retain SHA-256 Base64 padding.
            val officialChallenge = java.util.Base64.getUrlEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(it.verifier.toByteArray(Charsets.US_ASCII)))
            assertEquals(officialChallenge, it.challenge)
            assertEquals(44, it.challenge.length); assertTrue(it.challenge.endsWith("=")); assertNotEquals(it.state, it.verifier)
        }
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
    @Test fun api400IdentifiesSafePhaseWithoutExposingResponseMessage(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"InvalidClientId","message":"sensitive authorization value","requestId":"safe-request-123"}"""))
        val error=assertFailsWith<AliyunApiFailure>{api(server).authorization(AliyunGrant.create(),true,Cancellation())}
        assertTrue(error.message!!.contains("创建授权链接"));assertTrue(error.message!!.contains("400"));assertTrue(error.message!!.contains("InvalidClientId"));assertTrue(error.message!!.contains("safe-request-123"))
        assertFalse(error.message!!.contains("sensitive"));assertFalse(error.message!!.contains("authorization value"))
    }
    @Test fun providerDiagnosticsRejectArbitraryResponseValues() {
        val error=AliyunApiFailure(AliyunOperation.DIRECTORY,400,"Bearer sensitive-value","sensitive token with spaces")
        assertEquals("阿里云盘读取目录失败（400）",error.message)
        for(code in listOf("PermissionDenied","ForbiddenDriveLocked","ForbiddenDriveNotValid")) {
            assertTrue(AliyunApiFailure(AliyunOperation.DIRECTORY,403,code).message!!.contains(code))
        }
    }
    @Test fun accountNameMayBeNullWithoutLosingRequiredIdentifiers() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"user_id":"user","name":null,"default_drive_id":"drive"}"""))
        assertEquals(AliyunDrive("user","阿里云盘","drive"),api(server).drive("fixture",Cancellation()))
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
    @Test fun connectorAcceptsNullableFolderMetadata(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"items":[{"file_id":"folder","name":"剧集","type":"folder","size":null,"created_at":null},{"file_id":"video","name":"01.mp4","type":"file","size":128,"created_at":null}],"next_marker":null}"""))
        val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T):T=action("fixture")}
        val source=Source(label="cloud",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
        AliyunConnector(api(server),access).connect(source,Credentials(""),Cancellation()).use { session ->
            val rows=session.list("")
            assertEquals(listOf("剧集","01.mp4"),rows.map{it.name})
            assertTrue(rows[0].directory);assertEquals(0L,rows[0].size);assertNull(rows[0].createdAt)
            assertEquals(128L,rows[1].size);assertNull(rows[1].createdAt)
        }
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
    @Test fun reopeningForResumeReusesFileLookupAndSignedLocation():Unit {
        val certificate=okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls=okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        MockWebServer().use{cdn->MockWebServer().use{server->
            cdn.useHttps(serverTls.sslSocketFactory(),false)
            val size=4*1024*1024
            val lookups=java.util.concurrent.atomic.AtomicInteger()
            val locations=java.util.concurrent.atomic.AtomicInteger()
            val leakedAuthorization=java.util.concurrent.atomic.AtomicBoolean()
            val expireNext=java.util.concurrent.atomic.AtomicBoolean()
            cdn.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    if(request.getHeader("Authorization")!=null)leakedAuthorization.set(true)
                    if(expireNext.getAndSet(false))return MockResponse().setResponseCode(403)
                    val match=Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(request.getHeader("Range").orEmpty())?:return MockResponse().setResponseCode(400)
                    val start=match.groupValues[1].toInt();val end=match.groupValues[2].toIntOrNull()?:size-1
                    val bytes=ByteArray(end-start+1){((start+it)%251).toByte()}
                    return MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $start-$end/$size")
                        .setHeadersDelay(120,java.util.concurrent.TimeUnit.MILLISECONDS).setBody(okio.Buffer().write(bytes))
                }
            }
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse=when(request.path){
                    "/adrive/v1.0/openFile/get"->{lookups.incrementAndGet();MockResponse().setBody("""{"file_id":"video","name":"resume.mkv","type":"file","size":$size}""")}
                    "/adrive/v1.0/openFile/getDownloadUrl"->{locations.incrementAndGet();MockResponse().setHeadersDelay(250,java.util.concurrent.TimeUnit.MILLISECONDS).setBody(com.google.gson.JsonObject().apply{addProperty("url",cdn.url("/movie").toString())}.toString())}
                    else->MockResponse().setResponseCode(404)
                }
            }
            val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T)=action("fixture")}
            val source=Source(label="cloud",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
            val started=System.nanoTime()
            AliyunConnector(api(server),access,downloadClient=client).connect(source,Credentials(""),Cancellation()).use{session->
                for(position in listOf(0L,size-512L,65536L,2*1024*1024L)){
                    ReadAheadFile(session.open("video",position)).use{file->
                        val bytes=ByteArray(32);assertEquals(32,file.readAt(position,bytes,0,bytes.size))
                        assertContentEquals(ByteArray(32){((position+it)%251).toByte()},bytes)
                    }
                }
            }
            println("ALIYUN_RESUME opens=4 lookups=${lookups.get()} locations=${locations.get()} elapsedMs=${(System.nanoTime()-started)/1000000}")
            assertFalse(leakedAuthorization.get())
            assertEquals(1,locations.get(),"One playback session should reuse its signed URL across extractor reopen/seek")
            assertEquals(1,lookups.get(),"Metadata for the same playback file should not be fetched on every reopen")
            expireNext.set(true)
            AliyunConnector(api(server),access,downloadClient=client).connect(source,Credentials(""),Cancellation()).use{session->
                session.open("video").use{file->assertEquals(1,file.readAt(0,ByteArray(1),0,1))}
            }
            assertEquals(3,locations.get(),"A new session resolves once; 403 refreshes once without reusing the rejected cache entry")
            assertEquals(2,lookups.get(),"Cached file metadata must not escape its source session")
            assertFalse(leakedAuthorization.get())

        }}
    }

    @Test fun telemetrySnapshotDoesNotWaitForBlockedBodyAndCancellationStillWorks():Unit=MockWebServer().use{server->
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range","bytes 0-7/8")
            .setBody("abcdefgh").setBodyDelay(2,java.util.concurrent.TimeUnit.SECONDS))
        val file=AliyunRangeFile(8,Cancellation()){server.url("/body").toString()}
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            val reading=worker.submit<Int>{file.readAt(0,ByteArray(1),0,1)}
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while(file.readTelemetry.snapshot().stage!=RemoteReadTelemetry.Stage.BODY&&System.nanoTime()<deadline)Thread.sleep(5)
            val began=System.nanoTime();val snapshot=file.readTelemetry.snapshot()
            assertEquals(RemoteReadTelemetry.Stage.BODY,snapshot.stage)
            assertTrue((System.nanoTime()-began)/1000000<150,"Diagnostic reads must not wait for body I/O")
            assertEquals(1L,snapshot.rangeRequests);assertEquals(0L,snapshot.sourceBytes)
            file.close()
            val failure=assertFailsWith<java.util.concurrent.ExecutionException>{reading.get(2,java.util.concurrent.TimeUnit.SECONDS)}
            assertTrue(failure.cause is IOException)
        }finally{file.close();worker.shutdownNow()}
    }

    @Test fun continuousCloudReadKeepsOneRangeStream():Unit=MockWebServer().use{server->
        val data=ByteArray(24*1024*1024){(it%251).toByte()}
        val requests=java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                requests.incrementAndGet()
                val range=Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(request.getHeader("Range").orEmpty())?:return MockResponse().setResponseCode(400)
                val start=range.groupValues[1].toInt();val end=range.groupValues[2].toIntOrNull()?:data.lastIndex
                return MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $start-$end/${data.size}")
                    .setHeadersDelay(150,java.util.concurrent.TimeUnit.MILLISECONDS).setBody(okio.Buffer().write(data,start,end-start+1))
            }
        }
        val began=System.nanoTime()
        ReadAheadFile(AliyunRangeFile(data.size.toLong(),Cancellation()){server.url("/continuous").toString()}).use{file->
            var position=0L;val bytes=ByteArray(8192);val digest=java.security.MessageDigest.getInstance("SHA-256")
            while(position<file.size){
                val n=file.readAt(position,bytes,0,bytes.size);assertTrue(n>0)
                digest.update(bytes,0,n);position+=n
            }
            assertContentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(data),digest.digest())
            assertEquals(data.size.toLong(),file.readTelemetry!!.snapshot().sourceBytes)
        }
        println("ALIYUN_CONTINUOUS requests=${requests.get()} elapsedMs=${(System.nanoTime()-began)/1000000}")
        assertEquals(1,requests.get(),"Continuous playback should not incur another HTTP response wait at each local chunk boundary")
    }

    @Test fun serverBoundedRangeContinuesWithoutSkippingBytes():Unit=MockWebServer().use{server->
        val data=ByteArray(512*1024+19){(it%251).toByte()}
        val requests=java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                assertTrue(request.getHeader("Range")!!.endsWith("-"))
                val start=request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                val end=minOf(start+65536-1,data.lastIndex);requests.incrementAndGet()
                return MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $start-$end/${data.size}")
                    .setBody(okio.Buffer().write(data,start,end-start+1))
            }
        }
        ReadAheadFile(AliyunRangeFile(data.size.toLong(),Cancellation()){server.url("/bounded").toString()}).use{file->
            var position=0L;val bytes=ByteArray(32768)
            while(position<data.size){val n=file.readAt(position,bytes,0,bytes.size);assertTrue(n>0)
                assertContentEquals(data.copyOfRange(position.toInt(),position.toInt()+n),bytes.copyOf(n));position+=n}
        }
        assertEquals(9,requests.get())
    }

    @Test fun changedTotalAndInvalidRangeBoundsRemainRejected():Unit=MockWebServer().use{server->
        for(header in listOf("bytes 5-7/9","bytes 4-7/8","bytes 5-8/8","bytes 5-4/8")){
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range",header).setBody("abc"))
            AliyunRangeFile(8,Cancellation()){server.url("/invalid").toString()}.use{file->
                assertFailsWith<IOException>{file.readAt(5,ByteArray(3),0,3)}
            }
        }
    }

    private fun api(server: MockWebServer) = AliyunApi(server.url("/"), OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())
}
