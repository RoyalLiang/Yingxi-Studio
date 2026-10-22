package io.localplayer.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AliyunDownloadNoticeTest {
    @Test fun rightsNoticeSurvivesCachedLocationAndSlowOriginalRead() = exercise(
        "当前用户未购买权益包，下载速度可能较慢。",
        true, true
    )
    @Test fun unknownProviderTextIsNotReclassifiedOrRetained() = exercise("private-film https://secret.invalid/token=private-token", false)
    @Test fun absentDescriptionDoesNotClaimServiceRestriction() = exercise(null, false)

    @Test fun signedLimitIsReportedWithoutChangingOriginalUrl() = exercise(null,false,signedLimit=1048576)
    @Test fun refreshedLocationReplacesTheOldAdvisory() = exercise("当前用户未购买权益包，下载速度可能较慢。",true,refreshing=true)
    @Test fun structuredDescriptionIsIgnoredSafely() = exercise(null,false,malformed=true)

    private fun exercise(description:String?, expectedNotice:Boolean, slow:Boolean=false,signedLimit:Long?=null,malformed:Boolean=false,refreshing:Boolean=false) {
        val held=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(held).build()
        val trusted=HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(),trusted.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        MockWebServer().use { cdn -> MockWebServer().use { api ->
            cdn.useHttps(tls.sslSocketFactory(),false)
            val expireNext=java.util.concurrent.atomic.AtomicBoolean()
            val data=ByteArray(128*1024){(it%251).toByte()}
            cdn.dispatcher=object:Dispatcher(){
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(expireNext.getAndSet(false))return MockResponse().setResponseCode(403)
                    assertNull(request.getHeader("Authorization"))
                    assertEquals(signedLimit?.toString(),request.requestUrl!!.queryParameter("x-oss-traffic-limit"))
                    val start=request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                    val response=MockResponse().setResponseCode(206).addHeader("Content-Range","bytes $start-${data.lastIndex}/${data.size}")
                        .setBody(okio.Buffer().write(data,start,data.size-start))
                    return if(slow) response.throttleBody(8192,100,TimeUnit.MILLISECONDS) else response
                }
            }
            val location=JsonObject().apply {
                val url=cdn.url("/original").newBuilder().apply{if(signedLimit!=null)addQueryParameter("x-oss-traffic-limit",signedLimit.toString())}.build()
                addProperty("url",url.toString())
                if(description!=null)addProperty("description",description)
                if(malformed)add("description",JsonObject().apply{addProperty("private-field","secret")})
            }
            api.enqueue(MockResponse().setBody("""{"file_id":"video","name":"original.mkv","type":"file","size":${data.size}}"""))
            api.enqueue(MockResponse().setBody(location.toString()))
            if(refreshing)api.enqueue(MockResponse().setBody(location.deepCopy().apply{remove("description")}.toString()))
            val access=object:AliyunAccess { override fun <T> authorized(cancellation:Cancellation,action:(String)->T):T=action("synthetic-token") }
            val source=Source(label="cloud",protocol=Protocol.ALIYUN,host="openapi.alipan.com",domain="drive")
            val connector=AliyunConnector(AliyunApi(api.url("/"),OkHttpClient()),access,downloadClient=client)
            connector.connect(source,Credentials(""),Cancellation()).use { session ->
                for(start in listOf(0,65536)) {
                    if(refreshing&&start>0)expireNext.set(true)
                    session.open("video",start.toLong()).use { file ->
                        val actual=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var position=start.toLong()
                        while(position<data.size){ val n=file.readAt(position,buffer,0,buffer.size);assertTrue(n>0);actual.write(buffer,0,n);position+=n }
                        assertContentEquals(data.copyOfRange(start,data.size),actual.toByteArray(),"Original bytes must remain identical")
                        val telemetry=file.readTelemetry!!.snapshot()
                        assertEquals(signedLimit,telemetry.signedTrafficLimitBitsPerSecond)
                        val serialized=Gson().toJson(telemetry)
                        val report=Gson().fromJson(serialized,JsonObject::class.java)
                        assertEquals(expectedNotice&&!(refreshing&&start>0),report.get("downloadRightsNotice")?.asBoolean?:false,"Provider rights warning was discarded")
                        listOf("secret.invalid","private-film","private-token","synthetic-token","https://").forEach { assertFalse(serialized.contains(it)) }
                        if(slow&&start==0){
                            assertTrue(telemetry.bodyMs>=900,"Fixture must reproduce slow socket reads")
                            assertTrue(telemetry.bodyBytesPerSecond!! in 32768..262144,"Measured speed must reflect slow original transfer")
                        }
                    }
                }
                assertEquals(if(refreshing)3 else 2,api.requestCount,"Cached URL and notice must survive MKV reopen")
            }
        }}
    }
}
