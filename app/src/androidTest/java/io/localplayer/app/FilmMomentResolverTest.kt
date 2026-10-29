package io.localplayer.app

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FilmMomentResolverTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun resolve(size:Long,changedBinding:Boolean=false,check:(LibraryViewModel,()->Unit)->Unit){
        val app=ui.activity.application as Application
        val source=Source(label="札记版本测试",protocol=Protocol.SFTP,host="fixture.invalid",root="/")
        SourceStore(app).save(source,"",false)
        val connector=object:Connector{
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
                override fun list(path:String)=listOf(Entry("old.mp4","old.mp4",false,size))
                override fun open(path:String):RemoteFile=error("Resolver test must not read video bytes")
                override fun close(){}
            }
        }
        val owner=ViewModelStore();lateinit var vm:LibraryViewModel
        val media=MediaRef("${source.id}:old.mp4","old.mp4",source.label,source.id,"old.mp4",sizeBytes=100,location=if(changedBinding)"older-binding" else mediaLocation(source))
        try{
            ui.runOnIdle{vm=LibraryViewModel(app,connector);owner.put("moment",vm)}
            check(vm){ui.runOnIdle{vm.resume(HistoryEntry(media,0,0,1,false),positionMs=12345)}}
        }finally{ui.runOnIdle{owner.clear();SourceStore(app).delete(source.id)}}
    }
    @Test fun explicitMomentPositionUsesExistingResolver(){resolve(100){vm,start->
        var target:PlayTarget?=null
        try{start();target=runBlocking{withTimeout(5000){vm.plays.first()}};assertEquals(12345L,target!!.resume);assertEquals("old.mp4",target!!.media.path)}finally{target?.release()}
    }}
    @Test fun changedSizeRejectsTheSavedMoment(){resolve(101){vm,start->start();ui.waitUntil(5000){vm.error!=null};assertTrue(vm.error!!.contains("影片文件已变化"));assertNull(vm.preparingKey)}}
    @Test fun changedBindingRejectsBeforeOpening(){resolve(100,true){vm,start->start();ui.waitUntil(5000){vm.error!=null};assertTrue(vm.error!!.contains("信源身份已变更"));assertNull(vm.preparingKey)}}
}
