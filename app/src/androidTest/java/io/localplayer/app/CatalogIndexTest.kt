package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class CatalogIndexTest {
    @Test fun selectedRootWalkIsIdempotentAndNeverDeletesOnFailure():Unit=runBlocking{
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source(label="旧片箱",protocol=Protocol.SFTP,host="fixture.invalid",initialPath="films")
        var failed=false
        val connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String):List<Entry>{
                    if(failed)throw java.io.IOException("fixture offline")
                    return if(path=="films")listOf(Entry("one.mp4","films/one.mp4",false,100),Entry("sub","films/sub",true,0),Entry("outside.mp4","outside/file.mp4",false,1))
                    else listOf(Entry("two.mp4","films/sub/two.mp4",false,200),Entry("readme.txt","films/sub/readme.txt",false,50))
                }
                override fun open(path:String):RemoteFile=error("Indexing must not open media")
                override fun close(){}
            }
        }
        try{
            SourceStore(context).save(source,"",false);MediaDataMigration.session(context).migrate()
            val root=CatalogRoot(source.id,source.label,source=source)
            assertEquals(2,CatalogIndex.scan(context,root,Credentials(""),connector=connector).films)
            val before=FilmLibraryStore(context).use{it.works().flatMap{w->w.films}.map{f->f.id}.toSet()}
            CatalogIndex.scan(context,root,Credentials(""),connector=connector)
            assertEquals(before,FilmLibraryStore(context).use{it.works().flatMap{w->w.films}.map{f->f.id}.toSet()})
            failed=true;assertTrue(runCatching{CatalogIndex.scan(context,root,Credentials(""),connector=connector)}.isFailure)
            assertEquals(before,FilmLibraryStore(context).use{it.works().flatMap{w->w.films}.map{f->f.id}.toSet()})
        }finally{context.dispose()}
    }
    @Test fun reconfigurationDuringListingCannotPublishOldFiles():Unit=runBlocking {
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source(label="旧片箱",protocol=Protocol.SMB,host="fixture.invalid",root="share",initialPath="films")
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String):List<Entry>{SourceStore(context).updatePresentation(source.id,"改过根目录","other","other");return listOf(Entry("one.mp4","films/one.mp4",false,100))}
            override fun open(path:String):RemoteFile=error("Not needed")
            override fun close(){}
        }}
        try{SourceStore(context).save(source,"",false);MediaDataMigration.session(context).migrate()
            assertTrue(runCatching{CatalogIndex.scan(context,CatalogRoot(source.id,source.label,source),Credentials(""),connector=connector)}.isFailure)
            assertTrue(FilmLibraryStore(context).use{it.works().isEmpty()})
        }finally{context.dispose()}
    }
    @Test fun oversizedSingleDirectoryDoesNotWriteBeyondTheLimit():Unit=runBlocking {
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source(label="大目录",protocol=Protocol.SMB,host="fixture.invalid",root="share")
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String)=(0..50000).map{Entry("$it.mp4","$it.mp4",false,100)}
            override fun open(path:String):RemoteFile=error("Not needed")
            override fun close(){}
        }}
        try{SourceStore(context).save(source,"",false);MediaDataMigration.session(context).migrate()
            assertTrue(runCatching{CatalogIndex.scan(context,CatalogRoot(source.id,source.label,source),Credentials(""),connector=connector)}.isFailure)
            assertTrue(FilmLibraryStore(context).use{it.works().isEmpty()})
        }finally{context.dispose()}
    }
    @Test fun mainThreadCancelDoesNotWaitForTransportClose():Unit=runBlocking {
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source(label="慢连接",protocol=Protocol.SFTP,host="fixture.invalid")
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation):RemoteSession{
            cancellation.own(java.io.Closeable{release.await(5,java.util.concurrent.TimeUnit.SECONDS)})
            return object:RemoteSession{
                override fun list(path:String):List<Entry>{entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return emptyList()}
                override fun open(path:String):RemoteFile=error("Not needed")
                override fun close(){}
            }
        }}
        try{SourceStore(context).save(source,"",false);MediaDataMigration.session(context).migrate()
            val job=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch{CatalogIndex.scan(context,CatalogRoot(source.id,source.label,source),Credentials(""),connector=connector)}
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            var elapsed=0L;InstrumentationRegistry.getInstrumentation().runOnMainSync{val start=System.nanoTime();job.cancel();elapsed=(System.nanoTime()-start)/1_000_000}
            assertTrue("cancel blocked for $elapsed ms",elapsed<250);release.countDown();job.join()
        }finally{release.countDown();context.dispose()}
    }

}
