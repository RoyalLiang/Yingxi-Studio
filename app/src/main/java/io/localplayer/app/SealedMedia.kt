package io.localplayer.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
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
    private val policyLock=Any()
    private var policyVersion=0L
    internal fun hasProtection():Boolean=synchronized(policyLock){damaged||rows.isNotEmpty()}
    internal fun version():Long=synchronized(policyLock){policyVersion}
    internal fun <T> withVersion(version:Long,action:()->T):T=synchronized(policyLock){
        if(version!=policyVersion)throw java.io.IOException("封存范围已变化，请重试")
        action()
    }
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
        }.onSuccess{next->synchronized(policyLock){rows=next;damaged=false;policyVersion++}}.onFailure{synchronized(policyLock){damaged=true;rows=emptyList();policyVersion++};lock()}
    }
    fun items():List<SealedItem> = if(unlocked)rows else emptyList()
    fun unlock(){synchronized(policyLock){check(!damaged){"封存记录无法读取，原记录已保留"};unlocked=true;policyVersion++}}
    fun lock(){synchronized(policyLock){unlocked=false;policyVersion++};PreviewFrames.clearPlaybackCache();changes.toList().forEach{it()}}

    fun observe(change:()->Unit):()->Unit{changes+=change;return{changes-=change}}
    fun protects(ref:MediaRef):Boolean=damaged||rows.any{it.covers(ref)}
    fun protects(source:Source,path:String)=protects(MediaRef("${source.id}:$path","",source.label,source.id,path,location=mediaLocation(source)))
    fun visible(ref:MediaRef,inside:Boolean=false)=!protects(ref)||(inside&&unlocked&&!damaged)
    fun visible(source:Source,path:String,inside:Boolean=false)=!protects(source,path)||(inside&&unlocked&&!damaged)
    fun visible(entry:LocalEntry,inside:Boolean=false)=visible(MediaRef(entry.uri.toString(),entry.name,"本机文件",localUri=entry.uri.toString(),localParentUri=entry.parentUri?.toString(),localAncestors=entry.ancestorUris),inside)
    fun seal(item:SealedItem){
        check(unlocked&&!damaged){"请先验证身份并开柜"}
        val next=rows.filterNot{it.id==item.id}+item.copy(aliases=item.aliases+rows.firstOrNull{it.id==item.id}?.aliases.orEmpty())
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
        synchronized(policyLock){rows=next;policyVersion++}
        MediaPosters.cancelProtected();OnlineSubtitles.cancelProtected();changes.toList().forEach{it()}
    }
    /** Call on IO: handles document providers whose document IDs are opaque. */
    fun protectsLocalUri(uri:Uri):Boolean=uri in protectedLocalUris(listOf(uri))

    /** Call on IO, outside the migration gate: URI conversion and child checks use provider IPC. */
    internal fun protectedLocalUris(uris:List<Uri>):Set<Uri>{
        if(damaged)return uris.toSet()
        return matchingLocalUris(uris,rows.filter{it.media.localUri!=null},true)
    }
    private fun matchingLocalUris(uris:List<Uri>,protected:List<SealedItem>,failClosed:Boolean):Set<Uri>{
        if(protected.isEmpty())return emptySet()
        val mediaAuthorities=setOf("media","com.android.providers.media.documents","com.android.externalstorage.documents")
        // Cache only this lookup: a SAF path can refer to a different file after replacement.
        val converted=mutableMapOf<Uri,Result<Set<Uri>>>()
        fun aliases(uri:Uri):Result<Set<Uri>> = converted.getOrPut(uri){runCatching{
            buildSet{
                add(uri)
                val media=if(uri.authority==MediaStore.AUTHORITY)uri else MediaStore.getMediaUri(context,uri)
                if(media!=null){add(media);if(isMediaStoreFile(media))runCatching{MediaStore.getDocumentUri(context,media)}.getOrNull()?.let{add(it)}}
            }
        }.onFailure{if(it is kotlinx.coroutines.CancellationException||it is android.os.OperationCanceledException)throw it}}
        fun related(item:SealedItem,parent:Uri,child:Uri):Boolean =
            sameLocalDocument(child.toString(),parent.toString()) || sameMediaStoreItem(parent,child) || child.toString() in item.aliases ||
                item.directory && parent.authority==child.authority && runCatching{DocumentsContract.isChildDocument(context.contentResolver,parent,child)}.getOrElse{
                    failClosed&&runCatching{DocumentsContract.getTreeDocumentId(parent)==DocumentsContract.getTreeDocumentId(child)}.getOrDefault(true)
                }
        return uris.distinct().filterTo(mutableSetOf()){uri->protected.any{item->
            val parent=Uri.parse(item.media.localUri)
            if(related(item,parent,uri))true
            else if(uri.authority in mediaAuthorities&&parent.authority in mediaAuthorities){
                val children=aliases(uri);val parents=aliases(parent)
                if(children.isFailure||parents.isFailure)failClosed else parents.getOrThrow().any{p->children.getOrThrow().any{c->related(item,p,c)}}
            }else false
        }}
    }
    private fun isMediaStoreFile(uri:Uri):Boolean {
        if(uri.scheme!="content"||uri.authority!=MediaStore.AUTHORITY)return false
        return uri.pathSegments.let{p->p.size==3&&p[1] in setOf("file","downloads")||p.size==4&&p[1] in setOf("video","audio","images")&&p[2]=="media"}
    }
    private fun sameMediaStoreItem(a:Uri,b:Uri):Boolean {
        if(!isMediaStoreFile(a)||!isMediaStoreFile(b))return false
        return runCatching{
            val first=MediaStore.getVolumeName(a);val second=MediaStore.getVolumeName(b)
            val id=android.content.ContentUris.parseId(a)
            id>0&&id==android.content.ContentUris.parseId(b)&&
                (first==second||first==MediaStore.VOLUME_EXTERNAL&&second!=MediaStore.VOLUME_INTERNAL||second==MediaStore.VOLUME_EXTERNAL&&first!=MediaStore.VOLUME_INTERNAL)
        }.getOrDefault(false)
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
    fun localAliases(parent:Uri,known:List<MediaRef>,directory:Boolean):Set<String>{
        val item=SealedItem(MediaRef(parent.toString(),"","",localUri=parent.toString()),directory)
        val related=matchingLocalUris(known.mapNotNull{it.localUri?.let(Uri::parse)},listOf(item),false)
        return known.filter{ref->ref.localUri!=null&&!item.covers(ref)&&Uri.parse(ref.localUri) in related}
            .flatMap{listOfNotNull(it.key,it.localUri)}.toSet()
    }

}
