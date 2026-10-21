package io.localplayer.core

import org.junit.Assert.*
import org.junit.Test

class SourceRootTest {
    @Test fun selectedFolderIsTheFirstAndLastReachableRoot() {
        val root = SourceRoot("media/series")
        assertEquals(listOf("media/series", "media/series/season", "media/series/season/extra"), root.trail("media/series/season/extra"))
        assertEquals("media/series", root.parent("media/series/season"))
        assertNull(root.parent("media/series"))
        assertEquals(listOf("media/series"), root.trail(""))
        assertEquals("media/series", root.clamp("media"))
        assertFalse(root.contains("media/series-other"))
        assertFalse(root.contains("media/other"))
    }

    @Test fun outerRootIsAvailableOnlyWhenExplicitlyUsingAnEmptyBoundary() {
        val root = SourceRoot("")
        assertEquals(listOf("", "media", "media/series"), root.trail("media/series"))
        assertNull(root.parent(""))
    }
}
