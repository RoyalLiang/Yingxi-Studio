package io.localplayer.app

import android.app.Application
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class LocalFolder(val tree:Uri,val name:String)
data class LocalDirectory(val uri:Uri,val name:String)
data class LocalEntry(val uri:Uri,val name:String,val directory:Boolean,val video:Boolean,val size:Long)

/** Reads only document trees the user explicitly grants through Android SAF. */
class LocalFilesViewModel(app:Application):AndroidViewModel(app){
    private val resolver=app.contentResolver
    private val prefs=app.getSharedPreferences("local-folders",0)
    var folders by mutableStateOf<List<LocalFolder>>(emptyList());private set
    var trail by mutableStateOf<List<LocalDirectory>>(emptyList());private set
    var entries by mutableStateOf<List<LocalEntry>>(emptyList());private set
    var active by mutableStateOf<LocalFolder?>(null);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null)
    private var job:Job?=null
    private var signal:CancellationSignal?=null
    private var generation=0L
    init{refreshFolders()}

    fun refreshFolders(){
        runCatching{
            val granted=resolver.persistedUriPermissions.filter{it.isReadPermission}.map{it.uri}.toSet()
            val saved=JSONArray(prefs.getString("folders","[]"))
            folders=(0 until saved.length()).map{saved.getJSONObject(it)}.mapNotNull{
                val uri=Uri.parse(it.getString("tree"))
                if(uri in granted&&uri.scheme=="content"&&DocumentsContract.isTreeUri(uri))LocalFolder(uri,it.getString("name")) else null
            }
        }.onFailure{error="文件夹记录无法读取，请重新授权文件夹"}
    }
    private fun save(){
        val list=JSONArray()
        folders.forEach{list.put(JSONObject().put("tree",it.tree.toString()).put("name",it.name))}
        prefs.edit().putString("folders",list.toString()).apply()
    }
    fun add(tree:Uri){
        require(tree.scheme=="content"&&DocumentsContract.isTreeUri(tree)){"请选择系统提供的文件夹"}
        cancel()
        val id=++generation;val cancellation=CancellationSignal();signal=cancellation;busy=true;error=null
        job=viewModelScope.launch{
            try{
                val folder=withContext(Dispatchers.IO){
                    val uri=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                    val name=resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null,cancellation)?.use{
                        if(it.moveToFirst())it.getString(0) else null
                    }?:throw java.io.IOException("无法读取所选文件夹")
                    LocalFolder(tree,name)
                }
                if(id==generation){folders=folders.filterNot{it.tree==tree}+folder;save();open(folder)}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(id==generation)error="无法读取文件夹，请确认授权仍然有效（${e.javaClass.simpleName}）"}
            finally{if(id==generation){busy=false;signal=null}}
        }
    }
    fun remove(folder:LocalFolder){
        cancel()
        if(active?.tree==folder.tree)root()
        runCatching{resolver.releasePersistableUriPermission(folder.tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        folders=folders.filterNot{it.tree==folder.tree};save()
    }
    fun open(folder:LocalFolder){
        if(folder !in folders)return
        val uri=DocumentsContract.buildDocumentUriUsingTree(folder.tree,DocumentsContract.getTreeDocumentId(folder.tree))
        load(folder,listOf(LocalDirectory(uri,folder.name)))
    }
    fun enter(entry:LocalEntry){
        val folder=active?:return
        if(entry.directory&&entry in entries)load(folder,trail+LocalDirectory(entry.uri,entry.name))
    }
    fun navigate(index:Int){val folder=active?:return;if(index in trail.indices)load(folder,trail.take(index+1))}
    fun up():Boolean{
        if(active==null)return false
        if(trail.size>1)navigate(trail.lastIndex-1)else root()
        return true
    }
    fun root(){cancel();active=null;trail=emptyList();entries=emptyList();error=null;refreshFolders()}
    fun refresh(){val folder=active;if(folder==null)refreshFolders()else load(folder,trail)}
    fun cancel(){generation++;signal?.cancel();signal=null;job?.cancel();job=null;busy=false}
    private fun load(folder:LocalFolder,target:List<LocalDirectory>){
        cancel();val id=++generation;val cancellation=CancellationSignal();signal=cancellation;busy=true;error=null
        job=viewModelScope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    val parent=target.last().uri
                    val children=DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree,DocumentsContract.getDocumentId(parent))
                    val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE)
                    val rows=mutableListOf<LocalEntry>()
                    resolver.query(children,columns,null,null,null,cancellation)?.use{cursor->
                        while(cursor.moveToNext()){
                            ensureActive();cancellation.throwIfCanceled()
                            val name=cursor.getString(1)?:"未命名文件"
                            val mime=cursor.getString(2).orEmpty();val directory=mime==DocumentsContract.Document.MIME_TYPE_DIR
                            val video=mime.startsWith("video/")||io.localplayer.core.Entry(name,name,false,0).isVideo
                            rows+=LocalEntry(DocumentsContract.buildDocumentUriUsingTree(folder.tree,cursor.getString(0)),name,directory,video,if(cursor.isNull(3))0L else cursor.getLong(3))
                        }
                    }?:throw java.io.IOException("文件夹无法读取")
                    rows.sortedWith(Comparator { a,b -> b.directory.compareTo(a.directory).takeIf { it != 0 } ?: io.localplayer.core.MediaOrdering.compare(a.name,null,a.uri.toString(),b.name,null,b.uri.toString()) })
                }
                if(id==generation&&folder in folders){active=folder;trail=target;entries=result}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(id==generation)error="无法打开文件夹，请检查授权或文件是否仍存在（${e.javaClass.simpleName}）"}
            finally{if(id==generation){busy=false;signal=null}}
        }
    }
    override fun onCleared(){cancel();super.onCleared()}
}
