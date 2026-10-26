package io.localplayer.app
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Check

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.UUID

internal const val PLAYBACK_REPORT_SUBMIT_PAGE="https://yingxi-station.pages.dev/report/submit"
internal const val PLAYBACK_REPORT_CONSENT="2026-10-26"

internal fun playbackReportRequest(report:String):JSONObject = JSONObject()
    .put("id",UUID.randomUUID().toString()).put("kind","other")
    .put("nativeUi",2).put("report",JSONObject(report)).put("consent",true).put("consentVersion",PLAYBACK_REPORT_CONSENT)

/** No native JS interface, local file access, credentials or payload in the page URL. */
@SuppressLint("SetJavaScriptEnabled")
internal fun playbackReportWebView(context:Context,request:JSONObject,confirmed:(Long)->Unit={},phase:(String)->Unit={},error:(String)->Unit):WebView = WebView(context).apply {
    setBackgroundColor(Color.TRANSPARENT)
    settings.javaScriptEnabled=true
    settings.domStorageEnabled=true
    settings.allowFileAccess=false
    settings.allowContentAccess=false
    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    webViewClient=object:WebViewClient(){
        private var started=false
        private var acknowledged=false
        override fun shouldOverrideUrlLoading(view:WebView,resource:WebResourceRequest):Boolean {
            if(!resource.isForMainFrame)return false
            val target=resource.url.toString()
            if(view.url==PLAYBACK_REPORT_SUBMIT_PAGE&&resource.url.scheme=="yingxi-report"&&resource.url.host=="receipt"){
                val number=resource.url.getQueryParameter("number")?.toLongOrNull()
                if(!acknowledged&&resource.url.getQueryParameter("id")==request.getString("id")&&number!=null&&number>0){acknowledged=true;confirmed(number)}
                return true
            }
            if(view.url==PLAYBACK_REPORT_SUBMIT_PAGE&&resource.url.scheme=="yingxi-report"&&resource.url.host=="state"){
                val value=resource.url.getQueryParameter("phase")
                if(!acknowledged&&resource.url.getQueryParameter("id")==request.getString("id")&&value in setOf("checking","sending","challenge","error"))phase(value!!)
                return true
            }
            if(target==PLAYBACK_REPORT_SUBMIT_PAGE)return false
            if(resource.hasGesture()&&Regex("^https://github\\.com/RoyalLiang/Yingxi-Studio/issues(?:/[0-9]+|\\?q=.*)$").matches(target)){
                runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,resource.url))}
                    .onFailure{Toast.makeText(context,"未能打开回执链接",Toast.LENGTH_SHORT).show()}
            }
            return true
        }
        override fun onPageFinished(view:WebView,url:String) {
            if(started||url!=PLAYBACK_REPORT_SUBMIT_PAGE||view.url!=PLAYBACK_REPORT_SUBMIT_PAGE)return
            started=true
            view.evaluateJavascript("typeof window.startPlaybackReport==='function'&&(window.startPlaybackReport($request),true)"){ready->
                if(!acknowledged&&ready!="true")error("上报页面未能载入，请保留检修内容后稍后重试。")
            }
        }
        override fun onReceivedError(view:WebView,resource:WebResourceRequest,failure:WebResourceError) {
            if(!acknowledged&&resource.isForMainFrame)error("未能连接上报通道，请保留检修内容后稍后重试。")
        }
    }
}

@Composable internal fun PlaybackReportSubmission(request:JSONObject,confirmed:(Long)->Unit,dismiss:()->Unit={}){
    var phase by remember(request.getString("id")){mutableStateOf("checking")}
    var number by remember{mutableLongStateOf(0)}
    LaunchedEffect(phase){
        if(phase=="success"){kotlinx.coroutines.delay(1200);dismiss()}
        else if(phase=="checking"||phase=="sending"){kotlinx.coroutines.delay(45_000);phase="error"}
    }
    androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        SheetGlassEffect()
        GlassSurface(Modifier.width(264.dp).testTag("report-delivery"),dark=true,radius=26){
            Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                Box(Modifier.fillMaxWidth().height(if(phase=="challenge")160.dp else 72.dp),contentAlignment=androidx.compose.ui.Alignment.Center){
                    if(phase!="success"&&phase!="error")AndroidView(
                        factory={context->playbackReportWebView(context,request,confirmed={receipt->number=receipt;phase="success";confirmed(receipt)},phase={phase=it},error={phase="error"}).apply{loadUrl(PLAYBACK_REPORT_SUBMIT_PAGE)}},
                        onRelease={it.stopLoading();it.destroy()},modifier=Modifier.matchParentSize().testTag("report-submission")
                    )
                    when(phase){
                        "checking","sending"->androidx.compose.material3.CircularProgressIndicator(Modifier.size(30.dp).testTag("report-sending"),color=PlayerAccent,strokeWidth=2.dp)
                        "success"->androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check,"上报成功",Modifier.size(36.dp),tint=PlayerAccent)
                        "error"->androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ErrorOutline,"上报未完成",Modifier.size(36.dp),tint=PlayerAccent)
                    }
                }
                Text(when(phase){"challenge"->"请完成安全校验";"success"->"已上报 · #$number";"error"->"上报未完成";else->"正在上报"},Modifier.testTag(if(phase=="error")"report-send-error"else"report-delivery-status").semantics{liveRegion=LiveRegionMode.Polite},fontSize=15.sp,color=PlayerInk)
                if(phase=="error"||phase=="challenge")androidx.compose.material3.TextButton(onClick=dismiss){Text("关闭",color=PlayerAccent)}
            }
        }
    }
}

/** A receipt marker survives closing the dialog or process death; no diagnostic body is stored. */
internal class PlaybackReportAttempts(context:Context){
    private val prefs=context.applicationContext.getSharedPreferences("playback-report-attempt",Context.MODE_PRIVATE)
    fun pending():String?=prefs.getString("pending",null)
    fun begin(id:String):Boolean=synchronized(lock){
        if(pending()!=null)false else prefs.edit().putString("pending",id).commit()
    }
    fun clear(id:String):Boolean=synchronized(lock){
        if(pending()!=id)false else prefs.edit().remove("pending").commit()
    }
    companion object{private val lock=Any()}
}
