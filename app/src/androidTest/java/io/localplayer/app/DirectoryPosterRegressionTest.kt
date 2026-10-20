package io.localplayer.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DirectoryPosterRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val app get()=ui.activity.application
    private val owner=ViewModelStore()
    private val saved=mutableListOf<Source>()
    private val historyKeys=mutableListOf<String>()
    private val gate=CountDownLatch(1)
    private lateinit var vm:LibraryViewModel
    private val oldConnector=MediaPosters.connector
    private val oldLookup=MediaPosters.onlineLookup
    private var posters=true
    @Before fun setup(){posters=EnrichmentPreferences(app).posters;EnrichmentPreferences(app).posters=false}
    @After fun cleanup(){
        gate.countDown();ui.runOnUiThread{owner.clear()}
        saved.forEach{SourceStore(app).delete(it.id)}
        historyKeys.forEach{HistoryStore(app).remove(it)}
        MediaPosters.connector=oldConnector;MediaPosters.onlineLookup=oldLookup
        EnrichmentPreferences(app).posters=posters
    }
    private fun browser(initial:String="",blockChild:Boolean=false):Source {
        val source=Source(id="directory-"+UUID.randomUUID(),label="测试片库",protocol=Protocol.WEBDAV,host="directory.invalid",initialPath=initial)
        SourceStore(app).save(source,"",true);saved+=source
        val connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String):List<Entry>{
                    if(blockChild&&path.endsWith("child"))while(!gate.await(10,TimeUnit.MILLISECONDS))cancellation.check()
                    return listOf(Entry("子文件夹",Paths.join(path,"child"),true,0))
                }
                override fun open(path:String):RemoteFile=error("Browse only")
                override fun close(){}
            }
        }
        ui.runOnUiThread{vm=LibraryViewModel(app,connector);owner.put("browser",vm);vm.open(source)
            ui.activity.setContent{PlayerTheme{LibraryScreen(vm,{},{})}}}
        ui.waitUntil(5000){vm.source!=null&&!vm.busy};ui.waitForIdle();return source
    }
    @Test fun folderLoadingKeepsPressedRowAtItsOriginalPosition(){
        browser(blockChild=true)
        val row=ui.onNodeWithText("子文件夹");val before=row.fetchSemanticsNode().boundsInRoot
        row.performClick();ui.runOnIdle{assertTrue(vm.busy)}
        row.assertIsSelected()
        assertEquals("Loading must not move the pressed folder",before,row.fetchSemanticsNode().boundsInRoot)
        gate.countDown();ui.waitUntil(5000){vm.path=="child"&&!vm.busy}
        ui.onNodeWithText("子文件夹").assertIsNotSelected()
    }
    @Test fun directoryToolsLiveInTheToolbarAndTargetCurrentFolder(){
        val source=browser();ui.runOnIdle{vm.browse("child")};ui.waitUntil(5000){vm.path=="child"&&!vm.busy}
        ui.onNodeWithTag("directory-actions").assertDoesNotExist()
        ui.onNodeWithTag("source-menu:"+source.id).performClick()
        ui.onNodeWithText("收藏").performClick()
        ui.runOnIdle{assertTrue(vm.organization.bookmarks().any{it.sourceId==source.id&&it.path=="child"&&it.favorite})}
    }
    @Test fun chosenRootBreadcrumbHasNoParentOrClickActions(){
        browser("private/films")
        ui.onAllNodes(hasTestTag("breadcrumb:sources")).assertCountEquals(0)
        ui.onAllNodes(hasText("private")).assertCountEquals(0)
        ui.onNodeWithTag("breadcrumb:remote:private/films").assertHasNoClickAction()
        ui.onNodeWithTag("breadcrumb:remote:private/films").performTouchInput{click()}
        ui.runOnIdle{assertEquals("private/films",vm.path);vm.up();assertNull(vm.source)}
    }
    @Test fun returningHomeRetriesArtworkAfterTemporaryMiss(){
        val source=Source(id="poster-retry-"+UUID.randomUUID(),label="测试海报",protocol=Protocol.WEBDAV,host="poster.invalid")
        saved+=source;SourceStore(app).save(source,"",true)
        val media=MediaRef(source.id+":film","Test Film 2024.mp4",source.label,source.id,"film.mp4",localUri=java.io.File(app.cacheDir,"missing-poster-video.mp4").toURI().toString(),location=mediaLocation(source))
        historyKeys+=media.key;val store=HistoryStore(app);store.update(media,store.token(media.key),0,120000,false)
        val attempts=AtomicInteger();val available=java.util.concurrent.atomic.AtomicBoolean(false)
        val bytes=java.io.ByteArrayOutputStream().also{out->Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.MAGENTA)}.compress(Bitmap.CompressFormat.PNG,100,out)}.toByteArray()
        MediaPosters.onlineLookup={_,_->attempts.incrementAndGet();null}
        MediaPosters.connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String)=if(available.get())listOf(Entry("poster.jpg","poster.jpg",false,bytes.size.toLong()))else emptyList()
                override fun open(path:String)=object:RemoteFile{
                    override val size=bytes.size.toLong()
                    override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{val n=minOf(length,bytes.size-position.toInt());if(n<=0)return -1;bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+n);return n}
                    override fun close(){}
                }
                override fun close(){}
            }
        }
        EnrichmentPreferences(app).posters=true
        ui.runOnUiThread{vm=LibraryViewModel(app);owner.put("home",vm);ui.activity.setContent{PlayerTheme{HomeScreen(vm,{},{},{})}}}
        try{
            ui.waitUntil(5000){attempts.get()>0}
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED);available.set(true)
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.waitUntil(5000){MediaPosters.credit(app,media)!=null}
            ui.waitUntil(5000){runCatching{ui.onNodeWithTag("media-thumbnail:"+media.key).assertIsDisplayed()}.isSuccess}
        }finally{MediaPosters.remove(app,media)}
    }
}
