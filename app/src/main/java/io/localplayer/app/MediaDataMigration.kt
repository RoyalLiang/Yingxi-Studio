package io.localplayer.app

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class DataMigrationStatus(val state:String="NotStarted",val imported:Int=0,val quarantined:Int=0,val message:String?=null)
internal object MediaDataMigration {
    private val sessions=ConcurrentHashMap<String,MediaDataSession>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var job:Job?=null
    @Volatile private var app:Context?=null
    private var observesPolicy=false
    var status by mutableStateOf(DataMigrationStatus());private set
    fun session(context:Context,name:String="history-v1"):MediaDataSession {
        val database=if(name=="history-v1")MEDIA_DATABASE else "history-"+digest(name)+".db"
        return sessions.computeIfAbsent(context.getDatabasePath(database).path){MediaDataSession(context.applicationContext,name,database)}
    }
    @Synchronized fun start(context:Context){
        app=context.applicationContext
        if(!observesPolicy){SealedMedia.observe{session(context).changed()};observesPolicy=true}
        if(job?.isActive==true)return
        job=scope.launch{
            val result=try{session(context).migrate()}catch(e:CancellationException){throw e}catch(_:Exception){DataMigrationStatus("Failed",message="旧资料暂时无法核对，原数据已保留")}
            withContext(Dispatchers.Main.immediate){status=result}
        }
    }
    fun preparation(context:Context):java.io.Closeable {
        val session=session(context);val id="preparation:"+java.util.UUID.randomUUID()
        session.targetCreated(id)
        val closed=java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable{if(closed.compareAndSet(false,true))session.targetReleased(id)}
    }
    fun playbackStarted(context:Context){session(context).playbackStarted()}
    fun playbackStopped(context:Context){session(context).playbackStopped();start(context)}
    fun changed(context:Context){session(context).changed()}
    fun playbackIntent(context:Context){session(context).changed()}
    fun playbackIntent(){app?.let{session(it).changed()}}
    fun targetCreated(id:String){app?.let{session(it).targetCreated(id)}}
    fun targetReleased(id:String){app?.let{session(it).targetReleased(id)}}
    internal fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

/** The gate is shared by old and new writers; only one side is authoritative at any instant. */
internal class MediaDataSession(private val context:Context,private val historyName:String,private val databaseName:String){
    internal val gate=Any()
    private val helper by lazy{MediaLibraryDatabase(context,databaseName)}
    private val legacy=LegacyHistoryStore(context,historyName)
    private var committedCache:Boolean?=null
    private var changes=0L
    private var players=0
    private val targets=mutableSetOf<String>()
    private fun committed():Boolean {
        committedCache?.let{return it}
        val ready=context.getDatabasePath(databaseName).exists()&&helper.readableDatabase.rawQuery("SELECT value FROM metadata WHERE name='data-migration-v1'",null).use{it.moveToFirst()&&it.getString(0)=="Committed"}
        committedCache=ready;return ready
    }
    fun changed()=synchronized(gate){changes++;Unit}
    fun playbackStarted()=synchronized(gate){players++;changes++;Unit}
    fun playbackStopped()=synchronized(gate){players=(players-1).coerceAtLeast(0);changes++;Unit}
    fun targetCreated(id:String)=synchronized(gate){targets.add(id);changes++;Unit}
    fun targetReleased(id:String)=synchronized(gate){targets.remove(id);changes++;Unit}
    fun close()=synchronized(gate){helper.close()}
    fun isCommitted()=synchronized(gate){committed()}
    fun requireIdle()=synchronized(gate){check(players==0&&targets.isEmpty()){ "请先结束放映并关闭待播影片，再恢复行囊" }}
    private fun hash(key:String)=MediaDataMigration.digest(key)
    private fun long(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT value FROM metadata WHERE name=?",arrayOf(name)).use{if(it.moveToFirst())it.getString(0).toLong()else 0L}
    private fun token(db:SQLiteDatabase,key:String)=HistoryToken(long(db,"history-clear"),db.rawQuery("SELECT revision FROM history_revisions WHERE legacy_key=?",arrayOf(hash(key))).use{if(it.moveToFirst())it.getLong(0)else 0L},1)
    private fun mediaToken(db:SQLiteDatabase,ref:MediaRef):HistoryToken {
        val film=CatalogIdentity.find(db,ref)?:error("影片身份尚未登记")
        val identified=CatalogIdentity.current(db,film,ref.json())
        val revision=db.rawQuery("SELECT revision FROM history_revisions WHERE legacy_key=?",arrayOf("media:"+identified.mediaId)).use{if(it.moveToFirst())it.getLong(0)else 0L}
        return HistoryToken(long(db,"history-clear"),revision,1,identified.mediaId,identified.contentRevisionId)
    }
    fun token(ref:MediaRef)=synchronized(gate){if(!committed())legacy.token(ref.key)else {val db=helper.readableDatabase;if(CatalogIdentity.find(db,ref)==null)token(db,ref.key)else mediaToken(db,ref)}}
    fun token(key:String)=synchronized(gate){if(committed())token(helper.readableDatabase,key)else legacy.token(key)}
    fun entries():List<HistoryEntry> = synchronized(gate){if(!committed())legacy.entries()else entries(helper.readableDatabase)}
    private fun entries(db:SQLiteDatabase):List<HistoryEntry> {
        val rows=mutableListOf<HistoryEntry>()
        db.rawQuery("SELECT f.id,f.media,c.position,c.duration,c.updated,c.completed FROM checkpoints c JOIN films f ON f.id=c.film_id ORDER BY c.updated DESC,f.id LIMIT 100",null).use{c->while(c.moveToNext())rows+=HistoryEntry(CatalogIdentity.current(db,c.getString(0),c.getString(1)),c.getLong(2),c.getLong(3),c.getLong(4),c.getInt(5)==1)}
        return rows
    }
    private fun <T> transaction(action:(SQLiteDatabase)->T):T {
        val db=helper.writableDatabase;db.beginTransaction();try{return action(db).also{db.setTransactionSuccessful()}}finally{db.endTransaction()}
    }
    fun reference(ref:MediaRef):MediaRef=synchronized(gate){if(!committed())ref else transaction{CatalogIdentity.register(it,ref,System.currentTimeMillis())}}
    fun checkLocator(ref:MediaRef)=synchronized(gate){
        if(!LocalBindingEpochs.accepts(context,ref))throw java.io.IOException("文件夹授权已撤回，请重新选择影片")
        if(committed()&&!CatalogIdentity.isCurrent(helper.readableDatabase,ref))throw java.io.IOException("片签位置已变化，请从片架重新打开")
    }
    fun update(ref:MediaRef,expected:HistoryToken,position:Long,duration:Long,completed:Boolean):Boolean=synchronized(gate){
        val ready=committed()
        if(!SourceBindingEpochs.acceptsWrite(context,ref,allowUnregistered=!ready))return@synchronized false
        if(!ready){changes++;return@synchronized legacy.update(ref,expected,position,duration,completed)}
        transaction{db->
            if(!CatalogIdentity.isCurrent(db,ref))return@transaction false
            if(expected.generation!=1L||expected.clearRevision!=long(db,"history-clear"))return@transaction false
            if(expected.mediaId!=null&&CatalogIdentity.find(db,ref)!=expected.mediaId)return@transaction false
            if(expected!=(if(expected.mediaId!=null)mediaToken(db,ref)else token(db,ref.key)))return@transaction false
            val media=CatalogIdentity.register(db,ref,System.currentTimeMillis());val film=media.mediaId!!
            val previous=db.rawQuery("SELECT duration FROM checkpoints WHERE film_id=?",arrayOf(film)).use{if(it.moveToFirst())it.getLong(0)else 0L}
            val known=duration.takeIf{it>0}?:previous
            val bounded=position.coerceAtLeast(0).let{if(known>0)it.coerceAtMost(known)else it}
            checkpoint(db,film,bounded,known,System.currentTimeMillis(),completed)
            db.execSQL("DELETE FROM checkpoints WHERE film_id NOT IN (SELECT film_id FROM checkpoints ORDER BY updated DESC,film_id LIMIT 100)")
            true
        }
    }
    private fun checkpoint(db:SQLiteDatabase,film:String,position:Long,duration:Long,updated:Long,completed:Boolean){
        db.execSQL("INSERT OR REPLACE INTO checkpoints(film_id,position,duration,updated,completed) VALUES(?,?,?,?,?)",arrayOf(film,position,duration,updated,if(completed)1 else 0))
    }
    fun resume(ref:MediaRef,oldPosition:Long):Long=synchronized(gate){
        if(!committed())return@synchronized legacy.resumePosition(ref,oldPosition)
        val db=helper.readableDatabase;val film=CatalogIdentity.find(db,ref)?:return@synchronized 0L
        db.rawQuery("SELECT position,completed FROM checkpoints WHERE film_id=?",arrayOf(film)).use{if(it.moveToFirst())return@synchronized if(it.getInt(1)==1)0L else it.getLong(0)}
        val t=token(db,ref.key);if(t.clearRevision>0||t.itemRevision>0||db.rawQuery("SELECT revision FROM history_revisions WHERE legacy_key=?",arrayOf("media:$film")).use{it.moveToFirst()&&it.getLong(0)>0})return@synchronized 0L
        db.rawQuery("SELECT value FROM metadata WHERE name=?",arrayOf("legacy-position:$film")).use{if(it.moveToFirst())it.getString(0).toLong()else 0L}
    }
    fun remove(key:String,mediaId:String?=null)=synchronized(gate){
        if(!committed()){changes++;legacy.remove(key);return@synchronized}
        transaction{db->
            val films=mutableListOf<String>();db.rawQuery("SELECT DISTINCT film_id FROM locators WHERE legacy_key=?"+(if(mediaId!=null)" AND film_id=?"else""),if(mediaId!=null)arrayOf(key,mediaId)else arrayOf(key)).use{while(it.moveToNext())films+=it.getString(0)}
            val keys=mutableSetOf(key)
            films.forEach{film->db.execSQL("INSERT INTO history_revisions(legacy_key,revision) VALUES(?,1) ON CONFLICT(legacy_key) DO UPDATE SET revision=revision+1",arrayOf("media:$film"));db.rawQuery("SELECT legacy_key FROM locators WHERE film_id=?",arrayOf(film)).use{while(it.moveToNext())keys+=it.getString(0)};db.delete("checkpoints","film_id=?",arrayOf(film));db.delete("metadata","name=?",arrayOf("legacy-position:$film"))}
            keys.forEach{alias->db.execSQL("INSERT INTO history_revisions(legacy_key,revision) VALUES(?,1) ON CONFLICT(legacy_key) DO UPDATE SET revision=revision+1",arrayOf(hash(alias)))}
        };Unit
    }
    fun clear()=synchronized(gate){
        if(!committed()){changes++;legacy.clear();return@synchronized}
        transaction{db->db.execSQL("UPDATE metadata SET value=? WHERE name='history-clear'",arrayOf((long(db,"history-clear")+1).toString()));db.delete("checkpoints",null,null);db.delete("history_revisions",null,null);db.delete("metadata","name LIKE 'legacy-position:%'",null)};Unit
    }
    fun preferenceValues(scope:String,original:SharedPreferences):Map<String,Any?> = synchronized(gate){
        val result=original.all.toMutableMap()
        if(committed()){
            importAddedScope(scope,original)
            result.keys.filter{MigratingPreferences.included(scope,it)}.forEach{result.remove(it)}
            helper.readableDatabase.rawQuery("SELECT name,value FROM preferences WHERE scope=?",arrayOf(scope)).use{while(it.moveToNext())result[it.getString(0)]=MigratingPreferences.decode(it.getString(1))}
        };result
    }
    fun writePreferences(scope:String,original:SharedPreferences,values:Map<String,Any?>,clear:Boolean)=synchronized(gate){
        changes++
        if(!committed()){val editor=original.edit();if(clear)editor.clear();values.forEach{(k,v)->put(editor,k,v)};check(editor.commit());return@synchronized}
        importAddedScope(scope,original)
        transaction{db->
            if(clear)db.delete("preferences","scope=?",arrayOf(scope))
            values.filter{MigratingPreferences.included(scope,it.key)}.forEach{(k,v)->if(v==null)db.delete("preferences","scope=? AND name=?",arrayOf(scope,k))else db.execSQL("INSERT OR REPLACE INTO preferences(scope,name,value) VALUES(?,?,?)",arrayOf(scope,k,MigratingPreferences.encode(v)))}
        }
        val editor=original.edit()
        if(clear)original.all.keys.filterNot{MigratingPreferences.included(scope,it)}.forEach{editor.remove(it)}
        values.filterNot{MigratingPreferences.included(scope,it.key)}.forEach{(k,v)->put(editor,k,v)}
        check(editor.commit())
    }
    // Import these Alpha.67 domains once for older local Beta databases, never revive a removed key.
    private fun importAddedScope(scope:String,original:SharedPreferences){
        if(scope !in MigratingPreferences.addedScopes)return
        val marker="preference-scope:$scope"
        if(helper.readableDatabase.rawQuery("SELECT 1 FROM metadata WHERE name=?",arrayOf(marker)).use{it.moveToFirst()})return
        transaction{db->
            original.all.filter{MigratingPreferences.included(scope,it.key)}.forEach{(key,value)->if(value!=null)
                db.execSQL("INSERT OR IGNORE INTO preferences(scope,name,value) VALUES(?,?,?)",arrayOf(scope,key,MigratingPreferences.encode(value)))}
            db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,'1')",arrayOf(marker))
        }
    }
    private fun put(editor:SharedPreferences.Editor,key:String,value:Any?){when(value){null->editor.remove(key);is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Long->editor.putLong(key,value);is Int->editor.putInt(key,value);is Float->editor.putFloat(key,value);is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet());else->error("Unsupported preference type")}}
    private data class Snapshot(val epoch:Long,val raw:String,val history:Map<String,Any?>,val preferences:Map<String,Map<String,Any?>>,val positions:Map<String,Long>,val sources:List<io.localplayer.core.Source>)
    private fun snapshot():Snapshot=synchronized(gate){
        val prefs=context.getSharedPreferences(historyName,Context.MODE_PRIVATE).all.toMap()
        val domains=if(historyName=="history-v1")MigratingPreferences.scopes.associateWith{name->context.getSharedPreferences(name,Context.MODE_PRIVATE).all.filter{MigratingPreferences.included(name,it.key)}.toMap()}else emptyMap()
        val positions=if(historyName=="history-v1")context.getSharedPreferences("sources-v1",Context.MODE_PRIVATE).all.filterKeys{it.startsWith("position-")}.mapValues{(_,v)->require(v is Long&&v>=0);v}else emptyMap()
        val sources=if(historyName=="history-v1"){
            val original=context.getSharedPreferences("sources-v1",Context.MODE_PRIVATE).all
            val rows=JSONArray(stringRoot(original,"sources"));require(rows.length()<=512)
            SourceStore(context).load().sortedBy{it.id}.also{require(it.size==rows.length()){"Source profiles cannot be decoded"}}
        }else emptyList()
        val identities=sources.map{source->source.id+":"+mediaLocation(source)+":"+SourceBindingEpochs.current(context,source)}
        val policy=if(historyName=="history-v1")MediaDataMigration.digest(encodeMap(context.getSharedPreferences("sealed-media-v1",Context.MODE_PRIVATE).all).toString())else "test-domain"
        val root=JSONObject().put("sourceIdentities",JSONArray(identities)).put("policyDigest",policy).put("history",encodeMap(prefs)).put("preferences",JSONObject().apply{domains.toSortedMap().forEach{(name,values)->put(name,encodeMap(values))}}).put("positions",encodeMap(positions))
        Snapshot(changes,root.toString(),prefs,domains,positions,sources)
    }
    private fun encodeMap(values:Map<String,Any?>)=JSONObject().apply{values.toSortedMap().forEach{(k,v)->if(v!=null)put(k,MigratingPreferences.encode(v))}}
    internal var migrationProbe:((String)->Unit)?=null
    fun migrate(beforeCommit:(()->Unit)?=null):DataMigrationStatus {
        synchronized(gate){if(committed())return report();if(players>0||targets.isNotEmpty())return DataMigrationStatus("Deferred",message="放映仍在进行，散场后再归位")}
        if(SealedMedia.damaged)return DataMigrationStatus("Failed",message="封存记录需要修复，旧数据已保留")
        var stage="snapshot"
        return try{
            val snapshot=snapshot();val digest=MediaDataMigration.digest(snapshot.raw)
            // apply() updates memory before disk. Flush already queued legacy writes before
            // the durable snapshot so a killed process cannot restart from older preferences.
            // No values are changed and no shared gate is held during disk I/O.
            (snapshot.preferences.keys+historyName+"sources-v1").forEach{scope->
                check(context.getSharedPreferences(scope,Context.MODE_PRIVATE).edit().commit()){"Legacy data could not be flushed"}
            }
            val directory=File(context.noBackupFilesDir,"data-migration").apply{check(exists()||mkdirs())}
            val saved=File(directory,"${MediaDataMigration.digest(historyName)}-$digest.json")
            if(!saved.exists()){val temp=File(directory,saved.name+".pending");temp.outputStream().use{it.write(snapshot.raw.toByteArray());it.fd.sync()};check(temp.renameTo(saved))}
            check(saved.readText()==snapshot.raw){"Migration snapshot verification failed"}
            migrationProbe?.invoke("snapshot")
            stage="import"
            data class Issue(val scope:String,val key:String,val reason:String,val raw:String?)
            val issues=mutableListOf<Issue>()
            fun issue(scope:String,key:String,reason:String,raw:String?){issues+=Issue(scope,key,reason,raw)}
            val historyText=stringRoot(snapshot.history,"entries");require(historyText.length<=1_048_576)
            val history=JSONArray(historyText);require(history.length()<=1024)
            val entries=mutableListOf<HistoryEntry>()
            val known=linkedMapOf<String,MediaRef>()
            fun remember(ref:MediaRef){known[ref.key+"\u0000"+CatalogIdentity.binding(ref)+"\u0000"+CatalogIdentity.version(ref)]=ref}
            for(i in 0 until history.length()){
                val entry=runCatching{val o=history.getJSONObject(i);val ref=MediaRef.parse(o.getString("media"));val p=o.getLong("position");val d=o.getLong("duration");val updated=o.getLong("updated");require(p>=0&&d>=0&&updated>=0);HistoryEntry(ref,p,d,updated,o.optBoolean("completed"))}.getOrElse{issue(historyName,i.toString(),"invalid history record",history.opt(i)?.toString());null}?:continue
                entries+=entry;remember(entry.media)
            }
            val clear=snapshot.history["clear-revision"]?.let{require(it is Long&&it>=0);it}?:0L
            val revisions=snapshot.history.filterKeys{it.startsWith("revision-")}.mapValues{(_,v)->require(v is Long&&v>=0);v}
            val observations=mutableListOf<Pair<MediaRef,Long>>()
            if(historyName=="history-v1"){
                val raw=stringRoot(snapshot.preferences["station-inventory-v1"].orEmpty(),"items");require(raw.length<=2_097_152)
                val inventory=JSONArray(raw);require(inventory.length()<=512)
                for(i in 0 until inventory.length())runCatching{
                    val o=inventory.getJSONObject(i);val ref=MediaRef.parse(o.getString("media"));val seen=o.getLong("seen");require(seen>=0);remember(ref)
                    // Provider calls and policy checks happen before taking the database writer gate.
                    if(SealedMedia.visible(ref)&&ref.localUri?.let{!SealedMedia.protectsLocalUri(android.net.Uri.parse(it))}!=false)observations+=ref to seen
                }.onFailure{issue("station-inventory-v1",i.toString(),"invalid inventory record",inventory.opt(i)?.toString())}
            }
            val positions=mutableListOf<Pair<MediaRef,Long>>()
            val associated=mutableSetOf<String>()
            known.values.groupBy{it.key}.forEach{(key,refs)->val hashed="position-"+hash(key);val value=snapshot.positions[hashed];if(value!=null){
                associated+=hashed
                if(refs.size==1)positions+=refs.single() to value.coerceAtLeast(0)else issue("sources-v1",hash(key),"ambiguous legacy position: multiple content versions",null)
            }}
            snapshot.positions.keys.filterNot{it in associated}.forEach{issue("sources-v1",it,"unmapped legacy position retained in original store",null)}
            val preferences=snapshot.preferences.mapValues{(scope,values)->values.mapValues{(key,value)->
                if(scope=="station-experience-v1"&&key=="diary-line"&&value is String&&value.startsWith("quote:"))LegacyQuoteAliases.resolve(value)?:run{issue(scope,key,"legacy quote identity cannot be verified",value);value}else value
            }}
            migrationProbe?.invoke("validated")
            beforeCommit?.invoke()
            stage="commit"
            synchronized(gate){
                if(committed())return@synchronized report()
                if(players>0||targets.isNotEmpty()||changes!=snapshot.epoch||snapshot().raw!=snapshot.raw||SealedMedia.damaged)throw MigrationDeferred()
                // Only bounded, prepared SQL writes run under the shared gate. No files, provider IPC or network here.
                transaction{db->
                    entries.forEach{entry->
                        val ref=CatalogIdentity.register(db,entry.media,entry.updatedAt)
                        db.execSQL("INSERT OR IGNORE INTO history_revisions(legacy_key,revision) VALUES(?,?)",arrayOf<Any>("media:"+ref.mediaId,revisions["revision-"+hash(entry.media.key)]?:0L))
                        val previous=db.rawQuery("SELECT updated FROM checkpoints WHERE film_id=?",arrayOf(ref.mediaId)).use{if(it.moveToFirst())it.getLong(0)else -1L}
                        if(entry.updatedAt>=previous)checkpoint(db,ref.mediaId!!,entry.positionMs,entry.durationMs,entry.updatedAt,entry.completed)
                    }
                    db.execSQL("UPDATE metadata SET value=? WHERE name='history-clear'",arrayOf(clear.toString()))
                    revisions.forEach{(key,value)->db.execSQL("INSERT OR REPLACE INTO history_revisions(legacy_key,revision) VALUES(?,?)",arrayOf<Any>(key.removePrefix("revision-"),value))}
                    observations.forEach{(ref,seen)->CatalogIdentity.register(db,ref,seen)}
                    snapshot.sources.forEach{source->db.execSQL("INSERT OR IGNORE INTO source_bindings(source_id,digest,epoch) VALUES(?,?,?)",arrayOf<Any>(source.id,mediaLocation(source),SourceBindingEpochs.current(context,source)))}
                    positions.forEach{(ref,value)->CatalogIdentity.find(db,ref)?.let{film->db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,?)",arrayOf("legacy-position:$film",value.toString()))}}
                    preferences.forEach{(scope,values)->values.forEach{(key,value)->if(value!=null)db.execSQL("INSERT OR REPLACE INTO preferences(scope,name,value) VALUES(?,?,?)",arrayOf(scope,key,MigratingPreferences.encode(value)))}}
                    MigratingPreferences.addedScopes.forEach{scope->db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,'1')",arrayOf("preference-scope:$scope"))}
                    issues.forEach{db.execSQL("INSERT INTO migration_issues(scope,record_key,reason,raw) VALUES(?,?,?,?)",arrayOf(it.scope,it.key,it.reason,it.raw))}
                    migrationProbe?.invoke("imported")
                    db.execSQL("INSERT OR REPLACE INTO migration_runs(id,digest,state,imported,quarantined) VALUES('legacy-v1',?,'Committed',?,?)",arrayOf<Any>(digest,entries.size,issues.size))
                    db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('data-migration-v1','Committed')")
                }
                committedCache=true;changes++
                DataMigrationStatus("Committed",entries.size,issues.size)
            }.also{migrationProbe?.invoke("committed")}
        }catch(_:MigrationDeferred){DataMigrationStatus("Deferred",message="资料或放映状态有变化，旧数据仍可使用，请稍后重试")}
        catch(_:Exception){DataMigrationStatus("Failed",message="旧数据在${if(stage=="snapshot")"备份"else if(stage=="commit")"归位"else"读取"}时未完成，原记录已保留")}
    }
    private fun stringRoot(values:Map<String,Any?>,key:String):String {if(!values.containsKey(key))return "[]";return values[key] as? String?:error("Invalid root type: $key")}
    private fun report():DataMigrationStatus=helper.readableDatabase.rawQuery("SELECT imported,quarantined FROM migration_runs WHERE id='legacy-v1'",null).use{if(it.moveToFirst())DataMigrationStatus("Committed",it.getInt(0),it.getInt(1))else DataMigrationStatus("Committed")}
    private class MigrationDeferred:Exception()
}

/** Existing callers retain their API; the session owns the storage cutover and stale-write fence. */
class HistoryStore(context:Context,name:String="history-v1"){
    private val session=MediaDataMigration.session(context,name)
    fun entries()=session.entries()
    fun token(key:String)=session.token(key)
    fun token(media:MediaRef)=session.token(media)
    fun reference(media:MediaRef)=session.reference(media)
    fun update(media:MediaRef,token:HistoryToken,position:Long,duration:Long,completed:Boolean)=session.update(media,token,position,duration,completed)
    fun resumePosition(media:MediaRef,legacy:Long=0)=session.resume(media,legacy)
    fun remove(key:String)=session.remove(key)
    fun remove(media:MediaRef)=session.remove(media.key,media.mediaId)
    fun clear()=session.clear()
}
