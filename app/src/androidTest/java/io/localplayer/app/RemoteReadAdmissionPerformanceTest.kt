package io.localplayer.app

import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercise the source/catalog guards supplied by the real library entry, including tiny EBML reads. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteReadAdmissionPerformanceTest {
    @Test fun registeredContainerHeaderReadsStayFastAndRejectChangedAuthority() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val session=MediaDataMigration.session(app)
        session.migrate();check(session.isCommitted())
        val source=Source(label="Header performance fixture",protocol=Protocol.SFTP,host="fixture.invalid",root="/",initialPath="movies")
        val store=SourceStore(app);store.save(source,"",false)
        val size=65536L
        val ref=HistoryStore(app).reference(MediaRef(source.id+":movies/fixture.mkv","fixture.mkv",source.label,source.id,"movies/fixture.mkv",sizeBytes=size,location=mediaLocation(source),bindingEpoch=SourceBindingEpochs.current(app,source)))
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession {
                override fun list(path:String)=emptyList<Entry>()
                override fun open(path:String)=object:RemoteFile {
                    override val size=65536L
                    override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {
                        cancellation.check();val count=minOf(length.toLong(),size-position).toInt()
                        repeat(count){buffer[offset+it]=((position+it)%251).toByte()};return count
                    }
                    override fun close(){}
                }
                override fun close(){}
            }
        }
        fun register(guarded:Boolean)=PlaybackRegistry.add(source,Credentials(""),ref.path!!,ref.title,connector,if(guarded)app else null,ref.bindingEpoch,if(guarded)ref else null)
        val control=register(false);val guarded=register(true);var movedUri:android.net.Uri?=null
        fun readHeader(uri:android.net.Uri):Long {
            val data=RemoteDataSource()
            try {
                data.open(DataSpec.Builder().setUri(uri).build())
                val byte=ByteArray(1);val start=SystemClock.elapsedRealtime()
                repeat(20_000){assertEquals(1,data.read(byte,0,1));assertEquals((it%251).toByte(),byte[0])}
                return SystemClock.elapsedRealtime()-start
            }finally{data.close()}
        }
        try {
            val withoutGuards=readHeader(control);val withGuards=readHeader(guarded)
            println("HEADER_ADMISSION_PERF controlMs=$withoutGuards guardedMs=$withGuards reads=20000 committed=true")
            session.checkLocator(ref)
            val moved=ref.copy(key=source.id+":movies/moved.mkv",path="movies/moved.mkv",mediaId=null,locatorId=null,contentRevisionId=null)
            FilmLibraryStore(app,MEDIA_DATABASE).use{it.rebindFilm(ref.mediaId!!,moved,true)}
            assertThrows(IOException::class.java){session.checkLocator(ref)}
            val current=HistoryStore(app).reference(moved)
            movedUri=PlaybackRegistry.add(source,Credentials(""),current.path!!,current.title,connector,app,current.bindingEpoch,current)
            val data=RemoteDataSource()
            try {
                data.open(DataSpec.Builder().setUri(movedUri!!).build());assertEquals(1,data.read(ByteArray(1),0,1))
                store.updatePresentation(source.id,source.label,"series","series")
                assertThrows(IOException::class.java){data.read(ByteArray(1),0,1)}
            }finally{data.close()}
            assertTrue("Twenty thousand byte-sized header reads must not repeatedly hash source identity or query SQLite: $withGuards ms",withGuards<maxOf(500,withoutGuards*8))
        }finally{PlaybackRegistry.remove(control);PlaybackRegistry.remove(guarded);PlaybackRegistry.remove(movedUri);HistoryStore(app).remove(ref);store.delete(source.id)}
    }
}
