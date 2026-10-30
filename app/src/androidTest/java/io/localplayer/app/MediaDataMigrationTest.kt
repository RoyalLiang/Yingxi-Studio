package io.localplayer.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

internal class IsolatedMediaContext(base:Context,private val prefix:String="migration-${UUID.randomUUID()}-"):ContextWrapper(base){
    private val names=mutableSetOf<String>()
    override fun getApplicationContext():Context=this
    override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {names+=name;return baseContext.getSharedPreferences(prefix+name,mode)}
    override fun getDatabasePath(name:String)=baseContext.getDatabasePath(prefix+name)
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase=baseContext.openOrCreateDatabase(prefix+name,mode,factory)
    override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase=baseContext.openOrCreateDatabase(prefix+name,mode,factory,errorHandler)
    override fun getNoBackupFilesDir()=File(baseContext.noBackupFilesDir,prefix).apply{mkdirs()}
    fun dispose(){MediaDataMigration.session(this).close();baseContext.deleteDatabase(prefix+MEDIA_DATABASE);names.forEach{baseContext.deleteSharedPreferences(prefix+it)};noBackupFilesDir.deleteRecursively()}
}
class MediaDataMigrationTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun media(key:String="one",size:Long=100)=MediaRef(key,"旧片.mp4","测试片箱",sourceId="test-source",path="$key.mp4",sizeBytes=size,location="test-binding")
    private fun test(action:(IsolatedMediaContext,MediaDataSession)->Unit){val context=IsolatedMediaContext(base);try{action(context,MediaDataMigration.session(context))}finally{context.dispose()}}
    @Test fun cutoverPreservesValuesAndLeavesLegacyBytesUntouched(){test{context,session->
        val authority=io.localplayer.core.Source(id="test-source",label="测试片箱",protocol=io.localplayer.core.Protocol.SFTP,host="fixture.invalid",root="/");SourceStore(context).save(authority,"",false)
        val ref=media().copy(location=mediaLocation(authority));val legacy=LegacyHistoryStore(context);legacy.update(ref,legacy.token(ref.key),42000,120000,false)
        val facade=HistoryStore(context);val oldToken=facade.token(ref.key)
        val before=context.getSharedPreferences("history-v1",0).all.toMap()
        val result=session.migrate();assertEquals("Committed",result.state)
        assertEquals(before,context.getSharedPreferences("history-v1",0).all)
        val entry=facade.entries().single();assertEquals(42000L,entry.positionMs);assertEquals(120000L,entry.durationMs);assertNotNull(entry.media.mediaId);assertNotNull(entry.media.contentRevisionId)
        assertFalse(facade.update(ref,oldToken,90000,120000,false))
        assertTrue(facade.update(entry.media,facade.token(entry.media),43000,120000,false));assertEquals(43000L,facade.resumePosition(ref))
        assertEquals(before,context.getSharedPreferences("history-v1",0).all)
        assertEquals(result,session.migrate())
    }}
    @Test fun deletedKeysAndClearEpochCannotReviveLegacyPositions(){test{context,session->
        val ref=media();val legacy=LegacyHistoryStore(context);legacy.update(ref,legacy.token(ref.key),100,1000,false);legacy.remove(ref.key)
        val hash=MediaDataMigration.digest(ref.key)
        context.getSharedPreferences("sources-v1",0).edit().putLong("position-$hash",900).commit()
        val finding=JSONArray().put(JSONObject().put("media",ref.json()).put("seen",1L))
        context.getSharedPreferences("station-inventory-v1",0).edit().putString("items",finding.toString()).commit()
        assertEquals("Committed",session.migrate().state)
        val facade=HistoryStore(context);assertEquals(1L,facade.token(ref.key).itemRevision);assertEquals(0L,facade.resumePosition(ref,900))
        val current=facade.token(ref);facade.clear();assertFalse(facade.update(ref,current,200,1000,false));assertEquals(0L,facade.resumePosition(ref,900))
    }}
    @Test fun faultsRollbackAndRetryWithoutDuplicatingIdentity(){test{context,session->
        val ref=media();val legacy=LegacyHistoryStore(context);legacy.update(ref,legacy.token(ref.key),50,0,false)
        val before=context.getSharedPreferences("history-v1",0).all.toMap()
        assertEquals("Failed",session.migrate{throw java.io.IOException("injected before commit")}.state)
        assertFalse(session.isCommitted());assertEquals(before,context.getSharedPreferences("history-v1",0).all);assertEquals(50L,HistoryStore(context).entries().single().positionMs)
        assertEquals("Committed",session.migrate().state)
        val id=HistoryStore(context).entries().single().media.mediaId
        assertEquals("Committed",session.migrate().state);assertEquals(id,HistoryStore(context).entries().single().media.mediaId)
    }}
    @Test fun newLegacyWriteDuringImportDefersCutover(){test{context,session->
        val facade=HistoryStore(context);val ref=media();facade.update(ref,facade.token(ref.key),100,1000,false)
        assertEquals("Deferred",session.migrate{facade.update(ref,facade.token(ref.key),200,1000,false)}.state)
        assertFalse(session.isCommitted());assertEquals(200L,facade.entries().single().positionMs)
        assertEquals("Committed",session.migrate().state);assertEquals(200L,facade.entries().single().positionMs)
    }}
    @Test fun pausedServiceAndPendingTargetsBothHoldTheMigrationLease(){test{context,session->
        session.playbackStarted();assertEquals("Deferred",session.migrate().state)
        val facade=HistoryStore(context);val ref=media();assertTrue(facade.update(ref,facade.token(ref.key),300,1000,false))
        session.playbackStopped();session.targetCreated("queued-but-not-started");assertEquals("Deferred",session.migrate().state)
        session.targetReleased("queued-but-not-started");assertEquals("Committed",session.migrate().state);assertEquals(300L,facade.entries().single().positionMs)
    }}
    @Test fun preferenceAdaptersSwitchTogetherAndKeepTypesAndRemovalSemantics(){test{context,session->
        val settings=PlaybackPreferences(context);settings.speed=1.25f;settings.rememberedAudioLanguage="ja";settings.audioLanguage="en";settings.textLanguage="zh";settings.subtitlesDisabled=true
        val prefs=MigratingPreferences.open(context,"source-presentation-v1");prefs.edit().putStringSet("pinned",setOf("one","two")).apply()
        val reading=MigratingPreferences.open(context,"station-reading-v1");reading.edit().putString("chapter","chapter-2").putString("letter:chapter-1","first").putString("letter:chapter-2","reply").putString("progress","{\"fixture\":true}").apply()
        val original=context.getSharedPreferences("playback-settings-v1",0).all.toMap()
        assertEquals("Committed",session.migrate().state)
        assertEquals(1.25f,settings.speed);assertEquals("en",settings.audioLanguage);assertNull(settings.rememberedAudioLanguage);assertTrue(settings.subtitlesDisabled)
        assertEquals(setOf("one","two"),prefs.getStringSet("pinned",emptySet()));assertEquals("reply",reading.getString("letter:chapter-2",null));assertEquals("{\"fixture\":true}",reading.getString("progress",null))
        settings.speed=1.5f;reading.edit().remove("letter:chapter-1").apply();assertFalse(reading.contains("letter:chapter-1"));assertEquals(original,context.getSharedPreferences("playback-settings-v1",0).all)
    }}
    @Test fun corruptRecordIsQuarantinedAndOriginalJsonIsRetained(){test{context,session->
        val ref=media();val legacy=LegacyHistoryStore(context);legacy.update(ref,legacy.token(ref.key),100,1000,true)
        val prefs=context.getSharedPreferences("history-v1",0);val data=JSONArray(prefs.getString("entries","[]"));data.put(JSONObject().put("media","invalid"));prefs.edit().putString("entries",data.toString()).commit()
        val result=session.migrate();assertEquals("Committed",result.state);assertEquals(1,result.quarantined);assertEquals(data.toString(),prefs.getString("entries",null));assertEquals(0L,HistoryStore(context).resumePosition(ref));assertTrue(HistoryStore(context).entries().single().completed)
    }}
    @Test fun malformedRootFailsWithoutAnEmptySuccessfulMigration(){test{context,session->
        val prefs=context.getSharedPreferences("history-v1",0);prefs.edit().putString("entries","broken fixture").commit()
        assertEquals("Failed",session.migrate().state);assertFalse(session.isCommitted());assertEquals("broken fixture",prefs.getString("entries",null))
        prefs.edit().putString("entries","[]").commit();assertEquals("Committed",session.migrate().state)
    }}
    @Test fun authorityEpochNeverReusesAnOldBindingAfterConfigurationComesBack(){test{context,session->
        val source=io.localplayer.core.Source(id="authority",label="原名",protocol=io.localplayer.core.Protocol.SFTP,host="first.invalid",root="/")
        val sources=SourceStore(context);sources.save(source,"",false)
        val first=SourceBindingEpochs.current(context,source)
        sources.save(source.copy(label="新名"),"",false);assertEquals(first,SourceBindingEpochs.current(context,source))
        val other=source.copy(host="second.invalid");sources.save(other,"",false);sources.save(source,"",false)
        assertTrue(SourceBindingEpochs.current(context,source)>first)
        assertFalse(SourceBindingEpochs.matches(context,source,media().copy(sourceId=source.id,location=mediaLocation(source),bindingEpoch=first)))
        val returned=SourceBindingEpochs.current(context,source);sources.delete(source.id);sources.save(source,"",false);assertTrue(SourceBindingEpochs.current(context,source)>returned)
    }}
    @Test fun wrongRootTypeFailsInsteadOfCommittingAnEmptyLibrary(){
        test{context,session->context.getSharedPreferences("history-v1",0).edit().putInt("entries",17).commit();assertEquals("Failed",session.migrate().state);assertFalse(session.isCommitted())}
        test{context,session->context.getSharedPreferences("station-inventory-v1",0).edit().putInt("items",17).commit();assertEquals("Failed",session.migrate().state);assertFalse(session.isCommitted())}
    }
    @Test fun confirmedRelinkKeepsIdentityCheckpointShelfAndMomentWithTwoSources(){test{context,session->
        val source=io.localplayer.core.Source(id="source",label="片箱",protocol=io.localplayer.core.Protocol.SFTP,host="fixture.invalid",root="/")
        SourceStore(context).save(source,"",false);SourceStore(context).save(source.copy(id="second",host="other.invalid"),"",false)
        val old=media("source:old").copy(sourceId=source.id,path="old.mp4",location=mediaLocation(source),bindingEpoch=0)
        val legacy=LegacyHistoryStore(context);legacy.update(old,legacy.token(old.key),42000,120000,false)
        assertEquals("Committed",session.migrate().state)
        val history=HistoryStore(context);val original=history.entries().single().media;val token=history.token(original)
        FilmLibraryStore(context).use{catalog->
            val film=catalog.film(original.mediaId!!)!!;val shelf=catalog.createShelf("旧片架");catalog.addToShelf(shelf,film.id);catalog.renameWork(film.workId,"手写片名")
            val moment=catalog.addMoment(original,12345);catalog.writeMoment(moment,"纸背记号")
            val moved=old.copy(key="source:new",path="new.mp4",title="renamed.mp4")
            catalog.registerLegacy(listOf(HistoryEntry(moved,0,0,1,false)),emptyList())
            catalog.rebindFilm(film.id,moved,true)
            val after=catalog.film(film.id)!!;assertEquals(original.contentRevisionId,after.media.contentRevisionId);assertEquals("new.mp4",after.media.path)
            assertEquals("手写片名",catalog.works(shelfId=shelf).single().title);assertEquals(film.id,catalog.works(shelfId=shelf).single().films.single().id)
            assertEquals("纸背记号",catalog.moments(film.id).single().note);assertEquals(42000L,history.resumePosition(after.media));assertNotEquals(token,history.token(after.media))
            history.remove(after.media);assertFalse(history.update(original,token,50000,120000,false));assertEquals(0L,history.resumePosition(after.media))
        }
    }}
    @Test fun legacyUnknownBindingRequiresConfirmationAndRetainsOriginalIdentity(){test{context,session->
        val source=io.localplayer.core.Source(id="legacy",label="旧片箱",protocol=io.localplayer.core.Protocol.SFTP,host="legacy.invalid",root="/");SourceStore(context).save(source,"",false)
        val old=MediaRef("legacy:old.mp4","old.mp4","旧片箱",source.id,"old.mp4")
        val legacy=LegacyHistoryStore(context);legacy.update(old,legacy.token(old.key),42000,120000,false);assertEquals("Committed",session.migrate().state)
        val h=HistoryStore(context);val original=h.entries().single().media
        FilmLibraryStore(context).use{catalog->
            val resolved=old.copy(location=mediaLocation(source),sizeBytes=100,bindingEpoch=0)
            assertTrue(runCatching{catalog.rebindFilm(original.mediaId!!,resolved,false)}.isFailure)
            catalog.rebindFilm(original.mediaId!!,resolved,true)
            assertEquals(original.mediaId,h.entries().single().media.mediaId);assertEquals(42000L,h.resumePosition(resolved))
            assertEquals(h.token(original),h.token(resolved))
        }
    }}
    @Test fun quoteAliasesKeepTheExactTextAndStableIdentity(){test{context,session->
        val world=StationWorld.load(context)
        // Freeze Alpha-written indices; new Beta quotes are selected by their stable IDs.
        val legacyCounts=mapOf("axi" to 14,"chideng" to 17,"mitu" to 6)
        world.people.forEach{person->person.quotes.take(legacyCounts.getValue(person.id)).forEachIndexed{index,text->
            assertEquals(text.id,LegacyQuoteAliases.resolve("quote:${person.id}:$index"))
        }}
        legacyCounts.forEach{(person,count)->assertNull(LegacyQuoteAliases.resolve("quote:$person:$count"))}
        val quote=world.people.first().quotes.first();val old="quote:${world.people.first().id}:0";val now=System.currentTimeMillis()
        context.getSharedPreferences("station-experience-v1",0).edit().putString("diary-line",old).putLong("diary-at",now).commit()
        assertEquals(quote.zh,StationPreferences(context).diary(now).text.zh)
        assertEquals("Committed",session.migrate().state);assertEquals(quote.id,StationPreferences(context).diary(now).id)
    }}

    @Test fun earlyFilmLibrarySchemaUpgradesWithoutChangingUserIds(){test{context,session->
        val ref=media()
        val db=context.openOrCreateDatabase(MEDIA_DATABASE,0,null)
        listOf("CREATE TABLE works(id TEXT PRIMARY KEY,title TEXT NOT NULL)","CREATE TABLE films(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES works(id),legacy_key TEXT NOT NULL,binding TEXT NOT NULL,version TEXT NOT NULL,media TEXT NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,UNIQUE(legacy_key,binding,version))","CREATE TABLE shelves(id TEXT PRIMARY KEY,name TEXT NOT NULL)","CREATE TABLE shelf_films(shelf_id TEXT NOT NULL REFERENCES shelves(id),film_id TEXT NOT NULL REFERENCES films(id),position INTEGER NOT NULL,PRIMARY KEY(shelf_id,film_id))","CREATE TABLE moments(id TEXT PRIMARY KEY,film_id TEXT NOT NULL REFERENCES films(id),position_ms INTEGER NOT NULL,note TEXT NOT NULL,created_at INTEGER NOT NULL)","CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)").forEach{db.execSQL(it)}
        db.execSQL("INSERT INTO works VALUES('work','手写片名')");db.execSQL("INSERT INTO films VALUES('film','work',?,'test-binding','size:100',?,1,1)",arrayOf(ref.key,ref.json()));db.execSQL("INSERT INTO shelves VALUES('shelf','旧片架')");db.execSQL("INSERT INTO shelf_films VALUES('shelf','film',0)");db.execSQL("INSERT INTO moments VALUES('moment','film',12345,'旧札记',1)");db.version=1;db.close()
        val legacy=LegacyHistoryStore(context);legacy.update(ref,legacy.token(ref.key),42000,120000,false)
        assertEquals("Committed",session.migrate().state)
        FilmLibraryStore(context).use{catalog->assertEquals("film",catalog.works(shelfId="shelf").single().films.single().id);assertEquals("手写片名",catalog.works().single().title);assertEquals("旧札记",catalog.moments("film").single().note)}
        assertEquals("film",HistoryStore(context).entries().single().media.mediaId)
    }}

    @Test fun browsingRootChangesAdvanceEpochEvenWhenThePathReturns(){test{context,session->
        val source=io.localplayer.core.Source(id="root-scope",label="片箱",protocol=io.localplayer.core.Protocol.SFTP,host="fixture.invalid",root="/",initialPath="movies")
        val store=SourceStore(context);store.save(source,"",false);val first=SourceBindingEpochs.current(context,source)
        store.updatePresentation(source.id,"新片名","movies","影片");assertEquals(first,SourceBindingEpochs.current(context,source))
        store.updatePresentation(source.id,"新片名","series","剧集");val other=store.load().single();assertEquals(first+1,SourceBindingEpochs.current(context,other))
        store.updatePresentation(source.id,"新片名","movies","影片");assertEquals(first+2,SourceBindingEpochs.current(context,store.load().single()))
        assertFalse(SourceBindingEpochs.matches(context,store.load().single(),media().copy(sourceId=source.id,location=mediaLocation(source),bindingEpoch=first)))
    }}

    @Test fun reusingAnOldAddressAfterRelinkCreatesAFreshFilm(){test{context,session->
        val source=io.localplayer.core.Source(id="reuse",label="片箱",protocol=io.localplayer.core.Protocol.SFTP,host="fixture.invalid",root="/");SourceStore(context).save(source,"",false)
        val old=MediaRef("reuse:old.mp4","old.mp4",source.label,source.id,"old.mp4",sizeBytes=100,location=mediaLocation(source),bindingEpoch=0)
        val legacy=LegacyHistoryStore(context);legacy.update(old,legacy.token(old.key),42000,120000,false);assertEquals("Committed",session.migrate().state)
        val h=HistoryStore(context);val original=h.entries().single().media
        FilmLibraryStore(context).use{store->
            val note=store.addMoment(original,12345);store.writeMoment(note,"原片札记")
            val moved=old.copy(key="reuse:new.mp4",path="new.mp4");store.rebindFilm(original.mediaId!!,moved,true)
            val replacement=h.reference(old.copy(title="另放的一部.mp4"));assertNotEquals(original.mediaId,replacement.mediaId)
            assertEquals(0L,h.resumePosition(replacement));assertTrue(store.moments(replacement.mediaId!!).isEmpty())
            assertEquals(42000L,h.resumePosition(moved));assertEquals("原片札记",store.moments(original.mediaId).single().note)
            assertEquals(h.token(original),h.token(moved));assertEquals(2,store.works().flatMap{it.films}.size)
        }
    }}

    @Test fun oldInventoryReplayDoesNotCreateAGhostButFreshObservationDoes(){test{context,session->
        val source=io.localplayer.core.Source(id="replay",label="片箱",protocol=io.localplayer.core.Protocol.SFTP,host="fixture.invalid",root="/");SourceStore(context).save(source,"",false)
        val ref=MediaRef("replay:old.mp4","old.mp4",source.label,source.id,"old.mp4",sizeBytes=100,location=mediaLocation(source),bindingEpoch=0)
        val legacy=LegacyHistoryStore(context);legacy.update(ref,legacy.token(ref.key),42000,120000,false);assertEquals("Committed",session.migrate().state)
        val history=HistoryStore(context);val original=history.entries().single().media;val oldToken=history.token(original)
        val oldFinding=StationFinding(ref,1)
        FilmLibraryStore(context).use{catalog->
            catalog.registerLegacy(emptyList(),listOf(oldFinding))
            val staleUri=PlaybackRegistry.add(source,io.localplayer.core.Credentials(""),ref.path!!,ref.title,context=context,media=original)
            val preview=PlaybackRegistry.preview(staleUri)
            val moved=ref.copy(key="replay:new.mp4",path="new.mp4")
            catalog.rebindFilm(original.mediaId!!,moved,true)
            assertTrue(runCatching{PlaybackRegistry.get(staleUri)}.isFailure);assertTrue(runCatching{preview.checkCurrent()}.isFailure);preview.close()
            assertFalse(history.update(original,oldToken,90000,120000,false))
            assertFalse(history.update(original,history.token(original),90000,120000,false))
            assertTrue(runCatching{PlaybackRegistry.add(source,io.localplayer.core.Credentials(""),ref.path,ref.title,context=context,media=original)}.isFailure)
            val pending=PlayTarget(android.net.Uri.parse("file:///old.mp4"),ref.title,42000,original,oldToken)
            try{assertTrue(runCatching{PlaybackRequests.items(context,pending)}.isFailure)}finally{pending.release()}
            repeat(3){assertEquals(0,catalog.registerLegacy(emptyList(),listOf(oldFinding)))}
            assertEquals(1,catalog.works().flatMap{it.films}.size)
            val inventory=StationInventory(context);inventory.observe(listOf(ref),1,actualObservation=false)
            Thread.sleep(2);inventory.observe(listOf(ref))
            assertEquals(1,catalog.registerLegacy(emptyList(),inventory.entries()))
            assertEquals(2,catalog.works().flatMap{it.films}.size);assertEquals(42000L,history.resumePosition(moved))
            val current=history.entries().single().media
            assertTrue(history.update(current,history.token(current),43000,120000,false))
        }
    }}

}
