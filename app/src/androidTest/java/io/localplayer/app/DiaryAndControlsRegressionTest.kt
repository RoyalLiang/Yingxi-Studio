package io.localplayer.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DiaryAndControlsRegressionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun archiveAvatarAndQuoteBelongToOneDiaryWithLargeText() {
        val models=ViewModelStore();var opened=0
        ui.runOnIdle{
            val vm=LibraryViewModel(ui.activity.application as Application);models.put("diary",vm)
            ui.activity.setContent{PlayerTheme{val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)){HomeScreen(vm,{},{},{},{opened++})}}}
        }
        try{
            ui.onAllNodesWithTag("home-archives").assertCountEquals(1)
            ui.onNode(hasTestTag("home-admin-avatar") and hasAnyAncestor(hasTestTag("station-diary"))).assertExists()
            ui.onNode(hasTestTag("home-admin-quote") and hasAnyAncestor(hasTestTag("station-diary"))).assertExists()
            val avatar=ui.onNodeWithTag("home-admin-avatar").getUnclippedBoundsInRoot()
            val quote=ui.onNodeWithTag("home-admin-quote").getUnclippedBoundsInRoot()
            assertTrue("Avatar is above the full-width quote",avatar.bottom<=quote.top)
            File(ui.activity.cacheDir,"repair33-diary.png").outputStream().use{ui.onNodeWithTag("station-diary").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ui.onNodeWithTag("home-archives").performClick();ui.runOnIdle{assertEquals(1,opened)}
        }finally{ui.runOnIdle{models.clear()}}
    }

    @Test fun portraitActionsUseOneRightRailAndSpeedPanelHasMargins() {
        val preferences=PlaybackPreferences(ui.activity);val oldSpeed=preferences.speed
        ui.runOnIdle{ui.activity.setContent{PlayerTheme{FullscreenPlayer(null,"布局回归",null,{},{},{},{},false,{},{} ,{})}}}
        try{
            ui.onNodeWithTag("portrait-playback-rail").assertExists()
            ui.onNodeWithTag("playback-actions").assertDoesNotExist()
            val rail=ui.onNodeWithTag("portrait-playback-rail").getUnclippedBoundsInRoot()
            val root=ui.onRoot().getUnclippedBoundsInRoot()
            assertTrue("Rail stays on the right",rail.left.value>(root.right-root.left).value*.6f)
            ui.onNodeWithContentDescription("播放速度").performClick()
            val panel=ui.onNodeWithTag("playback-speed-panel").getUnclippedBoundsInRoot()
            assertTrue("Panel is narrower than the screen",(panel.right-panel.left).value<(root.right-root.left).value-20f)
            File(ui.activity.cacheDir,"repair33-speed-panel.png").outputStream().use{ui.onNodeWithTag("playback-speed-panel").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ui.onNodeWithTag("speed-option-1.5").performClick()
            ui.runOnIdle{assertEquals(1.5f,preferences.speed)}
        }finally{preferences.speed=oldSpeed}
    }

    @Test fun storageCountsInstalledApksAndDeduplicatesCacheRoots()=runBlocking {
        val parent=File(ui.activity.cacheDir,"storage-regression-${System.nanoTime()}");parent.mkdirs()
        val cache=File(parent,"cache").apply{mkdirs()};val code=File(cache,"code").apply{mkdirs()};val external=File(parent,"external").apply{mkdirs()}
        File(cache,"a").writeBytes(ByteArray(7));File(code,"b").writeBytes(ByteArray(11));File(external,"c").writeBytes(ByteArray(13))
        val context=object:ContextWrapper(ui.activity){override fun getCacheDir()=cache;override fun getCodeCacheDir()=code;override fun getExternalCacheDirs()=arrayOf(cache,external)}
        try{
            val usage=StorageUsage.measure(context)
            assertEquals(31L,usage.cacheBytes)
            val info=context.applicationInfo
            val expected=(listOf(info.sourceDir)+info.splitSourceDirs.orEmpty()).map(::File).distinctBy{it.canonicalPath}.sumOf{it.length()}
            assertEquals(expected,usage.packageBytes);assertTrue(usage.packageBytes>0)
        }finally{parent.deleteRecursively()}
    }
}
