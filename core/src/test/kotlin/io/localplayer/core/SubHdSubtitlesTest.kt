package io.localplayer.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubHdSubtitlesTest {
    private val caption="1\n00:00:01,000 --> 00:00:03,000\n这是用于测试的中文字幕。\n".toByteArray()
    private fun client(server:MockWebServer)=SubHdSubtitles(EnrichmentHttp(permitHttpForTests=true),server.url("/").toString())
    private fun resource(server:MockWebServer,title:String="A Test 2024")=SubtitleResource("ABC123",title,server.url("/a/ABC123").toString())
    private fun zip(vararg files:Pair<String,ByteArray>)=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->files.forEach{(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}}.toByteArray()

    @Test fun searchKeepsEpisodeAndYearBoundariesAndDeduplicatesImageLinks() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("""
                <a href='/a/ABC123'><img src='/poster.jpg'></a>
                <a class='text' href='/a/ABC123'><b>A.Test.S01E03.2024</b> 简体双语</a>
                <a href='/a/PACK'>A.Test.S01.2024</a>
                <a href="/a/WRONG1">A Test S01E04 2024</a>
                <a href='/a/WRONG2'>A Test S02E03 2024</a>
                <a href='/a/WRONG3'>A Test S01E03 2021</a>
                <a href='/a/OTHER'>Another Film S01E03 2024</a>
            """.trimIndent()))
            val found=client(s).search(MediaIdentity("A Test",2024,1,3),Cancellation())
            assertEquals(listOf("ABC123","PACK"),found.map{it.id})
            assertTrue(found.all{it.automatic})
            val request=s.takeRequest();assertTrue(request.path!!.startsWith("/search/"));assertFalse(request.path!!.contains("S01E03"))
            assertEquals(s.url("/a/ABC123").toString(),found.first().page)
        }
    }
    @Test fun rejectedPreparationDoesNotPretendToDownloadOrFetchAnotherHost() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("""{"success":false,"msg":"Return to details"}"""))
            assertThrows(SubtitleDownloadUnavailable::class.java){client(s).download(resource(s),MediaIdentity("A Test",2024),Cancellation())}
            assertEquals(1,s.requestCount)
            val request=s.takeRequest();assertEquals("POST",request.method);assertEquals("/api/sub/prepare-download",request.path)
            assertEquals("{\"sid\":\"ABC123\"}",request.body.readUtf8())
        }
    }
    @Test fun seasonArchiveOnlyReturnsTheRequestedEpisodeAndIgnoresUnsafeEntries() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/ticket"}"""))
            s.enqueue(MockResponse().setBody(Buffer().write(zip("A.Test.S01E03.chs.srt" to caption,"A.Test.S01E04.chs.srt" to caption,
                "A.Test.S02E03.chs.srt" to caption,"../A.Test.S01E03.srt" to caption,"__MACOSX/A.Test.S01E03.srt" to caption))))
            val found=client(s).download(resource(s,"A Test S01 2024"),MediaIdentity("A Test",2024,1,3),Cancellation())
            assertEquals(1,found.size);assertTrue(found.single().name.endsWith("E03.chs.srt"));assertArrayEquals(caption,found.single().bytes)
        }
    }
    @Test fun directCaptionWorksAndHtmlCannotBecomeASubtitle() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/ticket"}"""));s.enqueue(MockResponse().setBody(Buffer().write(caption)))
            assertEquals("srt",client(s).download(resource(s),MediaIdentity("A Test",2024),Cancellation()).single().extension)
            s.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/ticket"}"""));s.enqueue(MockResponse().setBody("<!DOCTYPE html><html>Verify --> access</html>"))
            assertThrows(java.io.IOException::class.java){client(s).download(resource(s),MediaIdentity("A Test",2024),Cancellation())}
        }
    }
    @Test fun downloadCannotEscapeThePreparedPathOrOrigin() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("""{"success":true,"url":"//another.invalid/down/file"}"""))
            assertThrows(SubtitleDownloadUnavailable::class.java){client(s).download(resource(s),MediaIdentity("A Test"),Cancellation())}
            s.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/../private"}"""))
            assertThrows(IllegalArgumentException::class.java){client(s).download(resource(s),MediaIdentity("A Test"),Cancellation())}
            assertEquals(2,s.requestCount)
        }
    }
    @Test fun oversizedInflatedEntryIsRejected() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/ticket"}"""))
            s.enqueue(MockResponse().setBody(Buffer().write(zip("oversize.srt" to ByteArray(4*1024*1024+1)))))
            assertThrows(IllegalArgumentException::class.java){client(s).download(resource(s),MediaIdentity("A Test"),Cancellation())}
        }
    }
    @Test fun partialNamesAndUnidentifiedYearsCannotAutomaticallyLoadAnotherFilm() {
        MockWebServer().use{s->
            s.enqueue(MockResponse().setBody("<a href='/a/SUPER'>Superman.2009</a><a href='/a/UP'>Up.2009</a><a href='/a/SEQUEL'>Up.Chapter.Two.2009</a><a href='/a/TV'>Up.S01E01.2009</a>"))
            val found=client(s).search(MediaIdentity("Up",2009),Cancellation())
            assertEquals(setOf("UP","SEQUEL","TV"),found.map{it.id}.toSet())
            assertEquals(listOf("UP"),found.filter{it.automatic}.map{it.id})
            s.enqueue(MockResponse().setBody("<a href='/a/WRONG'>射雕英雄传.2002</a><a href='/a/HERO'>英雄.2002</a>"))
            assertEquals(listOf("HERO"),client(s).search(MediaIdentity("英雄",2002),Cancellation()).map{it.id})
            assertTrue(client(s).search(MediaIdentity("123456789"),Cancellation()).isEmpty())
            s.enqueue(MockResponse().setBody("<a href='/a/HERO'>英雄.2002</a>"))
            assertFalse(client(s).search(MediaIdentity("英雄"),Cancellation()).single().automatic)
        }
    }
}
