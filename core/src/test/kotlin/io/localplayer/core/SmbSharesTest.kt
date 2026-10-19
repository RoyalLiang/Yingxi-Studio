package io.localplayer.core

import kotlin.test.*

class SmbSharesTest {
    @Test fun opaqueHandlesArePassedUnchangedAcrossThreePages() {
        val seen = mutableListOf<Long?>()
        Cancellation().use { token ->
            val names = SmbShares.collectPages(token) { cursor ->
                seen += cursor
                when (cursor) {
                    0L -> SmbShares.SharePage(listOf("Video10\u0000", "IPC$"),234,7)
                    7L -> SmbShares.SharePage(listOf("Video2", "Video10"),234,20)
                    20L -> SmbShares.SharePage(listOf("Video1"),0,null)
                    else -> error("Handle was modified")
                }
            }
            assertEquals(listOf<Long?>(0L,7L,20L),seen)
            assertEquals(listOf("Video1","Video2","Video10"),names)
        }
    }
    @Test fun repeatedHandlesAndServerErrorsFailClearly() {
        Cancellation().use { token ->
            assertFailsWith<java.io.IOException> { SmbShares.collectPages(token) { SmbShares.SharePage(emptyList(),234,2) } }
            assertFailsWith<java.io.IOException> { SmbShares.collectPages(token) { SmbShares.SharePage(emptyList(),5,null) } }
        }
    }
}
