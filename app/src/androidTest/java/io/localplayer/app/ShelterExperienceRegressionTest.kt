package io.localplayer.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ShelterExperienceRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String) {
        ui.waitForIdle()
        val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try{java.io.File(ui.activity.getExternalFilesDir(null),"repair31-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
    }


    @Test fun exploreKeepsPlacesAndArchivesKeepPeople() {
        ui.onNodeWithText("探索").performClick()
        ui.onNodeWithTag("world-map").assertIsDisplayed()
        ui.onNodeWithTag("world-person:axi").assertDoesNotExist()
        capture("explore")
        ui.onNodeWithTag("world-node:salt").performClick().assertIsSelected()
        ui.onNodeWithText("放映站").performClick()
        ui.onNodeWithTag("home-archives").performClick()
        ui.onNodeWithTag("world-person:axi").assertIsDisplayed()
        ui.onNodeWithTag("world-map").assertDoesNotExist()
        capture("archives")
        ui.onNodeWithTag("archives-back").performClick()
        ui.onNodeWithText("探索").performClick()
        ui.onNodeWithTag("world-node:salt").assertIsSelected()
    }

    @Test fun remoteSeriesFoldersAndEpisodesHavePostersAndRealDirectoryNames() {
        val app=ui.activity.application
        val source=Source(id="poster-wall-"+UUID.randomUUID(),label="测试避难所",protocol=Protocol.WEBDAV,host="poster-wall.invalid")
        val folder=Entry("Example (2024)","show",true,0)
        val episode=Entry("01.mkv","show/01.mkv",false,4000)
        val connector=object:Connector {
            override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession {
                override fun list(path:String)=if(path.isEmpty())listOf(folder)else listOf(episode)
                override fun open(path:String):RemoteFile=error("Only browsing and poster lookup are expected")
                override fun close(){}
            }
        }
        val owner=ViewModelStore()
        val oldConnector=MediaPosters.connector;val oldLookup=MediaPosters.onlineLookup;val oldImage=MediaPosters.imageDownload
        val oldEnabled=EnrichmentPreferences(app).posters
        val image=Bitmap.createBitmap(160,240,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(96,40,58))}
        val bytes=java.io.ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
        val identities=java.util.Collections.synchronizedList(mutableListOf<MediaIdentity>())
        SourceStore(app).save(source,"",true)
        try {
            EnrichmentPreferences(app).posters=true
            MediaPosters.connector=connector
            MediaPosters.onlineLookup={id,_->identities.add(id);PosterMatch(imageUrl="https://poster.invalid/image",sourceUrl="https://poster.invalid",credit="目录档案",confidence=1.0)}
            MediaPosters.imageDownload={_,_->bytes}
            lateinit var vm:LibraryViewModel
            ui.runOnUiThread {
                vm=LibraryViewModel(app,connector);owner.put("shelter",vm)
                val files=LocalFilesViewModel(app);owner.put("local",files)
                ui.activity.setContent{PlayerTheme{AppShell(vm,files,{},{},{})}}
            }
            ui.onNodeWithTag("nav:Sources").performClick()
            ui.runOnIdle{vm.open(source)}
            ui.waitUntil(5000){vm.source!=null&&!vm.busy}
            ui.onNodeWithTag("shelter-poster-wall").assertIsDisplayed()
            ui.waitUntil(5000){runCatching{ui.onNodeWithTag("shelter-poster:directory:${source.id}:show",useUnmergedTree=true).assertIsDisplayed()}.isSuccess}
            capture("shelter-wall")
            ui.onNodeWithTag("shelter-entry:show").performClick()
            ui.waitUntil(5000){vm.path=="show"&&!vm.busy}
            ui.onNodeWithText("Example (2024)").assertIsDisplayed()
            ui.onNodeWithText("文件夹").assertDoesNotExist()
            ui.waitUntil(5000){runCatching{ui.onNodeWithTag("shelter-poster:${source.id}:show/01.mkv",useUnmergedTree=true).assertIsDisplayed()}.isSuccess}
            capture("episodes")
            assertTrue("Folder and numbered episodes use the series identity: $identities",identities.count{it.title=="Example"&&it.series&&it.year==2024}>=2)
        } finally {
            ui.runOnUiThread{owner.clear()}
            MediaPosters.remove(app,shelterPosterRef(source,folder));MediaPosters.remove(app,shelterPosterRef(source,episode))
            SourceStore(app).delete(source.id)
            MediaPosters.connector=oldConnector;MediaPosters.onlineLookup=oldLookup;MediaPosters.imageDownload=oldImage
            EnrichmentPreferences(app).posters=oldEnabled
        }
    }
}
