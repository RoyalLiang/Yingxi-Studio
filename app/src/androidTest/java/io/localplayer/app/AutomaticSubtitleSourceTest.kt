package io.localplayer.app

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.EnrichmentHttp
import io.localplayer.core.SubHdSubtitles
import io.localplayer.core.MediaIdentity
import io.localplayer.core.Cancellation
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AutomaticSubtitleSourceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var player:CompatibilityPlayer
    private lateinit var oldSource:String
    private var oldEnabled=true
    private var oldBilingual=false
    private lateinit var oldProvider:SubHdSubtitles
    private fun <T> main(block:()->T):T{var result:Result<T>?=null;instrumentation.runOnMainSync{result=runCatching(block)};return result!!.getOrThrow()}
    @Before fun setup(){
        val prefs=EnrichmentPreferences(context);oldSource=prefs.subtitleSource;oldEnabled=prefs.subtitles;oldBilingual=prefs.bilingual
        prefs.subtitleSource="subhd";prefs.subtitles=true;prefs.bilingual=false;oldProvider=OnlineSubtitles.subHd
        main{OnlineSubtitles.cancel()}
    }
    @After fun cleanup(){
        main{OnlineSubtitles.cancel();if(::player.isInitialized){player.stop();player.release()};OnlineSubtitles.subHd=oldProvider}
        EnrichmentPreferences(context).apply{subtitleSource=oldSource;subtitles=oldEnabled;bilingual=oldBilingual}
    }
    private fun prepare(caption:File?=null){
        val file=File(context.cacheDir,"seek-performance.mp4");assertTrue("Seed owned AVC fixture",file.isFile)
        val id="auto-source-${UUID.randomUUID()}"
        val ref=MediaRef(id,"A.Test.2024.mp4","本机文件",localUri=Uri.fromFile(file).toString(),sizeBytes=file.length())
        val extras=Bundle().apply{putString("media-ref",ref.json());putString("play-intent",id)}
        val item=MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).apply{
                if(caption!=null)setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(caption)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("zh").build()))
            }.build()
        main{
            val data=DefaultDataSource.Factory(context)
            player=CompatibilityPlayer(context,ExoPlayer.Builder(context).setMediaSourceFactory(SidecarMediaSourceFactory(DefaultMediaSourceFactory(data,PlaybackExtractors(context)),data)).build())
            player.setMediaItems(listOf(item,item.buildUpon().setMediaId("$id-next").build()),0,2500)
            player.setPlaybackSpeed(1.25f);player.prepare();player.pause()
        }
        await{player.playerError!=null||player.playbackState==Player.STATE_READY};main{assertNull(player.playerError)}
    }
    private fun await(condition:()->Boolean){
        val until=android.os.SystemClock.elapsedRealtime()+15000
        while(android.os.SystemClock.elapsedRealtime()<until){if(main(condition))return;Thread.sleep(30)}
        fail("Automatic subtitle state did not settle: ${OnlineSubtitles.status.value}")
    }
    @Test fun missingSubtitleAutomaticallyLoadsAndRendersWithoutChangingPlayback(){
        MockWebServer().use{server->
            server.enqueue(MockResponse().setBody("<a href='/a/ABC123'>A Test 2024 简体</a>"))
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/test"}"""))
            server.enqueue(MockResponse().setBody("1\n00:00:00,000 --> 00:00:30,000\n这是用于测试的自动字幕，播放进度应当保持不变。\n"))
            val provider=SubHdSubtitles(EnrichmentHttp(permitHttpForTests=true),server.url("/").toString())
            main{OnlineSubtitles.subHd=provider}
            prepare();main{OnlineSubtitles.schedule(context,player)}
            try{await{player.currentCues.cues.any{it.text.toString().contains("用于测试的自动字幕")}}}
            catch(e:AssertionError){throw AssertionError("${e.message}; requests=${server.requestCount}; resources=${OnlineSubtitles.resources.value?.second?.map{it.id}}",e)}
            main{
                assertEquals(2,player.mediaItemCount);assertEquals(0,player.currentMediaItemIndex)
                assertEquals(2500L,player.currentPosition);assertFalse(player.playWhenReady);assertEquals(1.25f,player.playbackParameters.speed)
                assertEquals("已载入 SubHD 字幕",OnlineSubtitles.status.value?.second)
                OnlineSubtitles.schedule(context,player)
            }
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun refusedDownloadKeepsRealCandidatesAndLeavesPlaybackAlone(){
        MockWebServer().use{server->
            server.enqueue(MockResponse().setBody("<a href='/a/ABC123'>A Test 2024 简体</a>"))
            server.enqueue(MockResponse().setBody("""{"success":false,"msg":"Return to detail page"}"""))
            val provider=SubHdSubtitles(EnrichmentHttp(permitHttpForTests=true),server.url("/").toString())
            main{OnlineSubtitles.subHd=provider}
            prepare();main{OnlineSubtitles.schedule(context,player)}
            await{OnlineSubtitles.resources.value?.second?.isNotEmpty()==true}
            main{
                assertTrue(player.currentMediaItem!!.localConfiguration!!.subtitleConfigurations.isEmpty())
                assertEquals(2500L,player.currentPosition);assertFalse(player.playWhenReady);assertNull(player.playerError)
                assertTrue(OnlineSubtitles.status.value!!.second.contains("暂未提供"))
            }
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun existingCaptionAndDisabledTextDoNotStartAWebsiteRequest(){
        MockWebServer().use{server->
            val provider=SubHdSubtitles(EnrichmentHttp(permitHttpForTests=true),server.url("/").toString())
            main{OnlineSubtitles.subHd=provider}
            val caption=File(context.cacheDir,"already-there.srt").apply{writeText("1\n00:00:00,000 --> 00:00:30,000\n已有字幕。\n")}
            try{
                prepare(caption);main{OnlineSubtitles.schedule(context,player)}
                assertNull(OnlineSubtitles.status.value)
                main{player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build();OnlineSubtitles.schedule(context,player)}
                assertEquals(0,server.requestCount)
            }finally{caption.delete()}
        }
    }
    @Test fun disablingSubtitlesDuringTheInitialDelayPreventsTheLookup(){
        MockWebServer().use{server->
            val provider=SubHdSubtitles(EnrichmentHttp(permitHttpForTests=true),server.url("/").toString())
            main{OnlineSubtitles.subHd=provider}
            prepare();main{OnlineSubtitles.schedule(context,player)
                player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()}
            Thread.sleep(3400)
            assertEquals(0,server.requestCount)
            assertNull(OnlineSubtitles.status.value)
        }
    }
    @Test fun downloadedCaptionIsEligibleForTheChinesePreference(){
        MockWebServer().use{server->
            server.enqueue(MockResponse().setBody("<a href='/a/ABC123'>A Test 2024 简体</a>"))
            server.enqueue(MockResponse().setBody("""{"success":true,"url":"/down/test"}"""))
            server.enqueue(MockResponse().setBody("1\n00:00:00,000 --> 00:00:30,000\n这是用于测试的自动字幕，播放进度应当保持不变。\n"))
            val provider=SubHdSubtitles(EnrichmentHttp(permitHttpForTests=true),server.url("/").toString())
            val identity=MediaIdentity("A Test",2024)
            Cancellation().use{c->
                val resource=provider.search(identity,c).single()
                val caption=provider.download(resource,identity,c).single()
                assertNotNull("Valid simplified caption must pass the existing preference filter",OnlineSubtitles.provider.preferenceScore(caption.bytes,false))
            }
        }
    }
}
