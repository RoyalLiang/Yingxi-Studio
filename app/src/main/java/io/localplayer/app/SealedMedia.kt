package io.localplayer.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import io.localplayer.core.Source
import io.localplayer.core.SourceRoot
import org.json.JSONArray
import org.json.JSONObject

internal fun sameLocalDocument(a:String?,b:String?):Boolean{
    if(a==null||b==null)return false
    val first=Uri.parse(a);val second=Uri.parse(b)
    if(first.authority!=second.authority)return false
    val documents=runCatching{DocumentsContract.getDocumentId(first) to DocumentsContract.getDocumentId(second)}.getOrNull()
    return if(documents!=null)documents.first==documents.second else
        first.buildUpon().clearQuery().fragment(null).build().normalizeScheme()==second.buildUpon().clearQuery().fragment(null).build().normalizeScheme()
}

internal data class SealedItem(
    val media:MediaRef,val directory:Boolean,val tree:String?=null,
    val trail:List<LocalDirectory> = emptyList(),val aliases:Set<String> = emptySet(),val ignoreCase:Boolean=false
) {
    val id:String get()=media.key
    fun covers(ref:MediaRef):Boolean {
        if(media.sourceId!=null){
            if(ref.sourceId!=media.sourceId)return false
            if(media.location!=null&&ref.location!=null&&media.location!=ref.location)return false
            val target=media.path.orEmpty();val candidate=ref.path.orEmpty()
            return if(directory)SourceRoot(if(ignoreCase)target.lowercase(java.util.Locale.ROOT)else target).contains(if(ignoreCase)candidate.lowercase(java.util.Locale.ROOT)else candidate)else target.equals(candidate,ignoreCase=ignoreCase)
        }
        val target=media.localUri?:return false
        return sameLocalDocument(ref.localUri,target) || ref.key in aliases || directory &&
            (ref.localAncestors.any{sameLocalDocument(it,target)} || sameLocalDocument(ref.localParentUri,target))
    }
}

/** An app visibility/access policy. Original media files are neither moved nor encrypted. */
internal object SealedMedia {
    private lateinit var context:Context
    private var rows by mutableStateOf<List<SealedItem>>(emptyList())
    var unlocked by mutableStateOf(false);private set
    var damaged by mutableStateOf(false);private set
    private val changes=mutableSetOf<()->Unit>()
    private val preferences get()=context.getSharedPreferences("sealed-media-v1",Context.MODE_PRIVATE)

    fun initialize(app:Application){
        context=app.applicationContext
        reload()
        app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
            private var started=0
            override fun onActivityStarted(activity:Activity){started++}
            override fun onActivityStopped(activity:Activity){started=(started-1).coerceAtLeast(0);if(started==0&&!activity.isChangingConfigurations)lock()}
            override fun onActivityCreated(activity:Activity,state:Bundle?){}
            override fun onActivityResumed(activity:Activity){}
            override fun onActivityPaused(activity:Activity){}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
            override fun onActivityDestroyed(activity:Activity){}
        })
    }
    internal fun reload(){
        runCatching {
            val a=JSONArray(preferences.getString("items","[]"))
            require(a.length()<=512)
            (0 until a.length()).map { n->val o=a.getJSONObject(n)
                val trail=o.optJSONArray("trail")?:JSONArray();val aliases=o.optJSONArray("aliases")?:JSONArray()
                SealedItem(MediaRef.parse(o.getString("media")),o.getBoolean("directory"),o.optString("tree").takeIf{it.isNotBlank()&&it!="null"},
                    (0 until trail.length()).map{trail.getJSONObject(it).let{d->LocalDirectory(Uri.parse(d.getString("uri")),d.getString("name"))}},
                    (0 until aliases.length()).map{aliases.getString(it)}.toSet(),o.optBoolean("ignoreCase"))
            }
        }.onSuccess{rows=it;damaged=false}.onFailure{damaged=true;rows=emptyList();lock()}
    }
    fun items():List<SealedItem> = if(unlocked)rows else emptyList()
    fun unlock(){check(!damaged){"封存记录无法读取，原记录已保留"};unlocked=true}
    fun lock(){unlocked=false;PreviewFrames.clearPlaybackCache();changes.toList().forEach{it()}}
    fun observe(change:()->Unit):()->Unit{changes+=change;return{changes-=change}}
    fun protects(ref:MediaRef):Boolean=damaged||rows.any{it.covers(ref)}
    fun protects(source:Source,path:String)=protects(MediaRef("${source.id}:$path","",source.label,source.id,path,location=mediaLocation(source)))
    fun visible(ref:MediaRef,inside:Boolean=false)=!protects(ref)||(inside&&unlocked&&!damaged)
    fun visible(source:Source,path:String,inside:Boolean=false)=!protects(source,path)||(inside&&unlocked&&!damaged)
    fun visible(entry:LocalEntry,inside:Boolean=false)=visible(MediaRef(entry.uri.toString(),entry.name,"本机文件",localUri=entry.uri.toString(),localParentUri=entry.parentUri?.toString(),localAncestors=entry.ancestorUris),inside)
    fun seal(item:SealedItem){
        check(unlocked&&!damaged){"请先验证身份并开柜"}
        val next=rows.filterNot{it.id==item.id}+item
        require(next.size<=512){"封存记录已满，请先整理封存柜"}
        save(next)
    }
    fun unseal(id:String){check(unlocked&&!damaged);save(rows.filterNot{it.id==id})}
    private fun save(next:List<SealedItem>){
        val a=JSONArray();next.forEach{item->
            val trail=JSONArray();item.trail.forEach{trail.put(JSONObject().put("uri",it.uri.toString()).put("name",it.name))}
            a.put(JSONObject().put("media",item.media.json()).put("directory",item.directory).put("tree",item.tree).put("trail",trail).put("aliases",JSONArray(item.aliases.toList())).put("ignoreCase",item.ignoreCase))
        }
        check(preferences.edit().putString("items",a.toString()).commit()){"封存记录未能保存，请重试"}
        rows=next;MediaPosters.cancelProtected();OnlineSubtitles.cancelProtected();changes.toList().forEach{it()}
    }
    /** Call on IO: handles document providers whose document IDs are opaque. */
    fun protectsLocalUri(uri:Uri):Boolean {
        if(damaged)return true
        return rows.filter{it.media.localUri!=null}.any{item->
            val parent=Uri.parse(item.media.localUri)
            sameLocalDocument(uri.toString(),parent.toString()) || uri.toString() in item.aliases || item.directory && parent.authority==uri.authority &&
                runCatching{DocumentsContract.isChildDocument(context.contentResolver,parent,uri)}.getOrElse{
                    // Fail closed for an unresolvable item inside the same granted tree.
                    runCatching{DocumentsContract.getTreeDocumentId(parent)==DocumentsContract.getTreeDocumentId(uri)}.getOrDefault(true)
                }
        }
    }
    fun checkDirectorySupport(parent:Uri,signal:android.os.CancellationSignal){
        try{
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent))
            val child=context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null,signal)?.use{cursor->
                if(cursor.moveToFirst())DocumentsContract.buildDocumentUriUsingTree(parent,cursor.getString(0))else null
            }
            val relation=DocumentsContract.isChildDocument(context.contentResolver,parent,child?:parent)
            require(child==null||relation){"此文件提供方无法核验目录范围，请逐集封存"}
        }catch(e:android.os.OperationCanceledException){throw e}catch(e:Exception){throw java.io.IOException("此文件提供方无法核验目录范围，请逐集封存",e)}
    }
    fun localAliases(parent:Uri,known:List<MediaRef>):Set<String> = known.filter{ref->
        val uri=ref.localUri?.let(Uri::parse)?:return@filter false
        sameLocalDocument(parent.toString(),uri.toString()) || sameLocalDocument(ref.localParentUri,parent.toString()) || ref.localAncestors.any{sameLocalDocument(it,parent.toString())} ||
            parent.authority==uri.authority && runCatching{DocumentsContract.isChildDocument(context.contentResolver,parent,uri)}.getOrElse{
                runCatching{DocumentsContract.getTreeDocumentId(parent)==DocumentsContract.getTreeDocumentId(uri)}.getOrDefault(true)
            }
    }.map{it.key}.toSet()
}
