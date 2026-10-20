package io.localplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionDialog(vm: LibraryViewModel, d: Draft) {
    if(d.source.protocol==Protocol.WEBDAV){WebDavConnectionDialog(vm,d);return}

    var showPassword by remember { mutableStateOf(false) }
    var advanced by remember(d.source.id) { mutableStateOf(d.source.initialPath.isNotEmpty()) }
    ModalBottomSheet(onDismissRequest = { vm.dismissDraft() }, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true), containerColor = Paper.copy(alpha = .97f), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), scrimColor = Ink.copy(alpha = .22f)) {
        SheetGlassEffect()
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().imePadding().testTag("connection-form"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Column { Text("连接来源", fontSize = 26.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("输入地址，连接你的媒体库。", fontSize = 14.sp, color = Muted) } }
            item { Row(Modifier.fillMaxWidth().background(Color(0xFFE8E8EE), RoundedCornerShape(17.dp)).padding(4.dp).selectableGroup()) {
                Protocol.entries.filter { it != Protocol.ALIYUN }.forEach { protocol -> ChoicePill(if(protocol==Protocol.WEBDAV)"WebDAV" else protocol.name, d.source.protocol == protocol, Modifier.weight(1f)) {
                    if (d.source.protocol != protocol) vm.draft = d.copy(source = d.source.copy(protocol = protocol, root = if (protocol != Protocol.SMB) "/" else "", initialPath = "", trustedKey = null, trustedAuthority = null), port = when(protocol){Protocol.SFTP->"22";Protocol.SMB->"445";Protocol.WEBDAV,Protocol.ALIYUN->"443"},browsePath=null)
                } }
            } }
            item { PlayerField(if (d.source.protocol == Protocol.SMB) "服务器地址 / SMB 链接" else "主机 / IP", d.source.host, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) { vm.draft = d.copy(source = d.source.copy(host = it, trustedKey = null, trustedAuthority = null)) } }
            item { PlayerField("名称（可选）", d.source.label) { vm.draft = d.copy(source = d.source.copy(label = it)) } }
            item { Text("身份验证", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted) }
            item { PlayerField(if (d.source.protocol == Protocol.SMB) "用户名（空白为匿名）" else "用户名", d.source.username) { vm.draft = d.copy(source = d.source.copy(username = it)) } }
            item { PlayerField("密码", d.password, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), trailing = {
                IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "隐藏密码" else "显示密码") }
            }) { vm.draft = d.copy(password = it) } }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级选项" else "高级选项：端口、域、共享名") } }
            if (advanced || d.source.protocol == Protocol.SFTP) {
                item { PlayerField("端口", d.port, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) { vm.draft = d.copy(port = it, source = d.source.copy(trustedKey = null, trustedAuthority = null)) } }
                item { PlayerField(if (d.source.protocol == Protocol.SMB) "共享名（留空则选择共享）" else "根目录", d.source.root) { vm.draft = d.copy(source = d.source.copy(root = it)) } }
                item { PlayerField("起始文件夹（可选）",d.source.initialPath) { vm.draft=d.copy(source=d.source.copy(initialPath=it),browsePath=null) } }
                if (d.source.protocol == Protocol.SMB) item { PlayerField("域（可选）", d.source.domain) { vm.draft = d.copy(source = d.source.copy(domain = it)) } }
            }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = d.remember, onCheckedChange = { vm.draft = d.copy(remember = it) }, modifier = Modifier.semantics { contentDescription = "记住密码" })
                Text("记住密码，仅本机加密保存", Modifier.padding(start = 12.dp), fontSize = 13.sp)
            } }
            vm.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
            item { Button(onClick = { vm.connectDraft() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                if (vm.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
                Text(if (vm.busy) "正在连接…" else "连接并浏览")
            } }
            if (vm.busy) item { TextButton(onClick = { vm.cancelOperation() }) { Text("取消连接") } }
            if (vm.sources.any { it.id == d.source.id }) item { TextButton(onClick = { vm.deleteDraft() }) { Text("移除此来源", color = MaterialTheme.colorScheme.error) } }
        }
    }
}
