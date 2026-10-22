package io.localplayer.core

import kotlin.test.*

class SegmentCacheTest {
    @Test fun boundsStorageExpiresUnusedSegmentsAndKeepsAccountsSeparate() {
        val root=kotlin.io.path.createTempDirectory("bounded-cache-").toFile();var now=10_000L
        try {
            val cache=SegmentCache(root,200,1000){now}
            cache.put("account-a-file-revision1",0,ByteArray(80){1})
            assertNull(cache.get("account-b-file-revision1",0,80))
            assertNull(cache.get("account-a-file-revision2",0,80))
            now+=10;cache.put("account-a-file-revision1",80,ByteArray(80){2})
            assertTrue(cache.bytes()<=200)
            assertNull(cache.get("account-a-file-revision1",0,80))
            assertNotNull(cache.get("account-a-file-revision1",80,80))
            now+=1001;assertEquals(0,cache.bytes());cache.clear();assertEquals(0,cache.bytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun unavailableDiskCacheDoesNotFailPlaybackStorage() {
        val file=kotlin.io.path.createTempFile("cache-unavailable-").toFile()
        try {val cache=SegmentCache(file);cache.put("identity",0,ByteArray(10));assertNull(cache.get("identity",0,10))}
        finally{file.delete()}
    }
}
