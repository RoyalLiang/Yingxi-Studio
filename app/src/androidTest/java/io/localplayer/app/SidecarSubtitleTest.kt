package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test

class SidecarSubtitleTest {
    @Test fun opaqueSafIdsUseTheParentSavedByBrowsing(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val tree=android.provider.DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"sidecars")
        context.startActivity(android.content.Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name)
            .putExtra("tree",tree.toString()).putExtra("target",context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline=android.os.SystemClock.elapsedRealtime()+10000
        while(context.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            check(android.os.SystemClock.elapsedRealtime()<deadline);Thread.sleep(20)
        }
        lateinit var files:LocalFilesViewModel
        instrumentation.runOnMainSync{files=LocalFilesViewModel(context.applicationContext as android.app.Application);files.add(tree)}
        var ready=false
        while(!ready){
            instrumentation.runOnMainSync{ready=files.entries.any{it.name=="Film.mkv"}}
            check(android.os.SystemClock.elapsedRealtime()<deadline);if(!ready)Thread.sleep(20)
        }
        var result:java.io.File?=null
        val cancellation=Cancellation()
        try {
            lateinit var entry:LocalEntry
            instrumentation.runOnMainSync{entry=files.entries.first{it.name=="Film.mkv"}}
            assertEquals("video42",android.provider.DocumentsContract.getDocumentId(entry.uri))
            val ref=MediaRef(entry.uri.toString(),entry.name,"Fixture",localUri=entry.uri.toString(),localParentUri=entry.parentUri.toString())
            val restored=MediaRef.parse(ref.json())
            assertEquals(entry.parentUri.toString(),restored.localParentUri)
            result=SidecarSubtitles.find(context,restored,entry.uri,cancellation,ShooterSubtitles(),false)
            assertNotNull(result);assertTrue(result!!.readText().contains("Opaque document subtitle"))
        } finally {
            result?.delete();cancellation.close()
            instrumentation.runOnMainSync{files.folders.firstOrNull{it.tree==tree}?.let(files::remove)}
        }
    }
    @Test fun sidecarUsesIndependentConnectionAndPreservesPrimaryLease() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=Source(label="Subtitle fixture",protocol=Protocol.WEBDAV,host="example.invalid",root="/",secure=true)
        val uri=PlaybackRegistry.add(source,Credentials("synthetic"),"Films/Film.mkv","Film.mkv")
        val primary=PlaybackRegistry.get(uri)
        val text="1\n00:00:01,000 --> 00:00:02,000\n同目录字幕\n".toByteArray(java.nio.charset.Charset.forName("GB18030"))
        var closed=false;var requested:String?=null
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation):RemoteSession = object:RemoteSession {
                override fun list(path:String):List<Entry>{assertEquals("Films",path);return listOf(Entry("Film.chs.srt","Films/Film.chs.srt",false,text.size.toLong()),Entry("Film.other-cut.srt","Films/Film.other-cut.srt",false,64))}
                override fun open(path:String):RemoteFile { requested=path;return object:RemoteFile {
                    override val size=text.size.toLong()
                    override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int { if(position>=size)return -1;val n=minOf(length,text.size-position.toInt());text.copyInto(buffer,offset,position.toInt(),position.toInt()+n);return n }
                    override fun close(){}
                } }
                override fun close(){closed=true}
            }
        }
        val cancellation=Cancellation()
        var result:java.io.File?=null
        try {
            result=SidecarSubtitles.find(context,MediaRef("${source.id}:Films/Film.mkv","Film.mkv",source.label,source.id,"Films/Film.mkv"),uri,cancellation,ShooterSubtitles(),false,connector)
            assertNotNull(result);assertTrue(result!!.readText().contains("同目录字幕"))
            assertEquals("Films/Film.chs.srt",requested);assertTrue(closed)
            assertSame(primary,PlaybackRegistry.get(uri));cancellation.check()
        }finally{result?.delete();cancellation.close();PlaybackRegistry.remove(uri)}
    }
}
