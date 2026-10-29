package io.localplayer.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal data class ParcelPreview(val text:String,val digest:String,val currentDigest:String,val films:Int,val lists:Int,val moments:Int,val positions:Int,val replacing:Int)

/** Portable records contain no locations, grants, credentials or sealed entries. */
internal class LibraryParcel(private val context:Context,private val databaseName:String=MEDIA_DATABASE) {
    companion object {
        const val MAX_BYTES=16*1024*1024
        private val tables=listOf("works","films","shelves","shelf_films","moments","metadata","locators","checkpoints","history_revisions","preferences","migration_runs","migration_issues","source_bindings")
        private val preferenceKeys=mapOf(
            "library-appearance" to setOf("mode"),
            "cloud-playback" to setOf("workers","segment-cache","next-episode"),
            "playback-settings-v1" to setOf("remember-manual","speed","preferred-audio-language","audio-language","text-language","subtitles-disabled")
        )
        private fun array(values:List<JSONObject>)=JSONArray().also{a->values.forEach(a::put)}
        private fun rows(db:SQLiteDatabase,table:String):List<JSONObject> = db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use{c->buildList{
            while(c.moveToNext())add(JSONObject().also{o->c.columnNames.forEachIndexed{i,k->o.put(k,when(c.getType(i)){android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL;android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i);android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);else->c.getString(i)})}})
        }}
        private fun list(o:JSONObject,key:String,max:Int):List<JSONObject> {val a=o.getJSONArray(key);require(a.length()<=max){"行囊条目过多"};return (0 until a.length()).map{a.getJSONObject(it)}}
        private fun fields(o:JSONObject,keys:Set<String>){require(o.keys().asSequence().toSet()==keys){"行囊字段不兼容"}}
        private fun id(o:JSONObject,key:String):String=o.getString(key).also{require(it.matches(Regex("[a-zA-Z0-9_-]{1,128}"))){"行囊标识无效"}}
        private fun label(o:JSONObject,key:String,max:Int):String=o.getString(key).also{require(it.isNotBlank()&&it.length<=max&&'\u0000' !in it){"行囊文字无效"}}
        private fun nonnegative(o:JSONObject,key:String):Long {val value=o.get(key);require(value is Number&&value !is Double&&value !is Float){"行囊数值无效"};return (value as Number).toLong().also{require(it>=0){"行囊数值无效"}}}
        private fun unique(items:List<JSONObject>,key:String):Set<String> {val ids=items.map{id(it,key)};require(ids.size==ids.toSet().size){"行囊标识重复"};return ids.toSet()}
        private fun parse(text:String):JSONObject {
            require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"行囊超过 16 MB"}
            var quoted=false;var escaped=false;var depth=0
            text.forEach{c->if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}else when(c){'"'->quoted=true;'[','{'->{depth++;require(depth<=16){"行囊层级过深"}};']','}'->{depth--;require(depth>=0){"行囊结构损坏"}}}}
            require(!quoted&&depth==0){"行囊结构损坏"}
            val root=JSONObject(text)
            fields(root,setOf("format","version","works","films","lists","members","moments","positions","preferences"))
            require(root.getString("format")=="yingxi-library"&&root.get("version")==1){"无法读取这个版本的行囊"}
            val works=list(root,"works",50000);val films=list(root,"films",50000);val lists=list(root,"lists",10000)
            val workIds=unique(works,"id");val filmIds=unique(films,"id");val listIds=unique(lists,"id")
            works.forEach{fields(it,setOf("id","title"));label(it,"title",200)}
            films.forEach{fields(it,setOf("id","work","revision","title","size","firstSeen"));require(id(it,"work") in workIds);id(it,"revision");label(it,"title",1000);nonnegative(it,"firstSeen");if(!it.isNull("size"))nonnegative(it,"size")}
            lists.forEach{fields(it,setOf("id","name"));label(it,"name",60)}
            val members=list(root,"members",100000);val memberIds=HashSet<String>();val orders=HashSet<String>()
            members.forEach{fields(it,setOf("list","film","position"));val shelf=id(it,"list");val film=id(it,"film");require(shelf in listIds&&film in filmIds);val pos=nonnegative(it,"position");require(pos<100000&&memberIds.add("$shelf:$film")&&orders.add("$shelf:$pos")){"放映单条目或次序重复"}}
            val moments=list(root,"moments",100000);unique(moments,"id")
            moments.forEach{fields(it,setOf("id","film","position","note","created"));require(id(it,"film") in filmIds);require(nonnegative(it,"position")<=31_536_000_000L);nonnegative(it,"created");require(it.getString("note").length<=2000)}
            val positions=list(root,"positions",50000);unique(positions,"film")
            positions.forEach{fields(it,setOf("film","position","duration","updated","completed"));require(id(it,"film") in filmIds);require(nonnegative(it,"position")<=31_536_000_000L&&nonnegative(it,"duration")<=31_536_000_000L);nonnegative(it,"updated");require(it.get("completed") is Boolean)}
            val prefs=list(root,"preferences",32);val seen=HashSet<String>()
            prefs.forEach{fields(it,setOf("scope","name","value"));val scope=it.getString("scope");val name=it.getString("name");require(name in preferenceKeys[scope].orEmpty()&&seen.add("$scope:$name"));validatePreference(scope,name,it.getString("value"))}
            return root
        }
        private fun validatePreference(scope:String,name:String,raw:String){
            require(raw.length<=500)
            val encoded=JSONObject(raw);fields(encoded,setOf("type","value"))
            val payload=encoded.get("value")
            when(encoded.getString("type")){"int"->require(payload is Int);"boolean"->require(payload is Boolean);"float"->require(payload is Number&&payload.toDouble().isFinite());"string"->require(payload is String);else->error("行囊偏好类型无效")}
            val value=MigratingPreferences.decode(raw)
            when {
                scope=="library-appearance"->require(value is String&&value in LibraryAppearance.entries.map{it.name})
                scope=="cloud-playback"&&name=="workers"->require(value is Int&&value in 1..8)
                name in setOf("segment-cache","next-episode","remember-manual","subtitles-disabled")->require(value is Boolean)
                name=="speed"->require(value is Float&&value.isFinite()&&value in .5f..2f)
                else->require(value is String&&value.length<=40)
            }
        }
    }
    private fun fingerprint(db:SQLiteDatabase)=MediaDataMigration.digest(tables.joinToString("\n"){it+array(rows(db,it)).toString()})
    private fun portable(db:SQLiteDatabase,visible:Set<String>):JSONObject {
        val allFilms=rows(db,"films");val protectedWorks=allFilms.filter{it.getString("id") !in visible}.map{it.getString("work_id")}.toSet()
        val films=allFilms.filter{it.getString("id") in visible&&it.getString("work_id") !in protectedWorks}
        val ids=films.map{it.getString("id")}.toSet();val workIds=films.map{it.getString("work_id")}.toSet()
        val memberships=rows(db,"shelf_films");val privateLists=memberships.filter{it.getString("film_id") !in ids}.map{it.getString("shelf_id")}.toSet()
        val shelves=rows(db,"shelves").filter{it.getString("id") !in privateLists};val shelfIds=shelves.map{it.getString("id")}.toSet()
        return JSONObject().put("format","yingxi-library").put("version",1)
            .put("works",array(rows(db,"works").filter{it.getString("id") in workIds}))
            .put("films",array(films.map{val ref=MediaRef.parse(it.getString("media"));JSONObject().put("id",it.getString("id")).put("work",it.getString("work_id")).put("revision",it.getString("revision_id")).put("title",ref.title).put("size",ref.sizeBytes?:JSONObject.NULL).put("firstSeen",it.getLong("first_seen"))}))
            .put("lists",array(shelves))
            .put("members",array(memberships.filter{it.getString("film_id") in ids&&it.getString("shelf_id") in shelfIds}.map{JSONObject().put("list",it.getString("shelf_id")).put("film",it.getString("film_id")).put("position",it.getLong("position"))}))
            .put("moments",array(rows(db,"moments").filter{it.getString("film_id") in ids}.map{JSONObject().put("id",it.getString("id")).put("film",it.getString("film_id")).put("position",it.getLong("position_ms")).put("note",it.getString("note")).put("created",it.getLong("created_at"))}))
            .put("positions",array(rows(db,"checkpoints").filter{it.getString("film_id") in ids}.map{JSONObject().put("film",it.getString("film_id")).put("position",it.getLong("position")).put("duration",it.getLong("duration")).put("updated",it.getLong("updated")).put("completed",it.getInt("completed")!=0)}))
            .put("preferences",array(rows(db,"preferences").filter{it.getString("name") in preferenceKeys[it.getString("scope")].orEmpty()}.filter{runCatching{validatePreference(it.getString("scope"),it.getString("name"),it.getString("value"))}.isSuccess}))
    }
    fun export():String=FilmLibraryStore(context,databaseName).use{store->store.withDatabase{db,visible->
        check(MediaDataMigration.session(context).isCommitted()||databaseName!=MEDIA_DATABASE){"请先完成旧片签归位"}
        portable(db,visible).toString().also{parse(it)}
    }}
    fun preview(text:String):ParcelPreview {
        val root=parse(text)
        return FilmLibraryStore(context,databaseName).use{it.withDatabase{db,visible->ParcelPreview(text,MediaDataMigration.digest(text),fingerprint(db),root.getJSONArray("films").length(),root.getJSONArray("lists").length(),root.getJSONArray("moments").length(),root.getJSONArray("positions").length(),visible.size)}}
    }
    private val recovery get()=File(context.noBackupFilesDir,"library-parcel/recovery.json")
    fun hasRecovery()=recovery.isFile
    private fun protectedDigest(db:SQLiteDatabase,ids:Set<String>):String {
        val workIds=rows(db,"films").filter{it.getString("id") in ids}.map{it.getString("work_id")}.toSet()
        val listIds=rows(db,"shelf_films").filter{it.getString("film_id") in ids}.map{it.getString("shelf_id")}.toSet()
        val content=JSONObject()
        listOf("films","works","shelves","shelf_films","moments","checkpoints","locators").forEach{table->
            content.put(table,array(rows(db,table).filter{row->when(table){"films"->row.getString("id") in ids;"works"->row.getString("id") in workIds;"shelves"->row.getString("id") in listIds;else->row.getString("film_id") in ids}}))
        }
        return MediaDataMigration.digest(content.toString())
    }
    private fun protectedIds(db:SQLiteDatabase,visible:Set<String>):Set<String>{
        val films=rows(db,"films");val works=films.filter{it.getString("id") !in visible}.map{it.getString("work_id")}.toSet()
        return films.filter{it.getString("id") !in visible||it.getString("work_id") in works}.map{it.getString("id")}.toSet()
    }
    private fun snapshot(db:SQLiteDatabase,protected:Set<String>){
        val content=JSONObject().put("protectedDigest",protectedDigest(db,protected));tables.forEach{content.put(it,array(rows(db,it)))}
        val bytes=content.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=64*1024*1024){"本机恢复点过大，未更改资料"}
        val target=recovery;check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs())
        val temporary=File(target.parentFile,"recovery.tmp")
        FileOutputStream(temporary).use{it.write(bytes);it.fd.sync()}
        check(temporary.renameTo(target)){"无法保存本机恢复点，未更改资料"}
    }
    private fun put(db:SQLiteDatabase,table:String,row:JSONObject){
        val values=android.content.ContentValues();row.keys().forEach{k->val v=row.get(k);when(v){JSONObject.NULL->values.putNull(k);is Number->values.put(k,v.toLong());else->values.put(k,v.toString())}}
        db.insertOrThrow(table,null,values)
    }
    private fun atomic(db:SQLiteDatabase,action:()->Unit){db.beginTransaction();try{action();db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun restore(preview:ParcelPreview){
        val root=parse(preview.text);require(MediaDataMigration.digest(preview.text)==preview.digest)
        MediaDataMigration.session(context).requireIdle()
        FilmLibraryStore(context,databaseName).use{store->store.withDatabase{db,visible->
            MediaDataMigration.session(context).requireIdle()
            check(databaseName!=MEDIA_DATABASE||MediaDataMigration.session(context).isCommitted())
            require(fingerprint(db)==preview.currentDigest){"资料已变化，请重新预览行囊"}
            val current=rows(db,"films");val protectedWorks=current.filter{it.getString("id") !in visible}.map{it.getString("work_id")}.toSet()
            val replace=current.filter{it.getString("id") in visible&&it.getString("work_id") !in protectedWorks}.map{it.getString("id")}.toSet()
            val protected=current.map{it.getString("id")}.toSet()-replace
            val privateListIds=rows(db,"shelf_films").filter{it.getString("film_id") in protected}.map{it.getString("shelf_id")}.toSet()
            val incoming=list(root,"films",50000);val existing= current.associateBy{it.getString("id")}
            require(incoming.none{it.getString("id") in protected}&&list(root,"works",50000).none{it.getString("id") in protectedWorks}&&list(root,"lists",10000).none{it.getString("id") in privateListIds}){"行囊与本机保留资料冲突，未更改资料"}
            val oldRefs=replace.associateWith{id->CatalogIdentity.current(db,id,existing.getValue(id).getString("media")).takeIf{CatalogIdentity.isCurrent(db,it)}}
            val notifications=rows(db,"preferences").filter{it.getString("name") in preferenceKeys[it.getString("scope")].orEmpty()}.groupBy{it.getString("scope")}.mapValues{it.value.map{v->v.getString("name")}.toSet()}
            snapshot(db,protected)
            atomic(db){
                replace.forEach{id->listOf("shelf_films","moments","checkpoints","locators").forEach{db.delete(it,"film_id=?",arrayOf(id))};db.delete("metadata","name=? OR name=?",arrayOf("restore-pending:$id","legacy-position:$id"));db.delete("films","id=?",arrayOf(id))}
                rows(db,"shelves").filter{it.getString("id") !in privateListIds}.forEach{db.delete("shelves","id=?",arrayOf(it.getString("id")))}
                db.execSQL("DELETE FROM works WHERE id NOT IN (SELECT work_id FROM films)")
                list(root,"works",50000).forEach{put(db,"works",it)}
                incoming.forEach{film->
                    val id=film.getString("id");val revision=film.getString("revision");val old=oldRefs[id]
                    val usable=old?.takeIf{it.contentRevisionId==revision&&it.locatorId!=null&&it.sizeBytes==(if(film.isNull("size"))null else film.getLong("size"))&&SourceBindingEpochs.acceptsWrite(context,it,false)}
                    val ref=usable?:MediaRef("restored:$id",film.getString("title"),"待接回",sizeBytes=if(film.isNull("size"))null else film.getLong("size"),mediaId=id,contentRevisionId=revision)
                    put(db,"films",JSONObject().put("id",id).put("work_id",film.getString("work")).put("legacy_key",ref.key).put("binding",CatalogIdentity.binding(ref)).put("version",CatalogIdentity.version(ref)).put("media",ref.json()).put("first_seen",film.getLong("firstSeen")).put("last_seen",film.getLong("firstSeen")).put("revision_id",revision))
                    if(usable!=null)put(db,"locators",JSONObject().put("id",usable.locatorId).put("film_id",id).put("legacy_key",ref.key).put("binding",CatalogIdentity.binding(ref)).put("version",CatalogIdentity.version(ref)).put("media",ref.json()).put("current",1))
                    else db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES(?,'1')",arrayOf("restore-pending:$id"))
                }
                list(root,"lists",10000).forEach{put(db,"shelves",it)}
                list(root,"members",100000).forEach{put(db,"shelf_films",JSONObject().put("shelf_id",it.getString("list")).put("film_id",it.getString("film")).put("position",it.getLong("position")))}
                list(root,"moments",100000).forEach{put(db,"moments",JSONObject().put("id",it.getString("id")).put("film_id",it.getString("film")).put("position_ms",it.getLong("position")).put("note",it.getString("note")).put("created_at",it.getLong("created")))}
                list(root,"positions",50000).forEach{put(db,"checkpoints",JSONObject().put("film_id",it.getString("film")).put("position",it.getLong("position")).put("duration",it.getLong("duration")).put("updated",it.getLong("updated")).put("completed",if(it.getBoolean("completed"))1 else 0))}
                preferenceKeys.forEach{(scope,keys)->keys.forEach{db.delete("preferences","scope=? AND name=?",arrayOf(scope,it))}}
                list(root,"preferences",32).forEach{put(db,"preferences",it)}
                db.execSQL("UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE name='history-clear'")
                db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('portable-restore',?)",arrayOf(System.currentTimeMillis().toString()))
                db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('catalog-epoch',?)",arrayOf((store.catalogEpoch()+1).toString()))
            }
            (notifications.keys+preferenceKeys.keys).forEach{scope->MigratingPreferences.notifyRestored(context,scope,notifications[scope].orEmpty()+preferenceKeys[scope].orEmpty())}
        }}
    }
    fun recover(){
        MediaDataMigration.session(context).requireIdle()
        val content=JSONObject(recovery.readText());require(content.keys().asSequence().toSet()==tables.toSet()+"protectedDigest")
        FilmLibraryStore(context,databaseName).use{store->store.withDatabase{db,visible->
            MediaDataMigration.session(context).requireIdle();val epoch=store.catalogEpoch()+1
            require(protectedDigest(db,protectedIds(db,visible))==content.getString("protectedDigest")){"封存关联资料已变化，未更改资料"}
            val clear=db.rawQuery("SELECT value FROM metadata WHERE name='history-clear'",null).use{if(it.moveToFirst())it.getString(0).toLong()else 0L}
            val retainedPreferences=rows(db,"preferences").filter{it.getString("name") !in preferenceKeys[it.getString("scope")].orEmpty()}
            val retained= listOf("source_bindings","migration_runs","migration_issues").associateWith{rows(db,it)}
            atomic(db){
                listOf("shelf_films","moments","checkpoints","locators","films","works","shelves","metadata","history_revisions","preferences","migration_runs","migration_issues","source_bindings").forEach{db.delete(it,null,null)}
                tables.forEach{table->val a=content.getJSONArray(table);(0 until a.length()).forEach{put(db,table,a.getJSONObject(it))}}
                db.execSQL("UPDATE metadata SET value=MAX(CAST(value AS INTEGER),?)+1 WHERE name='history-clear'",arrayOf(clear))
                db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('catalog-epoch',?)",arrayOf(epoch.toString()))
                rows(db,"preferences").filter{it.getString("name") !in preferenceKeys[it.getString("scope")].orEmpty()}.forEach{db.delete("preferences","scope=? AND name=?",arrayOf(it.getString("scope"),it.getString("name")))}
                retainedPreferences.forEach{put(db,"preferences",it)}
                retained.forEach{(table,records)->db.delete(table,null,null);records.forEach{put(db,table,it)}}
                db.execSQL("INSERT OR REPLACE INTO metadata(name,value) VALUES('portable-restore',?)",arrayOf(System.currentTimeMillis().toString()))
            }
            preferenceKeys.forEach{(scope,keys)->MigratingPreferences.notifyRestored(context,scope,keys)}
        }}
    }
}
