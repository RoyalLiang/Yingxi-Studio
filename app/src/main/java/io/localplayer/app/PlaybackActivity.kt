package io.localplayer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Rational
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackActivity:ComponentActivity(){
    private var privateWindowObserver:(()->Unit)?=null
    private var presentedMedia:MediaRef?=null
    private var playerController:MediaController?=null
    private var directionMediaId:String?=null
    private val automaticDirection=PlaybackOrientationState()
    private var manualDirection:Int?=null
    private var directionLocked=false
    private var foreground=false
    private var compact by mutableStateOf(false)
    private var backgroundRequested=false
    private var captionPickerOpen=false
    private var videoView:PlayerView?=null
    private val supersededRequests=mutableSetOf<String>()
    private var pipDismissJob:kotlinx.coroutines.Job?=null
    private val pipCloseReceiver=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){if(intent.action==ACTION_CLOSE_PIP)leave()}
    }
    private val presentationListener=object:Player.Listener{
        override fun onEvents(player:Player,events:Player.Events){player.currentMediaItem?.mediaMetadata?.extras?.getString("media-ref")?.let{raw->runCatching{MediaRef.parse(raw)}.getOrNull()?.let{presentedMedia=it}};updateVideoOrientation(events);updatePictureInPicture()}
    }
    private data class Caption(val uri:android.net.Uri,val mime:String,val label:String)
    private var pendingCaption:Caption?=null
    private var captionUri:android.net.Uri?=null
    private var captionReady=false
    private var captionMediaId:String?=null
    private var resumeAfterPicker=false
    private var audioMediaId:String?=null
    private val audioPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        captionPickerOpen=false
        val c=playerController
        if(uri!=null&&c!=null&&c.currentMediaItem?.mediaId==audioMediaId)lifecycleScope.launch {
            try {
                val name=withContext(Dispatchers.IO){
                    var label=uri.lastPathSegment?:"音轨"
                    contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())label=it.getString(0)?:label}
                    require(label.substringAfterLast('.',"").lowercase() in io.localplayer.core.SubtitleSidecars.audioExtensions){"请选择 MKA、M4A、AAC、AC3、FLAC、MP3、Ogg 或 WAV 音轨；裸 DTS/TrueHD 请先封装为 MKA"}
                    runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                    label.take(240)
                }
                if(c.currentMediaItem?.mediaId==audioMediaId)c.currentMediaItem?.let { item ->
                    val audio=(listOf(SidecarAudio(uri,name))+SidecarAudio.from(item)).distinctBy{it.uri}.take(8)
                    c.replaceMediaItem(c.currentMediaItemIndex,SidecarAudio.attach(item,audio))
                    android.widget.Toast.makeText(this@PlaybackActivity,"音轨已加入，可在播放选项中选择",android.widget.Toast.LENGTH_LONG).show()
                }
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){android.widget.Toast.makeText(this@PlaybackActivity,e.message?:"音轨无法读取",android.widget.Toast.LENGTH_LONG).show()}
            finally{if(resumeAfterPicker&&c.currentMediaItem?.mediaId==audioMediaId)c.play();resumeAfterPicker=false;updatePictureInPicture()}
        } else {if(resumeAfterPicker&&c?.currentMediaItem?.mediaId==audioMediaId)c?.play();resumeAfterPicker=false;updatePictureInPicture()}
    }
    private fun chooseAudio(){
        audioMediaId=playerController?.currentMediaItem?.mediaId
        resumeAfterPicker=playerController?.isPlaying==true
        captionPickerOpen=true;playerController?.pause();updatePictureInPicture()
        audioPicker.launch(arrayOf("audio/*","application/ogg","application/octet-stream"))
    }
    private val captionPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        captionPickerOpen=false
        if(uri==null){captionReady=true;playerController?.let(::applyPendingCaption)}else readCaption(uri)
    }
    companion object {
        private const val ACTION_CLOSE_PIP="io.localplayer.app.CLOSE_PIP"
        fun intent(context:Context,target:PlayTarget,initialOrientation:Int?=null):Intent {
            PlaybackRequests.put(target.copy(historyToken=target.historyToken ?: HistoryStore(context).token(target.media.key)))
            return Intent(context,PlaybackActivity::class.java).putExtra("play-intent",target.playId).putExtra("initial-orientation",initialOrientation?:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        ContextCompat.registerReceiver(this,pipCloseReceiver,android.content.IntentFilter(ACTION_CLOSE_PIP),ContextCompat.RECEIVER_NOT_EXPORTED)
        super.onCreate(savedInstanceState)
        presentedMedia=PlaybackRequests.get(intent.getStringExtra("play-intent"))?.media
        if(presentedMedia?.let{SealedMedia.protects(it)}==true)window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        privateWindowObserver=SealedMedia.observe{
            val ref=playerController?.currentMediaItem?.mediaMetadata?.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}?:presentedMedia
            if(ref!=null&&SealedMedia.protects(ref)){window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);finish()}
        }
        directionMediaId=savedInstanceState?.getString("direction-media")
        manualDirection=savedInstanceState?.getInt("direction-manual",-1)?.takeIf{it>=0}
        directionLocked=savedInstanceState?.getBoolean("direction-locked")?:false
        // Apply the preflight hint before composing; unknown metadata keeps the inherited direction.
        requestedOrientation=if(directionLocked)ActivityInfo.SCREEN_ORIENTATION_LOCKED else
            manualDirection?:savedInstanceState?.getInt("direction-auto",ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            ?:intent.getIntExtra("initial-orientation",ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        resumeAfterPicker=savedInstanceState?.getBoolean("caption-resume") ?: false;enableEdgeToEdge();window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);immersive()
        val title=PlaybackRequests.get(intent.getStringExtra("play-intent"))?.title ?: "视频"
        captionMediaId=savedInstanceState?.getString("caption-media")
        savedInstanceState?.getString("caption-uri")?.let{readCaption(android.net.Uri.parse(it))}
        setContent{PlayerTheme{
            var controller by remember{mutableStateOf<MediaController?>(null)}
            var connectionError by remember{mutableStateOf<String?>(null)}
            DisposableEffect(Unit){
                val future=MediaController.Builder(this@PlaybackActivity,SessionToken(this@PlaybackActivity,ComponentName(this@PlaybackActivity,PlaybackService::class.java))).buildAsync()
                future.addListener({
                    runCatching{future.get()}.onSuccess{c ->
                    if(isFinishing || isDestroyed){releaseSuperseded();c.release();return@onSuccess}
                    playerController=c;controller=c;c.addListener(presentationListener)
                if(owns(c)){automaticDirection.restoreSnapshot(c)}
                    releaseSuperseded()
                    if(foreground){loadInitial(c);applyPendingCaption(c)}
                updateVideoOrientation()
                    updatePictureInPicture()
                    }.onFailure{connectionError="无法连接播放会话，请返回重试"}
                },ContextCompat.getMainExecutor(this@PlaybackActivity))
                onDispose{
                    // Compose may dispose from the pre-destroy lifecycle callback before onDestroy.
                    // Stop while the controller is still connected when PiP/task is dismissed.
                    if(isFinishing)runCatching{playerController?.takeIf(::owns)?.let{it.stop();it.clearMediaItems()}}
                    releaseSuperseded();playerController?.removeListener(presentationListener)
                    playerController=null;videoView=null;MediaController.releaseFuture(future)
                }
            }
            BackHandler{leave()}
            FullscreenPlayer(controller,title,connectionError,::leave,::rotate,::lock,::chooseCaption,
                compact,if(supportsPictureInPicture())::enterSmallWindow else null,::playInBackground,::attachVideoView,::chooseAudio)
        }}
    }
    private fun chooseCaption(){
        captionMediaId=playerController?.currentMediaItem?.mediaId
        resumeAfterPicker=playerController?.isPlaying==true
        captionPickerOpen=true
        playerController?.pause()
        updatePictureInPicture()
        captionPicker.launch(arrayOf("application/x-subrip","text/vtt","text/plain","text/x-ssa","text/x-ass","application/octet-stream"))
    }
    private fun readCaption(uri:android.net.Uri){
        captionUri=uri
        lifecycleScope.launch{
            try{
                pendingCaption=withContext(Dispatchers.IO){
                    var name=uri.lastPathSegment ?: "字幕"
                    contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor ->
                        if(cursor.moveToFirst())name=cursor.getString(0) ?: name
                    }
                    val type=contentResolver.getType(uri)
                    val mime=when{
                        name.endsWith(".srt",true)||type=="application/x-subrip"->androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                        name.endsWith(".vtt",true)||type=="text/vtt"->androidx.media3.common.MimeTypes.TEXT_VTT
                        name.endsWith(".ass",true)||name.endsWith(".ssa",true)->androidx.media3.common.MimeTypes.TEXT_SSA
                    else->throw java.io.IOException("请选择 SRT、VTT、ASS 或 SSA 字幕文件")
                    }
                    contentResolver.openInputStream(uri)?.use{input ->
                        require(input.readNBytes(4*1024*1024+1).size<=4*1024*1024){"字幕文件请小于 4 MB"}
                    } ?: throw java.io.IOException("字幕文件无法读取")
                    Caption(uri,mime,name)
                }
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){
                pendingCaption=null
                android.widget.Toast.makeText(this@PlaybackActivity,e.message ?: "字幕无法读取",android.widget.Toast.LENGTH_LONG).show()
            }
            captionReady=true;playerController?.let(::applyPendingCaption)
        }
    }
    private fun applyPendingCaption(c:MediaController){
        if(!foreground||!captionReady||!owns(c))return
        captionReady=false
        if(c.currentMediaItem?.mediaId!=captionMediaId){
            pendingCaption=null;captionUri=null;resumeAfterPicker=false
            android.widget.Toast.makeText(this,"影片已切换，请为当前影片重新选择字幕",android.widget.Toast.LENGTH_LONG).show();return
        }
        captionUri=null
        pendingCaption?.let{caption ->
            val item=c.currentMediaItem ?: return@let
            val subtitle=MediaItem.SubtitleConfiguration.Builder(caption.uri).setMimeType(caption.mime).setLabel(caption.label)
                .setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            val restart=c.playbackState==Player.STATE_ENDED
            val index=c.currentMediaItemIndex
            val items=(0 until c.mediaItemCount).map{if(it==index)item.buildUpon().setSubtitleConfigurations((listOf(subtitle)+item.localConfiguration?.subtitleConfigurations.orEmpty()).distinctBy{it.uri}).build()else c.getMediaItemAt(it)}
            // Shared player rebuilds native merged sources; P5 adds attachments in place.
            c.replaceMediaItem(index,items[index]);if(restart)c.seekTo(0)
            c.trackSelectionParameters=c.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage("und").build()
            PlaybackPreferences(this).subtitlesDisabled=false
            c.prepare()
        }
        pendingCaption=null
        if(resumeAfterPicker)c.play()
        resumeAfterPicker=false
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putInt("direction-auto",requestedOrientation);outState.putString("direction-media",directionMediaId);outState.putInt("direction-manual",manualDirection?:-1);outState.putBoolean("direction-locked",directionLocked);outState.putBoolean("caption-resume",resumeAfterPicker);outState.putString("caption-uri",captionUri?.toString());outState.putString("caption-media",captionMediaId);super.onSaveInstanceState(outState)}
    private fun immersive(){WindowCompat.getInsetsController(window,window.decorView).apply{systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsetsCompat.Type.systemBars())}}
    private fun loadInitial(c:MediaController){
        if(!foreground || isFinishing || isDestroyed)return
        if(owns(c))return // Rotation or foreground return must keep the current episode.
        val target=PlaybackRequests.get(intent.getStringExtra("play-intent"))
        if(target==null){
            android.widget.Toast.makeText(this,"播放队列已失效，请从信源重新打开",android.widget.Toast.LENGTH_LONG).show()
            finish();return
        }
        if(SealedMedia.protects(target.media)){
            if(!SealedMedia.unlocked){c.pause();finish();return}
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val items=PlaybackRequests.items(this,target)
        val start=items.indexOfFirst{it.mediaId==target.uri.toString()}.coerceAtLeast(0)
        c.setMediaItems(items,start,target.resume)
        c.repeatMode=androidx.media3.common.Player.REPEAT_MODE_OFF;c.shuffleModeEnabled=false
        c.prepare();c.play()
    }

    private fun owns(c:MediaController)=c.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")==intent.getStringExtra("play-intent")
    private fun supportsPictureInPicture()=packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun attachVideoView(view:PlayerView){
        videoView=view
        view.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updatePictureInPicture()}
        updatePictureInPicture()
    }
    private fun pictureInPictureParams():PictureInPictureParams {
        val c=playerController
        val size=c?.videoSize
        val hasVideo=size!=null&&size.width>0&&size.height>0
        val closeIntent=Intent(ACTION_CLOSE_PIP).setPackage(packageName)
        val close=android.app.PendingIntent.getBroadcast(this,0,closeIntent,android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val closeAction=android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_brand_monochrome),"结束播放","结束播放",close)
        val builder=PictureInPictureParams.Builder().setCloseAction(closeAction)
            .setAspectRatio(pictureInPictureAspectRatio(size?.width?:0,size?.height?:0,size?.pixelWidthHeightRatio?:1f)).setSeamlessResizeEnabled(true)
            .setTitle(c?.mediaMetadata?.title?:"映汐放映站").setSubtitle("映汐放映站")
            .setAutoEnterEnabled(hasVideo&&c?.playWhenReady==true&&c.playerError==null&&c.playbackState!=Player.STATE_ENDED&&
                !captionPickerOpen&&!backgroundRequested&&!isFinishing)
        val rect=Rect()
        if(videoView?.videoSurfaceView?.getGlobalVisibleRect(rect)==true&&!rect.isEmpty)builder.setSourceRectHint(rect)
        return builder.build()
    }
    private fun updatePictureInPicture(){
        if(supportsPictureInPicture()&&!isDestroyed)runCatching{setPictureInPictureParams(pictureInPictureParams())}
    }
    private fun enterSmallWindow(){
        val c=playerController?:return
        if(!owns(c)||c.mediaItemCount==0||c.videoSize.width<=0)return
        backgroundRequested=false
        if(!runCatching{enterPictureInPictureMode(pictureInPictureParams())}.getOrDefault(false)){
            android.widget.Toast.makeText(this,"系统未允许画中画，可使用后台播放",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun playInBackground(){
        if(playerController?.let(::owns)!=true)return
        backgroundRequested=true;updatePictureInPicture();moveTaskToBack(true)
    }
    override fun onPictureInPictureUiStateChanged(pipState:PictureInPictureUiState){
        super.onPictureInPictureUiStateChanged(pipState)
        if(pipState.isTransitioningToPip)compact=true
    }
    override fun onPictureInPictureModeChanged(inPictureInPicture:Boolean,newConfig:Configuration){
        super.onPictureInPictureModeChanged(inPictureInPicture,newConfig)
        compact=inPictureInPicture
    }
    override fun onConfigurationChanged(newConfig:Configuration){
        super.onConfigurationChanged(newConfig);updatePictureInPicture()
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        val changed=intent.getStringExtra("play-intent")!=this.intent.getStringExtra("play-intent")
        if(changed)this.intent.getStringExtra("play-intent")?.let(supersededRequests::add)
        releaseSuperseded()
        setIntent(intent)
        if(changed){
            pendingCaption=null;captionReady=false;captionUri=null;captionMediaId=null;resumeAfterPicker=false
            directionMediaId=null;manualDirection=null
            if(!directionLocked)requestedOrientation=intent.getIntExtra("initial-orientation",ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
        playerController?.let{loadInitial(it)}
        updatePictureInPicture()
    }
    private fun releaseSuperseded(){
        // The Service owns active IDs even if this Activity's controller never connects.
        PlaybackRequests.releaseUnused(supersededRequests);supersededRequests.clear()
    }
    override fun onStart(){super.onStart();pipDismissJob?.cancel();pipDismissJob=null;foreground=true;playerController?.let{loadInitial(it);applyPendingCaption(it)}}
    override fun onResume(){super.onResume();backgroundRequested=false;compact=isInPictureInPictureMode;immersive();updateVideoOrientation();updatePictureInPicture()}
    override fun onStop(){
        foreground=false
        pipDismissJob?.cancel();pipDismissJob=null
        // A system accessibility dismiss can hide a PiP task but retain its history.
        // Wait for transitions; sleep and a visible/restored task keep playback alive.
        if(isInPictureInPictureMode)pipDismissJob=lifecycleScope.launch{
            var hiddenSamples=0
            repeat(4){
                delay(500)
                if(foreground||isFinishing||isDestroyed)return@launch
                val interactive=getSystemService(android.os.PowerManager::class.java).isInteractive
                val hidden=runCatching{
                    val task=getSystemService(android.app.ActivityManager::class.java).appTasks.firstOrNull{it.taskInfo.taskId==taskId}
                    task==null||!task.taskInfo.isVisible
                }.getOrDefault(false)
                hiddenSamples=if(interactive&&hidden)hiddenSamples+1 else 0
                if(hiddenSamples>=2){leave();return@launch}
            }
        }
        super.onStop()
    }
    override fun onDestroy(){
        privateWindowObserver?.invoke();privateWindowObserver=null
        unregisterReceiver(pipCloseReceiver)
        if(isFinishing){
            runCatching{playerController?.takeIf(::owns)?.let{it.stop();it.clearMediaItems()}}
            releaseSuperseded();intent.getStringExtra("play-intent")?.let{PlaybackRequests.releaseUnused(listOf(it))}
        }
        super.onDestroy()
    }
    private fun leave(){runCatching{playerController?.takeIf(::owns)?.let{it.stop();it.clearMediaItems()}};PlaybackRequests.remove(intent.getStringExtra("play-intent"));finish()}
    private fun updateVideoOrientation(events:Player.Events?=null) {
        val c=playerController?:return
        if(isFinishing||!owns(c))return
        val id=c.currentMediaItem?.mediaId?:return
        if(directionMediaId!=id){directionMediaId=id;manualDirection=null}
        val target=PlaybackRequests.get(intent.getStringExtra("play-intent"))
        val media=if(target?.uri?.toString()==id)target.media else c.currentMediaItem?.mediaMetadata?.extras?.getString("media-ref")?.let{runCatching{MediaRef.parse(it)}.getOrNull()}
        val hint=media?.let(PlaybackOrientationProbe::cached)?:if(target?.uri?.toString()==id)
            intent.getIntExtra("initial-orientation",ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED).takeIf{it==ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE||it==ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT} else null
        val direction=automaticDirection.update(c,events,hint)
        direction?.let{orientation->media?.let{PlaybackOrientationProbe.remember(it,orientation)}}
        if(!foreground||compact||captionPickerOpen)return
        val desired=if(directionLocked)ActivityInfo.SCREEN_ORIENTATION_LOCKED else
            manualDirection?:direction?:return
        if(requestedOrientation!=desired)requestedOrientation=desired
    }
    private fun rotate(){
        manualDirection=if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        updateVideoOrientation()
    }
    private fun lock(value:Boolean){directionLocked=value;updateVideoOrientation()}

}

internal fun pictureInPictureAspectRatio(width:Int,height:Int,pixelRatio:Float=1f):Rational {
    if(width<=0||height<=0||!pixelRatio.isFinite()||pixelRatio<=0)return Rational(16,9)
    val minimum=Rational(100,239);val maximum=Rational(239,100)
    val ratio=(width.toDouble()*pixelRatio/height).coerceIn(minimum.toDouble(),maximum.toDouble())
    // Clamp the final Rational too: decimal quantization must not cross the platform limit.
    return Rational((ratio*10000).toInt(),10000).coerceIn(minimum,maximum)
}
internal fun time(ms:Long):String{val sec=ms/1000;return if(sec>=3600)"%d:%02d:%02d".format(sec/3600,(sec/60)%60,sec%60)else"%02d:%02d".format(sec/60,sec%60)}
