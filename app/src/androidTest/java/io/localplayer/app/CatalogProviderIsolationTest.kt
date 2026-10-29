package io.localplayer.app

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CatalogProviderIsolationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val base get()=instrumentation.targetContext
    private fun run(changeCatalog:Boolean){
        val context=IsolatedMediaContext(base)
        val tree=DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"root")
        base.startActivity(Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name).putExtra("tree",tree.toString()).putExtra("target",base.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(base.checkUriPermission(tree,Process.myPid(),Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&System.nanoTime()<until)Thread.sleep(25)
        instrumentation.waitForIdleSync()
        val child=DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY,"clip")
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"movies")
        val film=MediaRef(child.toString(),"旧片","本机文件",localUri=child.toString())
        val folder=MediaRef(parent.toString(),"目录","本机文件",localUri=parent.toString())
        fun call(method:String)=base.contentResolver.call(tree,"fixture:$method",null,null)
        val executor=Executors.newFixedThreadPool(2)
        FilmLibraryStore(context).use{store->try{
            assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
            store.registerLegacy(emptyList(),listOf(StationFinding(film,1)))
            SealedMedia.unlock();SealedMedia.seal(SealedItem(folder,true,tree.toString()));SealedMedia.lock()
            call("block-child")
            val pending=executor.submit<Result<List<LibraryWork>>>{runCatching{store.works()}}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(call("child-entered")?.getBoolean("entered")!=true&&System.nanoTime()<deadline)Thread.sleep(10)
            assertTrue("opaque provider query started",call("child-entered")?.getBoolean("entered")==true)
            executor.submit{
                instrumentation.runOnMainSync{
                    val p=MigratingPreferences.open(context,"playback-settings-v1");p.edit().putFloat("speed",1.25f).apply();assertEquals(1.25f,p.getFloat("speed",0f))
                    HistoryStore(context).entries();MediaDataMigration.session(context).targetCreated("provider-test");MediaDataMigration.session(context).targetReleased("provider-test")
                }
            }.get(2,TimeUnit.SECONDS)
            if(changeCatalog)HistoryStore(context).reference(MediaRef("fresh","新观察","片箱",sourceId="fixture",path="new.mp4"))
            else {SealedMedia.unlock();SealedMedia.lock()}
            call("release-child")
            val checked=pending.get(5,TimeUnit.SECONDS)
            assertTrue("discard stale provider result",checked.isFailure||checked.getOrThrow().none{work->work.films.any{it.media.localUri==child.toString()}})
            assertTrue(store.works().none{work->work.films.any{it.media.localUri==child.toString()}})
        }finally{
            call("release-child");executor.shutdownNow();SealedMedia.unlock();SealedMedia.unseal(folder.key);SealedMedia.lock()
        }}
        context.dispose()
    }
    @Test fun blockedProviderDoesNotHoldPreferencesGateAndPolicyChangeDiscardsResult(){run(false)}
    @Test fun catalogChangeDiscardsDetachedProviderResult(){run(true)}
}
