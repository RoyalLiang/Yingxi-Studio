package io.localplayer.app

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.localplayer.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

internal data class CatalogRoot(val id:String,val label:String,val source:Source?=null,val tree:Uri?=null)
internal data class CatalogScan(val folders:Int,val films:Int)

/** A foreground, selected-root walk. Failed or interrupted walks never delete catalog records. */
internal object CatalogIndex {
    fun roots(context:Context):List<CatalogRoot> {
        val grants=context.contentResolver.persistedUriPermissions.filter{it.isReadPermission}.map{it.uri.toString()}.toSet()
        val rows=org.json.JSONArray(context.getSharedPreferences("local-folders",0).getString("folders","[]"))
        val local=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.getString("tree") in grants}.map{CatalogRoot(it.getString("tree"),it.getString("name"),tree=Uri.parse(it.getString("tree")))}
        return local+SourceStore(context).load().filter{it.protocol==Protocol.SMB||it.protocol==Protocol.SFTP}.map{CatalogRoot(it.id,it.label,source=it)}
    }
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun scan(context:Context,root:CatalogRoot,credentials:Credentials?,progress:(CatalogScan)->Unit={},connector:Connector=AppConnectors()):CatalogScan {
        val coroutine=currentCoroutineContext();val cancellation=Cancellation();val signal=CancellationSignal()
        val finish=coroutine.job.invokeOnCompletion(onCancelling=true,invokeImmediately=true){cancellation.close();signal.cancel()}
        val policy=SealedMedia.version()
        var folders=0;var films=0
        FilmLibraryStore(context).use{store->
            val epoch=store.catalogEpoch()
            val source=root.source
            val binding=source?.let{SourceBindingEpochs.current(context,it)}?:LocalBindingEpochs.current(context,requireNotNull(root.tree))
            fun check(){coroutine.ensureActive();cancellation.check();signal.throwIfCanceled();check(SealedMedia.version()==policy){"封存范围已变化，请重新收录"}
                if(source!=null)SourceBindingEpochs.verifyLease(context,source,binding) else check(LocalBindingEpochs.current(context,requireNotNull(root.tree))==binding){"文件夹授权已变化"}
            }
            fun record(refs:List<MediaRef>){check();films+=store.observeBatch(refs,epoch);progress(CatalogScan(folders,films))}
            try {
                if(source!=null){
                    val pending=ArrayDeque<String>();pending.add(source.initialPath);val seen=HashSet<String>();val fence=SourceRoot(source.initialPath)
                    connector.connect(source,requireNotNull(credentials){"请先在信源输入密码"},cancellation).use{session->
                        while(pending.isNotEmpty()){
                            check();val path=pending.removeFirst();if(!seen.add(path)||!fence.contains(path)||!SealedMedia.visible(source,path))continue
                            require(++folders<=10000&&films<50000){"本次收录达到上限，请选择更小的根目录"}
                            val rows=session.list(path);check()
                            rows.forEach{if(it.directory&&fence.contains(it.path)&&SealedMedia.visible(source,it.path))pending.add(it.path)}
                            record(rows.filter{it.isVideo&&fence.contains(it.path)&&SealedMedia.visible(source,it.path)}.map{MediaRef("${source.id}:${it.path}",it.name,source.label,source.id,it.path,sizeBytes=it.size,location=mediaLocation(source),bindingEpoch=binding)})
                        }
                    }
                }else{
                    val tree=requireNotNull(root.tree);val pending=ArrayDeque<Pair<String,List<String>>>()
                    pending.add(DocumentsContract.getTreeDocumentId(tree) to emptyList());val seen=HashSet<String>()
                    while(pending.isNotEmpty()){
                        check();val (id,ancestors)=pending.removeFirst();if(!seen.add(id))continue
                        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,id)
                        if(SealedMedia.protectsLocalUri(parent))continue
                        require(++folders<=10000&&films<50000){"本次收录达到上限，请选择更小的文件夹"}
                        val refs=mutableListOf<MediaRef>()
                        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE)
                        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),columns,null,null,null,signal)?.use{rows->
                            while(rows.moveToNext()){
                                check();val child=rows.getString(0);val uri=DocumentsContract.buildDocumentUriUsingTree(tree,child);val name=rows.getString(1)?:"未命名影片"
                                if(SealedMedia.protectsLocalUri(uri))continue
                                if(rows.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR)pending.add(child to (ancestors+parent.toString()))
                                else if(rows.getString(2).orEmpty().startsWith("video/")||Entry(name,child,false,0).isVideo)refs+=MediaRef(uri.toString(),name,root.label,localUri=uri.toString(),sizeBytes=if(rows.isNull(3))null else rows.getLong(3),localParentUri=parent.toString(),localAncestors=ancestors+parent.toString(),bindingEpoch=binding)
                            }
                        }?:throw java.io.IOException("文件夹暂时无法读取")
                        record(refs)
                    }
                }
                check();return CatalogScan(folders,films)
            }finally{finish.dispose();cancellation.close()}
        }
    }
}
