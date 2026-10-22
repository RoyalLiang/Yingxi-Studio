package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudPlaybackSettingsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun controlsStayReachableOnNarrowScreenWithLargeTextAndClearRealCache() {
        val prefs=CloudReadPreferences(ui.activity);val before=prefs.workers;val cached=prefs.cacheEnabled;val next=prefs.nextEpisode
        val cache=CloudReadPreferences.cache(ui.activity)
        try {
            cache.clear();cache.put("synthetic-ui-fixture",0,ByteArray(1024*1024))
            ui.runOnUiThread{ui.activity.setContent{PlayerTheme{
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)){
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("cloud-settings-scroll")){CloudPlaybackSettings()}
                }
            }}}
            ui.onNodeWithTag("cloud-workers-3").performScrollTo().performClick().assertIsSelected()
            assertEquals(3,CloudReadPreferences(ui.activity).workers)
            ui.onNodeWithTag("cloud-segment-cache").performScrollTo().performClick()
            assertEquals(!cached,CloudReadPreferences(ui.activity).cacheEnabled)
            if(cached)ui.onNodeWithTag("cloud-next-prefetch").assertIsNotEnabled()
            ui.onNodeWithTag("cloud-cache-clear").performScrollTo().performClick()
            ui.waitUntil(5000){cache.bytes()==0L}
            ui.onNodeWithText("已释放",substring=true).performScrollTo().assertIsDisplayed()
        }finally{prefs.workers=before;prefs.cacheEnabled=cached;prefs.nextEpisode=next;cache.clear()}
    }
    @Test fun manualSingleConnectionAndCacheOffAreRestorableFallbacks() {
        val prefs=CloudReadPreferences(ui.activity);val previous=prefs.workers;val cache=prefs.cacheEnabled
        try{prefs.workers=1;prefs.cacheEnabled=false;val options=CloudReadPreferences(ui.activity).options();assertEquals(1,options.workers);assertNull(options.cache)}
        finally{prefs.workers=previous;prefs.cacheEnabled=cache}
    }
    @Test fun nextEpisodeRequiresEightyPercentAndEnoughForegroundBuffer() {
        assertFalse(shouldPrefetchNext(79_999,100_000,20_000))
        assertFalse(shouldPrefetchNext(80_000,100_000,14_999))
        assertTrue(shouldPrefetchNext(80_000,100_000,15_000))
        assertFalse(shouldPrefetchNext(80_000,0,20_000))
    }
}
