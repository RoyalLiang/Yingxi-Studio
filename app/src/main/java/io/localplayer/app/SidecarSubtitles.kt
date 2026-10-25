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
    data class Discovery(val subtitles:List<File>,val subtitleNames:List<String>,val audio:List<SidecarAudio>,val registered:List<Uri>)
    fun find(context:Context,media:MediaRef,uri:Uri,c:Cancellation,provider:ShooterSubtitles,bilingual:Boolean,connector:Connector=AppConnectors()):File? =
        discover(context,media,uri,c,provider,bilingual,connector,false).subtitles.firstOrNull()

    fun discover(context:Context,media:MediaRef,uri:Uri,c:Cancellation,provider:ShooterSubtitles,bilingual:Boolean,
        connector:Connector=AppConnectors(),withAudio:Boolean=true):Discovery {
        val registered=mutableListOf<Uri>()
        fun collect(names:List<String>,read:(String)->ByteArray,audioUri:(String)->Uri):Discovery {
            val subtitles=mutableListOf<File>();val labels=mutableListOf<String>()
            val dir=File(context.cacheDir,"online-subtitles-v1").apply{mkdirs()}
            for(name in SubtitleSidecars.candidates(media.title,names,bilingual).take(12))try {
                c.check();val extension=name.substringAfterLast('.').lowercase()
                val data=provider.normalizeSidecar(read(name),extension)
                val key=java.security.MessageDigest.getInstance("SHA-256").digest((enrichmentKey(media)+name).toByteArray()).joinToString(""){"%02x".format(it)}
                val target=File(dir,"sidecar-$key.$extension")
                val temp=File.createTempFile("sidecar-",".tmp",dir)
                try{temp.writeBytes(data);c.check();check(temp.renameTo(target))}finally{temp.delete()}
                subtitles+=target;labels+=name
            } catch(e:Exception){c.check()}
            val audio=if(withAudio)SubtitleSidecars.audioCandidates(media.title,names).take(8).map { name->c.check();SidecarAudio(audioUri(name),name) }else emptyList()
            dir.listFiles()?.filter{it !in subtitles}?.sortedByDescending{it.lastModified()}?.drop(63)?.forEach{it.delete()}
            c.check();return Discovery(subtitles,labels,audio,registered.toList())
        }
        fun readLocal(uri:Uri):ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
            c.own(input)
            try{input.readNBytes(LIMIT+1).also{c.check();require(it.size<=LIMIT){"字幕文件超过大小限制"}}}finally{c.release(input)}
        }?:throw IOException("字幕文件无法读取")
        try {
            if(uri.isPlayerNetworkUri()) {
                val lease=PlaybackRegistry.get(uri)
                return connector.connect(lease.source,lease.credentials,c).use { session ->
                    val entries=session.list(lease.path.substringBeforeLast('/',"")).filter{!it.directory}.take(2000).associateBy{it.name}
                    collect(entries.keys.toList(),{ name ->
                        session.open(entries.getValue(name).path).use { file ->
                            require(file.size in 1L..LIMIT.toLong()){"字幕文件超过大小限制"}
                            val data=ByteArray(file.size.toInt());var read=0
                            while(read<data.size){c.check();val n=file.readAt(read.toLong(),data,read,minOf(65536,data.size-read));if(n<=0)throw IOException("字幕读取不完整");read+=n}
                            data
                        }
                    },{ name ->
                        val audio=PlaybackRegistry.add(lease.source,lease.credentials,entries.getValue(name).path,name,connector)
                        registered+=audio;c.check();audio
                    })
                }
            }
            if(uri.scheme=="file") {
                val parent=File(uri.path?:return Discovery(emptyList(),emptyList(),emptyList(),emptyList())).parentFile
                val files=parent?.listFiles()?.filter{it.isFile}?.take(2000)?.associateBy{it.name}.orEmpty()
                return collect(files.keys.toList(),{name->readLocal(Uri.fromFile(files.getValue(name)))},{name->Uri.fromFile(files.getValue(name))})
            }
            if(uri.scheme=="content"&&DocumentsContract.isTreeUri(uri)) {
                val parent=media.localParentUri?.let(Uri::parse)?:return Discovery(emptyList(),emptyList(),emptyList(),emptyList())
                require(parent.authority==uri.authority&&DocumentsContract.isTreeUri(parent)&&DocumentsContract.getTreeDocumentId(parent)==DocumentsContract.getTreeDocumentId(uri))
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent))
                val signal=CancellationSignal();val owner=java.io.Closeable{signal.cancel()};c.own(owner)
                val found=linkedMapOf<String,Uri>()
                try{context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null,signal)?.use { cursor ->
                    while(found.size<2000&&cursor.moveToNext()){c.check();if(cursor.getString(2)!=DocumentsContract.Document.MIME_TYPE_DIR)found[cursor.getString(1)]=DocumentsContract.buildDocumentUriUsingTree(uri,cursor.getString(0))}
                }}finally{c.release(owner)}
                return collect(found.keys.toList(),{name->readLocal(found.getValue(name))},{name->found.getValue(name)})
            }
            return Discovery(emptyList(),emptyList(),emptyList(),emptyList())
        }catch(e:Exception){registered.forEach(PlaybackRegistry::remove);throw e}
    }
}
