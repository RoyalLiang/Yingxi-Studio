package io.localplayer.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class SealedMediaAliasTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun mediaStoreAndDocumentAliasesStayOutOfThePublicCatalog(){
        val context=ui.activity.applicationContext
        val resolver=context.contentResolver
        val name="Yingxi-alias-${System.nanoTime()}.mp4"
        val sample=File(context.cacheDir,"direction-landscape.mp4");check(sample.isFile)
        val media=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/YingxiAliasTest");put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        val picked=AtomicReference<android.net.Uri>()
        var unrelated:android.net.Uri?=null
        val sealKeys=mutableSetOf<String>()
        var launcher:ActivityResultLauncher<Intent>?=null
        val database="alias-test-${System.nanoTime()}.db"
        try{
            resolver.openOutputStream(media)!!.use{output->sample.inputStream().use{it.copyTo(output)}}
            resolver.update(media,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            val other=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
                put(MediaStore.MediaColumns.DISPLAY_NAME,"Yingxi-public-${System.nanoTime()}.mp4");put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/YingxiAliasTest");put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!
            unrelated=other
            resolver.openOutputStream(other)!!.use{output->sample.inputStream().use{it.copyTo(output)}}
            resolver.update(other,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            ui.runOnUiThread{
                launcher=ui.activity.activityResultRegistry.register("alias-$name",ActivityResultContracts.StartActivityForResult()){result->
                    if(result.resultCode==android.app.Activity.RESULT_OK)picked.set(result.data?.data)
                }
                launcher!!.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Movies/YingxiAliasTest")))
            }
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            var file:android.view.accessibility.AccessibilityNodeInfo?=null
            while(file==null&&android.os.SystemClock.uptimeMillis()<deadline){
                file=automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(name)?.firstOrNull{it.isVisibleToUser}
                if(file==null)Thread.sleep(50)
            }
            var target=checkNotNull(file){"The synthetic video must be selected through the real system picker"}
            // The preview icon also carries the filename; select the document tile itself.
            while(target.viewIdResourceName?.endsWith(":id/item_root")!=true&&target.parent!=null)target=target.parent
            assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(5000){picked.get()!=null}
            val document=picked.get()
            resolver.takePersistableUriPermission(document,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val alias=checkNotNull(MediaStore.getMediaUri(context,document))
            println("SEALED_ALIAS document=$document media=$alias inserted=$media")
            assertEquals(ContentUris.parseId(media),ContentUris.parseId(alias))
            val first=resolver.openInputStream(document)!!.use{it.readNBytes(128)}
            assertArrayEquals(first,resolver.openInputStream(alias)!!.use{it.readNBytes(128)})
            FilmLibraryStore(context,database).use{store->
                val variants=listOf(alias,media,document).distinct()
                val publicRef=MediaRef(other.toString(),"公开影片.mp4","本机文件",localUri=other.toString(),sizeBytes=sample.length())
                store.registerLegacy(emptyList(),variants.map{uri->StationFinding(MediaRef(uri.toString(),name,"本机文件",localUri=uri.toString(),sizeBytes=sample.length()),1)}+StationFinding(publicRef,1))
                val films=store.works().flatMap{it.films}
                val privateFilms=films.filter{it.media.title==name}
                assertEquals(variants.map{it.toString()}.toSet(),privateFilms.map{it.media.localUri}.toSet())
                val publicId=films.single{it.media.key==publicRef.key}.id
                val shelf=store.createShelf("别名检查");privateFilms.forEach{store.addToShelf(shelf,it.id)}
                for(uri in listOf(document,media)){
                    val sealed=MediaRef(uri.toString(),name,"本机文件",localUri=uri.toString());sealKeys+=sealed.key
                    SealedMedia.unlock();SealedMedia.seal(SealedItem(sealed,false));SealedMedia.lock()
                    val started=android.os.SystemClock.elapsedRealtime()
                    assertEquals("Only the unrelated physical file should remain public",listOf(publicId),store.works().flatMap{it.films}.map{it.id})
                    println("SEALED_ALIAS_LOOKUP elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
                    assertTrue(store.works(shelfId=shelf).isEmpty());privateFilms.forEach{assertNull(store.film(it.id))}
                    variants.forEach{assertTrue(SealedMedia.protectsLocalUri(it))};assertFalse(SealedMedia.protectsLocalUri(other))
                    assertFalse(LibraryParcel(context,database).export().contains(name))
                    SealedMedia.unlock()
                    assertEquals("Unlocking the cabinet must not expose it in the public catalog",listOf(publicId),store.works().flatMap{it.films}.map{it.id})
                    SealedMedia.unseal(sealed.key)
                }
                val known=privateFilms.map{it.media}+publicRef
                val aliases=SealedMedia.localAliases(document,known,false)
                assertFalse("An unrelated file cannot become a permanent alias",publicRef.key in aliases)
                val prepared=SealedItem(MediaRef(document.toString(),name,"本机文件",localUri=document.toString()),false,aliases=aliases)
                sealKeys+=prepared.id;SealedMedia.unlock();SealedMedia.seal(prepared);SealedMedia.lock()
                known.filter{it.title==name}.forEach{assertTrue("Fast UI and playback observers must also hide the captured alias",SealedMedia.protects(it))}
                assertFalse(SealedMedia.protects(publicRef))
                SealedMedia.reload()
                known.filter{it.title==name}.forEach{assertTrue(SealedMedia.protects(it))}
                val internal=ContentUris.withAppendedId(MediaStore.Video.Media.INTERNAL_CONTENT_URI,ContentUris.parseId(media))
                assertFalse("Internal and external MediaStore row IDs are different namespaces",SealedMedia.protectsLocalUri(internal))
            }
        }finally{
            SealedMedia.unlock();sealKeys.forEach{key->if(SealedMedia.items().any{it.id==key})SealedMedia.unseal(key)};SealedMedia.lock()
            picked.get()?.let{document->runCatching{resolver.releasePersistableUriPermission(document,Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
            ui.runOnUiThread{launcher?.unregister()}
            unrelated?.let{resolver.delete(it,null,null)};resolver.delete(media,null,null);context.deleteDatabase(database)
        }
    }
}
