package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryParcelTest {
    private fun test(action:(IsolatedMediaContext,FilmLibraryStore,LibraryParcel)->Unit){
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try{
            assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
            FilmLibraryStore(context).use{action(context,it,LibraryParcel(context))}
        }finally{context.dispose()}
    }
    private fun seed(store:FilmLibraryStore,title:String="桥边的风.mp4"):LibraryFilm {
        val media=MediaRef("content://fixture/$title",title,"私有路径 / secret-user",localUri="content://fixture/$title",sizeBytes=100)
        store.addMoment(media,1200)
        val film=store.works().flatMap{it.films}.single{it.media.key==media.key}
        val shelf=store.createShelf("雨天放映");store.addToShelf(shelf,film.id)
        store.writeMoment(store.moments(film.id).single().id,"下次从这里开始")
        return film
    }
    @Test fun exportContainsRecordsButNoLocationsOrCredentials(){test{context,store,parcel->
        val film=seed(store);PlaybackPreferences(context).speed=1.25f
        val text=parcel.export();assertFalse(text.contains("content://"));assertFalse(text.contains("secret-user"));assertFalse(text.contains("localUri"))
        val o=JSONObject(text);assertEquals(film.id,o.getJSONArray("films").getJSONObject(0).getString("id"));assertEquals(1,o.getJSONArray("moments").length())
        assertEquals(1,parcel.preview(text).films)
    }}
    @Test fun foreignRestoreKeepsIdentityAndRequiresManualLocation(){test{context,store,parcel->
        val film=seed(store);assertTrue(HistoryStore(context).update(film.media,HistoryStore(context).token(film.media),42000,60000,false));val text=parcel.export()
        val foreign=IsolatedMediaContext(context.baseContext)
        try{
            MediaDataMigration.session(foreign).migrate();val other=LibraryParcel(foreign)
            other.restore(other.preview(text))
            FilmLibraryStore(foreign).use{restored->
                val copy=restored.works().single().films.single();assertEquals(film.id,copy.id);assertNull(copy.media.localUri);assertTrue(restored.isPending(copy.id));assertEquals("下次从这里开始",restored.moments(copy.id).single().note)
                assertEquals(FilmAccess.UNBOUND,kotlinx.coroutines.runBlocking{LibraryRepair.inspect(foreign,copy.media,null)})
                val source=io.localplayer.core.Source(label="新片箱",protocol=io.localplayer.core.Protocol.SFTP,host="new-fixture.invalid")
                SourceStore(foreign).save(source,"",false)
                val location=MediaRef("${source.id}:moved.mp4","重排后的片名.mp4",source.label,source.id,"moved.mp4",sizeBytes=100,location=mediaLocation(source),bindingEpoch=0)
                restored.observeBatch(listOf(location),restored.catalogEpoch())
                restored.rebindFilm(copy.id,location,true)
                val linked=restored.film(copy.id)!!
                assertFalse(restored.isPending(copy.id));assertEquals(location.path,linked.media.path)
                assertEquals(42000L,HistoryStore(foreign).resumePosition(linked.media))
                assertEquals("下次从这里开始",restored.moments(copy.id).single().note)

            }
        }finally{foreign.dispose()}
    }}
    @Test fun wholeLibraryReplacementAndRecoveryPreserveEarlierRecords(){test{context,store,parcel->
        seed(store);val original=parcel.export();val empty=JSONObject(original).apply{listOf("works","films","lists","members","moments","positions").forEach{put(it,org.json.JSONArray())}}.toString()
        parcel.restore(parcel.preview(empty));assertTrue(store.works().isEmpty());assertTrue(parcel.hasRecovery())
        parcel.recover();assertEquals(JSONObject(original).getJSONArray("films").getJSONObject(0).getString("id"),store.works().single().films.single().id)
    }}
    @Test fun changedCatalogInvalidatesPreviewWithoutOverwriting(){test{_,store,parcel->
        seed(store);val preview=parcel.preview(parcel.export());store.createShelf("新单")
        assertTrue(runCatching{parcel.restore(preview)}.isFailure);assertEquals(2,store.shelves().size)
    }}
    @Test fun failedSqlRestoreRollsBackEveryTable(){test{context,store,parcel->
        seed(store);val text=parcel.export();val preview=parcel.preview(text)
        MediaLibraryDatabase(context).use{it.writableDatabase.execSQL("CREATE TRIGGER refuse_restore BEFORE INSERT ON moments BEGIN SELECT RAISE(ABORT,'fixture'); END")}
        assertTrue(runCatching{parcel.restore(preview)}.isFailure)
        assertEquals(text,parcel.export());assertTrue(parcel.hasRecovery())
    }}
    @Test fun unknownVersionDuplicateIdentityAndOversizedNestingAreRejected(){test{_,store,parcel->
        seed(store);val text=parcel.export()
        assertTrue(runCatching{parcel.preview(JSONObject(text).put("version",2).toString())}.isFailure)
        val duplicates=JSONObject(text);val films=duplicates.getJSONArray("films");films.put(films.getJSONObject(0));assertTrue(runCatching{parcel.preview(duplicates.toString())}.isFailure)
        assertTrue(runCatching{parcel.preview("[".repeat(32)+"]".repeat(32))}.isFailure)
        assertEquals(1,store.works().size)
    }}
    @Test fun activePlaybackPreventsRestoreBeforeRecoveryPoint(){test{context,store,parcel->
        seed(store);val preview=parcel.preview(parcel.export());val session=MediaDataMigration.session(context);session.playbackStarted()
        try{assertTrue(runCatching{parcel.restore(preview)}.isFailure);assertFalse(parcel.hasRecovery())}finally{session.playbackStopped()}
    }}
    @Test fun sealedRowsAreNeitherExportedNorDeletedByPublicRestore(){test{context,store,parcel->
        val public=seed(store,"public.mp4");val private=seed(store,"private.mp4")
        try{
            SealedMedia.unlock();SealedMedia.seal(SealedItem(private.media,false));SealedMedia.lock()
            val text=parcel.export();assertFalse(text.contains("private.mp4"));assertTrue(text.contains("public.mp4"))
            val empty=JSONObject(text).apply{listOf("works","films","lists","members","moments","positions").forEach{put(it,org.json.JSONArray())}}.toString()
            parcel.restore(parcel.preview(empty));assertTrue(store.works().isEmpty())
            MediaLibraryDatabase(context).use{helper->helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM films WHERE id=?",arrayOf(private.id)).use{it.moveToFirst();assertEquals(1,it.getInt(0))}}
            parcel.recover();assertEquals(public.id,store.works().single().films.single().id)
        }finally{SealedMedia.unlock();SealedMedia.unseal(private.media.key);SealedMedia.lock()}
    }}
    @Test fun newlySealedRowsPreventRecoveryFromOverwritingThem(){test{_,store,parcel->
        seed(store);val text=parcel.export();parcel.restore(parcel.preview(text));val added=seed(store,"new-private.mp4")
        try{SealedMedia.unlock();SealedMedia.seal(SealedItem(added.media,false));SealedMedia.lock()
            assertTrue(runCatching{parcel.recover()}.isFailure)
        }finally{SealedMedia.unlock();SealedMedia.unseal(added.media.key);SealedMedia.lock()}
        assertNotNull(store.film(added.id))
    }}

    @Test fun oldInventoryStaysOutButFreshBrowsingStillEntersAfterRestore(){test{context,store,parcel->
        val old=seed(store);val text=parcel.export()
        val empty=JSONObject(text).apply{listOf("works","films","lists","members","moments","positions").forEach{put(it,org.json.JSONArray())}}.toString()
        parcel.restore(parcel.preview(empty))
        store.registerLegacy(emptyList(),listOf(StationFinding(old.media,1,lastObserved=1)))
        assertTrue(store.works().isEmpty())
        val fresh=old.media.copy(key="fresh-observation",title="新片.mp4",mediaId=null,locatorId=null,contentRevisionId=null)
        StationInventory(context).observe(listOf(fresh),seen=1)
        store.registerLegacy(emptyList(),StationInventory(context).entries())
        assertEquals("新片.mp4",store.works().single().title)
    }}
    @Test fun fractionalOverflowingAndNegativeValuesAreRejected(){test{_,store,parcel->
        seed(store);val text=parcel.export()
        for(value in listOf(-.5,1.5,Double.MAX_VALUE,-1)){
            val changed=JSONObject(text);changed.getJSONArray("films").getJSONObject(0).put("size",value)
            assertTrue(value.toString(),runCatching{parcel.preview(changed.toString())}.isFailure)
        }
        val changed=JSONObject(text).put("version",1.5);assertTrue(runCatching{parcel.preview(changed.toString())}.isFailure)
    }}

    @Test fun callbackCapturedBeforeRestoreCannotBecomeAFreshObservation(){test{context,store,parcel->
        val old=seed(store);val before=MediaDataMigration.session(context).catalogEpoch()
        parcel.restore(parcel.preview(parcel.export()))
        assertEquals(0,StationInventory(context).observe(listOf(old.media.copy(key="late-old-result")),operationEpoch=before))
        assertFalse(StationInventory(context).entries().any{it.media.key=="late-old-result"})
        assertEquals(1,StationInventory(context).observe(listOf(old.media.copy(key="fresh-result"))))
    }}

}
