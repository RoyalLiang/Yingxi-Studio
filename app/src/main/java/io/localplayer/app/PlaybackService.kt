package io.localplayer.app

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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService:MediaSessionService(){
    private var session:MediaSession?=null
    private var current:android.net.Uri?=null
    private var media:MediaRef?=null
    private var historyToken:HistoryToken?=null
    private lateinit var store:SourceStore
    private lateinit var history:HistoryStore
    private val handler=Handler(Looper.getMainLooper())
    private val checkpoint=object:Runnable{
        override fun run(){if(session?.player?.isPlaying==true)persist();handler.postDelayed(this,5000)}
    }
    override fun onCreate(){
        super.onCreate();store=SourceStore(this);history=HistoryStore(this)
        val prefs=PlaybackPreferences(this)
        val player=ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this,RemoteDataSource.Factory())))
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(),true)
            .setHandleAudioBecomingNoisy(true).build()
        player.setPlaybackSpeed(prefs.speed)
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLanguage).setPreferredTextLanguage(prefs.textLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,prefs.subtitlesDisabled).build()
        player.addListener(object:Player.Listener{
            override fun onMediaItemTransition(item:MediaItem?,reason:Int){
                val next=item?.localConfiguration?.uri
                if(current!=next){PlaybackRegistry.remove(current);current=next}
                media=item?.mediaMetadata?.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}
                historyToken=media?.let{HistoryToken(item?.mediaMetadata?.extras?.getLong("history-clear") ?: 0,item?.mediaMetadata?.extras?.getLong("history-item") ?: 0)}
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){if(!isPlaying)persist()}
            override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_READY||state==Player.STATE_ENDED)persist()}
            override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int){persist()}
        })
        session=MediaSession.Builder(this,player).setCallback(object:MediaSession.Callback{
            override fun onConnect(session:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult=
                if(controller.uid==Process.myUid()||controller.isTrusted)super.onConnect(session,controller)else MediaSession.ConnectionResult.reject()
        }).build()
        handler.post(checkpoint)
    }
    private fun persist(){
        val player=session?.player ?: return
        val uri=current ?: return
        if(player.currentMediaItem?.localConfiguration?.uri!=uri||player.playerError!=null)return
        val duration=player.duration.takeIf{it!=C.TIME_UNSET&&it>0} ?: 0
        if(duration<=0&&player.currentPosition<=0)return
        val completed=player.playbackState==Player.STATE_ENDED
        val ref=media
        if(ref!=null){
            val token=historyToken ?: return
            val saved=runCatching{history.update(ref,token,player.currentPosition,duration,completed)}.getOrDefault(false)
            if(saved)store.rememberPosition(ref.key,if(completed)0 else player.currentPosition)
        }else{
            val key=if(uri.scheme=="localplayer")runCatching{PlaybackRegistry.get(uri).resumeKey}.getOrNull()else uri.toString()
            if(key!=null)store.rememberPosition(key,if(completed)0 else player.currentPosition)
        }
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo)=session
    override fun onTaskRemoved(rootIntent:Intent?){persist();session?.player?.pause();session?.player?.clearMediaItems();stopSelf()}
    override fun onDestroy(){persist();handler.removeCallbacksAndMessages(null);session?.player?.release();session?.release();session=null;PlaybackRegistry.clear();super.onDestroy()}
}
