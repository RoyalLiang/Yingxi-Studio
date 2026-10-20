package io.localplayer.app

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal data class SourceNotice(val id: Long, val text: String, val isError: Boolean)

/** A single timer survives page and sheet changes; old timers cannot dismiss newer notices. */
@Composable internal fun SourceFeedbackLifetime(vm: LibraryViewModel) {
    val notice = vm.notice
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice?.id, accessibility) {
        notice ?: return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            if (notice.isError) 6_000L else 3_000L,
            containsIcons = true, containsText = true, containsControls = true,
        ) ?: if (notice.isError) 6_000L else 3_000L
        delay(timeout)
        vm.dismissNotice(notice.id)
    }
}

// finesse · component: toast · register=product · tokens: inherited mist+berry
@Composable internal fun SourceFeedback(vm: LibraryViewModel, modifier: Modifier = Modifier) {
    var details by remember { mutableStateOf<SourceNotice?>(null) }
    AnimatedContent(
        targetState = vm.notice,
        modifier = modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 20.dp),
        transitionSpec = {
            (fadeIn(tween(180)) + scaleIn(spring(dampingRatio = .82f, stiffness = 500f), initialScale = .94f)) togetherWith
                fadeOut(tween(130))
        },
        contentKey = { it?.id }, label = "source-toast",
    ) { notice ->
        if (notice != null) GlassSurface(radius = 24) {
            Row(
                Modifier.fillMaxWidth().testTag("source-toast").semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (notice.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null,
                    tint = if (notice.isError) MaterialTheme.colorScheme.error else Berry, modifier = Modifier.size(22.dp))
                Text(notice.text, Modifier.weight(1f).padding(horizontal = 12.dp), color = Ink,
                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (notice.isError) TextButton(onClick = { details = notice }) { Text("详情", maxLines = 1) }
                IconButton(onClick = { vm.dismissNotice(notice.id) }) {
                    Icon(Icons.Rounded.Close, "关闭提示", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
        } else Spacer(Modifier.size(0.dp))
    }
    details?.let { notice ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("操作未完成") },
            text = { Text(notice.text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { details = null }) { Text("知道了") } })
    }
}
