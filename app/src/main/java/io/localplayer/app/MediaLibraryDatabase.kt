package io.localplayer.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal const val MEDIA_DATABASE="film-library-v1.db"
internal class MediaLibraryDatabase(context:Context,name:String=MEDIA_DATABASE):SQLiteOpenHelper(context,name,null,2){
    override fun onConfigure(db:SQLiteDatabase){db.setForeignKeyConstraintsEnabled(true)}
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE works(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE films(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES works(id),legacy_key TEXT NOT NULL,binding TEXT NOT NULL,version TEXT NOT NULL,media TEXT NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,revision_id TEXT NOT NULL,UNIQUE(legacy_key,binding,version))")
        db.execSQL("CREATE INDEX film_work ON films(work_id)")
        db.execSQL("CREATE TABLE shelves(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE shelf_films(shelf_id TEXT NOT NULL REFERENCES shelves(id) ON DELETE CASCADE,film_id TEXT NOT NULL REFERENCES films(id),position INTEGER NOT NULL,PRIMARY KEY(shelf_id,film_id))")
        db.execSQL("CREATE TABLE moments(id TEXT PRIMARY KEY,film_id TEXT NOT NULL REFERENCES films(id),position_ms INTEGER NOT NULL CHECK(position_ms>=0),note TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
        createIdentityAndMigration(db)
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        check(oldVersion==1&&newVersion==2){"片架数据需要明确的升级路径，原数据已保留"}
        db.execSQL("ALTER TABLE films ADD COLUMN revision_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE films SET revision_id=lower(hex(randomblob(16)))")
        createIdentityAndMigration(db)
        db.execSQL("INSERT INTO locators(id,film_id,legacy_key,binding,version,media,current) SELECT lower(hex(randomblob(16))),id,legacy_key,CASE WHEN binding='device-local' THEN binding ELSE binding||'|epoch=0' END,version,media,1 FROM films")
    }
    override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){error("片架数据来自较新版本，原数据已保留")}
    private fun createIdentityAndMigration(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE locators(id TEXT PRIMARY KEY,film_id TEXT NOT NULL REFERENCES films(id),legacy_key TEXT NOT NULL,binding TEXT NOT NULL,version TEXT NOT NULL,media TEXT NOT NULL,current INTEGER NOT NULL,UNIQUE(legacy_key,binding,version))")
        db.execSQL("CREATE INDEX locator_film ON locators(film_id)")
        db.execSQL("CREATE TABLE checkpoints(film_id TEXT PRIMARY KEY REFERENCES films(id),position INTEGER NOT NULL,duration INTEGER NOT NULL,updated INTEGER NOT NULL,completed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history_revisions(legacy_key TEXT PRIMARY KEY,revision INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE preferences(scope TEXT NOT NULL,name TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(scope,name))")
        db.execSQL("CREATE TABLE migration_runs(id TEXT PRIMARY KEY,digest TEXT NOT NULL,state TEXT NOT NULL,imported INTEGER NOT NULL,quarantined INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE migration_issues(id INTEGER PRIMARY KEY,scope TEXT NOT NULL,record_key TEXT NOT NULL,reason TEXT NOT NULL,raw TEXT)")
        db.execSQL("CREATE TABLE source_bindings(source_id TEXT PRIMARY KEY,digest TEXT NOT NULL,epoch INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO metadata(name,value) VALUES('history-clear','0')")
    }
}
