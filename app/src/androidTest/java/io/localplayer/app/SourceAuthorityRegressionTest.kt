package io.localplayer.app
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceAuthorityRegressionTest {
    private fun fixture(action:(IsolatedMediaContext,Source,Connector,AtomicInteger)->Unit){
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val source=Source(id="authority-"+UUID.randomUUID(),label="旧片箱",protocol=Protocol.SFTP,host="fixture.invalid",root="/",initialPath="movies")
        SourceStore(context).save(source,"",false);val opens=AtomicInteger()
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            val closed=AtomicBoolean()
            override fun list(path:String)=emptyList<Entry>()
            override fun open(path:String):RemoteFile {opens.incrementAndGet();return object:RemoteFile{
                override val size=4096L
                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {if(closed.get())throw java.io.IOException("closed");val n=minOf(length.toLong(),size-position).toInt();if(n<=0)return -1;java.util.Arrays.fill(buffer,offset,offset+n,1.toByte());return n}
                override fun close(){}
            }}
            override fun close(){closed.set(true)}
        }}
        try{action(context,source,connector,opens)}finally{PlaybackRegistry.invalidateSource(source.id);context.dispose()}
    }
    private fun ref(source:Source)=MediaRef("${source.id}:movies/one.mp4","one.mp4",source.label,source.id,"movies/one.mp4",sizeBytes=4096,location=mediaLocation(source),bindingEpoch=0)
    @Test fun unopenedAndPreviewRequestsStayRevokedAfterRootReturns(){fixture{context,source,connector,opens->
        val uri=PlaybackRegistry.add(source,Credentials(""),"movies/one.mp4","one.mp4",connector,context,0);val preview=PlaybackRegistry.preview(uri)
        SourceStore(context).updatePresentation(source.id,"旧片箱","series","剧集");SourceStore(context).updatePresentation(source.id,"旧片箱","movies","影片")
        assertTrue(runCatching{PlaybackRegistry.get(uri).open()}.isFailure);assertTrue(runCatching{preview.open()}.isFailure);assertEquals(0,opens.get());preview.close()
    }}
    @Test fun alreadyOpenedReadsStopWhenAuthorityChanges(){fixture{context,source,connector,opens->
        val uri=PlaybackRegistry.add(source,Credentials(""),"movies/one.mp4","one.mp4",connector,context,0);val data=RemoteDataSource()
        try{data.open(DataSpec(uri));assertTrue(data.read(ByteArray(64),0,64)>0)
            SourceStore(context).updatePresentation(source.id,"旧片箱","series","剧集")
            assertTrue(runCatching{data.read(ByteArray(64),0,64)}.isFailure);assertTrue(runCatching{PlaybackRegistry.get(uri)}.isFailure)
        }finally{data.close()}
    }}
    @Test fun oldCallbacksAreRejectedOnBothSidesOfTheStorageCutover(){fixture{context,source,connector,opens->
        val history=HistoryStore(context);val media=ref(source);val old=history.token(media);assertTrue(history.update(media,old,100,1000,false))
        SourceStore(context).updatePresentation(source.id,"旧片箱","series","剧集");assertFalse(history.update(media,old,200,1000,false));assertEquals(100L,history.entries().single().positionMs)
        SourceStore(context).updatePresentation(source.id,"旧片箱","movies","影片");assertFalse(history.update(media,old,200,1000,false))
        assertEquals("Committed",MediaDataMigration.session(context).migrate().state);val imported=history.entries().single().media;assertFalse(history.update(imported,history.token(imported),300,1000,false));assertEquals(100L,history.entries().single().positionMs)
    }}
    @Test fun deleteAndRecreateCannotReviveAnyOldRequestOrCheckpoint(){fixture{context,source,connector,opens->
        val h=HistoryStore(context);val media=ref(source);h.update(media,h.token(media),100,1000,false);assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
        val entry=h.entries().single().media;val token=h.token(entry);val uri=PlaybackRegistry.add(source,Credentials(""),"movies/one.mp4","one.mp4",connector,context,0)
        SourceStore(context).delete(source.id);SourceStore(context).save(source,"",false)
        assertTrue(runCatching{PlaybackRegistry.get(uri)}.isFailure);assertFalse(h.update(entry,token,300,1000,false));assertEquals(100L,h.entries().single().positionMs)
    }}
    @Test fun connectionReleaseAndConfigurationChangesDoNotDeadlock(){fixture{context,source,connector,opens->
        val current=java.util.concurrent.atomic.AtomicReference<android.net.Uri?>()
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2);val start=java.util.concurrent.CountDownLatch(1)
        try{
            val writer=executor.submit{start.await();repeat(40){i->val path=if(i%2==0)"movies"else"series";SourceStore(context).updatePresentation(source.id,"片箱",path,path);val configured=SourceStore(context).load().single();current.set(PlaybackRegistry.add(configured,Credentials(""),"$path/one.mp4","one.mp4",connector,context,SourceBindingEpochs.current(context,configured)))}}
            val releaser=executor.submit{start.await();repeat(4000){PlaybackRegistry.releaseConnection(current.get())}}
            start.countDown();writer.get(10,java.util.concurrent.TimeUnit.SECONDS);releaser.get(10,java.util.concurrent.TimeUnit.SECONDS)
        }finally{executor.shutdownNow()}
    }}

    @Test fun absoluteHlsChildrenAreOwnedByTheOriginalSourceAndCloseOnRevocation(){fixture{context,source,connector,opens->
        val uri=PlaybackRegistry.add(source,Credentials(""),"movies/one.mp4","one.mp4",connector,context,0)
        val closed=java.util.concurrent.CountDownLatch(1);val made=AtomicInteger()
        val http=androidx.media3.datasource.DataSource.Factory{made.incrementAndGet();object:androidx.media3.datasource.DataSource{
            override fun addTransferListener(listener:androidx.media3.datasource.TransferListener){}
            override fun open(spec:DataSpec)=1024L
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int{buffer[offset]=1;return 1}
            override fun getUri()=android.net.Uri.parse("https://fixture.invalid/segment.ts")
            override fun close(){closed.countDown()}
        }}
        val factory=PlaybackNetworkFactory(http,uri);val data=factory.createDataSource()
        try{
            data.open(DataSpec(android.net.Uri.parse("https://fixture.invalid/segment.ts")));assertEquals(1,data.read(ByteArray(1),0,1))
            SourceStore(context).updatePresentation(source.id,"片箱","series","剧集");assertTrue(closed.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching{data.read(ByteArray(1),0,1)}.isFailure)
            SourceStore(context).updatePresentation(source.id,"片箱","movies","影片")
            val child=factory.createDataSource();try{assertTrue(runCatching{child.open(DataSpec(android.net.Uri.parse("https://fixture.invalid/next.ts")))}.isFailure)}finally{child.close()}
            assertEquals(1,made.get())
        }finally{data.close()}
    }}
    @Test fun aBlockedHlsReadIsCancelledBySourceRevocation(){fixture{context,source,connector,opens->
        val uri=PlaybackRegistry.add(source,Credentials(""),"movies/one.mp4","one.mp4",connector,context,0)
        val entered=java.util.concurrent.CountDownLatch(1);val released=java.util.concurrent.CountDownLatch(1)
        val http=androidx.media3.datasource.DataSource.Factory{object:androidx.media3.datasource.DataSource{
            override fun addTransferListener(listener:androidx.media3.datasource.TransferListener){}
            override fun open(spec:DataSpec)=1024L
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int{entered.countDown();check(released.await(5,java.util.concurrent.TimeUnit.SECONDS));return 1}
            override fun getUri()=android.net.Uri.parse("https://fixture.invalid/segment.ts")
            override fun close(){released.countDown()}
        }}
        val data=PlaybackNetworkFactory(http,uri).createDataSource();val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            data.open(DataSpec(android.net.Uri.parse("https://fixture.invalid/segment.ts")))
            val result=executor.submit<Boolean>{runCatching{data.read(ByteArray(1),0,1)}.isFailure};assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            SourceStore(context).delete(source.id);assertTrue(result.get(5,java.util.concurrent.TimeUnit.SECONDS))
        }finally{released.countDown();data.close();executor.shutdownNow()}
    }}

    @Test fun cachedOrientationCannotReviveARevokedSource(){fixture{context,source,connector,opens->
        val media=ref(source);val uri=PlaybackRegistry.add(source,Credentials(""),media.path!!,media.title,connector,context,0)
        val target=PlayTarget(uri,media.title,0,media)
        try{
            PlaybackOrientationProbe.remember(context,media,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,kotlinx.coroutines.runBlocking{PlaybackOrientationProbe.resolve(context,target)})
            SourceStore(context).updatePresentation(source.id,"片箱","series","剧集")
            assertNull(kotlinx.coroutines.runBlocking{PlaybackOrientationProbe.resolve(context,target)});assertEquals(0,opens.get())
        }finally{target.release()}
    }}
    @Test fun revokedOrientationReadCannotReturnOrCacheOldMetadata(){fixture{context,source,_,_->
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("upgrade-resume.mp4").use{it.readBytes()}
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val closed=AtomicBoolean()
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String)=emptyList<Entry>()
            override fun open(path:String)=object:RemoteFile{
                override val size=bytes.size.toLong()
                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{
                    entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));if(closed.get())throw java.io.IOException("closed")
                    if(position>=size)return -1;val count=minOf(length,bytes.size-position.toInt());bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+count);return count
                }
                override fun close(){closed.set(true)}
            }
            override fun close(){closed.set(true)}
        }}
        val media=ref(source).copy(sizeBytes=bytes.size.toLong());val uri=PlaybackRegistry.add(source,Credentials(""),media.path!!,media.title,connector,context,0)
        val target=PlayTarget(uri,media.title,0,media)
        try{kotlinx.coroutines.runBlocking{
            val pending=async(kotlinx.coroutines.Dispatchers.Default){PlaybackOrientationProbe.resolve(context,target)}
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            SourceStore(context).updatePresentation(source.id,"片箱","series","剧集");release.countDown()
            assertNull(pending.await());assertTrue(closed.get());assertNull(PlaybackOrientationProbe.resolve(context,target))
        }}finally{release.countDown();target.release()}
    }}

    @Test fun softwareInputRejectsAlreadyBufferedBytesAfterRevocation(){fixture{context,source,connector,opens->
        val media=ref(source)
        val uri=PlaybackRegistry.add(source,Credentials(""),media.path!!,media.title,connector,context,0)
        try{MpvInput.open(context,uri).use{input->
            assertEquals(1,android.system.Os.pread(input.descriptor.fileDescriptor,ByteArray(1),0,1,0))
            SourceStore(context).delete(source.id)
            assertTrue(runCatching{android.system.Os.pread(input.descriptor.fileDescriptor,ByteArray(1),0,1,0)}.isFailure)
        }}finally{PlaybackRegistry.remove(uri)}
    }}

    @Test fun revokedLocalTreeCannotReviveAfterRegrant(){fixture{context,_,_,_->
        val uri=android.net.Uri.parse("content://fixture/tree/reel/document/reel%2Fone.mp4")
        val original=MediaRef(uri.toString(),"one.mp4","本机文件",localUri=uri.toString(),bindingEpoch=LocalBindingEpochs.current(context,uri))
        assertTrue(SourceBindingEpochs.acceptsWrite(context,original,true))
        LocalBindingEpochs.revoke(context,android.net.Uri.parse("content://fixture/tree/reel"))
        assertFalse(SourceBindingEpochs.acceptsWrite(context,original,true))
        assertTrue(SourceBindingEpochs.acceptsWrite(context,original.copy(bindingEpoch=LocalBindingEpochs.current(context,uri)),true))
        assertTrue(runCatching{MediaDataMigration.session(context).checkLocator(original)}.isFailure)
    }}

}
