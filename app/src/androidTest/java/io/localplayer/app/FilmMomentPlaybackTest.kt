package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FilmMomentPlaybackTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun <T> main(action:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(action)};return result!!.getOrThrow()}
    @Test fun rememberingMomentKeepsTheSinglePlayerRunning(){rememberMoment(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)}
    @Test fun landscapeAlsoKeepsThePlayerRunning(){rememberMoment(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)}
    private fun rememberMoment(orientation:Int){
        main{ui.activity.requestedOrientation=orientation}
        val expected=if(orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ui.waitUntil(5000){ui.activity.resources.configuration.orientation==expected}
        val context=ui.activity.applicationContext
        val sample=betaPlaybackFixture(context);assertTrue("local fixture must be staged",sample.isFile)
        val ref=MediaRef("beta-playback-${System.nanoTime()}","灯下的刻度.mp4","本机片箱",localUri=Uri.fromFile(sample).toString(),sizeBytes=sample.length())
        val controller=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}.get(10,TimeUnit.SECONDS)
        try{
            main{
                val extras=Bundle().apply{putString("media-ref",ref.json())}
                controller.setMediaItem(MediaItem.Builder().setUri(ref.localUri!!).setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).setExtras(extras).build()).build());controller.prepare();controller.play()
                ui.activity.setContent{PlayerTheme{FullscreenPlayer(controller,ref.title,null,{},{},{},{},false,{},{} ,{})}}
            }
            ui.waitUntil(10000){main{controller.playbackState==Player.STATE_READY&&controller.currentPosition>300}}
            val before=main{controller.currentPosition}
            ui.onNodeWithTag("remember-film-moment").performClick()
            ui.waitUntil(5000){FilmLibraryStore(context).use{it.works().flatMap{w->w.films}.firstOrNull{f->f.media.key==ref.key}?.let{f->it.moments(f.id).isNotEmpty()}==true}}
            val after=main{controller.currentPosition}
            assertTrue(after>=before);main{assertTrue(controller.isPlaying);assertNull(controller.playerError);assertEquals(1,controller.mediaItemCount)}
            FilmLibraryStore(context).use{store->val film=store.works().flatMap{it.films}.first{it.media.key==ref.key};val note=store.moments(film.id).single();assertTrue(note.positionMs in before..after)}
        }finally{main{controller.stop();controller.clearMediaItems();controller.release()};HistoryStore(context).remove(ref.key)}
    }
}
