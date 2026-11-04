package io.localplayer.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AliyunParallelConnectorTest {
    private val access=object:AliyunAccess{override fun <T> authorized(cancellation:Cancellation,action:(String)->T):T{cancellation.check();return action("fixture-token")}}
    private fun fixture(block:(AliyunApi,OkHttpClient,MockWebServer,MockWebServer)->Unit) {
        val held=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(held).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        MockWebServer().use { cdn -> MockWebServer().use { api ->
            cdn.useHttps(tls.sslSocketFactory(),false);cdn.start();api.start()
            block(AliyunApi(api.url("/"),OkHttpClient()),client,cdn,api)
        }}
        client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
    }
    @Test fun refreshesOnceWhenProviderRejectsSignedRequest()=fixture {_,client,cdn,_->
        cdn.enqueue(MockResponse().setResponseCode(400).setBody(" not a valid request"))
        cdn.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 7-10/11").setBody("DATA"))
        val refreshes=AtomicInteger()
        AliyunRangeFile(11,Cancellation(),client,refresh={_,rejected->
            assertEquals(cdn.url("/rejected").toString(),rejected)
            refreshes.incrementAndGet();cdn.url("/fresh").toString()
        },resolve={cdn.url("/rejected").toString()}).use{file->
            val bytes=ByteArray(4);assertEquals(4,file.readAt(7,bytes,0,4));assertEquals("DATA",String(bytes))
        }
        assertEquals(1,refreshes.get());assertEquals(2,cdn.requestCount)
        repeat(2){assertEquals("bytes=7-",cdn.takeRequest().getHeader("Range"))}
    }
    @Test fun otherBadRequestsDoNotRefreshSignedAddresses()=fixture {_,client,cdn,_->
        cdn.enqueue(MockResponse().setResponseCode(400).setBody("invalid range"))
        val refreshes=AtomicInteger()
        AliyunRangeFile(11,Cancellation(),client,refresh={_,_->refreshes.incrementAndGet();cdn.url("/fresh").toString()},resolve={cdn.url("/bad-range").toString()}).use{file->
            assertFailsWith<AliyunRangeResponseFailure>{file.readAt(7,ByteArray(4),0,4)}
        }
        assertEquals(0,refreshes.get());assertEquals(1,cdn.requestCount)
    }
    @Test fun repeatedSignedRequestRejectionStopsAfterOneRefresh()=fixture {_,client,cdn,_->
        repeat(2){cdn.enqueue(MockResponse().setResponseCode(400).setBody(" not a valid request"))}
        val refreshes=AtomicInteger()
        AliyunRangeFile(11,Cancellation(),client,refresh={_,_->refreshes.incrementAndGet();cdn.url("/fresh").toString()},resolve={cdn.url("/rejected").toString()}).use{file->
            assertFailsWith<AliyunRangeResponseFailure>{file.readAt(7,ByteArray(4),0,4)}
        }
        assertEquals(1,refreshes.get());assertEquals(2,cdn.requestCount)
    }
    @Test fun refreshesExpiringLinkBeforeRejectionAndReusesOnlyVersionedAccountCache()=fixture {api,client,cdn,server->
        var data=ByteArray(2*1024*1024+55){(it%251).toByte()}
        val urls=AtomicInteger();val requests=AtomicInteger()
        val root=kotlin.io.path.createTempDirectory("account-segments-").toFile()
        val cache=SegmentCache(root)
        cdn.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            assertNull(request.getHeader("Authorization"));requests.incrementAndGet()
            val r=Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range")!!)!!
            val from=r.groupValues[1].toInt();val end=r.groupValues[2].toIntOrNull()?:data.lastIndex
            return MockResponse().setResponseCode(206).addHeader("ETag","revision-${data[0]}")
                .addHeader("Content-Range","bytes $from-$end/${data.size}").setBody(Buffer().write(data,from,end-from+1))
        }}
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.path){
            "/adrive/v1.0/openFile/get"->{
                val hash=MessageDigest.getInstance("SHA-1").digest(data).joinToString(""){"%02x".format(it)}
                MockResponse().setBody("""{"file_id":"video","name":"sample.mkv","type":"file","size":${data.size},"content_hash":"$hash"}""")
            }
            "/adrive/v1.0/openFile/getDownloadUrl"->{
                val n=urls.incrementAndGet();val ttl=if(n==1)10L else 3600L
                MockResponse().setBody("""{"url":"${cdn.url("/cdn?v=$n")}","expiration":"${Instant.now().plusSeconds(ttl)}"}""")
            }
            else->MockResponse().setResponseCode(404)
        }}
        val source=Source(id="account-a",label="fixture",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
        val connector=AliyunConnector(api,access,client,readOptions={AliyunReadOptions(3,cache)})
        fun readAll(selected:Source=source):ByteArray = connector.connect(selected,Credentials("")).use { session ->
            session.open("video",0).use { file ->
                assertTrue(file.reusableCache)
                val out=ByteArray(file.size.toInt());var at=0
                while(at<out.size){val n=file.readAt(at.toLong(),out,at,minOf(8192,out.size-at));assertTrue(n>0);at+=n}
                out
            }
        }
        try {
            assertContentEquals(data,readAll());assertEquals(2,urls.get(),"Expiry refresh happens before any 403")
            val count=requests.get();assertContentEquals(data,readAll());assertEquals(count,requests.get(),"Complete matching account/revision reads from disk")
            assertContentEquals(data,readAll(source.copy(id="account-b")));assertTrue(requests.get()>count,"Another account cannot reuse private cached bytes")
            val before=requests.get();data=ByteArray(data.size){(it%241).toByte()}
            assertContentEquals(data,readAll());assertTrue(requests.get()>before,"Same id and size with changed content hash must not use stale bytes")
        }finally{cache.clear();root.deleteRecursively()}
    }
    @Test fun rejectsChangedRepresentationAcrossParallelSegments()=fixture {api,client,cdn,server->
        val size=2*1024*1024;val requests=AtomicInteger()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody(
            if(request.path!!.endsWith("/get"))"""{"file_id":"video","name":"sample.mkv","type":"file","size":$size}"""
            else """{"url":"${cdn.url("/video")}"}""") }
        cdn.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val r=Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range")!!)!!
            val from=r.groupValues[1].toInt();val end=r.groupValues[2].toIntOrNull()?:size-1
            return MockResponse().setResponseCode(206).addHeader("ETag","version-${requests.incrementAndGet()}")
                .addHeader("Content-Range","bytes $from-$end/$size").setBody(Buffer().write(ByteArray(end-from+1)))
        }}
        AliyunConnector(api,access,client,readOptions={AliyunReadOptions(1)}).connect(Source(label="fixture",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive"),Credentials("")).use { session ->
            session.open("video",0).use { file ->
                val out=ByteArray(8192);var at=0L
                while(at<1024*1024){at+=file.readAt(at,out,0,minOf(out.size.toLong(),1024*1024-at).toInt())}
                assertFailsWith<AliyunRangeResponseFailure>{file.readAt(at,out,0,out.size)}
                assertEquals(2,requests.get(),"Representation mismatch must not enter generic retry loop")
            }
        }
    }
}
