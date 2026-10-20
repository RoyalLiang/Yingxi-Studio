package io.localplayer.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class MediaEnrichmentTest {
    private val http = EnrichmentHttp(permitHttpForTests = true)
    private class BytesFile(val bytes: ByteArray) : RemoteFile {
        val offsets = mutableListOf<Long>()
        override val size get() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            offsets += position
            val count = minOf(257, length, bytes.size - position.toInt())
            if (count <= 0) return -1
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() {}
    }
    @Test fun releaseNamesKeepFilmYearAndEpisodeSeparate() {
        val movie = MediaIdentity.parse("Interstellar.2014.1080p.BluRay.x265.mkv")
        assertEquals("Interstellar", movie.title); assertEquals(2014, movie.year)
        val show = MediaIdentity.parse("Severance.S02E03.2160p.WEB-DL.mkv")
        assertEquals("Severance", show.title); assertEquals(2, show.season); assertEquals(3, show.episode)
        assertEquals("1917", MediaIdentity.parse("1917.1080p.mkv").title)
    }
    @Test fun hashUsesFourProtocolBlocksEvenWhenRemoteReturnsShortReads() {
        val data = ByteArray(48000) { (it * 17 + it / 101).toByte() }
        val file = BytesFile(data)
        val expected = listOf(4096, 32000, 16000, 39808).joinToString(";") { at ->
            MessageDigest.getInstance("MD5").digest(data.copyOfRange(at, at + 4096)).joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected, ShooterSubtitles().fingerprint(file, Cancellation()))
        assertTrue(file.offsets.containsAll(listOf(4096L, 32000L, 16000L, 39808L)))
        assertFailsWith<IllegalArgumentException> { ShooterSubtitles().fingerprint(BytesFile(ByteArray(8192)), Cancellation()) }
    }
    @Test fun shooterSendsOnlyBasenameAndRanksBilingualSrt(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""[{"Desc":"中文字幕","Delay":0,"Files":[{"Ext":"srt","Link":"https://example.com/zh.srt"},{"Ext":"exe","Link":"https://example.com/a.exe"}]},{"Desc":"中英双语","Delay":250,"Files":[{"Ext":"ass","Link":"https://example.com/bi.ass"},{"Ext":"srt","Link":"https://example.com/bi.srt"},{"Ext":"srt","Link":"http://example.com/insecure.srt"}]}]"""))
        val results = ShooterSubtitles(http, server.url("/sub").toString()).search(BytesFile(ByteArray(48000)), "/private/NAS/Film.mkv", true, Cancellation())
        assertEquals(3, results.size); assertEquals("https://example.com/bi.srt", results.first().url)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("pathinfo=Film.mkv")); assertFalse(body.contains("private")); assertTrue(body.contains("lang=chn"))
    }
    @Test fun simplifiedChineseRanksBeforeTraditionalFormatPreference(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""[{"Desc":"繁体中文","Files":[{"Ext":"srt","Link":"https://example.com/traditional.srt"}]},{"Desc":"简体中英双语","Files":[{"Ext":"ass","Link":"https://example.com/simplified.ass"}]}]"""))
        val results=ShooterSubtitles(http,server.url("/").toString()).search(BytesFile(ByteArray(48000)),"Film.mkv",false,Cancellation())
        assertEquals("https://example.com/simplified.ass",results.first().url)
    }
    @Test fun shooterNoMatchByteIsAnEmptyResult(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(Buffer().writeByte(255)))
        assertTrue(ShooterSubtitles(http, server.url("/").toString()).search(BytesFile(ByteArray(48000)), "Film.mkv", false, Cancellation()).isEmpty())
    }
    @Test fun unlabeledSubtitleDialoguePrefersSimplifiedAndRejectsEnglish() {
        val p=ShooterSubtitles()
        fun s(text:String)=("1\n00:00:01,000 --> 00:00:02,000\n"+text).toByteArray()
        val simplified=s("这会让我们开始观看这个国家的影片")
        val traditional=s("這會讓我們開始觀看這個國家的影片")
        assertTrue(p.preferenceScore(simplified,false)!!>p.preferenceScore(traditional,false)!!)
        assertNull(p.preferenceScore(s("The subtitles are entirely in English."),false))
        val bilingual=s("这会让我们开始观看这个国家的影片\nThis film is ready for us to watch together.")
        assertTrue(p.preferenceScore(bilingual,true)!!>p.preferenceScore(simplified,true)!!)
    }
    @Test fun subtitleGzipLegacyEncodingAndDelayAreNormalized(): Unit = MockWebServer().use { server ->
        val raw = "1\n00:00:01,000 --> 00:00:02,500\n中文字幕\n".toByteArray(charset("GB18030"))
        val zipped = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(zipped)))
        val result = ShooterSubtitles(http).download(SubtitleMatch(server.url("/x").toString(), "srt", "中文", 750), Cancellation()).toString(Charsets.UTF_8)
        assertTrue(result.contains("00:00:01,750 --> 00:00:03,250"))
        assertTrue(result.contains("中文字幕"))
    }
    @Test fun big5ChineseIsNotMisdecodedAsGbk(): Unit = MockWebServer().use { server ->
        val raw="1\n00:00:01,000 --> 00:00:02,500\n這會讓我們開始觀看這個國家的影片\n"
        server.enqueue(MockResponse().setBody(Buffer().write(raw.toByteArray(charset("Big5")))))
        val data=ShooterSubtitles(http).download(SubtitleMatch(server.url("/").toString(),"srt","",0),Cancellation())
        assertEquals(raw,data.toString(Charsets.UTF_8))
    }
    @Test fun assDelayChangesStartAndEndTimestamps(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("[Events]\nDialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,字幕"))
        val text = ShooterSubtitles(http).download(SubtitleMatch(server.url("/").toString(), "ass", "x", -500), Cancellation()).toString(Charsets.UTF_8)
        assertTrue(text.contains("0:00:00.50,0:00:03.00"))
    }
    @Test fun htmlAndOversizedDecompressedSubtitleAreRejected(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("<html>00:00:00,000 --> 00:00:01,000</html>"))
        assertFailsWith<IllegalArgumentException> { ShooterSubtitles(http).download(SubtitleMatch(server.url("/").toString(), "srt", "x", 0), Cancellation()) }
        val zipped = java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(4 * 1024 * 1024 + 1) { 65 }) } }.toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(zipped)))
        assertFailsWith<IllegalArgumentException> { ShooterSubtitles(http).download(SubtitleMatch(server.url("/").toString(), "srt", "x", 0), Cancellation()) }
    }
    @Test fun metadataBodyAndCancellationAreBounded(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("123456"))
        assertFailsWith<IOException> { http.get(server.url("/").toString(), Cancellation(), 5) }
        val cancelled = Cancellation().apply { close() }
        assertFailsWith<java.io.InterruptedIOException> { http.get(server.url("/").toString(), cancelled, 5) }
        assertEquals(1, server.requestCount)
        assertFailsWith<IllegalArgumentException> { EnrichmentHttp().get("http://example.com/", Cancellation(), 5) }
    }
    @Test fun tvPosterRequiresExactUnambiguousShow(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""[{"show":{"name":"Severance","premiered":"2022-02-18","url":"https://www.tvmaze.com/shows/1/severance","image":{"medium":"https://static.tvmaze.com/x.jpg"}}}]"""))
        val poster = PosterLookup(http, server.url("/").toString()).find(MediaIdentity("Severance", 2022, 1, 1), Cancellation())
        assertNotNull(poster); assertTrue(poster.credit.contains("CC BY-SA"))
    }
    @Test fun wikiRejectsKnownWrongYearAndUsesMatchingFilm(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"query":{"pages":{"1":{"pageid":1,"title":"Example (1998 film)","thumbnail":{"source":"https://upload.wikimedia.org/wrong.jpg"}},"2":{"pageid":2,"title":"Example (2020 film)","thumbnail":{"source":"https://upload.wikimedia.org/right.jpg"}}}}}"""))
        val poster = PosterLookup(http, wikiBase = server.url("/").toString()).find(MediaIdentity("Example", 2020), Cancellation())
        assertEquals("https://upload.wikimedia.org/right.jpg", poster?.imageUrl)
    }
    @Test fun wikiAmbiguousTitlesAndUntrustedImagesFallBack(): Unit = MockWebServer().use { server ->
        val body = """{"query":{"pages":{"1":{"pageid":1,"title":"Example (1998 film)","thumbnail":{"source":"https://upload.wikimedia.org/a.jpg"}},"2":{"pageid":2,"title":"Example (2020 film)","thumbnail":{"source":"https://upload.wikimedia.org/b.jpg"}}}}}"""
        repeat(2) { server.enqueue(MockResponse().setBody(body)) }
        assertNull(PosterLookup(http, wikiBase = server.url("/").toString()).find(MediaIdentity("Example"), Cancellation()))
        repeat(2) { server.enqueue(MockResponse().setBody("""{"query":{"pages":{"1":{"pageid":1,"title":"Example","thumbnail":{"source":"https://unrelated.example/a.jpg"}}}}}""")) }
        assertNull(PosterLookup(http, wikiBase = server.url("/").toString()).find(MediaIdentity("Example"), Cancellation()))
    }
}
