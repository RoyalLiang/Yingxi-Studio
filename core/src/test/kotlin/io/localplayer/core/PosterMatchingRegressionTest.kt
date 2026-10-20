package io.localplayer.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class PosterMatchingRegressionTest {
    private val http = EnrichmentHttp(permitHttpForTests = true)

    @Test fun jokerSequelFollowsExactChineseAliasInsteadOfDroppingTheSequel(): Unit = MockWebServer().use { server ->
        val body = """{"query":{"redirects":[{"from":"小丑2","to":"小丑：雙重瘋狂"}],"pages":{"1":{"pageid":1,"title":"小丑：雙重瘋狂","categories":[{"title":"Category:2024年美國電影作品"}],"thumbnail":{"source":"https://upload.wikimedia.org/joker-folie-a-deux.jpg"}}}}}"""
        repeat(2) { server.enqueue(MockResponse().setBody(body)) }
        val poster = PosterLookup(http, wikiBase = server.url("/").toString())
            .find(MediaIdentity.parse("小丑2.2024.1080p.mkv"), Cancellation())
        assertEquals("https://upload.wikimedia.org/joker-folie-a-deux.jpg", poster?.imageUrl)
    }

    @Test fun actualJokerSequelApiSnapshotResolvesWithAndWithoutReleaseYear() {
        val body=actualJokerSequelResponse
        listOf("小丑2.mkv", "小丑2.2024.1080p.mkv").forEach { filename -> MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val poster=PosterLookup(http,wikiBase=server.url("/").toString()).find(MediaIdentity.parse(filename),Cancellation())
            assertNotNull(poster)
            assertTrue(poster.imageUrl.contains("Joker_Folie_"))
            val request=server.takeRequest().requestUrl!!
            assertEquals("any",request.queryParameter("pilicense"))
            assertEquals("1",request.queryParameter("converttitles"))
        } }
    }

    @Test fun sharedFolderCoverNeverAppliesToDifferentMovies() {
        val candidates=posterSidecarNames("小丑2.2024.mkv",listOf("小丑2.2024.mkv","小丑.2019.mkv","poster.jpg"))
        assertFalse("poster.jpg" in candidates)
        assertTrue("小丑2.2024-poster.jpg" in candidates)
        assertTrue("poster.jpg" in posterSidecarNames("Show.S01E01.mkv",listOf("Show.S01E01.mkv","Show.S02E01.mkv")))
    }

    @Test fun decadeAndSettingCategoriesAreNotReleaseYears(): Unit = MockWebServer().use { server ->
        val body="""{"query":{"pages":{"1":{"pageid":1,"title":"Example","categories":[{"title":"Category:2020年代电影作品"},{"title":"Category:2019年背景电影"},{"title":"Category:2024年电影作品"}],"thumbnail":{"source":"https://upload.wikimedia.org/2024.jpg"}}}}}"""
        repeat(2){server.enqueue(MockResponse().setBody(body))}
        assertNull(PosterLookup(http,wikiBase=server.url("/").toString()).find(MediaIdentity("Example",2020),Cancellation()))
    }

    @Test fun numbersInFilmTitlesAreNotReleaseDates(): Unit = MockWebServer().use { server ->
        val body="""{"query":{"pages":{"1":{"pageid":1,"title":"1917","categories":[{"title":"Category:2019 films"}],"thumbnail":{"source":"https://upload.wikimedia.org/1917.jpg"}}}}}"""
        server.enqueue(MockResponse().setBody(body))
        val identity=MediaIdentity.parse("1917.2019.1080p.mkv")
        assertEquals("1917",identity.title);assertEquals(2019,identity.year)
        assertNotNull(PosterLookup(http,wikiBase=server.url("/").toString()).find(identity,Cancellation()))
    }

    @Test fun actorPagesDoNotCountAsFilms(): Unit = MockWebServer().use { server ->
        val body="""{"query":{"pages":{"1":{"pageid":1,"title":"某演员","categories":[{"title":"Category:美国电影演员"}],"thumbnail":{"source":"https://upload.wikimedia.org/actor.jpg"}}}}}"""
        repeat(2){server.enqueue(MockResponse().setBody(body))}
        assertNull(PosterLookup(http,wikiBase=server.url("/").toString()).find(MediaIdentity("某演员"),Cancellation()))
    }

    @Test fun disambiguationIsNeverUsedAsAPoster(): Unit = MockWebServer().use { server ->
        val body="""{"query":{"pages":{"1":{"pageid":1,"title":"Example (film)","pageprops":{"disambiguation":""},"thumbnail":{"source":"https://upload.wikimedia.org/ambiguous.jpg"}}}}}"""
        repeat(2){server.enqueue(MockResponse().setBody(body))}
        assertNull(PosterLookup(http,wikiBase=server.url("/").toString()).find(MediaIdentity("Example"),Cancellation()))
    }

    @Test fun yearlessUnclassifiedPageCannotStandInForAReleaseWithAKnownYear(): Unit = MockWebServer().use { server ->
        val body = """{"query":{"pages":{"1":{"pageid":1,"title":"Example","thumbnail":{"source":"https://upload.wikimedia.org/old.jpg"}}}}}"""
        repeat(2) { server.enqueue(MockResponse().setBody(body)) }
        assertNull(PosterLookup(http, wikiBase = server.url("/").toString()).find(MediaIdentity("Example", 2024), Cancellation()))
    }

    @Test fun aSeriesMissMustNotUseTheNamesakeMoviePoster(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("[]"))
        val body = """{"query":{"pages":{"1":{"pageid":1,"title":"Example (film)","thumbnail":{"source":"https://upload.wikimedia.org/movie.jpg"}}}}}"""
        repeat(2) { server.enqueue(MockResponse().setBody(body)) }
        assertNull(PosterLookup(http, tvBase = server.url("/").toString(), wikiBase = server.url("/").toString())
            .find(MediaIdentity("Example", season = 1, episode = 2), Cancellation()))
    }

    @Test fun resolutionIsNotAReleaseYear() {
        assertNull(MediaIdentity.parse("Example.1920x1080.mkv").year)
        assertEquals("Example", MediaIdentity.parse("Example.1920x1080.mkv").title)
    }
    // Public Wikipedia API response captured 2026-10-20; metadata only, no image payload.
    private val actualJokerSequelResponse = """
{
  "batchcomplete": "",
  "query": {
    "redirects": [
      {
        "from": "小丑2",
        "to": "小丑：雙重瘋狂"
      }
    ],
    "pages": {
      "8013085": {
        "pageid": 8013085,
        "ns": 0,
        "title": "小丑：雙重瘋狂",
        "thumbnail": {
          "source": "https://upload.wikimedia.org/wikipedia/zh/b/b1/Joker_Folie_%C3%A0_Deux_Poster.jpg?utm_source=zh.wikipedia.org&utm_campaign=api&utm_content=thumbnail_unscaled",
          "width": 259,
          "height": 384
        },
        "pageimage": "Joker_Folie_à_Deux_Poster.jpg",
        "pageprops": {
          "defaultsort": "Joker: Folie à Deux",
          "page_image": "Joker_Folie_à_Deux_Poster.jpg",
          "wikibase_item": "Q108628759"
        },
        "categories": [
          {
            "ns": 14,
            "title": "Category:2020年代心理驚悚片"
          },
          {
            "ns": 14,
            "title": "Category:2020年代犯罪惊悚片"
          },
          {
            "ns": 14,
            "title": "Category:2020年代英語片"
          },
          {
            "ns": 14,
            "title": "Category:2024年美國電影作品"
          },
          {
            "ns": 14,
            "title": "Category:2024年電影作品"
          },
          {
            "ns": 14,
            "title": "Category:CS1美国英语来源 (en-us)"
          },
          {
            "ns": 14,
            "title": "Category:CS1英语来源 (en)"
          },
          {
            "ns": 14,
            "title": "Category:Cite tweet模板錯誤"
          },
          {
            "ns": 14,
            "title": "Category:DC漫画改编电影"
          },
          {
            "ns": 14,
            "title": "Category:ScreenX電影"
          },
          {
            "ns": 14,
            "title": "Category:华纳兄弟电影"
          },
          {
            "ns": 14,
            "title": "Category:含有英語的條目"
          },
          {
            "ns": 14,
            "title": "Category:小丑題材電影"
          },
          {
            "ns": 14,
            "title": "Category:漫畫改編真人電影"
          },
          {
            "ns": 14,
            "title": "Category:精神病院背景电影"
          },
          {
            "ns": 14,
            "title": "Category:精神病題材電影"
          },
          {
            "ns": 14,
            "title": "Category:美国报复题材电影"
          },
          {
            "ns": 14,
            "title": "Category:美國心理驚悚片"
          },
          {
            "ns": 14,
            "title": "Category:美國悲劇電影"
          },
          {
            "ns": 14,
            "title": "Category:美國犯罪劇情片"
          },
          {
            "ns": 14,
            "title": "Category:美國犯罪驚悚片"
          },
          {
            "ns": 14,
            "title": "Category:與維基數據不同的IMDb ID（Cite Mojo）"
          },
          {
            "ns": 14,
            "title": "Category:與維基數據相同的爛番茄ID"
          },
          {
            "ns": 14,
            "title": "Category:與維基數據相同的豆瓣電影模板"
          },
          {
            "ns": 14,
            "title": "Category:谋杀犯题材电影"
          },
          {
            "ns": 14,
            "title": "Category:陶德·菲利普斯電影"
          }
        ]
      }
    }
  }
}
    """.trimIndent()

}
