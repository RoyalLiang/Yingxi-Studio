package io.localplayer.app

import io.localplayer.core.RemoteReadTelemetry
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/** A user-requested, local report. Fields are explicitly selected; no media metadata or raw errors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackDiagnostic(player:Player?,bufferingWaitMs:Long?=null):String {
    val metrics=PlaybackMetrics.snapshot();val io=PlaybackIoProbe.snapshot()
    val transports=PlaybackIoProbe.transportSnapshots()
    val active=transports.filter{it.stage!=null}.maxByOrNull{it.currentMs}
    val advisory=transports.lastOrNull{it.rangeRequests>0||it.urlRequests>0}

    val state=when(player?.playbackState){Player.STATE_BUFFERING->"BUFFERING";Player.STATE_READY->"READY";Player.STATE_ENDED->"ENDED";Player.STATE_IDLE->"IDLE";else->"SESSION_CONNECTING"}
    val scheme=player?.currentMediaItem?.localConfiguration?.uri?.scheme
    val kind=if(scheme=="file"||scheme=="content")"LOCAL" else io.protocol
    val videoGroups=player?.currentTracks?.groups.orEmpty().filter{it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO}
    val selected=videoGroups.firstNotNullOfOrNull{group->
        (0 until group.length).firstOrNull{group.isTrackSelected(it)}?.let{group.getTrackFormat(it)}
    }?:videoGroups.firstOrNull{it.length>0}?.getTrackFormat(0)
    val mime=selected?.sampleMimeType?.takeIf{it in setOf("video/avc","video/hevc","video/dolby-vision","video/av01","video/x-vnd.on2.vp9","video/x-vnd.on2.vp8","video/mp4v-es","video/mpeg2","video/3gpp") }
    return JSONObject().put("report","yingxi-playback-v1")
        .put("httpPhase",active?.stage?.name?:if(transports.isEmpty())JSONObject.NULL else "IDLE")
        .put("httpPhaseMs",active?.currentMs?:0).put("urlRequests",transports.sumOf{it.urlRequests})
        .put("rangeRequests",transports.sumOf{it.rangeRequests}).put("sourceReadBytes",transports.sumOf{it.sourceBytes})
            .put("readParallelism",transports.maxOfOrNull{it.parallelism}?:1).put("diskCacheReadBytes",transports.sumOf{it.cacheBytes})
        .put("urlResolveMs",transports.sumOf{it.urlMs}).put("responseWaitMs",transports.sumOf{it.responseMs})
        .put("bodyReadMs",transports.sumOf{it.bodyMs}).put("lastHttpStatus",transports.lastOrNull()?.httpStatus?:JSONObject.NULL)
        .put("sourceBodyAverageBytesPerSecond",bodyAverageBytesPerSecond(transports)?:JSONObject.NULL)
        .put("downloadRightsNotice",advisory?.downloadRightsNotice?:false)
        .put("signedTrafficLimitBitsPerSecond",advisory?.signedTrafficLimitBitsPerSecond?:JSONObject.NULL)
        .put("version",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE)
        .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
        .put("state",state).put("bufferingWaitMs",bufferingWaitMs?:JSONObject.NULL).put("playRequested",player?.playWhenReady?:false)
        .put("isPlaying",player?.isPlaying?:false).put("suppression",player?.playbackSuppressionReason?:0)
        .put("positionMs",player?.currentPosition?:0).put("bufferedMs",player?.totalBufferedDuration?:0)
        .put("source",kind).put("ioOperation",io.operation?.name?:"NONE").put("ioWaitingMs",io.waitingMs)
        .put("sinceReadMs",io.sinceProgressMs?:JSONObject.NULL).put("ioFailures",io.failures)
        .put("lastIoFailure",io.lastFailure?:JSONObject.NULL)
        .put("bytes",metrics.bytes).put("readCalls",metrics.readCalls).put("maxReadMs",metrics.maxReadMs)
        .put("readTimeMs",metrics.readTimeMs).put("droppedFrames",metrics.droppedFrames)
        .put("audioDecoder",diagnosticCodec(metrics.audioDecoder)?:JSONObject.NULL).put("audioRenderedBuffers",metrics.audioRenderedBuffers?:JSONObject.NULL)
        .put("sdrDecoderDroppedFrames",metrics.sdrDecoderDroppedFrames?:JSONObject.NULL)
        .put("sdrAvSyncMs",metrics.sdrAvSyncMs?:JSONObject.NULL).put("sdrPollMs",metrics.sdrPollMs?:JSONObject.NULL)
        .put("playbackSpeed",player?.playbackParameters?.speed?:1f)
        .put("encodedFrameRate",selected?.frameRate?.takeIf{it.isFinite()&&it>0}?:JSONObject.NULL)
        .put("videoSkippedOutputs",metrics.videoSkippedOutputs?:JSONObject.NULL)
        .put("videoCodecs",diagnosticCodec(selected?.codecs)?:JSONObject.NULL)
        .put("nativeDolbyVisionDecoders",nativeDolbyVisionDecoders())
        .put("videoMime",mime?:JSONObject.NULL).put("encodedWidth",selected?.width?:0).put("encodedHeight",selected?.height?:0)
        .put("openCalls",metrics.openCalls).put("openTimeMs",metrics.openTimeMs)
        .put("firstFrameMs",metrics.firstFrameMs?:JSONObject.NULL).put("rebuffers",metrics.rebufferCount)
        .put("decoder",metrics.decoder?.takeIf{it.matches(Regex("[A-Za-z0-9._+-]{1,160}"))}?:JSONObject.NULL)
        .put("decoderInitMs",metrics.decoderInitMs)
        .put("videoInputBuffers",metrics.videoInputBuffers?:JSONObject.NULL).put("videoRenderedBuffers",metrics.videoRenderedBuffers?:JSONObject.NULL)
        .put("videoSkippedInputs",metrics.videoSkippedInputs?:JSONObject.NULL).put("videoRendererEnabled",metrics.videoEnabled)
        .put("videoCodecErrors",metrics.videoCodecErrors).put("videoDecoderReleases",metrics.videoDecoderReleases)
        .put("videoWidth",player?.videoSize?.width?:0)
        .put("videoHeight",player?.videoSize?.height?:0).put("playerError",player?.playerError?.errorCodeName?:JSONObject.NULL)
        .toString(2)
}

/** Socket-read average, excluding API/headers and player idle time. Includes the current blocked read. */
internal fun bodyAverageBytesPerSecond(transports:List<RemoteReadTelemetry.Snapshot>):Long? {
    val time=transports.sumOf{it.bodyMs+if(it.stage==RemoteReadTelemetry.Stage.BODY)it.currentMs else 0}
    val bytes=transports.sumOf{it.sourceBytes}
    return if(time<1000||bytes<=0)null else (bytes.toDouble()*1000/time).toLong()
}

internal fun playbackWaitTitle():String {
    val phase=PlaybackIoProbe.transportSnapshots().filter{it.stage!=null}.maxByOrNull{it.currentMs}?.stage
    return when(phase){
        RemoteReadTelemetry.Stage.URL->"接洽信源中"
        RemoteReadTelemetry.Stage.HEADERS->"等待信源回应"
        RemoteReadTelemetry.Stage.BODY->"原片传输中"
        null->when(PlaybackIoProbe.snapshot().operation){
            PlaybackIoProbe.Operation.OPEN->"接洽信源中"
            PlaybackIoProbe.Operation.READ->"原片读取中"
            null->"等待画面"
        }
    }
}

internal fun playbackWaitSummary():String {
    val io=PlaybackIoProbe.snapshot();val metrics=PlaybackMetrics.snapshot()
    val received=String.format(Locale.ROOT,"%.1f",metrics.bytes/1048576.0)
    val transports=PlaybackIoProbe.transportSnapshots()
    val http=transports.filter{it.stage!=null}.maxByOrNull{it.currentMs}
    val rate=bodyAverageBytesPerSecond(transports)?.let{String.format(Locale.ROOT,"%.0f",it/1024.0)}
    val progress=when(http?.stage){
        RemoteReadTelemetry.Stage.URL->"正在解析信源\n已等待 ${http.currentMs/1000}\u00A0秒"
        RemoteReadTelemetry.Stage.HEADERS->"等待信源回应\n已等待 ${http.currentMs/1000}\u00A0秒"
        else->when(io.operation){
            PlaybackIoProbe.Operation.OPEN->"正在连接信源\n已等待 ${io.waitingMs/1000}\u00A0秒"
            PlaybackIoProbe.Operation.READ->"原片已接收 $received\u00A0MB"+(rate?.let{"\n平均读取 $it\u00A0KB/s"}?:"")
            null->if(metrics.firstFrameMs==null&&metrics.bytes>0)"已接收 $received\u00A0MB\n等待画面" else if(metrics.firstFrameMs!=null)"正在恢复播放\n已接收 $received\u00A0MB" else "正在准备播放"
        }
    }
    val advisory=transports.lastOrNull{it.rangeRequests>0||it.urlRequests>0}
    return progress+if(advisory?.downloadRightsNotice==true)"\n阿里云盘返回下载通道提示\n可核对账户的下载高速流量配额" else ""
}

/** Only composed during buffering. Sampling stops with the overlay; it never controls playback. */
@Composable internal fun PlaybackWaitDiagnostics(player:Player?,thresholdMs:Long=12_000) {
    var elapsed by remember{mutableLongStateOf(0)}
    var summary by remember{mutableStateOf(playbackWaitSummary())}
    LaunchedEffect(Unit){
        val started=SystemClock.elapsedRealtime()
        while(true){elapsed=SystemClock.elapsedRealtime()-started;summary=playbackWaitSummary();delay(1000)}
    }
    if(elapsed>=thresholdMs){
        Text(summary,Modifier.widthIn(max=280.dp).testTag("playback-wait-details"),color=PlayerInk.copy(alpha=.78f),
            fontSize=13.sp,textAlign=TextAlign.Center)
        PlaybackDiagnosticCopy(player,elapsed)
    }
}

@Composable internal fun PlaybackDiagnosticCopy(player:Player?,waitMs:Long?=null){
    val context=LocalContext.current
    var copied by remember{mutableStateOf(false)}
    var copyFailed by remember{mutableStateOf(false)}
    TextButton(onClick={
            val result=runCatching{
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("映汐放映检修",playbackDiagnostic(player,waitMs)))
            }
            copied=result.isSuccess;copyFailed=result.isFailure
            if(result.isFailure)Toast.makeText(context,"未能复制，请重试",Toast.LENGTH_SHORT).show()
        },modifier=Modifier.heightIn(min=48.dp).testTag("playback-diagnostic-copy"),
            colors=ButtonDefaults.textButtonColors(contentColor=PlayerAccent)){
            Text(if(copyFailed)"重试复制检修记录" else if(copied)"已复制检修记录" else "复制检修记录")
        }
    }

/** Accept codec identifiers only; never copy arbitrary format strings or exception messages. */
internal fun diagnosticCodec(value:String?):String?=value?.takeIf{it.length<=80&&it.split(',').all{codec->
    Regex("(?i)(dvhe|dvh1|hvc1|hev1|avc1|avc3|av01|vp09)(\\.[0-9a-flh]{1,8}){1,5}").matches(codec.trim())
}}

private fun nativeDolbyVisionDecoders():org.json.JSONArray {
    val result=org.json.JSONArray()
    runCatching{android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
        .filter{!it.isEncoder&&it.supportedTypes.any{mime->mime.equals("video/dolby-vision",true)}}.take(16).forEach{codec->
            if(!codec.name.matches(Regex("[A-Za-z0-9._+-]{1,160}")))return@forEach
            val caps=codec.getCapabilitiesForType("video/dolby-vision")
            val profiles=org.json.JSONArray();caps.profileLevels.take(32).forEach{profiles.put(org.json.JSONObject().put("profile",it.profile).put("level",it.level))}
            result.put(org.json.JSONObject().put("name",codec.name).put("profileLevels",profiles))
        }}
    return result
}
