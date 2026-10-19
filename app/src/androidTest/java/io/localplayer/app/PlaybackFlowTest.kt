package io.localplayer.app

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun <T> main(block:()->T):T {
        var value:Result<T>?=null
        instrumentation.runOnMainSync{value=runCatching(block)}
        return value!!.getOrThrow()
    }
    // Software-rendered emulators share the host with both protocol fixtures.
    // Keep a bounded budget; success still requires actual state, frames and cues.
    private fun await(message:String,timeout:Long=60000,detail:()->String={""},condition:()->Boolean){
        val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout)
        while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)}
        fail("$message ${detail()}")
    }
    @Test fun actualPlayerPersistsProgressAndSeeksAfterIdleForBothProtocols(){
        val secret=Credentials(TestFixtures.password)
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("protocol")==null||args.getString("protocol") in listOf("SMB","SFTP"))
        val initialResume=args.getString("initial_resume_ms")?.toLong() ?: 45000L
        val smb=Source(label="SMB playback fixture",protocol=Protocol.SMB,host="10.0.2.2",port=TestFixtures.smbPort,root="media",username=TestFixtures.username)
        val untrusted=Source(label="SFTP playback fixture",protocol=Protocol.SFTP,host="10.0.2.2",port=TestFixtures.sftpPort,root="/",username=TestFixtures.username)
        val fingerprint=if(args.getString("protocol")=="SMB")"unused" else try{SftpConnector().connect(untrusted,secret).close();throw AssertionError("Expected untrusted key")}
            catch(key:HostKeyRequired){key.fingerprint}
        val sftp=untrusted.copy(trustedKey=fingerprint,trustedAuthority=untrusted.authority)
        for(source in listOf(smb,sftp).filter{args.getString("protocol")==null||it.protocol.name==args.getString("protocol")}){
            val key="playback-test-"+UUID.randomUUID()
            val media=MediaRef(key,"long-sample.mp4",source.label,source.id,"long-sample.mp4")
            val uri=PlaybackRegistry.add(source,secret,"long-sample.mp4",media.title)
            val history=HistoryStore(context)
            val scenario=ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(context,PlayTarget(uri,media.title,initialResume,media)))
            val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val controller=future.get(10,TimeUnit.SECONDS)
        val playbackDetail={main{"protocol=${source.protocol}, state=${controller.playbackState}, ready=${controller.playWhenReady}, position=${controller.currentPosition}, duration=${controller.duration}, error=${controller.playerError}"}}
            val captionFile=java.io.File(context.cacheDir,"caption-$key.srt").apply{writeText("1\n00:00:00,000 --> 00:02:00,000\nYINGXI TEST CAPTION\n")}
            try{
            await("${source.protocol} cold resume did not start",timeout=60000,detail={main{"state=${controller.playbackState}, ready=${controller.playWhenReady}, position=${controller.currentPosition}, error=${controller.playerError}"}}){main{controller.isPlaying&&controller.videoSize.width>0&&controller.currentPosition>=initialResume}}
            await("${source.protocol} did not display actual test-pattern pixels"){
                val screen=instrumentation.uiAutomation.takeScreenshot() ?: return@await false
                try{listOf(.15,.25,.75,.85).any{x->listOf(.45,.55).any{y->
                    val pixel=screen.getPixel((screen.width*x).toInt(),(screen.height*y).toInt())
                    val channels=listOf(android.graphics.Color.red(pixel),android.graphics.Color.green(pixel),android.graphics.Color.blue(pixel))
                    channels.max()-channels.min()>50&&channels.max()>80
                }}}finally{screen.recycle()}
            }
            instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yingxi-m1-cold-${source.protocol}.png").use{descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use{it.readBytes()}}
            if(args.getString("probe_only")=="true")continue
                main{controller.pause()}
                await("Progress not saved"){history.entries().any{it.media.key==key&&it.positionMs>0&&it.durationMs>100000}}
                Thread.sleep(10_000)
                main{controller.seekTo(75000);controller.play()}
            await("${source.protocol} idle seek did not resume",detail={main{"state=${controller.playbackState}, ready=${controller.playWhenReady}, position=${controller.currentPosition}, error=${controller.playerError}"}}){main{controller.isPlaying&&controller.currentPosition>=75000&&controller.playerError==null}}
                main{controller.pause()}
                await("Seek progress not saved"){history.entries().any{it.media.key==key&&it.positionMs>=75000}}
                val saved=history.entries().first{it.media.key==key}
                assertEquals(saved.positionMs,history.resumePosition(media))
                main{controller.seekTo(controller.duration-800);controller.play()}
            await("Playback did not end",timeout=60000,detail=playbackDetail){main{controller.playbackState==Player.STATE_ENDED}}
                await("Completion not saved"){history.entries().any{it.media.key==key&&it.completed}}
                assertEquals(0L,history.resumePosition(media))
                history.remove(key)
            // Rebuilding the same item for captions must retain the revoked token.
            main{
                val caption=androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(captionFile))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP).setLanguage("und").setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()
                val item=controller.currentMediaItem!!.buildUpon().setSubtitleConfigurations(listOf(caption)).build()
                controller.setMediaItem(item,10000)
                controller.trackSelectionParameters=controller.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage("und").build()
                controller.prepare();controller.play()
            }
            await("${source.protocol} subtitle reload"){main{controller.isPlaying&&controller.currentPosition>=10000&&controller.currentTracks.groups.any{it.type==androidx.media3.common.C.TRACK_TYPE_TEXT&&it.isSelected}}}
            await("${source.protocol} subtitle cues were not delivered to the player view"){main{controller.currentCues.cues.any{it.text?.contains("YINGXI TEST CAPTION")==true}}}
            Thread.sleep(300)
            instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/yingxi-m1-caption-${source.protocol}.png").use{descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use{it.readBytes()}}
            main{controller.pause()}
            Thread.sleep(300)
            assertFalse(history.entries().any{it.media.key==key})
            }finally{
                main{controller.stop();controller.clearMediaItems();controller.release()}
                scenario.close();PlaybackRegistry.remove(uri)
                captionFile.delete()
                history.remove(key);SourceStore(context).forgetPosition(key)
            }
            assertFalse(history.entries().any{it.media.key==key})
        }
    }
}
