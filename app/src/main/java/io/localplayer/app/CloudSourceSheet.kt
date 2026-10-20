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

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WebDavConnectionDialog(vm:LibraryViewModel,d:Draft) {
    var manual by rememberSaveable(d.source.id){mutableStateOf(d.webDavUrl.trim()!="https://" && d.webDavUrl.isNotBlank())}
    if(manual){ExistingConnectionDialog(vm,d,onChooseCloud={manual=false},onDismiss={manual=false});return}
    val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    ModalBottomSheet(onDismissRequest={vm.dismissDraft()},sheetState=state,containerColor=Paper.copy(alpha=.97f),
        shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp),scrimColor=Ink.copy(alpha=.22f)) {
        SheetGlassEffect()
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().imePadding().testTag("connection-form"),
            contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Column(Modifier.padding(bottom=8.dp)) {
                Text("连接来源",fontSize=26.sp,letterSpacing=(-.5).sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(6.dp));Text("让你的私人片库，近在眼前。",fontSize=14.sp,color=Muted)
            } }
            item { Row(Modifier.fillMaxWidth().background(GroupedSurface,RoundedCornerShape(17.dp)).padding(4.dp).selectableGroup()) {
                Protocol.entries.forEach { p->ChoicePill(if(p==Protocol.WEBDAV)"WebDAV" else p.name,d.source.protocol==p,Modifier.weight(1f)) {
                    if(d.source.protocol!=p)vm.draft=d.copy(source=d.source.copy(protocol=p,initialPath="",root=if(p==Protocol.SMB)""else"/",trustedKey=null,trustedAuthority=null),port=if(p==Protocol.SMB)"445"else"22",browsePath=null)
                } }
            } }
            item { CloudProviderPicker() }
            item { OutlinedButton(onClick={manual=true},modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("manual-webdav"),shape=RoundedCornerShape(18.dp)) {Text("使用 WebDAV 地址")} }
            item { Text("已有 OpenList、NAS 或自建服务地址，可直接连接。",Modifier.padding(horizontal=4.dp),fontSize=13.sp,lineHeight=19.sp,color=Muted) }
        }
    }
}

@Composable internal fun CloudProviderPicker(gateway:CloudAppGateway?=null) {
    val context=LocalContext.current
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
        Column {Text("选择网盘",fontSize=20.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(5.dp));Text("打开官方 App 登录；官方授权接入准备中。",fontSize=14.sp,lineHeight=20.sp,color=Muted)}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            CloudProvider.entries.forEach { p->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                GlassSurface(Modifier.fillMaxWidth().testTag("cloud-open-${p.name}").clickable(role=Role.Button) {
                    val result=apps.open(p);statuses=CloudProvider.entries.associateWith(apps::status)
                    if(result!=CloudLaunchResult.OPENED)feedback=p to result
                },radius=22) {
                    Column(Modifier.fillMaxWidth().padding(vertical=22.dp,horizontal=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(p.icon),contentDescription=null,modifier=Modifier.size(48.dp))
                        Text(p.title,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                        Text(when(statuses[p]){CloudAppStatus.AVAILABLE->"打开官方 App";CloudAppStatus.NOT_INSTALLED->"未安装";else->"App 暂不可用"},fontSize=13.sp,color=Muted)
                    }
                }
            } }
        }
        Text("网盘直连授权尚未启用；打开 App 不会自动添加播放来源。",fontSize=13.sp,lineHeight=20.sp,color=Muted)
    }
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
