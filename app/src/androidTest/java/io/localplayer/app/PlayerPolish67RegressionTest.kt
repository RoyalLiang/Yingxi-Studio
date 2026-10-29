package io.localplayer.app

import android.content.ComponentName
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.localplayer.core.*
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerPolish67RegressionTest {
 @get:Rule val ui=createAndroidComposeRule<MainActivity>()
 private fun <T> main(block:()->T):T{var result:Result<T>?=null;ui.runOnUiThread{result=runCatching(block)};return result!!.getOrThrow()}
 private fun orientation(landscape:Boolean,activity:android.app.Activity){
  val desired=if(landscape)android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
  if(activity.resources.configuration.orientation!=desired){
   if(ui.onAllNodesWithTag("video-definition").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("playback-gesture-surface").performClick()
   ui.onNodeWithContentDescription("旋转屏幕").performClick()
  }
  ui.waitUntil(5000){activity.resources.configuration.orientation==desired}
  ui.waitForIdle()
 }
 private fun capture(name:String){
  ui.waitForIdle();android.os.SystemClock.sleep(350)
  androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{b->File(ui.activity.cacheDir,name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 }
 private fun withController(block:(MediaController)->Unit){
  val context=ui.activity.applicationContext;val prefs=EnrichmentPreferences(context);val old=prefs.subtitles;prefs.subtitles=false
  val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
  val c=future.get(10,TimeUnit.SECONDS)
  try{block(c)}finally{main{c.stop();c.clearMediaItems();c.release()};prefs.subtitles=old}
 }
 private fun show(c:MediaController){main{ui.activity.setContent{PlayerTheme{FullscreenPlayer(c,"First clip",null,{},{},{},{},false,null,{},{})}}}}
 private fun ready(c:MediaController){ui.waitUntil(15000){main{c.playerError!=null||c.playbackState==Player.STATE_READY&&PlaybackMetrics.snapshot().firstFrameMs!=null}};main{assertNull(c.playerError)}}
 private fun colorFraction(red:Boolean):Double{
  val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
  var matches=0;var total=0;val radius=b.width/9
  for(y in b.height/2-radius until b.height/2+radius step 12)for(x in b.width/2-radius until b.width/2+radius step 12){
   val p=b.getPixel(x,y);val r=android.graphics.Color.red(p);val g=android.graphics.Color.green(p);val blue=android.graphics.Color.blue(p)
   if(if(red)r>180&&g<90&&blue<90 else blue>170&&r<90&&g<90)matches++;total++
  }
  b.recycle();return matches.toDouble()/total
 }
 @Test fun subtitleStationTitleDoesNotWrapAtPhoneWidths(){
  val prefs=EnrichmentPreferences(ui.activity);val old=prefs.subtitleSource;prefs.subtitleSource="subhd"
  try{for(width in listOf(330,336,360)){
   val density=ui.activity.resources.displayMetrics.density
   main{ui.activity.setContent{PlayerTheme{CompositionLocalProvider(LocalDensity provides Density(density,1.3f)){Column(Modifier.width(width.dp).statusBarsPadding()){EnrichmentSettings()}}}}}
   val layouts=mutableListOf<TextLayoutResult>()
   ui.onNodeWithText("字幕驿站").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
   capture("subtitle67-layout.png")
   assertEquals("Subtitle station title wraps at $width dp",1,layouts.single().lineCount)
  }}finally{prefs.subtitleSource=old}
 }
 @Test fun qualityOpensACompactMenuAtTheActualPlayerControl()=withController{c->
  val file=File(ui.activity.cacheDir,"red67.mp4");assertTrue(file.isFile)
  val uri=Uri.fromFile(file);val ref=MediaRef("quality67-${java.util.UUID.randomUUID()}","Quality fixture","Local",localUri=uri.toString(),sizeBytes=file.length())
  val target=PlayTarget(uri,ref.title,0,ref)
  var playerActivity:PlaybackActivity?=null
  main{ui.activity.startActivity(PlaybackActivity.intent(ui.activity,target))}
  try{
   ui.waitUntil(10000){playerActivity=main{androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()};playerActivity!=null}
   ready(c);main{c.pause()}
   for(landscape in listOf(false,true)){
    orientation(landscape,playerActivity!!)
    if(ui.onAllNodesWithTag("video-definition").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("playback-gesture-surface").performClick()
    ui.onNodeWithTag("video-definition").performClick();capture("quality67-${if(landscape)"landscape" else "portrait"}.png")
    ui.onNodeWithTag("audio-subtitle-panel").assertDoesNotExist()
    val menu=ui.onNodeWithTag("video-quality-popup").getUnclippedBoundsInRoot()
    assertTrue("Quality popup is too wide: $menu",menu.width<=280.dp)
    assertTrue("Quality popup is too tall: $menu",menu.height<=320.dp)
    ui.onNodeWithTag("video-quality-current").performClick()
    ui.onNodeWithTag("video-quality-popup").assertDoesNotExist()
   }
  }finally{main{playerActivity?.finish()};PlaybackRequests.remove(target.playId)}
 }
 @Test fun customStationInputUsesTheSettingWidthAndSavesAtLargeFont(){
  val prefs=EnrichmentPreferences(ui.activity);val oldSource=prefs.subtitleSource;val oldUrl=prefs.customSubtitleSearch
  prefs.subtitleSource="custom"
  try{
   val density=ui.activity.resources.displayMetrics.density
   main{ui.activity.setContent{PlayerTheme{CompositionLocalProvider(LocalDensity provides Density(density,1.8f)){
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp).verticalScroll(rememberScrollState())){EnrichmentSettings()}
   }}}}
   ui.onNodeWithTag("subtitle-search-url").performScrollTo().performTextReplacement("https://example.org/search/{query}")
   ui.onNodeWithTag("save-subtitle-search").performClick()
   main{assertEquals("https://example.org/search/{query}",prefs.customSubtitleSearch)}
   ui.onNodeWithTag("save-subtitle-search").assertIsNotEnabled()
   ui.activity.currentFocus?.let{androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{ui.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0)}}
   capture("subtitle67-custom-large.png")
  }finally{prefs.subtitleSource=oldSource;ui.activity.getSharedPreferences("media-enrichment-v1",0).edit().putString("custom-subtitle-search",oldUrl).commit()}
 }
 @Test fun delayedNativeFrameRetainsTheEventTimelinesIdentity(){
  val player=main{androidx.media3.exoplayer.ExoPlayer.Builder(ui.activity).build()}
  try{main{
   val metadata=MediaMetadata.Builder().setExtras(android.os.Bundle().apply{putString("play-intent","shared-queue67")}).build()
            val old=MediaItem.Builder().setMediaId("frame-old67").setUri("file:///old.mp4").setMediaMetadata(metadata).build()
            val next=MediaItem.Builder().setMediaId("frame-next67").setUri("file:///next.mp4").setMediaMetadata(metadata).build()
            assertNotEquals(PlaybackFrames.key(old),PlaybackFrames.key(next))
            player.setMediaItem(old);val oldTimeline=player.currentTimeline
   player.setMediaItem(next)
   val event=androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime(0,oldTimeline,0,null,0,player.currentTimeline,0,null,0,0)
   PlaybackMetrics.listener().onRenderedFirstFrame(event,Any(),0)
   assertEquals(PlaybackFrames.key(old),PlaybackFrames.last.value.key)
   assertNotEquals(PlaybackFrames.key(player.currentMediaItem),PlaybackFrames.last.value.key)
  }}finally{main{player.release()}}
 }
 @Test fun selectingDelayedEpisodeHidesThePreviousVideoWhileLoading()=delayedEpisode("blue67.mp4")
 @Test fun selectingAudioEndsTheLoadingCoverWithoutAVideoFrame()=delayedEpisode("tone67.m4a")
 @Test fun selectingFailedEpisodeShowsTheErrorWithoutThePreviousVideo()=delayedEpisode("missing67.mp4")
 @Test fun selectingCompatibilityEpisodeReleasesTheCoverOnItsActualFrame()=delayedEpisode("software-mpeg1.mkv")
 private fun delayedEpisode(destination:String)=withController{c->
  val blueRequested=CountDownLatch(1);val releaseBlue=CountDownLatch(1)
  val source=Source(label="Delayed episodes",protocol=Protocol.WEBDAV,host="fixture.invalid")
  val connector=object:Connector{
   override fun connect(source:Source,credentials:Credentials,cancellation:Cancellation)=object:RemoteSession{
    override fun list(path:String)=emptyList<Entry>()
    override fun open(path:String):RemoteFile{
     if(path==destination){blueRequested.countDown();check(releaseBlue.await(15,TimeUnit.SECONDS))}
     return object:RemoteFile{
      private val file=RandomAccessFile(File(ui.activity.cacheDir,path),"r")
      override val size=file.length()
      override fun readAt(position:Long,buffer:ByteArray,offset:Int,length:Int):Int{file.seek(position);return file.read(buffer,offset,length)}
      override fun close(){file.close()}
     }
    }
    override fun close(){}
   }
  }
  val uris=listOf("red67.mp4",destination).map{PlaybackRegistry.add(source,Credentials(""),it,it,connector)}
  val items=uris.mapIndexed{index,uri->MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(if(index==0)"Red first" else "Blue second").setExtras(android.os.Bundle().apply{putString("play-intent","shared-queue67")}).build()).build()}
   try{
    main{c.setMediaItems(items)};show(c);main{c.prepare();c.play()};ready(c)
    ui.waitUntil(5000){colorFraction(true)>.5}
    main{c.pause();c.seekTo(5000)}
    ui.onNodeWithContentDescription("选集",useUnmergedTree=true).performClick();ui.onNodeWithTag("queue-item-1").performClick()
    assertTrue("Next episode request did not start",blueRequested.await(5,TimeUnit.SECONDS))
    ui.onNodeWithText("Blue second").assertIsDisplayed();capture("episode67-${destination.substringBefore('.')}-loading.png")
    assertTrue("Previous video remains visible while the chosen episode loads",colorFraction(true)<.05)
    releaseBlue.countDown()
    ui.waitUntil(25000){main{c.currentMediaItemIndex==1&&(c.playbackState==Player.STATE_READY||c.playerError!=null)}}
    if(destination=="missing67.mp4")main{assertNotNull(c.playerError)} else main{assertNull(c.playerError?.stackTraceToString(),c.playerError)}
    ui.waitUntil(10000){ui.onAllNodesWithTag("episode-loading-cover").fetchSemanticsNodes().isEmpty()}
    if(destination=="blue67.mp4")ui.waitUntil(5000){colorFraction(false)>.5}
    if(destination=="software-mpeg1.mkv")ui.waitUntil(15000){main{c.currentMediaItem?.mediaMetadata?.extras?.getBoolean("sdr-compatibility")==true}}
    if(destination=="software-mpeg1.mkv")main{assertTrue(c.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("sdr-compatibility"));assertEquals(PlaybackFrames.key(c.currentMediaItem),PlaybackFrames.last.value.key)}
    capture("episode67-${destination.substringBefore('.')}-ready.png")
    if(destination=="missing67.mp4"){
     ui.onNodeWithContentDescription("选集",useUnmergedTree=true).performClick();ui.onNodeWithTag("queue-item-0").performClick()
     ui.waitUntil(10000){main{c.currentMediaItemIndex==0&&c.playbackState==Player.STATE_READY&&c.playerError==null}&&colorFraction(true)>.5}
     ui.onNodeWithTag("episode-loading-cover").assertDoesNotExist()
    }
   }finally{releaseBlue.countDown();main{c.stop();c.clearMediaItems()};uris.forEach(PlaybackRegistry::remove)}
 }
}
