package io.localplayer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackActivity:ComponentActivity(){
    private var playerController:MediaController?=null
    private var foreground=false
    private data class Caption(val uri:android.net.Uri,val mime:String,val label:String)
    private var pendingCaption:Caption?=null
    private var captionUri:android.net.Uri?=null
    private var captionReady=false
    private var resumeAfterPicker=false
    private val captionPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri==null){captionReady=true;playerController?.let(::applyPendingCaption)}else readCaption(uri)
    }
    companion object {
        fun intent(context:Context,target:PlayTarget):Intent {
            val token=target.historyToken ?: HistoryStore(context).token(target.media.key)
            return Intent(context,PlaybackActivity::class.java)
            .putExtra("uri",target.uri.toString()).putExtra("title",target.title).putExtra("resume",target.resume).putExtra("media-ref",target.media.json()).putExtra("history-clear",token.clearRevision).putExtra("history-item",token.itemRevision).putExtra("play-intent",target.playId)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);resumeAfterPicker=savedInstanceState?.getBoolean("caption-resume") ?: false;enableEdgeToEdge();window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);immersive()
        val uri=android.net.Uri.parse(intent.getStringExtra("uri") ?: run{finish();return})
        val title=intent.getStringExtra("title") ?: "视频"
        savedInstanceState?.getString("caption-uri")?.let{readCaption(android.net.Uri.parse(it))}
        setContent{PlayerTheme{
            var controller by remember{mutableStateOf<MediaController?>(null)}
            var connectionError by remember{mutableStateOf<String?>(null)}
            DisposableEffect(Unit){
                val future=MediaController.Builder(this@PlaybackActivity,SessionToken(this@PlaybackActivity,ComponentName(this@PlaybackActivity,PlaybackService::class.java))).buildAsync()
                future.addListener({
                    runCatching{future.get()}.onSuccess{c ->
                        if(isFinishing || isDestroyed){c.release();return@onSuccess}
                        playerController=c;controller=c
                    if(foreground){loadInitial(c);applyPendingCaption(c)}else pauseOwned(c)
                    }.onFailure{connectionError="无法连接播放会话，请返回重试"}
                },ContextCompat.getMainExecutor(this@PlaybackActivity))
                onDispose{playerController=null;MediaController.releaseFuture(future)}
            }
            BackHandler{leave()}
            FullscreenPlayer(controller,title,connectionError,::leave,::rotate,::lock,::chooseCaption)
        }}
    }
    private fun chooseCaption(){
        resumeAfterPicker=playerController?.isPlaying==true
        playerController?.pause()
        captionPicker.launch(arrayOf("application/x-subrip","text/vtt","text/plain","application/octet-stream"))
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
                        else->throw java.io.IOException("请选择 SRT 或 VTT 字幕文件")
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
        if(!foreground||!captionReady)return
        captionReady=false
        captionUri=null
        pendingCaption?.let{caption ->
            val item=c.currentMediaItem ?: return@let
            val subtitle=MediaItem.SubtitleConfiguration.Builder(caption.uri).setMimeType(caption.mime).setLabel(caption.label)
                .setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            val position=if(c.playbackState==androidx.media3.common.Player.STATE_ENDED)0L else c.currentPosition
            c.setMediaItem(item.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build(),position)
            c.trackSelectionParameters=c.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false).setPreferredTextLanguage("und").build()
            PlaybackPreferences(this).subtitlesDisabled=false
            c.prepare()
        }
        pendingCaption=null
        if(resumeAfterPicker)c.play()
        resumeAfterPicker=false
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putBoolean("caption-resume",resumeAfterPicker);outState.putString("caption-uri",captionUri?.toString());super.onSaveInstanceState(outState)}
    private fun immersive(){WindowCompat.getInsetsController(window,window.decorView).apply{systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsetsCompat.Type.systemBars())}}
    private fun loadInitial(c:MediaController){
        if(!foreground || isFinishing || isDestroyed)return
        val uri=android.net.Uri.parse(intent.getStringExtra("uri") ?: return)
        if(c.currentMediaItem?.mediaId!=uri.toString()||c.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")!=intent.getStringExtra("play-intent")){
            val extras=Bundle().apply{putString("media-ref",intent.getStringExtra("media-ref"));putLong("history-clear",intent.getLongExtra("history-clear",0));putLong("history-item",intent.getLongExtra("history-item",0));putString("play-intent",intent.getStringExtra("play-intent"))}
            c.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(intent.getStringExtra("title") ?: "视频").setExtras(extras).build()).build(),intent.getLongExtra("resume",0L))
            c.prepare();c.play()
        }
    }
    private fun owns(c:MediaController)=c.currentMediaItem?.mediaMetadata?.extras?.getString("play-intent")==intent.getStringExtra("play-intent")
    private fun pauseOwned(c:MediaController){if(owns(c))c.pause()}
    override fun onStart(){super.onStart();foreground=true;playerController?.let{loadInitial(it);applyPendingCaption(it)}}
    override fun onResume(){super.onResume();immersive()}
    override fun onStop(){foreground=false;if(!isChangingConfigurations)runCatching{playerController?.let(::pauseOwned)};super.onStop()}
    private fun leave(){runCatching{playerController?.takeIf(::owns)?.let{it.stop();it.clearMediaItems()}};intent.getStringExtra("uri")?.let{PlaybackRegistry.remove(android.net.Uri.parse(it))};finish()}
    private fun rotate(){requestedOrientation=if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE}
    private fun lock(value:Boolean){requestedOrientation=if(value)ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun FullscreenPlayer(controller:MediaController?,title:String,connectionError:String?,back:()->Unit,rotate:()->Unit,lock:(Boolean)->Unit,externalCaption:()->Unit){
    var visible by remember{mutableStateOf(true)}
    var locked by rememberSaveable{mutableStateOf(false)}
    var settingsOpen by remember{mutableStateOf(false)}
    var speed by remember{mutableFloatStateOf(1f)}
    var position by remember{mutableLongStateOf(0)}
    var duration by remember{mutableLongStateOf(0)}
    var playing by remember{mutableStateOf(false)}
    var buffering by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var dragging by remember{mutableStateOf(false)}
    var preview by remember{mutableFloatStateOf(0f)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val touchExploration=remember{context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled}
    LaunchedEffect(controller){while(controller!=null){speed=controller.playbackParameters.speed;position=controller.currentPosition.coerceAtLeast(0);duration=controller.duration.takeIf{it!=C.TIME_UNSET && it>0} ?: 0;playing=controller.isPlaying;buffering=controller.playbackState==androidx.media3.common.Player.STATE_BUFFERING
        error=controller.playerError?.let{"播放失败（${it.errorCodeName}），请返回后重新打开"};delay(250)}}
    LaunchedEffect(visible,playing,dragging,locked,settingsOpen){if(visible&&playing&&!dragging&&!locked&&!touchExploration&&!settingsOpen){delay(3000);visible=false}}
    Box(Modifier.fillMaxSize().background(Color(0xFF101113))){
        AndroidView(factory={ctx->PlayerView(ctx).apply{useController=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT;setKeepContentOnPlayerReset(true)}},update={it.player=controller},onRelease={it.player=null},modifier=Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().clickable(interactionSource=remember{MutableInteractionSource()},indication=null){visible=!visible})
        if(buffering || controller==null)CircularProgressIndicator(Modifier.align(Alignment.Center),color=Paper)
        if(visible && !locked){
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0xD9101113),Color.Transparent))).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)).padding(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=back){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回",tint=Paper)};Text(title,Modifier.weight(1f),color=Paper,maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=16.sp);IconButton(onClick={locked=true;lock(true)}){Icon(Icons.Rounded.LockOpen,"锁定方向与控件",tint=Paper)}}
            }
            IconButton(onClick={if(playing)controller?.pause()else controller?.play()},modifier=Modifier.align(Alignment.Center).size(72.dp).background(Color(0x99101113),CircleShape)){
                Icon(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playing)"暂停"else"播放",tint=Paper,modifier=Modifier.size(42.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xF2101113)))).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal)).padding(horizontal=24.dp,vertical=16.dp)){
                Slider(value=if(dragging)preview else position.toFloat().coerceAtMost(duration.toFloat()),onValueChange={dragging=true;preview=it},onValueChangeFinished={controller?.seekTo(preview.toLong());dragging=false},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0&&controller?.isCurrentMediaItemSeekable==true,colors=SliderDefaults.colors(thumbColor=Paper,activeTrackColor=Berry,inactiveTrackColor=Color(0xFF6B6B70)))
                Row(verticalAlignment=Alignment.CenterVertically){Text(time(if(dragging)preview.toLong()else position),color=Paper,fontSize=13.sp);Text(" / ${time(duration)}",Modifier.weight(1f),color=Color(0xFFCDCDD1),fontSize=13.sp);Text(speedLabel(speed),fontSize=12.sp,color=Paper);IconButton(onClick={settingsOpen=true}){Icon(Icons.Rounded.Tune,"播放设置",tint=Paper)};IconButton(onClick=rotate){Icon(Icons.Rounded.ScreenRotation,"旋转屏幕",tint=Paper)}}
            }
        }
        if(locked && visible)IconButton(onClick={locked=false;lock(false)},modifier=Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).background(Color(0xB3101113),CircleShape)){Icon(Icons.Rounded.Lock,"解锁方向与控件",tint=Paper)}
        if(settingsOpen&&controller!=null)PlaybackOptions(controller,{settingsOpen=false},externalCaption)
        (error ?: connectionError)?.let{message ->Column(Modifier.align(Alignment.Center).fillMaxWidth().background(Color(0xE6101113)).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(message,color=Paper);TextButton(onClick=back){Text("返回重试",color=Color(0xFFFF93AF))}}}
    }
}
internal fun time(ms:Long):String{val sec=ms/1000;return if(sec>=3600)"%d:%02d:%02d".format(sec/3600,(sec/60)%60,sec%60)else"%02d:%02d".format(sec/60,sec%60)}
