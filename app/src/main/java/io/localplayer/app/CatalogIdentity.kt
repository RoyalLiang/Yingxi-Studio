package io.localplayer.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

internal object CatalogIdentity {
    fun binding(ref:MediaRef)=if(ref.localUri!=null)"device-local"+(if((ref.bindingEpoch?:0L)==0L)"" else "|epoch=${ref.bindingEpoch}") else (ref.location?:"legacy-unverified")+"|epoch="+(ref.bindingEpoch?:0)
    fun version(ref:MediaRef)=ref.sizeBytes?.let{"size:$it"}?:"unverified"
    private fun id()=UUID.randomUUID().toString()
    fun register(db:SQLiteDatabase,ref:MediaRef,seen:Long,historical:Boolean=false,observedAt:Long=0):MediaRef {
        val locator=locator(db,ref)?:if(historical)retired(db,ref,observedAt)else null
        if(locator!=null){
            val revision=db.rawQuery("SELECT revision_id FROM films WHERE id=?",arrayOf(locator.second)).use{it.moveToFirst();it.getString(0)}
            val identified=ref.copy(mediaId=locator.second,locatorId=locator.first,contentRevisionId=revision)
            // Historical replays and retired aliases are read-only.
            if(historical||!locator.third)return identified
            // An old alias can resolve history, but cannot replace a user-confirmed current locator.
            db.update("locators",ContentValues().apply{put("media",identified.json())},"id=?",arrayOf(locator.first))
            if(locator.third)db.update("films",ContentValues().apply{put("media",identified.json());put("last_seen",seen)},"id=?",arrayOf(locator.second))
            return identified
        }
        val film=id();val work=id();val revision=id();val locatorId=id();val identified=ref.copy(mediaId=film,locatorId=locatorId,contentRevisionId=revision)
        db.execSQL("INSERT INTO works(id,title) VALUES(?,?)",arrayOf(work,ref.title))
        db.execSQL("INSERT INTO films(id,work_id,legacy_key,binding,version,media,first_seen,last_seen,revision_id) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(film,work,ref.key,binding(ref),version(ref),identified.json(),seen,seen,revision))
        db.execSQL("INSERT INTO locators(id,film_id,legacy_key,binding,version,media,current) VALUES(?,?,?,?,?,?,1)",arrayOf(locatorId,film,ref.key,binding(ref),version(ref),identified.json()))
        return identified
    }
    private fun locator(db:SQLiteDatabase,ref:MediaRef):Triple<String,String,Boolean>? {
        fun same(raw:String):Boolean {val old=MediaRef.parse(raw);return old.key==ref.key&&binding(old)==binding(ref)&&version(old)==version(ref)}
        if(ref.mediaId!=null){
            db.rawQuery("SELECT id,film_id,current,media FROM locators WHERE film_id=?"+(if(ref.locatorId!=null)" AND id=?"else""),if(ref.locatorId!=null)arrayOf(ref.mediaId,ref.locatorId)else arrayOf(ref.mediaId)).use{c->while(c.moveToNext())if(same(c.getString(3)))return Triple(c.getString(0),c.getString(1),c.getInt(2)==1)}
        }
        return db.rawQuery("SELECT id,film_id,current FROM locators WHERE legacy_key=? AND binding=? AND version=?",arrayOf(ref.key,binding(ref),version(ref))).use{c->if(c.moveToFirst())Triple(c.getString(0),c.getString(1),c.getInt(2)==1)else null}
    }
    private fun retired(db:SQLiteDatabase,ref:MediaRef,observedAt:Long):Triple<String,String,Boolean>? =
        db.rawQuery("SELECT l.id,l.film_id,l.media,m.value FROM locators l JOIN metadata m ON m.name='retired-locator:'||l.id WHERE l.current=0 AND l.legacy_key=? AND l.binding=? ORDER BY CAST(m.value AS INTEGER) DESC,l.id",arrayOf(ref.key,binding(ref))).use{c->
            while(c.moveToNext())if(observedAt<=c.getString(3).toLong()&&version(MediaRef.parse(c.getString(2)))==version(ref))return@use Triple(c.getString(0),c.getString(1),false)
            null
        }
    fun isCurrent(db:SQLiteDatabase,ref:MediaRef):Boolean {
        val found=locator(db,ref)?:return ref.mediaId==null&&ref.locatorId==null
        return found.third&&(ref.mediaId==null||found.second==ref.mediaId)&&(ref.locatorId==null||found.first==ref.locatorId)
    }
    fun find(db:SQLiteDatabase,ref:MediaRef):String?=locator(db,ref)?.second
    fun current(db:SQLiteDatabase,filmId:String,fallback:String):MediaRef {
        val row=db.rawQuery("SELECT l.id,l.media,f.revision_id FROM locators l JOIN films f ON f.id=l.film_id WHERE l.film_id=? AND l.current=1",arrayOf(filmId)).use{if(it.moveToFirst())Triple(it.getString(0),it.getString(1),it.getString(2))else null}
        return if(row==null)MediaRef.parse(fallback).copy(mediaId=filmId)else MediaRef.parse(row.second).copy(mediaId=filmId,locatorId=row.first,contentRevisionId=row.third)
    }
    fun rebind(db:SQLiteDatabase,filmId:String,ref:MediaRef){
        val previous=find(db,ref)
        if(previous!=null&&previous!=filmId){
            val busy=db.rawQuery("SELECT (SELECT COUNT(*) FROM moments WHERE film_id=?)+(SELECT COUNT(*) FROM shelf_films WHERE film_id=?)+(SELECT COUNT(*) FROM checkpoints WHERE film_id=?)+(SELECT COUNT(*) FROM metadata WHERE name=?)",arrayOf(previous,previous,previous,"legacy-position:$previous")).use{it.moveToFirst();it.getInt(0)>0}
            val edited=db.rawQuery("SELECT w.title,f.media,(SELECT COUNT(*) FROM films x WHERE x.work_id=f.work_id) FROM films f JOIN works w ON w.id=f.work_id WHERE f.id=?",arrayOf(previous)).use{it.moveToFirst();it.getString(0)!=MediaRef.parse(it.getString(1)).title||it.getInt(2)>1}
            require(!busy&&!edited){"新位置已有关联记录，请分别保留，不覆盖片架或札记"}
            db.delete("locators","film_id=?",arrayOf(previous));db.delete("films","id=?",arrayOf(previous))
        }
        val revision=db.rawQuery("SELECT revision_id FROM films WHERE id=?",arrayOf(filmId)).use{check(it.moveToFirst());it.getString(0)}
        val locatorId=db.rawQuery("SELECT id FROM locators WHERE film_id=? AND legacy_key=? AND binding=? AND version=?",arrayOf(filmId,ref.key,binding(ref),version(ref))).use{if(it.moveToFirst())it.getString(0)else id()}
        val linked=ref.copy(mediaId=filmId,locatorId=locatorId,contentRevisionId=revision)
        // Retain the old locator by ID for stale callbacks and privacy checks, but free its raw address.
        // A newly observed file at that address must receive a fresh identity.
        db.execSQL("INSERT OR REPLACE INTO metadata(name,value) SELECT 'retired-locator:'||id,? FROM locators WHERE film_id=? AND current=1",arrayOf(System.currentTimeMillis().toString(),filmId))
        db.execSQL("INSERT INTO history_revisions(legacy_key,revision) VALUES(?,1) ON CONFLICT(legacy_key) DO UPDATE SET revision=revision+1",arrayOf("media:$filmId"))
        db.execSQL("UPDATE locators SET version=version||'|retired:'||id,current=0 WHERE film_id=? AND current=1",arrayOf(filmId))
        db.execSQL("INSERT OR REPLACE INTO locators(id,film_id,legacy_key,binding,version,media,current) VALUES(?,?,?,?,?,?,1)",arrayOf(locatorId,filmId,ref.key,binding(ref),version(ref),linked.json()))
        db.update("films",ContentValues().apply{put("media",linked.json());put("legacy_key",ref.key);put("binding",binding(ref));put("version",version(ref));put("last_seen",System.currentTimeMillis())},"id=?",arrayOf(filmId))
    }
}
