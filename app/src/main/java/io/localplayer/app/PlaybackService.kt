package io.localplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import androidx.media3.session.DefaultMediaNotificationProvider

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService:MediaSessionService(){
    private var session:MediaSession?=null
 private var privateObserver:(()->Unit)?=null
 private var videoRecovery:VideoDecoderRecovery?=null
    private lateinit var store:SourceStore
    private lateinit var history:HistoryStore
    private var requestIds=setOf<String>()
    private var current:android.net.Uri?=null
    private val durations=mutableMapOf<String,Long>()
    private val completedItems=mutableSetOf<String>()
    private val handler=Handler(Looper.getMainLooper())
    private val checkpoint=object:Runnable{
        override fun run(){if(session?.player?.isPlaying==true)persist();handler.postDelayed(this,5000)}
    }
    override fun onCreate(){
        super.onCreate();store=SourceStore(this);history=HistoryStore(this)
        val prefs=PlaybackPreferences(this)
        val renderers=playbackRenderers(this)
 val player=ExoPlayer.Builder(this,renderers)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(10000,45000,750,1500).setTargetBufferBytes(64*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this,RemoteDataSource.Factory()),PlaybackExtractors(this)))
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(),true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        videoRecovery=VideoDecoderRecovery(player,renderers)
        privateObserver=SealedMedia.observe{
            val protected=(0 until player.mediaItemCount).any{index->player.getMediaItemAt(index).mediaMetadata.extras?.getString("media-ref")?.let{runCatching{SealedMedia.protects(MediaRef.parse(it))}.getOrDefault(true)}==true}
            if(protected){player.pause();player.stop();player.clearMediaItems()}
        }
 player.addAnalyticsListener(PlaybackMetrics.listener())
        player.setPlaybackSpeed(prefs.speed)
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.effectiveAudioLanguage).setPreferredTextLanguage(prefs.textLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,prefs.subtitlesDisabled).build()
        player.addListener(object:Player.Listener{
            override fun onTimelineChanged(timeline:Timeline,reason:Int){
                val next=(0 until player.mediaItemCount).mapNotNull{player.getMediaItemAt(it).mediaMetadata.extras?.getString("play-intent")}.toSet()
                PlaybackRequests.retainActive(next)
                (requestIds-next).forEach(PlaybackRequests::remove)
                requestIds=next
            }
            override fun onEvents(player:Player,events:Player.Events){
                val live=(0 until player.mediaItemCount).map{player.getMediaItemAt(it).mediaId}.toSet()
                durations.keys.retainAll(live);completedItems.retainAll(live)
                updateSessionActivity()
                if(player.mediaItemCount==0)stopSelf()
            }
            override fun onMediaItemTransition(item:MediaItem?,reason:Int){
                val next=item?.localConfiguration?.uri
                player.setWakeMode(if(next?.scheme=="localplayer")C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
                if(current!=next){
                    OnlineSubtitles.cancel();PlaybackRegistry.releaseConnection(current);current=next
                    player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().clearOverrides()
                        .setPreferredAudioLanguage(prefs.effectiveAudioLanguage).setPreferredTextLanguage(prefs.textLanguage)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,prefs.subtitlesDisabled).build()
                }
                persist()
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){if(!isPlaying)persist()}
            override fun onPlaybackStateChanged(state:Int){
                if(state==Player.STATE_READY||state==Player.STATE_ENDED)persist()
                if(state==Player.STATE_READY)OnlineSubtitles.schedule(this@PlaybackService,player)
            }
            override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){
                // At an automatic transition the Player already points at the NEXT item.
                // Save the old item's final position before updating the new item.
                oldPosition.mediaItem?.let{old->
                    if(old.mediaId!=newPosition.mediaItem?.mediaId||reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION){
                        save(old,oldPosition.positionMs,durations[old.mediaId] ?: 0L,reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION||old.mediaId in completedItems)
                    }
                }
                persist()
            }
        })
        session=MediaSession.Builder(this,player).setCallback(object:MediaSession.Callback{
            override fun onConnect(session:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
                if(controller.uid==Process.myUid())return super.onConnect(session,controller)
                if(!controller.isTrusted)return MediaSession.ConnectionResult.reject()
                // System/headset controllers may operate the existing queue, never inject media.
                val commands=Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE,Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,Player.COMMAND_GET_METADATA
                ).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SessionCommands.EMPTY).setAvailablePlayerCommands(commands).build()
            }
        }).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply{
            setSmallIcon(R.drawable.ic_brand_monochrome)
        })
        handler.post(checkpoint)
    }
    private fun updateSessionActivity(){
        val active=session?:return
        val id=active.player.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")?:return
        val intent=Intent(this,PlaybackActivity::class.java).putExtra("play-intent",id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        active.setSessionActivity(PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
    override fun onTaskRemoved(rootIntent:Intent?){
        // An explicit task dismissal ends playback; Home/lock screen keep the service alive.
        session?.player?.let{it.pause();persist();it.stop();it.clearMediaItems()}
        stopSelf()
    }
    private fun persist(){
        val player=session?.player ?: return
        val item=player.currentMediaItem ?: return
        if(player.playerError!=null)return
        val duration=player.duration.takeIf{it!=C.TIME_UNSET&&it>0} ?: 0
        if(duration>0)durations[item.mediaId]=duration
        if(duration<=0&&player.currentPosition<=0)return
        save(item,player.currentPosition,duration,player.playbackState==Player.STATE_ENDED)
    }
    private fun save(item:MediaItem,position:Long,duration:Long,completed:Boolean){
        if(completed)completedItems.add(item.mediaId)else completedItems.remove(item.mediaId)
        val extras=item.mediaMetadata.extras
        val ref=extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()} ?: return
        val token=HistoryToken(extras.getLong("history-clear"),extras.getLong("history-item"))
        val saved=runCatching{history.update(ref,token,position,duration,completed)}.getOrDefault(false)
        if(saved)store.rememberPosition(ref.key,if(completed)0L else position)
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session
    override fun onDestroy(){
        privateObserver?.invoke();privateObserver=null
        val owned=requestIds
        OnlineSubtitles.cancel();persist();handler.removeCallbacksAndMessages(null);videoRecovery?.close();videoRecovery=null;session?.player?.release();session?.release();session=null
        // A later Activity may already have registered its pending request.
        // This service only releases requests actually adopted by its timeline.
        PlaybackRequests.retainActive(emptySet());owned.forEach(PlaybackRequests::remove)
        PlaybackRegistry.releaseConnection(current);super.onDestroy()
    }
}
