package io.localplayer.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class MigrationApplication(context:android.content.Context):Application(){init{attachBaseContext(context)};override fun getApplicationContext():android.content.Context=this}
class MigrationPlaybackRaceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun fixture(missing:Boolean,action:(Application,MediaDataSession,LibraryViewModel,MediaRef,CountDownLatch,CountDownLatch)->Unit){
        val isolated=IsolatedMediaContext(instrumentation.targetContext);val app=MigrationApplication(isolated);val owner=ViewModelStore()
        val source=Source(id="source",label="旧片箱",protocol=Protocol.SFTP,host="fixture.invalid",root="/");SourceStore(app).save(source,"",false)
        val ref=MediaRef("source:old.mp4","old.mp4",source.label,source.id,"old.mp4",sizeBytes=if(missing)null else 100,location=if(missing)null else mediaLocation(source))
        val history=LegacyHistoryStore(app);history.update(ref,history.token(ref.key),42000,120000,false)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String):List<Entry>{entered.countDown();check(release.await(10,TimeUnit.SECONDS));cancellation.check();return listOf(Entry("old.mp4","old.mp4",false,100))}
            override fun open(path:String):RemoteFile=error("No media bytes in resolver test")
            override fun close(){}
        }}
        lateinit var vm:LibraryViewModel
        try{instrumentation.runOnMainSync{vm=LibraryViewModel(app,connector);owner.put("migration",vm)};action(app,MediaDataMigration.session(app),vm,ref,entered,release)}
        finally{release.countDown();instrumentation.runOnMainSync{owner.clear()};isolated.dispose()}
    }
    @Test fun blockedResolverHoldsMigrationFromTheBeginning(){fixture(false){app,session,vm,ref,entered,release->
        instrumentation.runOnMainSync{vm.resume(HistoryEntry(ref,42000,120000,1,false))};assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals("Deferred",session.migrate().state)
        release.countDown();val target=runBlocking{withTimeout(5000){vm.plays.first()}}
        try{assertEquals(42000L,target.resume)}finally{target.release()}
        assertEquals("Committed",session.migrate().state)
    }}
    @Test fun confirmationOpenedBeforeCutoverResolvesTheImportedIdentity(){fixture(true){app,session,vm,ref,entered,release->
        instrumentation.runOnMainSync{vm.resume(HistoryEntry(ref,42000,120000,1,false));assertNotNull(vm.legacyReplay)}
        assertEquals("Committed",session.migrate().state);val id=HistoryStore(app).entries().single().media.mediaId
        instrumentation.runOnMainSync{vm.confirmLegacyReplay()};assertTrue(entered.await(5,TimeUnit.SECONDS));release.countDown()
        val target=runBlocking{withTimeout(5000){vm.plays.first()}}
        try{assertEquals(42000L,target.resume);assertEquals(id,target.media.mediaId);assertEquals(id,HistoryStore(app).entries().single().media.mediaId)}finally{target.release()}
    }}
    @Test fun stalledPreparationOutsideTheWriterGateDoesNotBlockOrdinaryReads(){
        val context=IsolatedMediaContext(instrumentation.targetContext);val session=MediaDataMigration.session(context);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val thread=Thread{session.migrate{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        try{thread.start();assertTrue(entered.await(5,TimeUnit.SECONDS));val start=System.nanoTime();instrumentation.runOnMainSync{assertEquals(1f,PlaybackPreferences(context).speed)};assertTrue((System.nanoTime()-start)/1_000_000<1000);session.targetCreated("new-intent");release.countDown();thread.join(5000);assertFalse(thread.isAlive);assertFalse(session.isCommitted())}
        finally{release.countDown();thread.join(5000);context.dispose()}
    }
    private fun cancelledReplay(clear:Boolean){fixture(true){app,session,vm,ref,entered,release->
        assertEquals("Committed",session.migrate().state)
        instrumentation.runOnMainSync{vm.resume(HistoryStore(app).entries().single());vm.confirmLegacyReplay()}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        if(clear)HistoryStore(app).clear()else HistoryStore(app).remove(HistoryStore(app).entries().single().media)
        release.countDown()
        val target=runBlocking{withTimeoutOrNull(500){vm.plays.first()}}
        assertNull("cleared/deleted replay cannot adopt a fresh token",target)
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);var done=false
        while(!done&&System.nanoTime()<until){instrumentation.runOnMainSync{done=vm.preparingKey==null};Thread.sleep(10)}
        assertTrue(done);instrumentation.runOnMainSync{assertNotNull(vm.error)}
    }}
    @Test fun clearDuringLegacyConfirmationCannotBeBypassedByRelink(){cancelledReplay(true)}
    @Test fun deletionDuringLegacyConfirmationCannotBeBypassedByRelink(){cancelledReplay(false)}

}
