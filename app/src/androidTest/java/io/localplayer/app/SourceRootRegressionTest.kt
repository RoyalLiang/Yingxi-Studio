package io.localplayer.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class SourceRootRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun selectedRootBoundsBreadcrumbsDirectNavigationAndColdOpen() {
        val app=ui.activity.application as Application
        val source=Source(label="边界回归",protocol=Protocol.SFTP,host="fixture.invalid",root="/",initialPath="media/series")
        SourceStore(app).save(source,"",false)
        val paths=java.util.Collections.synchronizedList(mutableListOf<String>())
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession {
                override fun list(path:String):List<Entry>{paths.add(path);return if(path=="media/series")listOf(Entry("第一季","media/series/season",true,0))else emptyList()}
                override fun open(path:String):RemoteFile=error("No playback in navigation test")
                override fun close(){}
            }
        }
        lateinit var vm:LibraryViewModel
        val models=ViewModelStore()
        try {
            ui.runOnIdle {vm=LibraryViewModel(app,connector);models.put("root",vm);ui.activity.setContent{PlayerTheme{LibraryScreen(vm,{},{})}};vm.open(source)}
            ui.waitUntil(5000){vm.source?.id==source.id&&!vm.busy}
            ui.onNodeWithTag("breadcrumb:remote:media/series").assertExists()
            ui.onNodeWithTag("breadcrumb:remote:").assertDoesNotExist()
            ui.onNodeWithTag("breadcrumb:remote:media").assertDoesNotExist()
            ui.runOnIdle{vm.browse("media/series/season")}
            ui.waitUntil(5000){vm.path=="media/series/season"&&!vm.busy}
            ui.onNodeWithTag("breadcrumb:remote:media/series").performClick()
            ui.waitUntil(5000){vm.path=="media/series"&&!vm.busy}
            ui.runOnIdle{vm.browse("")}
            ui.runOnIdle{assertEquals("media/series",vm.path);assertFalse(paths.contains(""));vm.open(source,location="media")}
            ui.waitUntil(5000){vm.source?.id==source.id&&!vm.busy}
            ui.runOnIdle{assertEquals("media/series",vm.path);assertTrue(vm.updateSourcePresentation(source,source.label,"media/series/season","第一季"))}
            ui.waitUntil(5000){vm.path=="media/series/season"&&!vm.busy}
            ui.onNodeWithTag("breadcrumb:remote:media/series/season").assertExists()
            ui.onNodeWithTag("breadcrumb:remote:media/series").assertDoesNotExist()
            ui.runOnIdle{vm.up();assertNull(vm.source)}
            ui.runOnIdle{
                vm.organization.put(SourceBookmark(source.id,"media","越界路标"))
                ui.activity.setContent{PlayerTheme{SourcesCatalog(vm)}}
            }
            ui.onNodeWithText("越界路标").assertDoesNotExist()
        } finally {ui.runOnIdle{models.clear();SourceStore(app).delete(source.id)}}
    }
    @Test fun historyReplayCannotAddOutsideRootFilesToQueue(){
        val app=ui.activity.application as Application
        val source=Source(label="重播边界回归",protocol=Protocol.SFTP,host="fixture.invalid",root="/",initialPath="media/series")
        val inside=Entry("第一集.mkv","media/series/01.mkv",false,4)
        val outside=Entry("外层影片.mkv","media/elsewhere/02.mkv",false,4)
        SourceStore(app).save(source,"",false)
        val connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String)=listOf(inside,outside)
                override fun open(path:String):RemoteFile=error("No playback IO in queue test")
                override fun close(){}
            }
        }
        val models=ViewModelStore();lateinit var vm:LibraryViewModel
        var target:PlayTarget?=null
        try{
            ui.runOnIdle{
                vm=LibraryViewModel(app,connector);models.put("queue-root",vm)
                vm.resume(HistoryEntry(MediaRef("${source.id}:${inside.path}",inside.name,source.label,source.id,inside.path,sizeBytes=inside.size,location=mediaLocation(source)),0,0,0,false))
            }
            target=runBlocking{withTimeout(5000){vm.plays.first()}}
            assertEquals(listOf(inside.path),target.queue.map{it.media.path})
        }finally{
            target?.release()
            ui.runOnIdle{models.clear();SourceStore(app).delete(source.id)}
        }
    }

}
