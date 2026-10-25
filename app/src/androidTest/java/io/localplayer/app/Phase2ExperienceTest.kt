package io.localplayer.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Phase2ExperienceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun nightWatchFollowsSystemHonorsDayAndPersistsAfterRecreation() {
        val prefs=AppearancePreferences(ui.activity)
        val old=prefs.mode
        var background=Color.Unspecified
        var foreground=Color.Unspecified
        val night=Configuration(ui.activity.resources.configuration).apply{uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES}
        try{
            ui.runOnIdle{
                prefs.mode=LibraryAppearance.SYSTEM
                ui.activity.setContent{
                    CompositionLocalProvider(LocalConfiguration provides night){PlayerTheme{
                        val colors=MaterialTheme.colorScheme
 val contentColor=LocalContentColor.current
 SideEffect{background=colors.background;foreground=colors.onSurface;assertEquals(colors.onSurface,contentColor)}
                        NightWatchSettings()
                    }}
                }
            }
            ui.waitUntil(5000){background!=Color.Unspecified&&background.luminance()<.1f}
            assertTrue((foreground.luminance()+.05f)/(background.luminance()+.05f)>=7f)
            ui.onNodeWithTag("appearance-day").performClick()
            ui.waitUntil(5000){background.luminance()>.7f}
            assertEquals(LibraryAppearance.DAY,AppearancePreferences(ui.activity).mode)
            ui.activityRule.scenario.recreate()
            ui.onNodeWithContentDescription("站务").performClick()
            ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("night-watch-settings"))
            ui.onNodeWithTag("appearance-day").assertIsSelected()
            ui.onNodeWithTag("appearance-night").performClick()
            ui.waitUntil(5000){AppearancePreferences(ui.activity).mode==LibraryAppearance.NIGHT}
            ui.onNodeWithTag("appearance-night").assertIsSelected()
        }finally{ui.runOnIdle{AppearancePreferences(ui.activity).mode=old}}
    }

    @Test fun routeUsesActualPublicHistoryAndRemovalWorksAtLargeFont() {
        val context=ui.activity
        val prefs=AppearancePreferences(context);val old=prefs.mode
        val history=HistoryStore(context)
        val prefix="route46-"+UUID.randomUUID()
        val public=MediaRef(prefix+"-a","很长的拾荒剧集.S01E01.mkv","夜班片架",sizeBytes=9_000_000)
        val second=MediaRef(prefix+"-b","第二集.mp4","夜班片架")
        val sealedRef=MediaRef(prefix+"-secret","封存内容不应出现在路线图","私密片架",localUri="content://fixture.documents/document/"+prefix+"-secret")
        val owner=ViewModelStore()
        lateinit var vm:LibraryViewModel
        try{
            listOf(public,second,sealedRef).forEach{history.update(it,history.token(it.key),12_000,60_000,it==second)}
            ui.runOnIdle{
                SealedMedia.unlock();SealedMedia.seal(SealedItem(sealedRef,false));SealedMedia.lock()
                prefs.mode=LibraryAppearance.NIGHT
                vm=LibraryViewModel(context.application);owner.put("library",vm)
                val files=LocalFilesViewModel(context.application);owner.put("files",files)
                context.setContent{
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,1.8f)){
                        PlayerTheme{Box(Modifier.width(320.dp).fillMaxHeight()){AppShell(vm,files,{},{},{})}}
                    }
                }
            }
            ui.onNodeWithTag("nav:History").performClick()
            ui.onNodeWithTag("history-title").assertTextEquals("拾荒路线图")
            ui.onNodeWithTag("history-view-options").assertDoesNotExist()
            ui.onNodeWithTag("scavenger-route").assertDoesNotExist()
            ui.onNodeWithTag("open-sealed-cabinet").assertDoesNotExist()
            ui.onNodeWithTag("history-row:${sealedRef.key}").assertDoesNotExist()
            ui.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-row:${public.key}"))
            ui.onNodeWithTag("history-row:${public.key}").assertIsDisplayed()
            capture("night-route-large")
            ui.onNodeWithContentDescription("记录操作 ${io.localplayer.core.mediaDisplayTitle(public.title)}").performClick()
            ui.onNodeWithText("移除此记录").performClick()
            ui.onNodeWithTag("history-row:${public.key}").assertDoesNotExist()
            ui.runOnIdle{assertTrue(vm.history.any{it.media.key==second.key});assertFalse(vm.history.any{it.media.key==sealedRef.key})}
            ui.onNodeWithTag("history-row:${second.key}").assertExists()
        }finally{
            ui.runOnIdle{owner.clear();SealedMedia.unlock();SealedMedia.unseal(sealedRef.key);SealedMedia.lock();prefs.mode=old}
            listOf(public,second,sealedRef).forEach{history.remove(it.key)}
        }
    }

    @Test fun cloudRefreshBudgetIsBoundedAndResetsWithEpisodeOrTime() {
        var now=0L
        val gate=CloudStreamRetryGate{now}
        assertTrue(gate.allow("one"));assertTrue(gate.allow("one"));assertFalse(gate.allow("one"))
        now=60_000;assertTrue(gate.allow("one"));assertTrue(gate.allow("two"));assertTrue(gate.allow("two"));assertFalse(gate.allow("two"))
    }

    @Test fun variantOnlyAutoUsesRealAlternativesWithCooldownAndNoUpwardFlapping(){
        var at=0L
        val state=CloudAdaptiveState{at}
        val variants=listOf("QHD","FHD","HD","SD","LD").map{io.localplayer.core.AliyunVideoVariant(it,"https://fixture.invalid/$it.m3u8")}
        val info=io.localplayer.core.AliyunVideoInfo(variants,null)
        assertTrue(state.resolve(info).endsWith("HD.m3u8"))
        assertTrue(state.stepDown());assertEquals("SD",state.currentTemplate())
        assertFalse(state.stepDown());at=14999;assertFalse(state.stepDown())
        at=15000;assertTrue(state.stepDown());assertEquals("LD",state.currentTemplate())
        assertTrue(state.resolve(info).endsWith("LD.m3u8"));assertFalse(state.stepDown())
        assertEquals("https://fixture.invalid/master.m3u8",state.resolve(io.localplayer.core.AliyunVideoInfo(variants,"https://fixture.invalid/master.m3u8")))
        assertFalse(state.canStepDown())
    }

    @Test fun qualityDiscoveryLoadsOnOpenOriginalStaysSelectedAndAutoNeedsRealAlternatives(){
        val source=io.localplayer.core.Source(label="画质夹具",protocol=io.localplayer.core.Protocol.ALIYUN,host="fixture.invalid",domain="fixture")
        val uri=PlaybackRegistry.add(source,io.localplayer.core.Credentials(""),"video","video.mp4")
        val item=androidx.media3.common.MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        lateinit var player:androidx.media3.exoplayer.ExoPlayer
        try{
            ui.runOnIdle{
                player=androidx.media3.exoplayer.ExoPlayer.Builder(ui.activity).build();player.setMediaItem(item)
                ui.activity.setContent{PlayerTheme{CloudQualityChoices(player,item){_,cancellation->
                    cancellation.check()
                    val choices=if(calls.incrementAndGet()==1)listOf("FHD")else listOf("FHD","LD")
                    io.localplayer.core.AliyunVideoInfo(choices.map{io.localplayer.core.AliyunVideoVariant(it,"https://fixture.invalid/$it.m3u8")},null)
                }}}
            }
            ui.onNodeWithTag("cloud-quality-original").assertIsSelected()
            ui.waitUntil(5000){ui.onAllNodesWithTag("cloud-quality-FHD").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(1,calls.get())
            ui.onNodeWithTag("cloud-quality-auto").assertDoesNotExist()
            ui.onNodeWithTag("load-cloud-quality").performClick()
            ui.waitUntil(5000){ui.onAllNodesWithTag("cloud-quality-auto").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("cloud-quality-original").assertIsSelected()
            ui.runOnIdle{
                assertEquals(androidx.media3.common.Player.STATE_IDLE,player.playbackState)
                val state=PlaybackRegistry.get(uri).adaptive
                PlaybackRegistry.releaseConnection(uri)
                assertSame("Transport renewal preserves adaptive session IDs",state,PlaybackRegistry.get(uri).adaptive)
            }
        }finally{ui.runOnIdle{player.release()};PlaybackRegistry.remove(uri)}
    }

    private fun capture(name:String){
        ui.waitForIdle()
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(ui.activity.cacheDir,"phase2-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
