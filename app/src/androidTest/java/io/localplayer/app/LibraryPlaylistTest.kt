package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.localplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LibraryPlaylistTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(action:()->T):T {var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(action)};return result!!.getOrThrow()}
    @Test fun mixedSourceQueueKeepsSnapshotAndSkipsRevokedFutureWithoutStoppingCurrent(){
        val app=ui.activity.application;val sample=betaPlaybackFixture(app);val bytes=sample.readBytes()
        val a=Source(label="北侧片箱",protocol=Protocol.SFTP,host="fixture.invalid")
        val b=Source(label="门边片箱",protocol=Protocol.SMB,host="other.invalid",root="share")
        val connector=object:Connector{override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
            override fun list(path:String)=emptyList<Entry>()
            override fun open(path:String)=object:RemoteFile{
                override val size=bytes.size.toLong()
                override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int {cancellation.check();if(position>=size)return -1;val n=minOf(length.toLong(),size-position).toInt();bytes.copyInto(buffer,offset,position.toInt(),position.toInt()+n);return n}
                override fun close(){}
            }
            override fun close(){}
        }}
        val owner=ViewModelStore();var target:PlayTarget?=null;var controller:MediaController?=null
        try{
            SourceStore(app).save(a,"",false);SourceStore(app).save(b,"",false)
            val history=HistoryStore(app)
            val refs=listOf(
                MediaRef("${a.id}:one.mp4","北侧旧片.mp4",a.label,a.id,"one.mp4",sizeBytes=bytes.size.toLong(),location=mediaLocation(a),bindingEpoch=0),
                MediaRef("playlist-local-${System.nanoTime()}","门边重映.mp4","本机文件",localUri=Uri.fromFile(sample).toString(),sizeBytes=bytes.size.toLong()),
                MediaRef("${b.id}:two.mp4","门边旧片.mp4",b.label,b.id,"two.mp4",sizeBytes=bytes.size.toLong(),location=mediaLocation(b),bindingEpoch=0)
            ).map(history::reference)
            val films=FilmLibraryStore(app).use{store->refs.map{store.film(it.mediaId!!)!!}}
            val shelf=FilmLibraryStore(app).use{store->store.createShelf("混合放映").also{id->films.forEach{store.addToShelf(id,it.id)}}}
            val vm=main{LibraryViewModel(app,connector).also{owner.put("playlist",it)}}
            main{vm.playCatalog(films)}
            target=runBlocking{withTimeout(10000){vm.plays.first()}}
            assertEquals(refs.map{it.mediaId},target.queue.map{it.media.mediaId})
            FilmLibraryStore(app).use{it.moveInShelf(shelf,films[2].id,-1)}
            assertEquals(refs.map{it.mediaId},target.queue.map{it.media.mediaId})
            val active=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()}.get(10,TimeUnit.SECONDS);controller=active
            main{active.setMediaItems(PlaybackRequests.items(app,target),0,0);active.prepare();active.play()}
            ui.waitUntil(15000){main{active.playbackState==Player.STATE_READY&&active.isPlaying&&active.currentPosition>200}}
            main{active.seekToNextMediaItem()}
            ui.waitUntil(10000){main{active.currentMediaItemIndex==1&&active.isPlaying&&active.currentPosition>100}}
            SourceStore(app).delete(b.id)
            ui.waitUntil(5000){main{active.mediaItemCount==2}}
            main{assertTrue(active.isPlaying);assertNull(active.playerError);assertEquals(1,active.currentMediaItemIndex)}
        }finally{
            controller?.let{main{it.stop();it.clearMediaItems();it.release()}}
            main{owner.clear()};target?.release();SourceStore(app).delete(a.id);SourceStore(app).delete(b.id)
        }
    }
}
