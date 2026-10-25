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

@Composable internal fun CloudProviderAccess(provider:CloudProvider,gateway:CloudAppGateway?=null,onConnected:((io.localplayer.core.Source)->Unit)?=null,onFailureMessage:((String)->Unit)?=null) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var authorizing by remember{mutableStateOf(false)}
    var authorizationJob by remember{mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var authError by remember{mutableStateOf<String?>(null)}
    val apps=remember(context,gateway){gateway ?: AndroidCloudApps(context)}
    val owner=LocalLifecycleOwner.current
    var status by remember(apps,provider){mutableStateOf(apps.status(provider))}
    var feedback by remember{mutableStateOf<Pair<CloudProvider,CloudLaunchResult>?>(null)}
    DisposableEffect(owner,apps,provider) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_RESUME)status=apps.status(provider) }
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer)}
    }
    val p=provider
    Column(Modifier.testTag("cloud-provider-access"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        FilledTonalButton(onClick={
if(p==CloudProvider.ALIYUNPAN && gateway==null && onConnected!=null && status==CloudAppStatus.AVAILABLE){
                        if(!authorizing)authorizationJob=scope.launch{authorizing=true;try{onConnected(AliyunCloud.authorize())}catch(e:CancellationException){throw e}catch(e:Exception){val message=e.message ?: "授权失败，请重试";if(onFailureMessage!=null)onFailureMessage(message)else authError=message}finally{authorizing=false}}
                        return@FilledTonalButton
                    }
                    val result=apps.open(p);status=apps.status(provider)
                    if(result!=CloudLaunchResult.OPENED)feedback=p to result
        },enabled=!authorizing,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("cloud-open-${p.name}")) {
            Image(painterResource(p.icon),null,Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(if(p==CloudProvider.ALIYUNPAN)"登录并授权${p.title}"else"打开${p.title}",fontSize=16.sp,lineHeight=24.sp)
        }
    if(authorizing) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("正在等待阿里云盘授权…",Modifier.weight(1f),color=Muted);TextButton(onClick={authorizationJob?.cancel()}){Text("取消授权")}}
    authError?.let{message->AlertDialog(onDismissRequest={authError=null},title={Text("授权未完成")},text={Text(message)},confirmButton={TextButton(onClick={authError=null}){Text("知道了")}})}
    feedback?.let { (p,result)->AlertDialog(onDismissRequest={feedback=null},
        title={Text(if(result==CloudLaunchResult.NOT_INSTALLED)"未安装${p.title}"else"${p.title}暂不可用")},
        text={Text(if(result==CloudLaunchResult.NOT_INSTALLED)"请先安装官方 App，再回到映汐打开。"else"无法打开官方 App，请检查它是否已被停用或受系统限制。")},
        confirmButton={TextButton(onClick={feedback=null}){Text("知道了")}},
        dismissButton={TextButton(onClick={openCloudWebsite(context,p.home)}){Text("官方安装入口")}}) }
}

}

private fun openCloudWebsite(context:Context,url:String) {
    try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    catch(_:android.content.ActivityNotFoundException){Toast.makeText(context,"没有可用的浏览器，请先安装或启用浏览器",Toast.LENGTH_LONG).show()}
    catch(_:SecurityException){Toast.makeText(context,"系统暂不允许打开网页",Toast.LENGTH_LONG).show()}
}
