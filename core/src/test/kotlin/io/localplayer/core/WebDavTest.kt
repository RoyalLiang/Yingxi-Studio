package io.localplayer.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test

class WebDavTest {
    private fun source(server:MockWebServer)=Source(label="Cloud",protocol=Protocol.WEBDAV,host=server.hostName,port=server.port,root="/dav",username="reader",secure=false)
    private fun xml(entries:String)="""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">$entries</d:multistatus>"""
    private fun entry(href:String,directory:Boolean=false,status:Int=200)="""<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if(directory)"<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>123</d:getcontentlength></d:prop><d:status>HTTP/1.1 $status OK</d:status></d:propstat></d:response>"""

    @Test fun authenticatedDirectoryListingIsBoundedToRequestedFolder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(entry("/dav/folder/",true)+entry("/dav/folder/%E6%B5%B7%20%E8%BE%B9.mp4")+entry("/dav/outside.mp4")+entry("/dav/folder/child/deep.mp4")+entry("/dav/folder/no.mp4",status=403)+entry("/dav/folder/encoded%2fslash.mp4"))))
            WebDavConnector().connect(source(server),Credentials("secret")).use { session ->
                val files=session.list("folder")
                assertEquals(listOf("海 边.mp4"),files.map{it.name})
                assertEquals("folder/海 边.mp4",files.single().path)
            }
            val request=server.takeRequest();assertEquals("PROPFIND",request.method);assertEquals("/dav/folder/",request.path)
            assertEquals("1",request.getHeader("Depth"));assertNotNull(request.getHeader("Authorization"))
        }
    }

    @Test fun openingAtResumePositionDoesNotReadPrefixOrProbe() {
  val content=ByteArray(200){it.toByte()}
  MockWebServer().use{server->
   server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 100-199/200").setBody(Buffer().write(content,100,100)))
   WebDavConnector().connect(source(server),Credentials("secret")).use{session->session.open("movie.mp4",100).use{file->
    val output=ByteArray(10);assertEquals(200L,file.size);assertEquals(10,file.readAt(100,output,0,10));assertContentEquals(content.copyOfRange(100,110),output)
   }}
   assertEquals(1,server.requestCount);assertEquals("bytes=100-",server.takeRequest().getHeader("Range"))
  }
 }
 @Test fun openingUsesOneRangeForMetadataAndFirstBytes() {
  MockWebServer().use { server ->
   repeat(2){server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-99/100").setBody("x".repeat(100)).setHeadersDelay(300,TimeUnit.MILLISECONDS))}
   val started=System.nanoTime()
   WebDavConnector().connect(source(server),Credentials("secret")).use{session->
    session.open("movie.mp4").use{file->
     assertEquals(100L,file.size)
     val bytes=ByteArray(10);assertEquals(10,file.readAt(0,bytes,0,10));assertEquals("x".repeat(10),bytes.decodeToString())
    }
   }
   println("DAV_FIRST_READ elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" requests="+server.requestCount)
   assertEquals(1,server.requestCount,"Opening must preserve the response used to obtain file size")
   assertEquals("bytes=0-",server.takeRequest().getHeader("Range"))
  }
 }
 @Test fun readsReuseStreamAndSeekByRangeWithoutDownloadingPrefix() {
        val content=ByteArray(2*1024*1024){(it%251).toByte()}
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val range=request.getHeader("Range")!!
                val start=range.removePrefix("bytes=").substringBefore('-').toInt()
                val end=range.substringAfter('-').toIntOrNull() ?: content.lastIndex
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${content.size}").setBody(Buffer().write(content,start,end-start+1))
            }}
            WebDavConnector().connect(source(server),Credentials("secret")).use { session -> session.open("movie.mp4").use { file ->
                assertEquals(content.size.toLong(),file.size)
                val output=ByteArray(100)
                for(position in listOf(0L,100L,1_500_000L,0L)){
                    assertEquals(100,file.readAt(position,output,0,100));assertContentEquals(content.copyOfRange(position.toInt(),position.toInt()+100),output)
                }
                assertEquals(-1,file.readAt(file.size,output,0,100))
            }}
            assertEquals(3,server.requestCount)
            assertEquals(listOf("bytes=0-","bytes=1500000-","bytes=0-"),(1..3).map{server.takeRequest().getHeader("Range")})
        }
    }

    @Test fun slowStreamReturnsAvailableBytesWithoutFillingReadAheadWindow() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-2097151/2097152")
                .setBody(Buffer().write(ByteArray(2097152){(it%251).toByte()})).throttleBody(8192,200,TimeUnit.MILLISECONDS))
            val pool=Executors.newSingleThreadExecutor()
            val session=WebDavConnector().connect(source(server),Credentials("secret"))
            val file=ReadAheadFile(session.open("slow.mp4"))
            try {
                val output=ByteArray(8192)
                val started=System.nanoTime()
                val result=pool.submit<Int>{file.readAt(0,output,0,output.size)}
                val n=result.get(3,TimeUnit.SECONDS)
                val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
                assertTrue(elapsed<700,"First bytes blocked behind read-ahead: ${elapsed}ms")
                assertTrue(n>0)
                assertContentEquals(ByteArray(n){(it%251).toByte()},output.copyOf(n))
            } finally {file.close();session.close();pool.shutdownNow()}
        }
    }


 @Test fun rejectsIgnoredOrMismatchedRangeAndAuthenticationFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("whole file"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1-1/100").setBody("x"))
            server.enqueue(MockResponse().setResponseCode(401))
            WebDavConnector().connect(source(server),Credentials("secret")).use { session ->
                assertFailsWith<IOException>{session.open("one.mp4")}
                assertFailsWith<IOException>{session.open("two.mp4")}
                assertTrue(assertFailsWith<IOException>{session.list("")}.message!!.contains("登录失败"))
            }
        }
    }

    @Test fun crossOriginHttpsRedirectRetainsRangeButNeverBasicCredentials() {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager).followRedirects(false).build()
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            origin.useHttps(serverTls.sslSocketFactory(),false);cdn.useHttps(serverTls.sslSocketFactory(),false)
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location",cdn.url("/movie")))
            cdn.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-0/100").setBody("x"))
            val config=source(origin).copy(secure=true,userAgent="pan.baidu.com",referer="https://www.alipan.com/")
            WebDavConnector(client).connect(config,Credentials("secret")).use{session->session.open("movie.mp4").close()}
            assertNotNull(origin.takeRequest().getHeader("Authorization"))
            val request=cdn.takeRequest();assertNull(request.getHeader("Authorization"));assertEquals("bytes=0-",request.getHeader("Range"));assertEquals("pan.baidu.com",request.getHeader("User-Agent"))
        }}
    }

    @Test fun rejectsCrossOriginCleartextRedirectAndXmlEntities() {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/movie")))
            origin.enqueue(MockResponse().setResponseCode(207).setBody("""<!DOCTYPE x [<!ENTITY x SYSTEM "file:///etc/passwd">]><x/>"""))
            WebDavConnector().connect(source(origin),Credentials("secret")).use { session ->
                assertFailsWith<IOException>{session.open("movie.mp4")};assertEquals(0,other.requestCount)
                assertFailsWith<IOException>{session.list("")}
            }
        }}
    }

    @Test fun cancellationInterruptsPendingNetworkRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val token=Cancellation();val session=WebDavConnector().connect(source(server),Credentials("secret"),token)
            val executor=Executors.newSingleThreadExecutor()
            try {
                val task=executor.submit<Boolean>{try{session.open("movie.mp4");false}catch(e:IOException){true}}
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));token.close()
                assertTrue(task.get(2,TimeUnit.SECONDS))
            }finally{token.close();session.close();executor.shutdownNow()}
        }
    }

    @Test fun closingFileInterruptsRangeRequestBeforeResponseHeaders() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-0/100").setBody("x"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val session=WebDavConnector().connect(source(server),Credentials("secret"))
            val executor=Executors.newSingleThreadExecutor()
            try {
                val file=session.open("movie.mp4")
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))
                val task=executor.submit<Boolean>{try{file.readAt(10,ByteArray(10),0,10);false}catch(e:IOException){true}}
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))
                file.close()
                assertTrue(task.get(2,TimeUnit.SECONDS))
            } finally {session.close();executor.shutdownNow()}
        }
    }

    @Test fun addressParsingPreservesEncodedNamesAndRejectsInlineCredentials() {
        val source=Source(label="",protocol=Protocol.WEBDAV,host="")
        val parsed=source.withWebDavAddress("https://cloud.example/dav/%E6%B5%B7%20%E8%BE%B9/")
        assertEquals("/dav/海 边",parsed.root);assertEquals(443,parsed.port);assertTrue(parsed.secure)
        assertFailsWith<IllegalArgumentException>{source.withWebDavAddress(java.net.URI("https","reader:secret","cloud.example",-1,"/dav",null,null).toString())}
        assertFailsWith<IllegalArgumentException>{source.withWebDavAddress("https://cloud.example/dav/../")}
        assertFailsWith<IllegalArgumentException>{source.withWebDavAddress("file:///dav")}
    }
}
