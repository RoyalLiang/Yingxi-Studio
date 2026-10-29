package io.localplayer.app
import androidx.test.platform.app.InstrumentationRegistry
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Receipt is emitted by instrumentation compiled against each actual old tag, before install -r. */
class LegacyUpgradeAcceptanceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun encoded(value:Any?):String=when(value){is Set<*>->"set:"+value.filterIsInstance<String>().sorted().joinToString("|");is Float->"float:"+value.toString();else->value?.javaClass?.simpleName+":"+value.toString()}
    @Test fun upgradePreservesDataAndSecurityBoundaries(){
        val c=context;val receipt=JSONObject(File(c.noBackupFilesDir,"upgrade-receipt.json").readText());val session=MediaDataMigration.session(c)
        val result=session.migrate();assertEquals("Committed",result.state)
        assertEquals(receipt.getLong("uid"),android.os.Process.myUid().toLong());assertEquals(receipt.getLong("firstInstall"),c.packageManager.getPackageInfo(c.packageName,0).firstInstallTime)
        val h=HistoryStore(c);val entries=h.entries();assertEquals(5,entries.size)
        val expected=receipt.getJSONArray("entries");for(i in 0 until expected.length()){
            val old=expected.getJSONObject(i);val ref=MediaRef.parse(old.getString("media"));val actual=entries.single{it.media.key==ref.key}
            assertEquals(old.getLong("position"),actual.positionMs);assertEquals(old.getLong("duration"),actual.durationMs);assertEquals(old.getBoolean("completed"),actual.completed);assertEquals(old.getLong("updated"),actual.updatedAt)
            assertNotNull(actual.media.mediaId);assertNotNull(actual.media.locatorId);assertNotNull(actual.media.contentRevisionId)
        }
        assertEquals(receipt.getString("passwordHash"),hash(SourceStore(c).password("upgrade-source")!!));assertEquals(setOf("upgrade-source"),SourceStore(c).pinnedIds())
        val originals=receipt.getJSONObject("originals")
        originals.keys().forEach{scope->val before=originals.getJSONObject(scope);val original=c.getSharedPreferences(scope,0).all
            before.keys().forEach{key->assertEquals("Original changed: $scope/$key",before.getString(key),if(scope=="secrets-v1")hash(encoded(original[key]))else encoded(original[key]))}
            if(scope in MigratingPreferences.scopes){val active=MigratingPreferences.open(c,scope).all
                before.keys().forEach{key->val expectedValue=if(scope=="station-experience-v1"&&key=="diary-line")encoded(LegacyQuoteAliases.resolve(original[key] as String))else before.getString(key);assertEquals("Migrated mismatch: $scope/$key",expectedValue,encoded(active[key]))}
            }
        }
        val tree=android.net.Uri.parse(receipt.getString("tree"))
        assertTrue(c.contentResolver.persistedUriPermissions.any{it.uri==tree&&it.isReadPermission})
        val document=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"movies")
        c.contentResolver.query(document,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)!!.use{assertTrue(it.moveToFirst());assertEquals("影片",it.getString(0))}
        val files=LocalFilesViewModel(c.applicationContext as android.app.Application);assertEquals(tree,files.folders.single().tree)
        if(originals.getJSONObject("sealed-media-v1").has("items")){assertFalse(SealedMedia.unlocked);assertTrue(SealedMedia.protectsLocalUri(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"shorts")))}
        assertEquals(1.25f,PlaybackPreferences(c).speed);assertEquals("ja",PlaybackPreferences(c).rememberedAudioLanguage);assertTrue(PlaybackPreferences(c).subtitlesDisabled)
        assertEquals(receipt.getString("quoteZh"),StationPreferences(c).diary().text.zh)
        assertEquals("legacy-group",SourceOrganization(c).bookmarks().single().groupId);assertTrue(SourceOrganization(c).bookmarks().single().favorite)
        val removed=MediaRef("upgrade-source:removed.mp4","removed.mp4","旧片箱","upgrade-source","removed.mp4",sizeBytes=100,location=mediaLocation(SourceStore(c).load().single()))
        assertEquals(0L,h.resumePosition(removed,88000));assertTrue(h.token(removed.key).itemRevision>0);assertTrue(h.token(removed.key).clearRevision>0)
        val ids=JSONObject();entries.forEach{ids.put(it.media.key,it.media.mediaId)}
        val identityFile=File(c.noBackupFilesDir,"upgrade-identities.json")
        if(identityFile.exists())assertEquals(JSONObject(identityFile.readText()).toString(),ids.toString())else identityFile.writeText(ids.toString())
        assertEquals(result,session.migrate())
    }
}
