package io.localplayer.app

import androidx.compose.ui.graphics.luminance

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.Protocol

/** All protocols share one Android window. Only the configuration body crossfades. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun ConnectionDialog(vm: LibraryViewModel, d: Draft, requestNearby: () -> Unit = {}) {
    val external=d.source.protocol==Protocol.WEBDAV||d.source.protocol==Protocol.ALIYUN
    val adding=vm.sources.none{it.id==d.source.id}
    val categoryDrafts=remember(d.source.id){mutableMapOf<Boolean,Draft>()}
    categoryDrafts[external]=d
    var manualWebDav by remember(d.source.id) { mutableStateOf(d.webDavUrl.trim() !in listOf("", "https://")) }
    var cloudProvider by remember(d.source.id) { mutableStateOf(CloudProvider.ALIYUNPAN) }
    val focus = LocalFocusManager.current
    val scene = rememberGraphicsLayer()
    // Preserve the outgoing form while Crossfade composes both protocol panels.
    val drafts = remember(d.source.id) { mutableMapOf<Protocol, Draft>() }
    drafts[d.source.protocol] = d
    val panel = if (vm.discoveryPanelOpen) "discovery" else if (external && !manualWebDav) "cloud:${cloudProvider.name}" else d.source.protocol.name
    val nested by rememberUpdatedState(vm.discoveryPanelOpen)
    val back: () -> Unit = {
        focus.clearFocus()
        if (vm.discoveryPanelOpen) { vm.discoveryPanelOpen = false; vm.stopDiscovery() }
        else manualWebDav = false
    }
    val currentBack by rememberUpdatedState(back)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.dismissDraft() }, sheetState = sheetState,
        containerColor = Paper.copy(alpha = .97f), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        scrimColor = Ink.copy(alpha = .22f),
    ) {
        BackHandler(enabled = nested) { currentBack() }
        val sheetView = LocalView.current
        val lightBars=MaterialTheme.colorScheme.background.luminance()>.5f
        val updateSystemBars=remember(sheetView,lightBars){Runnable{
            if(sheetView.isAttachedToWindow){
                (sheetView.parent as? DialogWindowProvider)?.window?.let{window->
                    WindowCompat.getInsetsController(window,sheetView).apply{
                        isAppearanceLightStatusBars=lightBars
                        isAppearanceLightNavigationBars=lightBars
                    }
                }
            }
        }}
        SideEffect{sheetView.removeCallbacks(updateSystemBars);sheetView.post(updateSystemBars)}
        DisposableEffect(sheetView){onDispose{sheetView.removeCallbacks(updateSystemBars)}}

        DisposableEffect(sheetView, nested) {
            // Material3 1.3.2 owns the dialog dispatcher. Register on that window so
            // nested Back changes content without starting the sheet's hide animation.
            val dispatcher = sheetView.findOnBackInvokedDispatcher()
            val callback = android.window.OnBackInvokedCallback { currentBack() }
            if (nested) dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            onDispose { if (nested) dispatcher?.unregisterOnBackInvokedCallback(callback) }
        }
        SheetGlassEffect()
        Box(Modifier.fillMaxWidth().fillMaxHeight().imePadding().testTag("connection-sheet")) {
            Column(Modifier.fillMaxSize().drawWithContent { scene.record { this@drawWithContent.drawContent() }; drawLayer(scene) }) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){
                        Text(if(adding) "登记信源" else if(external) "接入信号塔" else "连接幸存者据点", modifier=Modifier.weight(1f).testTag("connection-title"), fontSize=26.sp,fontWeight=FontWeight.Bold)
                        IconButton(onClick=vm::dismissDraft,modifier=Modifier.testTag("connection-close")){Icon(Icons.Rounded.Close,"关闭")}
                }
                Spacer(Modifier.height(16.dp))
                if(adding){
                    ConnectionCategoryTabs(external){tower->
                        if(tower!=external){
                            focus.clearFocus();vm.stopDiscovery();vm.cancelOperation();vm.discoveryPanelOpen=false
                            vm.draft=categoryDrafts[tower] ?: Draft(io.localplayer.core.Source(id=d.source.id,label="",protocol=if(tower)Protocol.WEBDAV else Protocol.SMB,host=""),webDavUrl=if(tower)"https://"else"")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                ConnectionProtocolSelector(external,if(external){if(manualWebDav)"WebDAV"else cloudProvider.title}else d.source.protocol.name){choice->
                    focus.clearFocus();vm.discoveryPanelOpen=false;vm.stopDiscovery()
                    if(external){
                    manualWebDav=choice=="WebDAV"
                    if(!manualWebDav)cloudProvider=CloudProvider.entries.first{it.title==choice}
                }else vm.switchDraftProtocol(Protocol.valueOf(choice))
                }
                Spacer(Modifier.height(12.dp))
                if(!external)Surface(onClick={
                    focus.clearFocus();vm.discoveryPanelOpen=true
                    if(vm.nearbyPermissionGranted)vm.discover()else requestNearby()
                },shape=RoundedCornerShape(17.dp),color=if(vm.discoveryPanelOpen)Berry.copy(alpha=.07f)else GroupedSurface,
                    modifier=Modifier.fillMaxWidth().testTag("auto-scan-entry").semantics{selected=vm.discoveryPanelOpen}
                        .border(1.dp,if(vm.discoveryPanelOpen)Berry.copy(alpha=.3f)else Hairline,RoundedCornerShape(17.dp))){
                    Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Rounded.WifiFind,null,tint=Berry,modifier=Modifier.size(22.dp))
                        Column(Modifier.weight(1f).padding(start=12.dp)){
                            Text("搜寻附近据点",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                            Text("SMB · SFTP / SSH",fontSize=12.sp,color=Muted)
                        }
                        Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                }
                Crossfade(targetState = panel, animationSpec = tween(200), label = "source-configuration", modifier = Modifier.weight(1f)) { visible ->
                    when (visible) {
                        "discovery" -> SourceDiscoveryPanel(vm, requestNearby) { vm.discoveryPanelOpen = false; vm.stopDiscovery() }
                        "cloud:ALIYUNPAN", "cloud:BAIDU" -> LazyColumn(Modifier.fillMaxSize().testTag("connection-form"), contentPadding = PaddingValues(20.dp)) {
                        item { CloudProviderAccess(CloudProvider.valueOf(visible.substringAfter(':')),onConnected=vm::cloudConnected,onFailureMessage={vm.error=it}) }
                    }
                    else -> ConnectionFields(vm, drafts[Protocol.valueOf(visible)] ?: d, webDav = visible == Protocol.WEBDAV.name)
                    }
                }
            }
            CompositionLocalProvider(LocalGlassBackdrop provides scene) {
                SourceFeedback(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
        }
    }
}

@Composable private fun ConnectionFields(vm: LibraryViewModel, d: Draft, webDav: Boolean) {
    var showPassword by remember(d.source.id) { mutableStateOf(false) }
    var advanced by remember(d.source.id, d.source.protocol) { mutableStateOf(d.source.initialPath.isNotEmpty()) }
    LazyColumn(Modifier.fillMaxSize().testTag("connection-form"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        item { Text(if(webDav)"中继路标" else "据点路标", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted) }
        if (webDav) {
            item { PlayerField("WebDAV 地址", d.webDavUrl, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) { vm.draft = d.copy(webDavUrl = it) } }
            item { Text("填写完整地址，例如 https://你的服务器/dav/。", fontSize = 12.sp, color = Muted) }
            if (d.webDavUrl.trim().startsWith("http://")) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(d.allowHttp, { vm.draft = d.copy(allowHttp = it) }, modifier = Modifier.testTag("allow-http"))
                    Column(Modifier.weight(1f)) { Text("允许明文 HTTP", fontSize = 14.sp); Text("仅用于可信网络，账号和影片在传输中不加密。", fontSize = 12.sp, color = Muted) }
                }
            }
        } else item {
            PlayerField(if (d.source.protocol == Protocol.SMB) "服务器地址" else "主机 IP", d.source.host,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) { vm.draft = d.copy(source = d.source.copy(host = it, trustedKey = null, trustedAuthority = null)) }
        }
        item { PlayerField("名称（可选）", d.source.label) { vm.draft = d.copy(source = d.source.copy(label = it)) } }
        item { Text("通行凭据", Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted) }
        item { PlayerField(if (d.source.protocol == Protocol.SMB) "用户名（可匿名）" else "用户名", d.source.username) { vm.draft = d.copy(source = d.source.copy(username = it)) } }
        item {
            PlayerField("密码", d.password, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "隐藏密码" else "显示密码", tint = Muted, modifier = Modifier.size(20.dp)) }
                }) { vm.draft = d.copy(password = it) }
        }
        if (!webDav) {
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起线路设置" else "线路设置") } }
            if (advanced) {
                item { PlayerField("端口", d.port, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) { vm.draft = d.copy(port = it, source = d.source.copy(trustedKey = null, trustedAuthority = null)) } }
                item { PlayerField(if (d.source.protocol == Protocol.SMB) "共享名（留空则选择共享）" else "根目录", d.source.root) { vm.draft = d.copy(source = d.source.copy(root = it)) } }
                item { PlayerField("起始文件夹（可选）", d.source.initialPath) { vm.draft = d.copy(source = d.source.copy(initialPath = it), browsePath = null) } }
                if (d.source.protocol == Protocol.SMB) item { PlayerField("域（可选）", d.source.domain) { vm.draft = d.copy(source = d.source.copy(domain = it)) } }
            }
        } else {
            item { Text("播放请求头（可选）", Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted) }
            item { PlayerField("User-Agent（可选）", d.source.userAgent) { vm.draft = d.copy(source = d.source.copy(userAgent = it)) } }
            item { PlayerField("Referer（可选，HTTPS）", d.source.referer) { vm.draft = d.copy(source = d.source.copy(referer = it)) } }
        }
        item {
            Row(Modifier.fillMaxWidth().background(GroupedSurface, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("记住密码", fontSize = 15.sp, fontWeight = FontWeight.Medium); Text("仅在这台设备加密保存", fontSize = 12.sp, color = Muted) }
                SettingsSwitch(d.remember, { vm.draft = d.copy(remember = it) }, modifier = Modifier.semantics { contentDescription = "记住密码" })
            }
        }
        item {
            Button(onClick = { vm.connectDraft() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                if (vm.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
                Text(if (vm.busy) "正在连接…" else "连接并浏览", maxLines = 1)
            }
        }
        if (vm.busy) item { TextButton(onClick = { vm.cancelOperation() }, modifier = Modifier.fillMaxWidth()) { Text("取消连接") } }
        if (vm.sources.any { it.id == d.source.id }) item { TextButton(onClick = { vm.deleteDraft() }, modifier = Modifier.fillMaxWidth()) { Text("移除此信源", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable private fun SourceDiscoveryPanel(vm: LibraryViewModel, requestNearby: () -> Unit, back: () -> Unit) {
    var probeConfirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("source-discovery"), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = back) { Text("返回手动输入") } }
        item { Text("附近设备", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("发现结果是待验证的设备，连接后才能访问媒体。", fontSize = 13.sp, lineHeight = 20.sp, color = Muted) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (vm.scanning) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                Text(vm.discoveryMessage, Modifier.weight(1f), fontSize = 13.sp, color = Muted)
                TextButton(onClick = { if (vm.scanning) vm.stopDiscovery() else if (vm.nearbyPermissionGranted) vm.discover() else requestNearby() }) { Text(if (vm.scanning) "停止" else "搜寻附近据点") }
            }
        }
        items(vm.candidates.filter{it.protocol==Protocol.SMB||it.protocol==Protocol.SFTP}, key = { it.id }) { candidate ->
            Surface(onClick = { vm.add(candidate) }, color = GroupedSurface, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(candidate.name, fontWeight = FontWeight.Medium)
                    Text("${candidate.protocol} · ${candidate.host}:${candidate.port}", fontSize = 13.sp, color = Muted)
                    Text("待验证 · ${candidate.hint}", fontSize = 12.sp, color = Muted)
                }
            }
        }
        if (vm.candidates.isEmpty() && !vm.scanning) item { Text("未发现设备时，可以返回输入服务器地址。", fontSize = 14.sp, color = Muted) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("自动发现", fontSize = 15.sp); Text("仅在打开扫描页时运行", fontSize = 12.sp, color = Muted) }
                SettingsSwitch(vm.autoDiscoveryEnabled, { vm.setAutoDiscovery(it) }, modifier = Modifier.testTag("auto-discovery"))
            }
        }
        item { TextButton(onClick = { probeConfirm = true }) { Text("补充查找未广播的设备") } }
    }
    if (probeConfirm) AlertDialog(onDismissRequest = { probeConfirm = false }, title = { Text("补充发现局域网候选") },
        text = { Text("将探测当前 /24 局域网的 22/445 端口，最多 16 个并发。仅在你信任的网络中使用。") },
        confirmButton = { TextButton(onClick = { probeConfirm = false; if (vm.nearbyPermissionGranted) vm.probe() else requestNearby() }) { Text("开始查找") } },
        dismissButton = { TextButton(onClick = { probeConfirm = false }) { Text("取消") } })
}
