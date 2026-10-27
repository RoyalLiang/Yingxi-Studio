package io.localplayer.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerUi66RegressionTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 private fun orientation(landscape:Boolean){
  ui.runOnUiThread{ui.activity.requestedOrientation=if(landscape)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
  ui.waitUntil(5000){runCatching{ui.activity.resources.configuration.orientation==if(landscape)android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT}.getOrDefault(false)}
  ui.waitForIdle()
 }
 private fun capture(name:String){
  ui.waitForIdle()
  android.os.SystemClock.sleep(350) // Native dialog exit animations outlive Compose idling.
  androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->
   java.io.File(ui.activity.cacheDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
   bitmap.recycle()
  }
 }
 @Test fun reportFormContainsConsentWithoutDiagnosticPreviewOrCopy(){
  try{for(landscape in listOf(false,true)){
   orientation(landscape)
   ui.activity.setContent{PlayerTheme{PlaybackReportDialog(null){}}}
   ui.onNodeWithTag("report-preview").assertDoesNotExist()
   ui.onNodeWithTag("report-payload").assertDoesNotExist()
   ui.onNodeWithTag("playback-diagnostic-copy").assertDoesNotExist()
   ui.onNodeWithTag("report-continue").assertIsNotEnabled()
   ui.onNodeWithTag("report-consent").performScrollTo().performClick()
   ui.onNodeWithTag("report-continue").assertIsEnabled()
   capture("report66-${if(landscape)"landscape" else "portrait"}.png")
  }}finally{orientation(false)}
 }
 @Test fun qualityUsesFullWidthRadioRows(){
  lateinit var player:ExoPlayer
  ui.runOnUiThread{player=ExoPlayer.Builder(ui.activity.applicationContext).build()}
  try{
   val wide=Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build()
   val low=wide.buildUpon().setWidth(1280).setHeight(720).build()
   val group=Tracks.Group(TrackGroup(wide,low),false,intArrayOf(C.FORMAT_HANDLED,C.FORMAT_HANDLED),booleanArrayOf(true,false))
   val tracks=Tracks(ImmutableList.of(group))
   for(landscape in listOf(false,true)){
    orientation(landscape)
    ui.activity.setContent{PlayerTheme{PlaybackSheet("画质","quality-style",{}){Column(Modifier.padding(20.dp)){PlaybackQualityChoices(player,null,tracks,player.trackSelectionParameters)}}}}
    val row=ui.onNodeWithTag("video-quality-auto").fetchSemanticsNode()
    assertEquals(Role.RadioButton,row.config[SemanticsProperties.Role])
    val parent=ui.onNodeWithTag("video-quality-options").fetchSemanticsNode()
    assertEquals(parent.boundsInRoot.width,row.boundsInRoot.width,1f)
    capture("quality66-${if(landscape)"landscape" else "portrait"}.png")
   }
  }finally{ui.runOnUiThread{player.release()};orientation(false)}
 }
 @Test fun reportMotionKeepsWaitingQuietAndCompletionTransparent(){
  val phase=mutableStateOf("checking")
  var window:android.view.Window?=null
  var released=false
  ui.activity.setContent{PlayerTheme{PlaybackReportDelivery(phase.value,{}){
   val view=LocalView.current
   SideEffect{window=(view.parent as? DialogWindowProvider)?.window}
   DisposableEffect(Unit){onDispose{released=true}}
  }}}
  ui.onNodeWithTag("report-sending").assertIsDisplayed()
  ui.onNodeWithText("正在上报").assertDoesNotExist()
  val before=ui.onNodeWithTag("report-delivery").fetchSemanticsNode().boundsInRoot
  ui.runOnIdle{phase.value="sending"}
  assertEquals(before,ui.onNodeWithTag("report-delivery").fetchSemanticsNode().boundsInRoot)
  capture("report66-waiting.png")
  ui.runOnIdle{phase.value="success"}
  ui.onNodeWithTag("report-success").assertIsDisplayed()
  ui.onNodeWithContentDescription("上报成功").assertExists()
  ui.onNodeWithText("上报成功").assertDoesNotExist()
  ui.mainClock.advanceTimeBy(650)
  capture("report66-success.png")
  ui.runOnIdle{
   assertTrue("Background submission is disposed after receipt",released)
   assertEquals(0f,window!!.attributes.dimAmount,0f)
   assertEquals(0,window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
   assertEquals(android.graphics.Color.TRANSPARENT,(window!!.decorView.background as? android.graphics.drawable.ColorDrawable)?.color?:android.graphics.Color.TRANSPARENT)
  }
 }
 @Test fun subtitleAndAudioKeepActionsWithoutResourceHints(){
  val context=ui.activity.applicationContext
  val prefs=EnrichmentPreferences(context);val enabled=prefs.subtitles;prefs.subtitles=false
  lateinit var future:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
  ui.runOnUiThread{future=androidx.media3.session.MediaController.Builder(context,androidx.media3.session.SessionToken(context,android.content.ComponentName(context,PlaybackService::class.java))).buildAsync()}
  val controller=future.get(10,java.util.concurrent.TimeUnit.SECONDS)
  try{
   val file=java.io.File(context.cacheDir,"seek-performance.mp4");assertTrue(file.isFile)
   val uri=android.net.Uri.fromFile(file)
   val id="ui66-subtitle"
   val ref=MediaRef(id,"UI fixture.mp4","本机文件",localUri=uri.toString(),sizeBytes=file.length())
   val item=MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setExtras(android.os.Bundle().apply{putString("media-ref",ref.json())}).build()).build()
   ui.runOnUiThread{controller.setMediaItem(item)}
   ui.waitUntil(5000){var ready=false;ui.runOnUiThread{ready=controller.currentMediaItem?.mediaId==id};ready}
   ui.runOnUiThread{
    OnlineSubtitles.status.value=id to "在线字幕测试提示"
    OnlineSubtitles.resources.value=id to listOf(io.localplayer.core.SubtitleResource("match","外部匹配资源","https://example.invalid/match"),io.localplayer.core.SubtitleResource("custom","自定义驿站","https://example.invalid/search"))
   }
   ui.activity.setContent{PlayerTheme{PlaybackOptions(controller,{},{},trackType=C.TRACK_TYPE_TEXT)}}
   ui.onNodeWithText("查看匹配资源",substring=true).assertDoesNotExist()
   ui.onNodeWithText("在线字幕测试提示").assertDoesNotExist()
   ui.onNodeWithText("前往自定义字幕驿站").assertIsDisplayed()
   ui.onNodeWithText("载入字幕文件").assertIsDisplayed()
   capture("subtitle66-portrait.png")
   ui.runOnUiThread{OnlineSubtitles.resources.value=null}
   ui.onNodeWithText("未找到匹配字幕",substring=true).assertDoesNotExist()
   ui.activity.setContent{PlayerTheme{PlaybackOptions(controller,{},{},trackType=C.TRACK_TYPE_AUDIO)}}
   ui.onNodeWithText("外挂音轨共用",substring=true).assertDoesNotExist()
   ui.onNodeWithText("载入音轨文件").assertIsDisplayed()
   capture("audio66-portrait.png")
  }finally{ui.runOnUiThread{OnlineSubtitles.cancel();controller.stop();controller.clearMediaItems();controller.release()};prefs.subtitles=enabled}
 }

}
