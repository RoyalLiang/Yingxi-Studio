package io.localplayer.app

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.Protocol
import io.localplayer.core.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// One quiet row, one menu. Revealed actions remain separate, deliberate taps.
@Composable internal fun SavedSourceRow(
    source: Source, pinned: Boolean, favorite: Boolean, folder: String,
    revealed: Boolean, onReveal: () -> Unit, open: () -> Unit, pin: () -> Unit,
    remove: () -> Unit, privateAccess: () -> Unit, selected: Boolean = false, actions: @Composable (() -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val leftLimit = with(density) { 92.dp.toPx() }
    val rightLimit = with(density) { 168.dp.toPx() }
    var offset by remember(source.id) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var settling by remember { mutableStateOf<Job?>(null) }
    val settle: (Float) -> Unit = { target ->
        settling?.cancel()
        settling = scope.launch { animate(offset, target, animationSpec = spring(dampingRatio = .84f, stiffness = 480f)) { value, _ -> offset = value } }
    }
    val collapse = { settle(0f) }
    LaunchedEffect(revealed) { if (!revealed) settle(0f) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed && !dragging) .985f else 1f, spring(stiffness = 650f), label = "source press")
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(22.dp)).testTag("source-row:${source.id}")) {
        if (offset > 1f) SwipeAction("私密访问", Icons.Rounded.Fingerprint, Modifier.align(Alignment.CenterStart).width(82.dp).fillMaxHeight(), false) { collapse(); privateAccess() }
        if (offset < -1f) Row(Modifier.align(Alignment.CenterEnd).width(158.dp).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwipeAction(if (pinned) "取消置顶" else "置顶", Icons.Rounded.PushPin, Modifier.weight(1f).fillMaxHeight(), false) { collapse(); pin() }
            SwipeAction("删除", Icons.Rounded.DeleteOutline, Modifier.weight(1f).fillMaxHeight(), true) { collapse(); remove() }
        }
        Surface(onClick = { if (abs(offset) > 1f) collapse() else open() }, interactionSource = interactions,
            color = if(selected) Berry.copy(alpha=.045f) else GroupedSurface, shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp,if(selected)Berry.copy(alpha=.24f)else androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.fillMaxWidth().offset { IntOffset(offset.roundToInt(), 0) }.graphicsLayer { scaleX = scale; scaleY = scale }
                .semantics { this.selected = selected; customActions = listOf(
                    CustomAccessibilityAction(if (pinned) "取消置顶" else "置顶") { pin(); true },
                    CustomAccessibilityAction("删除避难所") { remove(); true },
                    CustomAccessibilityAction("私密访问") { privateAccess(); true }) }
                .draggable(orientation = Orientation.Horizontal, state = rememberDraggableState { delta ->
                    val next = offset + delta
                    offset = when { next > leftLimit -> leftLimit + (next - leftLimit) * .2f; next < -rightLimit -> -rightLimit + (next + rightLimit) * .2f; else -> next }
                }, onDragStarted = { dragging = true; settling?.cancel(); onReveal() }, onDragStopped = { velocity ->
                    dragging = false
                    settle(when { offset > leftLimit * .4f || velocity > 900f -> leftLimit; offset < -rightLimit * .35f || velocity < -900f -> -rightLimit; else -> 0f })
                })) {
            Row(Modifier.heightIn(min = 86.dp).padding(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(source.label, Modifier.testTag("source-title:${source.id}"), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (selected) Icon(Icons.Rounded.Check, "已选择避难所", Modifier.size(14.dp), tint = Berry)
                        if (pinned) Icon(Icons.Rounded.PushPin, "已置顶", Modifier.size(12.dp), tint = Berry)
                        if (favorite) Icon(Icons.Rounded.Star, "已收藏避难所", Modifier.size(12.dp), tint = Berry)
                        Text(if (source.protocol == Protocol.ALIYUN) "阿里云盘 · 只读授权" else "${source.protocol} · ${source.host}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (source.initialPath.isNotEmpty()) Text("进入 $folder", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                actions(collapse)
            }
        }
    }
}

@Composable private fun SwipeAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, destructive: Boolean, click: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else Berry
    FilledTonalButton(onClick = click, modifier = modifier.padding(vertical = 4.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = tint.copy(alpha = .10f), contentColor = tint), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(23.dp)); Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
