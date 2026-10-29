package io.localplayer.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.UUID

internal data class LibraryFilm(val id:String,val workId:String,val media:MediaRef,val firstSeen:Long)
internal data class LibraryWork(val id:String,val title:String,val films:List<LibraryFilm>)
internal data class LibraryShelf(val id:String,val name:String,val count:Int)
internal data class LibraryMoment(val id:String,val filmId:String,val positionMs:Long,val note:String,val createdAt:Long)

/** Catalog identity is independent of paths and display names. Playback history keeps its existing writer. */
internal class FilmLibraryStore(context:Context,name:String="film-library-v1.db"):Closeable {
    private val app=context.applicationContext
    private val databaseName=name
    private val helper=MediaLibraryDatabase(context.applicationContext,name)
    private val lock=MediaDataMigration.session(app).gate
    private fun id()=UUID.randomUUID().toString()
    private fun binding(ref:MediaRef)=if(ref.localUri!=null)"device-local" else ref.location?:"legacy-unverified"
    private fun version(ref:MediaRef)=ref.sizeBytes?.let{"size:$it"}?:"unverified"
    private var closed=false
    private data class VisibilityKey(val key:String,val source:String?,val path:String?,val uri:String?,val location:String?,val parent:String?,val ancestors:List<String>)
    private fun visibilityKey(ref:MediaRef)=VisibilityKey(ref.key,ref.sourceId,ref.path,ref.localUri,ref.location,ref.localParentUri,ref.localAncestors)
    private data class VisibilityScope(val keys:Set<VisibilityKey>,val denied:Set<String>,val unrestricted:Boolean=false)
    private val allowed=ThreadLocal<VisibilityScope>()
    private fun visible(ref:MediaRef)=checkNotNull(allowed.get()).let{it.unrestricted||visibilityKey(ref) in it.keys}
    private fun snapshot(db:SQLiteDatabase):List<String> = buildList {
        db.rawQuery("SELECT id,media FROM films ORDER BY id",null).use{c->while(c.moveToNext()){add("film:"+c.getString(0));add(c.getString(1))}}
        db.rawQuery("SELECT id,current,media,film_id FROM locators ORDER BY id",null).use{c->while(c.moveToNext()){add("locator:"+c.getString(3)+":"+c.getString(0)+":"+c.getInt(1));add(c.getString(2))}}
    }
    /** Provider IPC uses detached values; only SQL and a policy fence run inside the shared gate. */
    private class CatalogChanged:java.io.IOException()
    private fun <T> access(extra:List<MediaRef> = emptyList(),action:()->T):T {
        if(allowed.get()!=null)return action()
        val policy=SealedMedia.version()
        if(!SealedMedia.hasProtection())return synchronized(lock){
            check(!closed)
            SealedMedia.withVersion(policy){
                allowed.set(VisibilityScope(emptySet(),emptySet(),unrestricted=true))
                try{action()}finally{allowed.remove()}
            }
        }
        repeat(3){
            val (before,policy)=synchronized(lock){check(!closed);snapshot(helper.readableDatabase) to SealedMedia.version()}
            val parsed=before.filterIndexed{index,_->index%2==1}.distinct().associateWith(MediaRef::parse)
            val refs=parsed.values+extra
            val visible=refs.distinctBy(::visibilityKey).filter{ref->SealedMedia.visible(ref)&&(ref.localUri?.let{!SealedMedia.protectsLocalUri(android.net.Uri.parse(it))}?:true)}.map(::visibilityKey).toSet()
            try{return synchronized(lock){
                check(!closed)
                if(snapshot(helper.readableDatabase)!=before)throw CatalogChanged()
                SealedMedia.withVersion(policy){
                    val denied=before.chunked(2).filter{visibilityKey(parsed.getValue(it[1])) !in visible}.map{it[0].split(':')[1]}.toSet()
                    allowed.set(VisibilityScope(visible,denied))
                    try{action()}finally{allowed.remove()}
                }
            }}catch(_:CatalogChanged){/* Discard the detached result before taking a fresh snapshot. */}
        }
        throw java.io.IOException("片匣记录已变化，请重试")
    }
    private fun visibleFilm(id:String,ref:MediaRef):Boolean=visible(ref)&&id !in checkNotNull(allowed.get()).denied
    private fun <T> transaction(extra:List<MediaRef> = emptyList(),action:(SQLiteDatabase)->T):T=access(extra){
        val db=helper.writableDatabase;db.beginTransaction()
        try{action(db).also{db.setTransactionSuccessful()}}finally{db.endTransaction()}
    }
    private fun put(db:SQLiteDatabase,table:String,vararg fields:Pair<String,Any>){
        val values=ContentValues();fields.forEach{(key,value)->when(value){is Long->values.put(key,value);is Int->values.put(key,value);else->values.put(key,value.toString())}}
        db.insertOrThrow(table,null,values)
    }
    private fun observe(db:SQLiteDatabase,ref:MediaRef,seen:Long,historical:Boolean=false,observedAt:Long=0):String? {
        if(!visible(ref))return null
        if(!historical&&!CatalogIdentity.isCurrent(db,ref))return null
        val identified=CatalogIdentity.register(db,ref,seen,historical,observedAt)
        return identified.mediaId?.takeIf{visibleFilm(it,identified)}
    }
    /** One transaction imports catalog observations only. Never clears or rewrites the legacy stores. */
    fun registerLegacy(history:List<HistoryEntry>,findings:List<StationFinding>):Int=transaction(history.map{it.media}+findings.map{it.media}){db->
        val restoredAt=db.rawQuery("SELECT value FROM metadata WHERE name='portable-restore'",null).use{if(it.moveToFirst())it.getString(0).toLongOrNull()?.takeIf{v->v>1_000_000_000_000L}?:Long.MAX_VALUE else 0L}
        val before=count(db,"films")
        findings.filter{restoredAt==0L||it.observedEpoch>=catalogEpoch()}.forEach{observe(db,it.media,it.firstSeen,historical=true,observedAt=it.lastObserved)}
        history.filter{restoredAt==0L}.forEach{observe(db,it.media,it.updatedAt,historical=true)}
        db.insertWithOnConflict("metadata",null,ContentValues().apply{put("name","legacy-catalog-v1");put("value","committed")},SQLiteDatabase.CONFLICT_REPLACE)
        count(db,"films")-before
    }
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}
    private fun readFilm(db:SQLiteDatabase,filmId:String):LibraryFilm?=db.rawQuery("SELECT id,work_id,media,first_seen FROM films WHERE id=?",arrayOf(filmId)).use{c->
        if(!c.moveToFirst())null else LibraryFilm(c.getString(0),c.getString(1),CatalogIdentity.current(db,c.getString(0),c.getString(2)),c.getLong(3)).takeIf{visibleFilm(it.id,it.media)}
    }
    fun film(filmId:String):LibraryFilm?=access{readFilm(helper.readableDatabase,filmId)}
    fun works(query:String="",shelfId:String?=null):List<LibraryWork> = access{
        val db=helper.readableDatabase
        val arguments=mutableListOf<String>()
        val base=if(shelfId==null)"FROM films f JOIN works w ON w.id=f.work_id" else "FROM shelf_films s JOIN films f ON f.id=s.film_id JOIN works w ON w.id=f.work_id"
        val conditions=mutableListOf<String>()
        if(shelfId!=null){conditions+="s.shelf_id=?";arguments+=shelfId}
        // SQLite's case folding covers ASCII; keep Kotlin matching for other cased alphabets.
        if(query.isNotBlank()&&query.none{it.code>127&&it.lowercaseChar()!=it.uppercaseChar()}){
            val unicode=if(query.any{it in 'a'..'z'||it in 'A'..'Z'})" OR w2.title GLOB '*[^ -~]*' OR f2.media GLOB '*[^ -~]*'" else ""
            conditions+="f.work_id IN (SELECT f2.work_id FROM films f2 JOIN works w2 ON w2.id=f2.work_id WHERE instr(lower(w2.title),lower(?))>0 OR instr(lower(f2.media),lower(?))>0$unicode)"
            arguments+=query;arguments+=org.json.JSONObject.quote(query).let{it.substring(1,it.length-1)}
        }
        val where=if(conditions.isEmpty())""else " WHERE "+conditions.joinToString(" AND ")
        val order=if(shelfId==null)"f.last_seen DESC,f.id"else "s.position,f.id"
        val sql="SELECT f.id,f.work_id,f.media,f.first_seen,w.title,l.media,l.id,f.revision_id $base LEFT JOIN locators l ON l.film_id=f.id AND l.current=1$where ORDER BY $order"
        val rows=mutableListOf<Pair<String,LibraryFilm>>()
        db.rawQuery(sql,arguments.toTypedArray()).use{c->while(c.moveToNext()){
            val ref=if(c.isNull(5))MediaRef.parse(c.getString(2)).copy(mediaId=c.getString(0))else MediaRef.parse(c.getString(5)).copy(mediaId=c.getString(0),locatorId=c.getString(6),contentRevisionId=c.getString(7))
            if(visibleFilm(c.getString(0),ref))rows+=c.getString(4) to LibraryFilm(c.getString(0),c.getString(1),ref,c.getLong(3))
        }}
        val result=if(shelfId!=null)rows.map{(title,film)->LibraryWork(film.workId,title,listOf(film))}
            else rows.groupBy{it.second.workId}.map{(workId,items)->LibraryWork(workId,items.first().first,items.map{it.second})}
        result.filter{work->query.isBlank()||work.title.contains(query,true)||work.films.any{it.media.title.contains(query,true)||it.media.sourceLabel.contains(query,true)}}
    }
    fun createShelf(name:String):String=transaction{db->
        val label=name.trim();require(label.isNotEmpty()&&label.length<=60){"放映单名称请填写 1–60 个字"}
        val shelfId=id();put(db,"shelves","id" to shelfId,"name" to label);shelfId
    }
    fun renameShelf(shelfId:String,name:String)=transaction{db->
        val label=name.trim();require(label.isNotEmpty()&&label.length<=60){"放映单名称请填写 1–60 个字"}
        db.update("shelves",ContentValues().apply{put("name",label)},"id=?",arrayOf(shelfId));Unit
    }
    fun deleteShelf(shelfId:String)=transaction{db->db.delete("shelves","id=?",arrayOf(shelfId));Unit}
    fun shelves():List<LibraryShelf> = access{
        val db=helper.readableDatabase;val rows=mutableListOf<Pair<String,String>>()
        db.rawQuery("SELECT id,name FROM shelves ORDER BY rowid",null).use{c->while(c.moveToNext())rows+=c.getString(0) to c.getString(1)}
        rows.map{(id,name)->LibraryShelf(id,name,works(shelfId=id).sumOf{it.films.size})}
    }
    fun addToShelf(shelfId:String,filmId:String)=transaction{db->
        require(readFilm(db,filmId)!=null){"这份影片当前不可访问"}
        val next=db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM shelf_films WHERE shelf_id=?",arrayOf(shelfId)).use{it.moveToFirst();it.getInt(0)}
        db.insertWithOnConflict("shelf_films",null,ContentValues().apply{put("shelf_id",shelfId);put("film_id",filmId);put("position",next)},SQLiteDatabase.CONFLICT_IGNORE);Unit
    }
    fun removeFromShelf(shelfId:String,filmId:String)=transaction{db->db.delete("shelf_films","shelf_id=? AND film_id=?",arrayOf(shelfId,filmId));Unit}
    fun moveInShelf(shelfId:String,filmId:String,direction:Int)=transaction{db->
        require(direction==-1||direction==1)
        val ids=mutableListOf<String>();db.rawQuery("SELECT film_id FROM shelf_films WHERE shelf_id=? ORDER BY position,film_id",arrayOf(shelfId)).use{c->while(c.moveToNext())ids+=c.getString(0)}
        val visiblePositions=ids.indices.filter{readFilm(db,ids[it])!=null}
        val index=visiblePositions.indexOf(ids.indexOf(filmId));val target=index+direction
        if(index>=0&&target in visiblePositions.indices){java.util.Collections.swap(ids,visiblePositions[index],visiblePositions[target]);ids.forEachIndexed{i,id->db.update("shelf_films",ContentValues().apply{put("position",i)},"shelf_id=? AND film_id=?",arrayOf(shelfId,id))}};Unit
    }
    fun renameWork(workId:String,title:String)=transaction{db->
        val label=title.trim();require(label.isNotEmpty()&&label.length<=200){"片名请填写 1–200 个字"}
        require(works().any{it.id==workId}){"这份档案当前不可访问"}
        db.update("works",ContentValues().apply{put("title",label)},"id=?",arrayOf(workId));Unit
    }
    /** Grouping changes the presentation only, never a file identity or playback checkpoint. */
    fun groupFilm(filmId:String,targetWorkId:String)=transaction{db->
        require(readFilm(db,filmId)!=null&&works().any{it.id==targetWorkId}){"这份档案当前不可访问"}
        db.update("films",ContentValues().apply{put("work_id",targetWorkId)},"id=?",arrayOf(filmId));Unit
    }
    fun rebindFilm(filmId:String,newLocation:MediaRef,confirmedSameVersion:Boolean,expectedToken:HistoryToken?=null):HistoryToken?=access(listOf(newLocation)){
        require(databaseName!=MEDIA_DATABASE||MediaDataMigration.session(app).isCommitted()){"旧片签尚未归位，请完成旧资料核对后再关联"}
        val source=newLocation.sourceId?.let{id->requireNotNull(SourceStore(app).load().firstOrNull{it.id==id}){"信源已移除，请重新登记后选择"}}
        val history=expectedToken?.let{require(databaseName==MEDIA_DATABASE);HistoryStore(app)}
        val original=requireNotNull(readFilm(helper.readableDatabase,filmId)){"这份档案当前不可访问"}
        if(history!=null)require(history.token(original.media)==expectedToken){"影片记录已变化，请重新选择后播放"}
        transaction{db->
        require(confirmedSameVersion){"请先确认这是同一版本"}
        require(visible(newLocation)){"新位置当前不可访问"}
        if(!isPending(filmId))require((original.media.bindingEpoch?:0L)==(newLocation.bindingEpoch?:0L)&&(original.media.location==null||original.media.location==newLocation.location)&&original.media.sourceId==newLocation.sourceId&&((original.media.localUri==null)==(newLocation.localUri==null))){"信源身份不同，请分别保留影片版本"}
        require(original.media.sizeBytes==null||newLocation.sizeBytes==null||original.media.sizeBytes==newLocation.sizeBytes){"影片尺寸不同，请分别保留影片版本"}
        if(source!=null){
            require((isPending(filmId)||SourceBindingEpochs.matches(app,source,original.media))&&SourceBindingEpochs.matches(app,source,newLocation)){"信源身份已变更，请分别保留影片版本"}
        }
        require(SourceBindingEpochs.acceptsWrite(app,newLocation,false)){"新位置授权已变化"}
        CatalogIdentity.rebind(db,filmId,newLocation)
        db.delete("metadata","name=?",arrayOf("restore-pending:$filmId"))
        PlaybackRegistry.invalidateMedia(filmId)
        }
        history?.token(requireNotNull(readFilm(helper.readableDatabase,filmId)).media)
    }
    fun separateFilm(filmId:String)=transaction{db->
        val film=requireNotNull(readFilm(db,filmId)){"这份档案当前不可访问"}
        val workId=id();put(db,"works","id" to workId,"title" to film.media.title)
        db.update("films",ContentValues().apply{put("work_id",workId)},"id=?",arrayOf(filmId));Unit
    }
    fun addMoment(ref:MediaRef,positionMs:Long):String=transaction(listOf(ref)){db->
        require(positionMs>=0){"播放位置尚不可用"}
        val filmId=observe(db,ref,System.currentTimeMillis())?:error("封存影片请留在封存柜中观看")
        val momentId=id();put(db,"moments","id" to momentId,"film_id" to filmId,"position_ms" to positionMs,"note" to "","created_at" to System.currentTimeMillis());momentId
    }
    fun moments(filmId:String):List<LibraryMoment> = access{
        val db=helper.readableDatabase;if(readFilm(db,filmId)==null)return@access emptyList()
        val rows=mutableListOf<LibraryMoment>();db.rawQuery("SELECT id,film_id,position_ms,note,created_at FROM moments WHERE film_id=? ORDER BY position_ms,id",arrayOf(filmId)).use{c->while(c.moveToNext())rows+=LibraryMoment(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getLong(4))};rows
    }
    fun writeMoment(momentId:String,note:String)=transaction{db->
        require(note.length<=2000){"札记最多 2000 个字"}
        val filmId=db.rawQuery("SELECT film_id FROM moments WHERE id=?",arrayOf(momentId)).use{if(it.moveToFirst())it.getString(0)else null}
        require(filmId!=null&&readFilm(db,filmId)!=null){"这条札记当前不可访问"}
        db.update("moments",ContentValues().apply{put("note",note.trim())},"id=?",arrayOf(momentId));Unit
    }
    fun deleteMoment(momentId:String)=transaction{db->
        val filmId=db.rawQuery("SELECT film_id FROM moments WHERE id=?",arrayOf(momentId)).use{if(it.moveToFirst())it.getString(0)else null}
        require(filmId!=null&&readFilm(db,filmId)!=null){"这条札记当前不可访问"}
        db.delete("moments","id=?",arrayOf(momentId));Unit
    }
    internal fun <T> withDatabase(action:(SQLiteDatabase,Set<String>)->T):T=access {
        action(helper.writableDatabase,works().flatMap{it.films}.map{it.id}.toSet())
    }
    fun catalogEpoch():Long=access{helper.readableDatabase.rawQuery("SELECT value FROM metadata WHERE name='catalog-epoch'",null).use{if(it.moveToFirst())it.getString(0).toLong()else 0L}}
    fun observeBatch(refs:List<MediaRef>,epoch:Long):Int=transaction(refs){db->
        check(catalogEpoch()==epoch){"片匣已更新，请重新收录"}
        refs.forEach{require(SourceBindingEpochs.acceptsWrite(app,it,false)){"信源授权已变化，请重新选择"}}
        val additions=refs.distinctBy{Triple(it.key,CatalogIdentity.binding(it),CatalogIdentity.version(it))}.count{CatalogIdentity.find(db,it)==null}
        require(count(db,"films")+additions<=50000){"片匣已达初版收录上限，请先整理资料"}
        refs.count{observe(db,it,System.currentTimeMillis())!=null}
    }
    fun isPending(filmId:String):Boolean=access{helper.readableDatabase.rawQuery("SELECT 1 FROM metadata WHERE name=?",arrayOf("restore-pending:$filmId")).use{it.moveToFirst()}}
    override fun close(){synchronized(lock){closed=true;helper.close()}}
}
