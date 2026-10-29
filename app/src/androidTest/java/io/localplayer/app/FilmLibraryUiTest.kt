package io.localplayer.app

import android.app.Application
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class FilmLibraryUiTest {
    private val databaseName="library-ui-${System.nanoTime()}.db"
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun screen(english:Boolean=false):Pair<ViewModelStore,String>{
        val context=ui.activity.applicationContext
        context.deleteDatabase(databaseName)
        val id=FilmLibraryStore(context,databaseName).use{store->store.addMoment(MediaRef("ui-library","风过旧桥.mp4","本机片箱",localUri="content://library-fixture/one",sizeBytes=100),12000);store.works().single().id}
        val models=ViewModelStore()
        ui.runOnUiThread{
            val vm=LibraryViewModel(ui.activity.application as Application);models.put("library",vm)
            val config=Configuration(ui.activity.resources.configuration).apply{setLocale(if(english)Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)}
            ui.activity.setContent{CompositionLocalProvider(LocalConfiguration provides config){PlayerTheme{FilmLibraryScreen(vm,databaseName){}}}}
        }
        ui.waitUntil(10000){ui.onAllNodesWithTag("film-work:$id").fetchSemanticsNodes().isNotEmpty()}
        return models to id
    }
    private fun capture(name:String){ui.waitForIdle();androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/beta-$name.png").close()}
    @Test fun shelfAndTitleRenameUseTheCorrectDialogAction(){
        val (models,id)=screen()
        try{
            capture("library")
            ui.onNodeWithText("新建片架").performClick();ui.onNodeWithTag("film-library-label").performTextInput("雨天");ui.onNodeWithText("保存").performClick()
            ui.waitUntil(5000){ui.onAllNodesWithText("雨天").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("film-work:$id").performClick()
            ui.onNodeWithText("更正显示片名").performClick();ui.onNodeWithTag("film-library-label").performTextReplacement("桥边的风");ui.onNodeWithText("保存").performClick()
            ui.waitUntil(5000){ui.onAllNodesWithText("桥边的风").fetchSemanticsNodes().isNotEmpty()}
            capture("dossier")
            FilmLibraryStore(ui.activity,databaseName).use{store->assertEquals("桥边的风",store.works().first{it.id==id}.title);assertEquals(listOf("雨天"),store.shelves().map{it.name})}
        }finally{ui.runOnUiThread{models.clear()};ui.activity.deleteDatabase(databaseName)}
    }
    @Test fun policyChangeDismissesAnInvalidRename(){
        val(models,id)=screen()
        try{
            ui.onNodeWithTag("film-work:$id").performClick();ui.onNodeWithText("更正显示片名").performClick();ui.onNodeWithTag("film-library-label").performTextReplacement("不应变成片架")
            ui.runOnUiThread{SealedMedia.lock()}
            ui.onNodeWithTag("film-library-label").assertDoesNotExist()
            FilmLibraryStore(ui.activity,databaseName).use{assertTrue(it.shelves().isEmpty())}
        }finally{ui.runOnUiThread{models.clear()};ui.activity.deleteDatabase(databaseName)}
    }
    @Test fun paperSlipDiaryAppearsThroughTheExistingHomeNotice(){
        val context=ui.activity.applicationContext;val prefs=MigratingPreferences.open(context,"station-experience-v1")
        val oldLine=prefs.getString("diary-line",null);val oldAt=prefs.getLong("diary-at",0)
        val models=ViewModelStore()
        try{
            val line=StationWorld.load(context).dailyLines.first{it.id=="diary_027"}
            prefs.edit().putString("diary-line",line.id).putLong("diary-at",System.currentTimeMillis()).commit()
            ui.runOnUiThread{
                val vm=LibraryViewModel(ui.activity.application as Application);models.put("diary",vm)
                val config=Configuration(ui.activity.resources.configuration).apply{setLocale(Locale.SIMPLIFIED_CHINESE)}
                ui.activity.setContent{CompositionLocalProvider(LocalConfiguration provides config){PlayerTheme{HomeScreen(vm,{},{},{})}}}
            }
            ui.onNodeWithText(line.text.zh).assertExists();capture("paper-slip-diary")
        }finally{ui.runOnUiThread{models.clear()};prefs.edit().putString("diary-line",oldLine).putLong("diary-at",oldAt).commit()}
    }
    @Test fun englishScreenShowsRealFunctionNames(){
        val(models,id)=screen(true)
        try{ui.onNodeWithText("Your film shelves").assertIsDisplayed();capture("english");ui.onNodeWithTag("film-work:$id").performClick();ui.onNodeWithText("Film dossier").assertIsDisplayed();ui.onNodeWithText("Versions and sources").assertIsDisplayed();capture("english-dossier")}
        finally{ui.runOnUiThread{models.clear()};ui.activity.deleteDatabase(databaseName)}
    }
    @Test fun oldSlipConfirmationAppearsFromHomeAndCanBeDismissed(){
        val models=ViewModelStore();lateinit var vm:LibraryViewModel
        try{
            ui.runOnUiThread{
                vm=LibraryViewModel(ui.activity.application);models.put("old-slip",vm);val files=LocalFilesViewModel(ui.activity.application);models.put("files",files)
                val config=Configuration(ui.activity.resources.configuration).apply{setLocale(Locale.SIMPLIFIED_CHINESE)}
                ui.activity.setContent{CompositionLocalProvider(LocalConfiguration provides config){PlayerTheme{AppShell(vm,files,{},{},{})}}}
                vm.resume(HistoryEntry(MediaRef("legacy-popup","旧胶片.mp4","本机文件",localUri="content://fixture/unknown"),42000,120000,1,false))
            }
            ui.onNodeWithText("核对旧片签").assertIsDisplayed();ui.onNodeWithText("确认同一版本").assertIsDisplayed();capture("old-slip-confirmation")
            ui.onNodeWithText("暂不关联").performClick();ui.onNodeWithText("核对旧片签").assertDoesNotExist();assertNull(vm.legacyReplay)
        }finally{ui.runOnUiThread{models.clear()}}
    }

}
