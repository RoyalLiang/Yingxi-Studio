package io.localplayer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.localplayer.core.Protocol
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable internal fun CloudProviderPicker(gateway:CloudAppGateway?=null,onConnected:((io.localplayer.core.Source)->Unit)?=null,onFailureMessage:((String)->Unit)?=null) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var authorizing by remember{mutableStateOf(false)}
    var authorizationJob by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var authError by remember{mutableStateOf<String?>(null)}
    val apps=remember(context,gateway){gateway ?: AndroidCloudApps(context)}
    val owner=LocalLifecycleOwner.current
    var statuses by remember(apps){mutableStateOf(CloudProvider.entries.associateWith(apps::status))}
    var feedback by remember{mutableStateOf<Pair<CloudProvider,CloudLaunchResult>?>(null)}
    DisposableEffect(owner,apps) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_RESUME)statuses=CloudProvider.entries.associateWith(apps::status) }
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    Column(Modifier.testTag("cloud-provider-picker"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Column {Text("选择网盘",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(5.dp));Text("授权后直接浏览和播放阿里云盘。",fontSize=14.sp,lineHeight=20.sp,color=Muted)}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CloudProvider.entries.forEach { p->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                GlassSurface(Modifier.fillMaxWidth().testTag("cloud-open-${p.name}").clickable(role=Role.Button) {
                    if(p==CloudProvider.ALIYUNPAN && gateway==null && onConnected!=null && statuses[p]==CloudAppStatus.AVAILABLE){
                        if(!authorizing)authorizationJob=scope.launch{authorizing=true;try{onConnected(AliyunCloud.authorize())}catch(e:CancellationException){throw e}catch(e:Exception){val message=e.message ?: "授权失败，请重试";if(onFailureMessage!=null)onFailureMessage(message)else authError=message}finally{authorizing=false}}
                        return@clickable
                    }
                    val result=apps.open(p);statuses=CloudProvider.entries.associateWith(apps::status)
                    if(result!=CloudLaunchResult.OPENED)feedback=p to result
                },radius=22) {
                    Column(Modifier.fillMaxWidth().padding(vertical=22.dp,horizontal=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(p.icon),contentDescription=null,modifier=Modifier.size(48.dp))
                        Text(p.title,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                        Text(when(statuses[p]){CloudAppStatus.AVAILABLE->if(p==CloudProvider.ALIYUNPAN)"登录并授权"else"即将支持";CloudAppStatus.NOT_INSTALLED->"未安装";else->"App 暂不可用"},fontSize=13.sp,color=Muted)
                    }
                }
            } }
        }
        Text("阿里云盘仅申请读取权限，你可以随时移除账户。",fontSize=13.sp,lineHeight=20.sp,color=Muted)
    }
    if(authorizing) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("正在等待阿里云盘授权…",Modifier.weight(1f),color=Muted);TextButton(onClick={authorizationJob?.cancel()}){Text("取消授权")}}
    authError?.let{message->AlertDialog(onDismissRequest={authError=null},title={Text("授权未完成")},text={Text(message)},confirmButton={TextButton(onClick={authError=null}){Text("知道了")}})}
    feedback?.let { (p,result)->AlertDialog(onDismissRequest={feedback=null},
        title={Text(if(result==CloudLaunchResult.NOT_INSTALLED)"未安装${p.title}"else"${p.title}暂不可用")},
        text={Text(if(result==CloudLaunchResult.NOT_INSTALLED)"请先安装官方 App，再回到映汐打开。"else"无法打开官方 App，请检查它是否已被停用或受系统限制。")},
        confirmButton={TextButton(onClick={feedback=null}){Text("知道了")}},
        dismissButton={TextButton(onClick={openCloudWebsite(context,p.home)}){Text("官方安装入口")}}) }
}

private fun openCloudWebsite(context:Context,url:String) {
    try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    catch(_:android.content.ActivityNotFoundException){Toast.makeText(context,"没有可用的浏览器，请先安装或启用浏览器",Toast.LENGTH_LONG).show()}
    catch(_:SecurityException){Toast.makeText(context,"系统暂不允许打开网页",Toast.LENGTH_LONG).show()}
}
