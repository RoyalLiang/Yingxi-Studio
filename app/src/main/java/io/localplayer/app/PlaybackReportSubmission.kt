package io.localplayer.app

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
    .put("report",JSONObject(report)).put("consent",true).put("consentVersion",PLAYBACK_REPORT_CONSENT)

/** No native JS interface, local file access, credentials or payload in the page URL. */
@SuppressLint("SetJavaScriptEnabled")
internal fun playbackReportWebView(context:Context,request:JSONObject,confirmed:(Long)->Unit={},error:(String)->Unit):WebView = WebView(context).apply {
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
                if(ready!="true")error("上报页面未能载入，请保留检修内容后稍后重试。")
            }
        }
        override fun onReceivedError(view:WebView,resource:WebResourceRequest,failure:WebResourceError) {
            if(resource.isForMainFrame)error("未能连接上报通道，请保留检修内容后稍后重试。")
        }
    }
}

@Composable internal fun PlaybackReportSubmission(request:JSONObject,confirmed:(Long)->Unit) {
    var failure by remember{mutableStateOf<String?>(null)}
    Text("正在发送这份回执；如需安全校验，可在此完成。",fontSize=13.sp,color=PlayerInk.copy(alpha=.76f))
    failure?.let{Text(it,Modifier.testTag("report-send-error"),fontSize=14.sp,color=PlayerAccent)}
    AndroidView(
        factory={context->playbackReportWebView(context,request,confirmed){failure=it}.apply{loadUrl(PLAYBACK_REPORT_SUBMIT_PAGE)}},
        onRelease={it.stopLoading();it.destroy()},
        modifier=Modifier.fillMaxWidth().height(320.dp).testTag("report-submission")
    )
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
