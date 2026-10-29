package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CatalogScaleTest {
    @Test fun tenThousandRecordLookupHasMeasuredBudget(){
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try{
            MediaDataMigration.session(context).migrate()
            FilmLibraryStore(context).use{store->
                store.registerLegacy(emptyList(),(0 until 10000).map{StationFinding(MediaRef("scale:$it","片目 $it.mp4","测试片箱",sizeBytes=100),1)})
                val started=System.nanoTime();assertEquals(10000,store.works().size);val all=(System.nanoTime()-started)/1_000_000
                val searches=(0..2).map{val start=System.nanoTime();assertTrue(store.works("9999").isNotEmpty());(System.nanoTime()-start)/1_000_000}
                println("CATALOG_SCALE records=10000 all_ms=$all search_ms=$searches")
            }
        }finally{context.dispose()}
    }
}
