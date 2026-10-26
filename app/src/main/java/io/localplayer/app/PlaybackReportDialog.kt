package io.localplayer.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var agreed by remember(report){mutableStateOf(false)}
    var preview by remember{mutableStateOf(false)}
    var sending by remember{mutableStateOf<JSONObject?>(null)}
    var reserving by remember{mutableStateOf(false)}
    var saveFailed by remember{mutableStateOf(false)}
    val maximum=(LocalConfiguration.current.screenHeightDp.dp-48.dp).coerceAtLeast(180.dp)
    sending?.let{request->
        PlaybackReportSubmission(request,confirmed={
            scope.launch{withContext(Dispatchers.IO){attempts.clear(request.getString("id"))}}
        },dismiss=dismiss)
        return
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        SheetGlassEffect()
        MaterialTheme(colorScheme=darkColorScheme(primary=PlayerAccent,onPrimary=Color(0xFF281820),surface=Color(0xFF202326),onSurface=PlayerInk)){
            GlassSurface(Modifier.padding(16.dp).widthIn(max=420.dp).fillMaxWidth().testTag("playback-diagnostics"),dark=true,radius=28){
                Column(Modifier.heightIn(max=maximum).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("上报播放问题",Modifier.weight(1f),fontSize=21.sp,fontWeight=FontWeight.SemiBold,color=PlayerInk)
                        PlaybackDiagnosticCopy(player,waitMs,report,iconOnly=true)
                        IconButton(onClick=dismiss,modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.Close,"关闭问题上报",tint=PlayerInk)}
                    }
                    TextButton(onClick={preview=!preview},modifier=Modifier.heightIn(min=48.dp).testTag("report-preview")){Text(if(preview)"收起检修内容"else"查看检修内容")}
                    if(preview)Text(report,Modifier.fillMaxWidth().heightIn(max=176.dp).verticalScroll(rememberScrollState()).testTag("report-payload"),fontFamily=FontFamily.Monospace,fontSize=11.sp,lineHeight=16.sp,color=PlayerInk)
                    if(previous!=null){
                        Text("上次上报待确认",Modifier.testTag("report-pending"),fontSize=15.sp,color=PlayerInk)
                        TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/RoyalLiang/Yingxi-Studio/issues").buildUpon().appendQueryParameter("q","yingxi-playback-report:$previous").build()))}.onFailure{Toast.makeText(context,"未能打开回执链接",Toast.LENGTH_SHORT).show()}}){Text("检查上次回执")}
                        TextButton(enabled=!reserving,onClick={
                            reserving=true
                            scope.launch{withContext(Dispatchers.IO){attempts.clear(previous!!)};previous=attempts.pending();agreed=false;reserving=false}
                        },modifier=Modifier.testTag("report-new-attempt")){Text("重新上报")}
                    }else{
                        Text("设备型号、系统及应用版本、编码、播放状态与性能计数将公开至 GitHub「RoyalLiang/Yingxi-Studio」。",fontSize=13.sp,lineHeight=20.sp,color=PlayerInk.copy(alpha=.8f))
                        Text("Cloudflare 使用 IP 做安全校验；IP、片名、路径与账号不写入仓库。",fontSize=12.sp,lineHeight=19.sp,color=PlayerInk.copy(alpha=.72f))
                        Row(Modifier.fillMaxWidth().toggleable(agreed,role=Role.Checkbox,onValueChange={agreed=it}).heightIn(min=48.dp).testTag("report-consent"),verticalAlignment=Alignment.CenterVertically){
                            Checkbox(checked=agreed,onCheckedChange=null)
                            Text("我同意公开以上检修信息",fontSize=14.sp,color=PlayerInk)
                        }
                        if(saveFailed)Text("暂无法上报，请保留检修内容",color=PlayerAccent,fontSize=13.sp)
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
    }
}
