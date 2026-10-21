package io.localplayer.app

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.localplayer.core.*
import java.io.File
import java.io.IOException

internal object SidecarSubtitles {
    private const val LIMIT=4*1024*1024
    fun find(context:Context,media:MediaRef,uri:Uri,c:Cancellation,provider:ShooterSubtitles,bilingual:Boolean,connector:Connector=AppConnectors()):File? {
        val content:Pair<String,ByteArray>?=if(uri.scheme=="localplayer") {
            val lease=PlaybackRegistry.get(uri)
            connector.connect(lease.source,lease.credentials,c).use { session->
                val entries=session.list(lease.path.substringBeforeLast('/',"")).filter{!it.directory}.take(2000)
                val name=SubtitleSidecars.candidates(media.title,entries.map{it.name},bilingual).firstOrNull()
                val entry=entries.firstOrNull{it.name==name}
                if(entry==null)null else session.open(entry.path).use { file->
                    if(file.size !in 1L..LIMIT.toLong())throw IOException("字幕文件超过大小限制")
                    val data=ByteArray(file.size.toInt());var read=0
                    while(read<data.size){c.check();val n=file.readAt(read.toLong(),data,read,minOf(65536,data.size-read));if(n<=0)throw IOException("字幕读取不完整");read+=n}
                    entry.name to data
                }
            }
        } else if(uri.scheme=="content"&&DocumentsContract.isTreeUri(uri)) {
            // SAF document IDs are opaque. Use the directory recorded when browsing.
            val parent=media.localParentUri?.let(Uri::parse)?:return null
            if(parent.authority!=uri.authority||!DocumentsContract.isTreeUri(parent)||
                DocumentsContract.getTreeDocumentId(parent)!=DocumentsContract.getTreeDocumentId(uri))return null
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent))
            val signal=CancellationSignal();val owner=java.io.Closeable{signal.cancel()};c.own(owner)
            val found=linkedMapOf<String,Uri>()
            try { context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null,signal)?.use { cursor->
                while(found.size<2000&&cursor.moveToNext()){c.check();found[cursor.getString(1)]=DocumentsContract.buildDocumentUriUsingTree(uri,cursor.getString(0))}
            } }finally{c.release(owner)}
            val name=SubtitleSidecars.candidates(media.title,found.keys.toList(),bilingual).firstOrNull()?:return null
            context.contentResolver.openInputStream(found.getValue(name))?.use { input->
                c.own(input)
                try { val data=input.readNBytes(LIMIT+1);c.check();require(data.size<=LIMIT);name to data }finally{c.release(input)}
            }
        } else null
        if(content==null)return null
        c.check()
        val extension=content.first.substringAfterLast('.').lowercase()
        val data=provider.normalizeSidecar(content.second,extension)
        val dir=File(context.cacheDir,"online-subtitles-v1").apply{mkdirs()}
        val target=File(dir,"sidecar-${enrichmentKey(media)}.$extension")
        val temp=File.createTempFile("sidecar-",".tmp",dir)
        try { temp.writeBytes(data);c.check();check(temp.renameTo(target)) }finally{temp.delete()}
        dir.listFiles()?.filter{it!=target}?.sortedByDescending{it.lastModified()}?.drop(63)?.forEach{it.delete()}
        return target
    }
}
