package io.localplayer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="history-test-"+UUID.randomUUID()
    private val first=HistoryStore(context,name)
    private val second=HistoryStore(context,name)
    private val media=MediaRef("fixture-id:path","片名.mp4","测试避难所",sourceId="fixture-id",path="影片/片名.mp4",sizeBytes=100,location="smb:loopback:media")
    @After fun cleanup(){context.deleteSharedPreferences(name)}
    @Test fun sourceIdentityIncludesPrincipalAndTrustedServerKey(){
        val source=io.localplayer.core.Source(label="fixture",protocol=io.localplayer.core.Protocol.SFTP,host="loopback",root="/",username="first")
        assertEquals(mediaLocation(source),mediaLocation(source.copy(label="renamed")))
        assertNotEquals(mediaLocation(source),mediaLocation(source.copy(username="second")))
        assertNotEquals(mediaLocation(source),mediaLocation(source.copy(domain="other")))
        assertNotEquals(mediaLocation(source),mediaLocation(source.copy(trustedKey="SHA256:changed")))
        assertTrue(mediaLocation(source).matches(Regex("v1:[a-f0-9]{64}")))
    }

    @Test fun recordSurvivesRecreationAndChangedMediaStartsAtZero(){
        assertEquals(9000L,first.resumePosition(media,9000))
        assertTrue(first.update(media,first.token(media.key),45000,120000,false))
        assertEquals(45000L,second.resumePosition(media))
        assertEquals(media,second.entries().single().media)
        assertEquals(0L,second.resumePosition(media.copy(sizeBytes=101)))
        assertEquals(0L,second.resumePosition(media.copy(location="smb:other:media")))
        assertTrue(first.update(media,first.token(media.key),120000,120000,true))
        assertEquals(0L,second.resumePosition(media))
        assertTrue(second.entries().single().completed)
    }
    @Test fun removalAndClearRejectStalePlayerWritesAcrossInstances(){
        val old=first.token(media.key)
        first.update(media,old,1000,120000,false)
        second.remove(media.key)
        assertFalse(first.update(media,old,2000,120000,false))
        assertEquals(0L,first.resumePosition(media,9000))
        val newer=first.token(media.key)
        assertTrue(first.update(media,newer,2000,120000,false))
        second.clear()
        assertFalse(first.update(media,newer,3000,120000,false))
        assertTrue(first.entries().isEmpty())
        assertEquals(0L,first.resumePosition(media,9000))
    }
    @Test fun concurrentStoresDoNotLoseRecords(){
        val pool=Executors.newFixedThreadPool(2)
        try{
            val tasks=(0 until 100).map{i->pool.submit{val repo=if(i%2==0)first else second;val ref=media.copy(key="fixture-$i");repo.update(ref,repo.token(ref.key),i.toLong(),120000,false)}}
            tasks.forEach{it.get(10,TimeUnit.SECONDS)}
            assertEquals(100,first.entries().map{it.media.key}.toSet().size)
            val ref=media.copy(key="newest");first.update(ref,first.token(ref.key),1,120000,false)
            assertEquals(100,second.entries().size)
            assertEquals("newest",second.entries().first().media.key)
        }finally{pool.shutdownNow()}
    }
    @Test fun corruptedHistoryIsPreservedUntilExplicitClear(){
        context.getSharedPreferences(name,0).edit().putString("entries","damaged fixture").commit()
        assertThrows(java.io.IOException::class.java){first.entries()}
        assertThrows(java.io.IOException::class.java){first.update(media,first.token(media.key),1,120000,false)}
        assertEquals("damaged fixture",context.getSharedPreferences(name,0).getString("entries",null))
        first.clear();assertTrue(first.entries().isEmpty())
    }
}
