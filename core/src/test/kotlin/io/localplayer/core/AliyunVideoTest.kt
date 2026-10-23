package io.localplayer.core

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class AliyunVideoTest {
    @Test fun officialPersonalDriveEndpointUsesReadTokenAndNumericExpiry() {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody("""{"video_preview_play_info":{"live_transcoding_task_list":[{"template_id":"FHD","status":"finished","url":"https://cdn.invalid/full.m3u8?secret=fixture"}]}}"""))
            val info=AliyunApi(server.url("/"),OkHttpClient()).videoInfo("test-token","drive","file",Cancellation())
            val request=server.takeRequest()
            assertEquals("/adrive/v1.0/openFile/getVideoPreviewPlayInfo",request.path)
            assertEquals("Bearer test-token",request.getHeader("Authorization"))
            val body=JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("live_transcoding",body["category"].asString)
            assertTrue(body["url_expire_sec"].asJsonPrimitive.isNumber)
            assertEquals(900,body["url_expire_sec"].asInt)
            assertEquals("FHD",info.variants.single().template)
            assertNull(info.masterUrl)
            assertFalse(info.toString().contains("fixture"))
            assertFalse(info.variants.single().toString().contains("secret"))
        }
    }
    @Test fun onlyFinishedFullHttpsVariantsCanBeSelected() {
        val info=parse("""{"live_transcoding_task_list":[
            {"template_id":"LD","status":"finished","url":"https://cdn.invalid/low.m3u8"},
            {"template_id":"FHD","status":"finished","url":"https://cdn.invalid/full.m3u8"},
            {"template_id":"HD","status":"running","url":"https://cdn.invalid/running.m3u8"},
            {"template_id":"SD","status":"finished","preview_url":"https://cdn.invalid/30-seconds.m3u8"},
            {"template_id":"QHD","status":"finished","url":"http://cdn.invalid/insecure.m3u8"},
            {"template_id":"FHD","status":"finished","url":"https://cdn.invalid/duplicate.m3u8"},
            {"template_id":"NEW","status":"finished","url":"https://cdn.invalid/unknown.m3u8"}] }""")
        assertEquals(listOf("FHD","LD"),info.variants.map{it.template})
        assertEquals("https://cdn.invalid/full.m3u8",info.resolve("FHD"))
        assertFailsWith<java.io.IOException>{info.resolve("auto")}
        assertFailsWith<java.io.IOException>{info.resolve("SD")}
    }
    @Test fun autoRequiresARealProviderMasterAndRejectsCredentialsOrFragments() {
        assertEquals("https://cdn.invalid/master.m3u8",parse("""{"master_url":"https://cdn.invalid/master.m3u8"}""").resolve("auto"))
        for(url in listOf("http://cdn.invalid/master.m3u8",java.net.URI("https","synthetic-user:synthetic-password","cdn.invalid",-1,"/master.m3u8",null,null).toString(),"https://cdn.invalid/master.m3u8#fragment")){
            assertNull(parse("""{"master_url":"$url"}""").masterUrl)
        }
        assertTrue(parse("""{"live_transcoding_task_list":[false,2,"bad",{}]}""").variants.isEmpty())
    }
    @Test fun providerFailuresRetainOperationButNeverEchoSignedUrls() {
        MockWebServer().use{server->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"Forbidden","message":"https://cdn.invalid/private?token=secret"}"""))
            val error=assertFailsWith<AliyunApiFailure>{AliyunApi(server.url("/"),OkHttpClient()).videoInfo("test-token","drive","file",Cancellation())}
            assertTrue(error.message.orEmpty().contains("读取播放清晰度"))
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }
    private fun parse(value:String)=parseAliyunVideoInfo(JsonParser.parseString("""{"video_preview_play_info":$value}""").asJsonObject)
}
