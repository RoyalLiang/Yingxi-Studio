package io.localplayer.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class FilmLibraryRegressionTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun ref(key:String="one",size:Long=100,location:String="binding-one")=MediaRef(key,"风过旧桥.mp4","北侧片箱",sourceId="source",path="/$key.mp4",sizeBytes=size,location=location)
    private fun database(test:(FilmLibraryStore)->Unit){
        val name="library-test-${System.nanoTime()}.db"
        try{FilmLibraryStore(context,name).use(test)}finally{context.deleteDatabase(name)}
    }
    @Test fun importIsIdempotentAndDisplayRenamePreservesIdentity(){database{store->
        val original=ref();val history=listOf(HistoryEntry(original,42000,90000,1234,false))
        assertEquals(1,store.registerLegacy(history,listOf(StationFinding(original,1000))))
        val first=store.works().single().films.single()
        val note=store.addMoment(original,8000)
        store.renameWork(first.workId,"桥边的风")
        assertEquals(0,store.registerLegacy(history.map{it.copy(media=it.media.copy(sourceLabel="改过名称的片箱"))},emptyList()))
        assertEquals(first.id,store.works().single().films.single().id)
        assertEquals("桥边的风",store.works().single().title)
        assertEquals(note,store.moments(first.id).single().id)
        assertEquals(42000L,history.single().positionMs)
    }}
    @Test fun versionsAndSourceBindingsNeverInheritMoments(){database{store->
        val original=ref();store.addMoment(original,5000)
        store.registerLegacy(emptyList(),listOf(StationFinding(ref(size=101),2),StationFinding(ref(location="different-binding"),3)))
        val films=store.works().flatMap{it.films};assertEquals(3,films.map{it.id}.distinct().size)
        assertEquals(1,films.sumOf{store.moments(it.id).size})
        val originalId=films.single{it.media.key==original.key&&it.media.sizeBytes==original.sizeBytes&&it.media.location==original.location}.id
        films.filter{it.id!=originalId}.forEach{assertTrue(store.moments(it.id).isEmpty())}
    }}
    @Test fun shelvesAndGroupingKeepFileAndNoteIdentity(){database{store->
        val one=ref();val two=ref("two");store.addMoment(one,1234);store.addMoment(two,5678)
        val films=store.works().flatMap{it.films}.associateBy{it.media.key};val a=films.getValue("one");val b=films.getValue("two")
        val shelf=store.createShelf("雨天放映")
        store.addToShelf(shelf,a.id);store.addToShelf(shelf,b.id);store.addToShelf(shelf,a.id)
        assertEquals(listOf(a.id,b.id),store.works(shelfId=shelf).flatMap{it.films}.map{it.id})
        store.moveInShelf(shelf,b.id,-1)
        assertEquals(b.id,store.works(shelfId=shelf).first().films.first().id)
        store.groupFilm(b.id,a.workId)
        assertEquals(1,store.works().size)
        assertEquals(1234L,store.moments(a.id).single().positionMs);assertEquals(5678L,store.moments(b.id).single().positionMs)
        store.deleteShelf(shelf)
        assertEquals(2,store.works().single().films.size);assertEquals(1,store.moments(b.id).size)
    }}
    @Test fun nonAdjacentVersionsKeepTheirShelfPositions(){database{store->
        listOf(ref("a"),ref("b"),ref("c")).forEach{store.addMoment(it,100)}
        val films=store.works().flatMap{it.films}.associateBy{it.media.key};val a=films.getValue("a");val b=films.getValue("b");val c=films.getValue("c")
        store.groupFilm(c.id,a.workId)
        val shelf=store.createShelf("保留顺序");listOf(a,b,c).forEach{store.addToShelf(shelf,it.id)}
        assertEquals(listOf(a.id,b.id,c.id),store.works(shelfId=shelf).flatMap{it.films}.map{it.id})
        store.separateFilm(c.id);assertEquals(3,store.works().size);assertEquals(1,store.moments(c.id).size)
        try{
            SealedMedia.unlock();SealedMedia.seal(SealedItem(b.media,false));SealedMedia.lock()
            store.moveInShelf(shelf,c.id,-1)
            assertEquals(listOf(c.id,a.id),store.works(shelfId=shelf).flatMap{it.films}.map{it.id})
        }finally{SealedMedia.unlock();SealedMedia.unseal(b.media.key);SealedMedia.lock()}
        assertEquals(listOf(c.id,b.id,a.id),store.works(shelfId=shelf).flatMap{it.films}.map{it.id})
    }}
    @Test fun clearingLegacyHistoryCannotDeleteLibrary(){database{store->
        val name="library-history-${System.nanoTime()}";val history=HistoryStore(context,name);val media=ref()
        try{
            history.update(media,history.token(media.key),9000,100000,false)
            val before=context.getSharedPreferences(name,Context.MODE_PRIVATE).all.toMap()
            store.registerLegacy(history.entries(),emptyList())
            assertEquals(before,context.getSharedPreferences(name,Context.MODE_PRIVATE).all)
            history.clear()
            assertEquals(1,store.works().size)
        }finally{context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()}
    }}
    @Test fun sealedFilmsDisappearFromAllPublicQueries(){database{store->
        val media=ref("sealed-library-${System.nanoTime()}");val moment=store.addMoment(media,1000);val film=store.works().single().films.single();val shelf=store.createShelf("保留")
        store.addToShelf(shelf,film.id)
        try{
            SealedMedia.unlock();SealedMedia.seal(SealedItem(media,false));SealedMedia.lock()
            assertTrue(store.works().isEmpty());assertTrue(store.works(shelfId=shelf).isEmpty());assertTrue(store.moments(film.id).isEmpty());assertNull(store.film(film.id))
            assertTrue(runCatching{store.writeMoment(moment,"不可写入")}.isFailure)
            assertTrue(runCatching{store.addMoment(media,2000)}.isFailure)
        }finally{SealedMedia.unlock();SealedMedia.unseal(media.key);SealedMedia.lock()}
    }}
    @Test fun opaqueDirectorySealHidesLibraryAfterHistoryIsGone(){database{store->
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val tree=android.provider.DocumentsContract.buildTreeDocumentUri(FixtureDocumentsProvider.AUTHORITY,"root")
        context.startActivity(android.content.Intent().setClassName(instrumentation.context.packageName,GrantFixtureActivity::class.java.name).putExtra("tree",tree.toString()).putExtra("target",context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline=System.nanoTime()+5_000_000_000L
        while(context.checkUriPermission(tree,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&System.nanoTime()<deadline)Thread.sleep(50)
        val child=android.provider.DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY,"clip")
        val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"movies")
        val film=MediaRef(child.toString(),"旧目录里的影片","本机文件",localUri=child.toString())
        val folder=MediaRef(parent.toString(),"影片目录","本机文件",localUri=parent.toString())
        store.registerLegacy(emptyList(),listOf(StationFinding(film,1)))
        store.addMoment(film,1000);val id=store.works().single().films.single().id
        try{
            SealedMedia.unlock();SealedMedia.seal(SealedItem(folder,true,tree.toString()));SealedMedia.lock()
            assertTrue(store.works().isEmpty());assertTrue(store.moments(id).isEmpty());assertNull(store.film(id))
        }finally{SealedMedia.unlock();SealedMedia.unseal(folder.key);SealedMedia.lock()}
    }}
    @Test fun interruptedImportRollsBackWithoutTouchingLegacy(){
        val name="library-rollback-${System.nanoTime()}.db"
        try{
            FilmLibraryStore(context,name).use{it.works()}
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{db->db.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON films WHEN NEW.legacy_key='two' BEGIN SELECT RAISE(ABORT,'injected interrupted import'); END")}
            val entries=listOf(HistoryEntry(ref(),100,1000,1,false),HistoryEntry(ref("two"),200,1000,2,false))
            FilmLibraryStore(context,name).use{store->assertTrue(runCatching{store.registerLegacy(entries,emptyList())}.isFailure);assertTrue(store.works().isEmpty());assertEquals(2,entries.size)}
        }finally{context.deleteDatabase(name)}
    }
    @Test fun reopenRetainsNamesOrderingAndNotes(){
        val name="library-persistence-${System.nanoTime()}.db";var film="";var shelf=""
        try{
            FilmLibraryStore(context,name).use{store->store.addMoment(ref(),12000);film=store.works().single().films.single().id;shelf=store.createShelf("长夜");store.addToShelf(shelf,film);store.writeMoment(store.moments(film).single().id,"回头再看")}
            FilmLibraryStore(context,name).use{store->assertEquals(film,store.works(shelfId=shelf).single().films.single().id);assertEquals("回头再看",store.moments(film).single().note)}
        }finally{context.deleteDatabase(name)}
    }
}
