package io.localplayer.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun PlaybackIssueAction(open:()->Unit){
    TextButton(onClick=open,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=PlayerAccent)){Text("上报播放问题")}
}

@Composable internal fun PlaybackReportDialog(player:Player?,waitMs:Long?=null,dismiss:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val attempts=remember(context){PlaybackReportAttempts(context)}
    var previous by remember{mutableStateOf(attempts.pending())}
    val report=remember{playbackDiagnostic(player,waitMs)}
    var agreed by remember{mutableStateOf(false)}
    var sending by remember{mutableStateOf<JSONObject?>(null)}
    var reserving by remember{mutableStateOf(false)}
    var saveFailed by remember{mutableStateOf(false)}
    sending?.let{request->
        PlaybackReportSubmission(request,confirmed={
            scope.launch{withContext(Dispatchers.IO){attempts.clear(request.getString("id"))}}
        },dismiss=dismiss)
        return
    }
    PlaybackSheet("上报播放问题","playback-diagnostics",dismiss,closeLabel="关闭问题上报"){
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            if(previous!=null){
                Text("上次送达尚未确认",Modifier.testTag("report-pending"),fontSize=15.sp,color=PlayerInk)
                TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/RoyalLiang/Yingxi-Studio/issues").buildUpon().appendQueryParameter("q","yingxi-playback-report:$previous").build()))}.onFailure{Toast.makeText(context,"未能打开回执链接",Toast.LENGTH_SHORT).show()}}){Text("检查上次回执")}
                TextButton(enabled=!reserving,onClick={
                    reserving=true
                    scope.launch{withContext(Dispatchers.IO){attempts.clear(previous!!)};previous=attempts.pending();agreed=false;reserving=false}
                },modifier=Modifier.testTag("report-new-attempt")){Text("上报新的问题")}
            }else{
                Text("将设备、应用版本、编解码与播放状态公开到 GitHub 问题区。",fontSize=15.sp,lineHeight=23.sp,color=PlayerInk)
                Text("片名、路径与账号不会上传。安全服务会处理连接信息，不写入问题正文。",fontSize=13.sp,lineHeight=20.sp,color=PlayerInk.copy(alpha=.72f))
                Row(Modifier.fillMaxWidth().toggleable(agreed,role=Role.Checkbox,onValueChange={agreed=it}).heightIn(min=48.dp).testTag("report-consent"),verticalAlignment=Alignment.CenterVertically){
                    Checkbox(agreed,null)
                    Text("我同意公开以上检修信息",fontSize=14.sp,color=PlayerInk)
                }
                if(saveFailed)Text("暂无法上报，请稍后重试",color=PlayerAccent,fontSize=13.sp)
                Button(enabled=agreed&&!reserving,onClick={
                    reserving=true
                    val request=playbackReportRequest(report)
                    scope.launch{
                        if(withContext(Dispatchers.IO){attempts.begin(request.getString("id"))})sending=request
                        else{previous=attempts.pending();saveFailed=previous==null}
                        reserving=false
                    }
                },shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("report-continue")){Text("上报")}
            }
        }
    }
}
