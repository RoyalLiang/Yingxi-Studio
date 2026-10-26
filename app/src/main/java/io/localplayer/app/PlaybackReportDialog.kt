package io.localplayer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

@Composable internal fun PlaybackIssueAction(open:()->Unit) {
    TextButton(onClick=open,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=PlayerAccent)){Text("上报播放问题")}
}

@Composable internal fun PlaybackReportDialog(player:Player?,waitMs:Long?=null,dismiss:()->Unit) {
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
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        SheetGlassEffect()
        MaterialTheme(colorScheme=darkColorScheme(primary=PlayerAccent,onPrimary=Color(0xFF281820),surface=Color(0xFF202326),onSurface=PlayerInk)) {
            GlassSurface(Modifier.padding(16.dp).widthIn(max=480.dp).fillMaxWidth().testTag("playback-diagnostics"),dark=true,radius=30) {
                Column(Modifier.heightIn(max=maximum).verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Build,null,tint=PlayerAccent,modifier=Modifier.size(25.dp))
                        Column(Modifier.weight(1f)) {
                            Text("放映站 · 检修回执",fontSize=12.sp,color=PlayerAccent)
                            Text("上报播放问题",fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold,color=PlayerInk)
                        }
                        IconButton(onClick=dismiss,modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.Close,"关闭问题上报",tint=PlayerInk)}
                    }
                    Text("先看看这份播放快照，再决定是否寄出。同意后可在播放器内直接上报；复制只保留在本机。",fontSize=14.sp,lineHeight=22.sp,color=PlayerInk.copy(alpha=.8f))
                    HorizontalDivider(color=PlayerInk.copy(alpha=.13f))
                    TextButton(onClick={preview=!preview},modifier=Modifier.heightIn(min=48.dp).testTag("report-preview")){Text(if(preview)"收起检修内容"else"预览检修内容")}
                    if(preview)Text(report,Modifier.fillMaxWidth().heightIn(max=200.dp).verticalScroll(rememberScrollState()).testTag("report-payload"),fontFamily=FontFamily.Monospace,fontSize=11.sp,lineHeight=16.sp,color=PlayerInk.copy(alpha=.85f))
                    Text("发送内容：设备型号、系统与应用版本、编码信息、播放状态及性能计数。不含片名、文件路径、信源地址或账号。报告提交后会公开到 GitHub 的 RoyalLiang/Yingxi-Studio 仓库。Cloudflare 提供页面与安全校验，网络 IP 仅用于校验，不写入仓库。",fontSize=13.sp,lineHeight=21.sp,color=PlayerInk.copy(alpha=.76f))
                    if(sending!=null)PlaybackReportSubmission(sending!!){
                        val id=sending!!.getString("id")
                        scope.launch{withContext(Dispatchers.IO){attempts.clear(id)}}
                    } else if(previous!=null){
                        Text("上一次上报还未取得回执。请先检查，避免重复寄送同一个问题。",Modifier.testTag("report-pending"),fontSize=14.sp,lineHeight=22.sp,color=PlayerAccent)
                        TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/RoyalLiang/Yingxi-Studio/issues").buildUpon().appendQueryParameter("q","yingxi-playback-report:$previous").build()))}.onFailure{Toast.makeText(context,"未能打开回执链接，可先复制检修内容",Toast.LENGTH_LONG).show()}}){Text("检查上次回执")}
                        TextButton(enabled=!reserving,onClick={
                            reserving=true
                            scope.launch{
                                withContext(Dispatchers.IO){attempts.clear(previous!!)}
                                previous=attempts.pending();reserving=false
                            }
                        },modifier=Modifier.testTag("report-new-attempt")){Text("开始一次新的上报")}
                    } else {
                        Row(Modifier.fillMaxWidth().toggleable(agreed,role=Role.Checkbox,onValueChange={agreed=it}).heightIn(min=48.dp).testTag("report-consent"),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(agreed,onCheckedChange=null)
                            Text("我已了解公开范围，同意将这份报告提交到上述仓库",Modifier.weight(1f),fontSize=14.sp,lineHeight=21.sp,color=PlayerInk)
                        }
                        if(saveFailed)Text("未能保存回执标记，请先复制并保留检修内容。",color=PlayerAccent,fontSize=13.sp)
                        Button(enabled=agreed&&!reserving,onClick={
                            reserving=true;saveFailed=false
                            val request=playbackReportRequest(report)
                            scope.launch{
                                if(withContext(Dispatchers.IO){attempts.begin(request.getString("id"))})sending=request
                                else {previous=attempts.pending();saveFailed=previous==null}
                                reserving=false
                            }
                        },shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("report-continue")){Text(if(reserving)"正在准备…"else"同意并上报")}
                    }
                    PlaybackDiagnosticCopy(player,waitMs,report)
                }
            }
        }
    }
}
