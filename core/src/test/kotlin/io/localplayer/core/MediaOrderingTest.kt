package io.localplayer.core

import kotlin.test.*

class MediaOrderingTest {
    @Test fun numbersAndBirthTimesHaveOneStableOrder() {
        val rows = listOf(Entry("第10集", "10", false, 0), Entry("第2集", "2", false, 0),
            Entry("第1集", "later", false, 0, 200), Entry("第1集", "unknown", false, 0),
            Entry("第1集", "earlier", false, 0, 100), Entry("Z", "folder", true, 0))
        assertEquals(listOf("folder", "earlier", "later", "unknown", "2", "10"), rows.sortedWith(MediaOrdering.entries).map { it.path })
        assertTrue(MediaOrdering.compareNames("part999999999999999999999", "part1000000000000000000000") < 0)
        assertEquals(0, MediaOrdering.compareNames("Episode 02", "episode 2"))
    }
    @Test fun smbInputsDoNotHideCredentialsOrTraversal() {
        assertEquals(SmbAddress("nas", 1445, "Movies", "Series/第2集"), SmbAddress.parse("smb://nas:1445/Movies/Series/第2集"))
        assertEquals(SmbAddress("nas", 445, "Movies", "Sub"), SmbAddress.parse("\\\\nas\\Movies\\Sub"))
        assertEquals(SmbAddress("192.0.2.2", 445, "", ""), SmbAddress.parse("192.0.2.2"))
        assertEquals("My Video",SmbAddress.parse("smb://nas/Movies/My Video").path)
        assertFailsWith<IllegalArgumentException> { SmbAddress.parse("smb://nas/Movies/invalid%") }
        assertFails { SmbAddress.parse("https://nas/Movies") }
        assertFails { SmbAddress.parse("smb://user@nas/Movies") }
        assertFails { SmbAddress.parse("smb://nas/Movies/%2e%2e/secret") }
        assertFails { SmbAddress.parse("smb://nas:70000/Movies") }
    }
    @Test fun malformedWebDavRefererIsAValidationError() {
        val source=Source(label="DAV",protocol=Protocol.WEBDAV,host="example.test",root="/dav")
        assertFailsWith<IllegalArgumentException>{source.copy(referer="https://example.test/a b").validate()}
        assertFailsWith<IllegalArgumentException>{source.copy(referer="https://example.test/%").validate()}
    }
    @Test fun webDavAddressDoesNotReuseAnotherProtocolStartFolder() {
        val source=Source(label="Migrating source",protocol=Protocol.SMB,host="example.test",root="media",initialPath="legacy")
        val dav=source.withWebDavAddress("https://example.test/dav/series")
        assertEquals("/dav/series",dav.root);assertEquals("",dav.initialPath)
    }
}
