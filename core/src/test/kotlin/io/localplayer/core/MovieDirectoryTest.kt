package io.localplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieDirectoryTest {
    private fun film(name:String)=Entry(name,"movies/$name",false,1234)
    @Test fun oneMovieWithSubtitlesAndArtworkPlays(){
        val movie=film("旧城.2024.2160p.mkv")
        assertEquals(movie,singleMovieEntry("旧城 (2024)",listOf(movie,film("旧城.srt"),film("poster.jpg"))))
    }
    @Test fun mimeIdentifiedMovieNeedsNoFilenameExtension(){
        val movie=film("旧城")
        assertEquals(movie,singleMovieEntry("旧城",listOf(movie)){it.path==movie.path})
        assertNull(singleMovieEntry("旧城",listOf(movie)))
    }
    @Test fun episodesAndSeasonsStayBrowsable(){
        assertNull(singleMovieEntry("旧城",listOf(film("旧城.S01E01.mkv"))))
        assertNull(singleMovieEntry("旧城 Season 1",listOf(film("旧城.mkv"))))
        assertNull(singleMovieEntry("旧城 S02",listOf(film("旧城.mkv"))))
        assertNull(singleMovieEntry("旧城 第一季",listOf(film("旧城.mkv"))))
        assertNull(singleMovieEntry("旧城",listOf(film("01.mp4"))))
    }
    @Test fun alternateCutsAndGenericContainersStayBrowsable(){
        assertNull(singleMovieEntry("旧城",listOf(film("旧城.mkv"),film("旧城.directors-cut.mp4"))))
        assertNull(singleMovieEntry("电影",listOf(film("旧城.mkv"))))
        assertNull(singleMovieEntry("旧城",listOf(film("旧城.trailer.mp4"))))
        assertNull(singleMovieEntry("旧城",listOf(film("旧城.srt"))))
    }
}
