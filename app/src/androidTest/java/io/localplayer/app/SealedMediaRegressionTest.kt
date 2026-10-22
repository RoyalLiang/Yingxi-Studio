package io.localplayer.app

import android.app.Application
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SealedMediaRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun sealingLocalVideoPreservesKnownBytesButDirectoryHasNoSize() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val tree=DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"root")
        context.startActivity(Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",tree.toString()).putExtra("target",context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.waitUntil(10000){context.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val models=ViewModelStore()
        lateinit var files:LocalFilesViewModel
        lateinit var vm:LibraryViewModel
        val sealed=mutableListOf<String>()
        try {
            ui.runOnIdle {
                files=LocalFilesViewModel(ui.activity.application);models.put("files",files)
                vm=LibraryViewModel(ui.activity.application);models.put("library",vm)
                files.add(tree)
            }
            ui.waitUntil(10000){!files.busy&&files.active!=null}
            ui.runOnIdle{files.enter(files.entries.first{it.directory})}
            ui.waitUntil(10000){!files.busy&&files.trail.size==2}
            val video=files.entries.first{it.video&&!it.directory}
            val directory=files.entries.first{it.directory}
            assertTrue(video.size>0)
            for(entry in listOf(video,directory)) {
                ui.runOnIdle {vm.sealLocal(entry,files);vm.completePrivateAccess(vm.privateRequest!!.id)}
                ui.waitUntil(10000){!vm.busy}
                ui.runOnIdle {
                    assertNull(vm.error)
                    val item=SealedMedia.items().first{it.id==entry.uri.toString()}
                    sealed+=item.id
                    assertEquals(if(entry.directory)null else entry.size,item.media.sizeBytes)
                    SealedMedia.lock();SealedMedia.reload();SealedMedia.unlock()
                    assertEquals(item.media.sizeBytes,SealedMedia.items().first{it.id==item.id}.media.sizeBytes)
                }
            }
        } finally {
            ui.runOnIdle {
                SealedMedia.unlock();sealed.forEach{SealedMedia.unseal(it)};SealedMedia.lock()
                files.folders.firstOrNull{it.tree==tree}?.let{files.remove(it)}
                models.clear()
            }
        }
    }

    @Test fun directoryIsPersistentlyHiddenAcrossPublicBrowseHistoryColdLoadAndRelock() {
        val app=ui.activity.application as Application
        val source=Source(label="封存回归",protocol=Protocol.SFTP,host="fixture.invalid",root="/",initialPath="series")
        val secret=Entry("隐秘剧集","series/private",true,0)
        val episode=MediaRef("${source.id}:series/private/01.mkv","第一集",source.label,source.id,"series/private/01.mkv",location=mediaLocation(source))
        val public=Entry("公开视频.mp4","series/public.mp4",false,4)
        val history=HistoryStore(app);val models=ViewModelStore();val store=SourceStore(app)
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String)=if(path=="series")listOf(secret,public)else listOf(Entry("第一集.mkv","series/private/01.mkv",false,4))
                override fun open(path:String):RemoteFile=error("No video IO in policy test")
                override fun close(){}
            }
        }
        lateinit var vm:LibraryViewModel
        val id="${source.id}:${secret.path}"
        try {
            store.save(source,"",false)
            history.update(episode,history.token(episode.key),1000,60000,false)
            ui.runOnIdle {vm=LibraryViewModel(app,connector);models.put("privacy",vm);ui.activity.setContent{PlayerTheme{LibraryScreen(vm,{},{})}};vm.open(source)}
            ui.waitUntil(5000){vm.source?.id==source.id&&!vm.busy}
            ui.onNodeWithTag("shelter-entry:${secret.path}").assertExists()
            ui.runOnIdle{vm.sealRemote(source,secret);assertFalse(SealedMedia.protects(episode));vm.cancelPrivateAccess(vm.privateRequest!!.id)}
            ui.runOnIdle{assertFalse("Cancelled authentication must not seal anything",SealedMedia.protects(episode));vm.sealRemote(source,secret);vm.completePrivateAccess(vm.privateRequest!!.id)}
            ui.onNodeWithTag("shelter-entry:${secret.path}").assertDoesNotExist()
            ui.onNodeWithTag("shelter-entry:${public.path}").assertExists()
            ui.runOnIdle{
                assertTrue(SealedMedia.protects(episode));assertFalse(vm.history.any{it.media.key==episode.key})
                assertFalse("Sibling prefix is not a descendant",SealedMedia.protects(source,"series/private-other/01.mkv"))
                SealedMedia.lock();SealedMedia.reload();assertTrue(SealedMedia.protects(episode));assertTrue(SealedMedia.items().isEmpty())
                vm.open(source,secret.path);assertNotNull(vm.error)
                vm.requestPrivate();vm.completePrivateAccess(vm.privateRequest!!.id)
                vm.openSealed(SealedMedia.items().first{it.id==id})
            }
            ui.waitUntil(5000){vm.path==secret.path&&!vm.busy}
            ui.onNodeWithTag("shelter-entry:series/private/01.mkv").assertExists()
            ui.runOnIdle{assertFalse("Public history stays hidden while cabinet is open",vm.history.any{it.media.key==episode.key});SealedMedia.lock();vm.closeSealed();assertNull(vm.source)}
            ui.runOnIdle{SealedMedia.unlock();SealedMedia.unseal(id);vm.open(source)}
            ui.waitUntil(5000){vm.source?.id==source.id&&!vm.busy}
            ui.onNodeWithTag("shelter-entry:${secret.path}").assertExists()
        }finally{
            ui.runOnIdle{models.clear();SealedMedia.unlock();SealedMedia.items().filter{it.id==id}.forEach{SealedMedia.unseal(it.id)};SealedMedia.lock()}
            history.remove(episode.key);store.delete(source.id)
        }
    }

    @Test fun localOpaqueAncestryAndSingleEpisodeSurviveSerialization() {
        val folder="content://fixture.documents/tree/grant/document/opaque-directory"
        val item=SealedItem(MediaRef(folder,"目录","本机文件",localUri=folder),true)
        val episode=MediaRef("opaque-child","剧集","本机文件",localUri="content://fixture.documents/tree/grant/document/opaque-child",localAncestors=listOf(folder))
        ui.runOnIdle{
            try{
                SealedMedia.unlock();SealedMedia.seal(item);SealedMedia.lock();SealedMedia.reload()
                assertTrue(SealedMedia.protects(MediaRef.parse(episode.json())))
                assertTrue(sameLocalDocument(folder,"content://fixture.documents/document/opaque-directory"))
                assertFalse(SealedMedia.visible(episode));assertFalse(SealedMedia.visible(episode,true))
                SealedMedia.unlock();assertTrue(SealedMedia.visible(episode,true));assertFalse(SealedMedia.visible(episode))
            }finally{SealedMedia.unlock();SealedMedia.unseal(item.id);SealedMedia.lock()}
        }
    }

    @Test fun protectedTitlesNeverReachOnlinePosterLookup()=runBlocking {
        val context=ui.activity.applicationContext
        val key="sealed-poster-${System.nanoTime()}"
        val ref=MediaRef(key,"PrivatePoster$key","本机文件",localUri="content://fixture.documents/document/$key")
        val publicRef=ref.copy(key=key+"-public",localUri=ref.localUri+"-public")
        val oldLookup=MediaPosters.onlineLookup
        val preferences=EnrichmentPreferences(context);val posters=preferences.posters
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val identity=posterIdentity(ref,null)
        try{
            preferences.posters=true
            MediaPosters.onlineLookup={query,_->if(query==identity)calls.incrementAndGet();null}
            ui.runOnIdle{SealedMedia.unlock();SealedMedia.seal(SealedItem(ref,false))}
            assertNull(MediaPosters.load(context,ref));assertEquals(0,calls.get())
            assertNull(MediaPosters.load(context,publicRef));assertEquals("Unprotected control reaches the lookup seam",1,calls.get())
        }finally{
            MediaPosters.onlineLookup=oldLookup;preferences.posters=posters
            MediaPosters.remove(context,publicRef)
            ui.runOnIdle{SealedMedia.unlock();SealedMedia.unseal(ref.key);SealedMedia.lock()}
        }
    }

    @Test fun singleEpisodeDoesNotSealItsSiblings() {
        val id="sealed-episode-${System.nanoTime()}"
        val ref=MediaRef("$id:season/01.mkv","第一集","测试来源",id,"season/01.mkv",location="fixture")
        ui.runOnIdle{
            try{
                SealedMedia.unlock();SealedMedia.seal(SealedItem(ref,false,ignoreCase=true));SealedMedia.lock();SealedMedia.reload()
                assertTrue(SealedMedia.protects(ref))
                assertTrue(SealedMedia.protects(ref.copy(path="SEASON/01.MKV")))
                assertFalse(SealedMedia.protects(ref.copy(key="$id:season/02.mkv",path="season/02.mkv")))
                assertFalse(SealedMedia.protects(ref.copy(key="$id:season/01.mkv-copy",path="season/01.mkv-copy")))
            }finally{SealedMedia.unlock();SealedMedia.unseal(ref.key);SealedMedia.lock()}
        }
    }

    @Test fun grantedOpaqueProviderChecksDirectoryContainmentAndAlternateUris()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val tree=DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"root")
        val had=context.contentResolver.persistedUriPermissions.any{it.uri==tree&&it.isReadPermission}
        context.startActivity(Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",tree.toString()).putExtra("target",context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.waitUntil(10000){context.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED}
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val folder=DocumentsContract.buildDocumentUriUsingTree(tree,"movies")
        val ref=MediaRef(folder.toString(),"影片","本机文件",localUri=folder.toString())
        try{
            SealedMedia.checkDirectorySupport(folder,android.os.CancellationSignal())
            ui.runOnIdle{SealedMedia.unlock();SealedMedia.seal(SealedItem(ref,true,tree.toString()))}
            assertTrue(SealedMedia.protectsLocalUri(DocumentsContract.buildDocumentUriUsingTree(tree,"clip")))
            assertTrue(SealedMedia.protectsLocalUri(DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY,"clip")))
            assertFalse(SealedMedia.protectsLocalUri(DocumentsContract.buildDocumentUriUsingTree(tree,"root")))
        }finally{
            ui.runOnIdle{SealedMedia.unlock();SealedMedia.unseal(ref.key);SealedMedia.lock()}
            if(!had)context.contentResolver.releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
