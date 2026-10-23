package io.localplayer.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class StationFirstDiscoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun cabinetHoldGoesStraightToDeviceVerificationOnFirstAndLaterVisits(){
        val context=ui.activity
        val prefs=StationPreferences(context);val previous=prefs.cabinetDiscoverySeen
        val owner=ViewModelStore()
        lateinit var vm:LibraryViewModel
        try{
            prefs.cabinetDiscoverySeen=false
            ui.runOnUiThread{
                vm=LibraryViewModel(context.application);owner.put("library",vm)
                val files=LocalFilesViewModel(context.application);owner.put("files",files)
                context.setContent{PlayerTheme{
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)){AppShell(vm,files,{},{},{})}
                }}
            }
            ui.onNodeWithTag("home-sealed-entry").performTouchInput{click()}
            ui.runOnIdle{assertNull(vm.privateRequest)}
            ui.onNodeWithTag("home-sealed-entry").performTouchInput{longClick(durationMillis=3100)}
            ui.onNodeWithTag("station-first-discovery").assertDoesNotExist()
            ui.onNodeWithTag("sealed-cabinet").assertDoesNotExist()
            ui.runOnIdle{assertTrue(vm.privateRequest!=null||vm.error?.contains("锁屏密码")==true)}
        }finally{
            ui.runOnUiThread{vm.privateRequest?.let{vm.cancelPrivateAccess(it.id)};owner.clear();prefs.cabinetDiscoverySeen=previous}
        }
    }

    @Test fun directReadingAndSkipRemainAvailableWithoutSolving() {
        var continued=0
        ui.runOnUiThread{ui.activity.setContent{PlayerTheme{StationFirstDiscovery({},{continued++})}}}
        ui.onNodeWithTag("station-discovery-continue").performScrollTo().performClick()
        ui.onNodeWithTag("station-discovery-note").assertExists()
        assertEquals(0,continued)
        ui.onNodeWithTag("station-discovery-skip").performScrollTo().performClick()
        assertEquals(1,continued)
    }
}
