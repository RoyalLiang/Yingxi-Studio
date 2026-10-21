package io.localplayer.core

import kotlin.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DirectoryPosterTest {
    @Test fun anonymousEpisodesUseTheShowFolder() {
        val a=MediaIdentity.forPoster("1.mkv","电视剧/漫长的季节/1.mkv")
        val b=MediaIdentity.forPoster("02.mp4","电视剧/漫长的季节/02.mp4")
        assertEquals("漫长的季节",a.title);assertTrue(a.series)
        assertEquals(a.copy(episode=2),b)
        assertEquals("向往的生活",MediaIdentity.forPoster("第3期.mp4","综艺/向往的生活/第3期.mp4").title)
        val season=MediaIdentity.forPoster("S02E03.mkv","Example (2024)/Season 2/S02E03.mkv")
        assertEquals("Example",season.title);assertEquals(2024,season.year);assertEquals(2,season.season);assertTrue(season.series)
    }
    @Test fun namedFilmsAndGenericFoldersNeverInheritAnUnrelatedTitle() {
        val film=MediaIdentity.parse("小丑2.2024.mkv")
        assertEquals(film,MediaIdentity.forPoster("小丑2.2024.mkv","其他片名/小丑2.2024.mkv"))
        assertEquals("1",MediaIdentity.forPoster("1.mkv","电视剧/1.mkv").title)
        assertEquals("1",MediaIdentity.forPoster("1.mkv","1.mkv").title)
    }
    @Test fun numericEpisodesCanShareTheirShowDirectoryCoverButMixedFilmsCannot() {
        val siblings=listOf("1.mkv","2.mkv","poster.jpg")
        assertTrue("poster.jpg" in posterSidecarNames("1.mkv",siblings,"漫长的季节/1.mkv"))
        assertFalse("poster.jpg" in posterSidecarNames("1.mkv",siblings+"小丑2.mkv","漫长的季节/1.mkv"))
    }
    @Test fun numericEpisodeUsesUniqueShowMatchRatherThanFilmSearch():Unit=MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""[{"show":{"name":"Example","premiered":"2024-01-01","url":"https://www.tvmaze.com/shows/1/example","image":{"medium":"https://static.tvmaze.com/example.jpg"}}}]"""))
        val id=MediaIdentity.forPoster("1.mkv","Example (2024)/1.mkv")
        val match=PosterLookup(EnrichmentHttp(permitHttpForTests=true),tvBase=server.url("/").toString()).find(id,Cancellation())
        assertNotNull(match);assertTrue(match.confidence>=.96)
        assertEquals("Example",server.takeRequest().requestUrl!!.queryParameter("q"))
    }
    @Test fun chineseSeriesCanUseExactWikipediaTelevisionIdentity():Unit=MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""{"query":{"pages":{"1":{"pageid":1,"title":"漫长的季节","categories":[{"title":"Category:2023年中国电视剧"}],"thumbnail":{"source":"https://upload.wikimedia.org/example.jpg"}}}}}"""))
        val match=PosterLookup(EnrichmentHttp(permitHttpForTests=true),tvBase=server.url("/").toString(),wikiBase=server.url("/").toString())
            .find(MediaIdentity.forPoster("1.mkv","漫长的季节/1.mkv"),Cancellation())
        assertNotNull(match)
        server.takeRequest();val request=server.takeRequest().requestUrl!!
        assertTrue(request.queryParameter("titles")!!.contains("电视剧"))
    }
}
