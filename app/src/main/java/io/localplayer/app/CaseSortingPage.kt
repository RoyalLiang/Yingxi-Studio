package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.CaseMatch
import io.localplayer.core.CaseSorting
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable internal fun CaseSortingPage(back: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val pine = MaterialTheme.colorScheme.secondary
    fun words(zh: String, en: String) = if (language == "zh") zh else en
    fun WorldText.say() = inLanguage(language)
    val rounds = remember(context) { PracticeCases.load(context) }
    val store = remember(context) { PracticeStore(context) }
    val initial = remember(store) { store.restore(rounds) }
    var progress by remember { mutableStateOf(initial.first) }
    val round = rounds.first { it.rules.id == progress.round }
    var selectedLabel by remember(round.rules.id) { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<WorldText?>(if (initial.second) WorldText("这盘题面已更新，纸签已重新摆好。", "This tray was updated; its labels have been laid out again.") else null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val targets = remember(round.rules.id) { mutableMapOf<String, Rect>() }
    var dragging by remember(round.rules.id) { mutableStateOf<String?>(null) }
    var point by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val edge = with(density) { 52.dp.toPx() }
    val step = with(density) { 7.dp.toPx() }
    val complete = progress.matched == round.rules.pairs
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // A held paper must be able to reach cases outside the viewport, including large type.
    LaunchedEffect(dragging, point, viewport) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            val delta = when {
                point.y < viewport.top + edge -> -step
                point.y > viewport.bottom - edge -> step
                else -> break
            }
            if (scroll.scrollBy(delta) == 0f) break
            delay(16)
        }
    }
    fun place(label: String, target: String) {
        val move = CaseSorting.place(round.rules, progress, label, target)
        progress = move.progress
        store.save(progress)
        selectedLabel = null
        feedback = when (move.result) {
            CaseMatch.Wrong -> WorldText("这张还不合适。" + round.pairs.first { it.id == label }.hint.zh, "Not quite. " + round.pairs.first { it.id == label }.hint.en)
            CaseMatch.Unavailable -> WorldText("这只片匣已经理好了，试试空着的那只。", "This case is already sorted. Try an empty one.")
            else -> WorldText("纸签归位了。", "Label in place.")
        }
    }
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing)
        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }.testTag("case-sorting")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, modifier = Modifier.testTag("practice-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, words("返回探索", "Back to Explore"), tint = Berry) }
                Text(words("旧片匣归位", "Old case sorting"), Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            Column(Modifier.weight(1f).onGloballyPositioned { viewport = it.boundsInRoot() }.testTag("practice-scroll").verticalScroll(scroll).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(words("练习片匣 · 第 ${rounds.indexOf(round) + 1} / ${rounds.size} 盘", "Practice cases · Tray ${rounds.indexOf(round) + 1} / ${rounds.size}"), fontSize = 12.sp, color = Berry, modifier = Modifier.testTag("practice-round"))
                Text(round.title.say(), fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 37.sp, color = Ink)
                Text(words("点一张纸签，再点对应片匣；也可以长按纸签拖放。只整理练习纸签。", "Tap a label, then its case. Or hold a label and drag it. These are practice labels only."), fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
                round.pairs.forEach { pair ->
                    val done = pair.id in progress.matched
                    val hovered = dragging != null && targets[pair.id]?.contains(point) == true
                    val tint by animateColorAsState(if (done) pine.copy(alpha = .09f) else GroupedSurface, label = "case lid")
                    Surface(onClick = {
                        val label = selectedLabel
                        if (label == null) feedback = WorldText("先点选一张纸签，再选片匣。", "Choose a paper label first, then a case.")
                        else place(label, pair.id)
                    }, enabled = !done, color = tint, shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(if (hovered) 2.dp else 1.dp, if (hovered) Berry else Muted.copy(alpha = .15f)),
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { targets[pair.id] = it.boundsInRoot() }
                            .testTag("practice-case:${pair.id}").semantics { stateDescription = if (done) words("已归位", "In place") else words("空片匣", "Empty case") }) {
                        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(if (done) Icons.Rounded.Check else Icons.Rounded.Inventory2, null, tint = if (done) pine else Berry, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(pair.title.say(), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                                Text(if (done) pair.label.say() else pair.clue.say(), fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                            }
                        }
                    }
                }
                Text(words("已归位 ${progress.matched.size} / 3", "In place ${progress.matched.size} / 3"), fontSize = 12.sp, color = pine, modifier = Modifier.testTag("practice-progress"))
                if (complete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        StationPortrait("scavenger", words("阿汐", "Axi"), Modifier.size(44.dp), false)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("“${round.note.say()}”", fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp, color = Ink, modifier = Modifier.testTag("practice-complete"))
                            Text(words("— 阿汐", "— Axi"), fontSize = 12.sp, color = Berry)
                        }
                    }
                    Button(onClick = {
                        val next = rounds[(rounds.indexOf(round) + 1) % rounds.size]
                        feedback = null
                        progress = CaseSorting.restore(next.rules, null); store.save(progress)
                        scope.launch { scroll.scrollTo(0) }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("practice-next"), shape = RoundedCornerShape(16.dp)) { Text(words("再理一盘", "Sort another tray")) }
                    TextButton(onClick = back, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(words("回到探索", "Back to Explore")) }
                } else {
                    feedback?.let { Text(it.say(), fontSize = 14.sp, lineHeight = 22.sp, color = Berry, modifier = Modifier.testTag("practice-feedback").semantics { liveRegion = LiveRegionMode.Polite }) }
                    Text(words("待归位的纸签", "Labels to place"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                    // Rotate the starting order, so label position cannot reveal its case.
                    (round.pairs.drop(1) + round.pairs.take(1)).filter { it.id !in progress.matched }.forEach { pair ->
                        var bounds by remember { mutableStateOf(Rect.Zero) }
                        val picked = selectedLabel == pair.id || dragging == pair.id
                        Surface(onClick = { selectedLabel = if (selectedLabel == pair.id) null else pair.id; feedback = null }, color = GroupedSurface,
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp),
                            border = BorderStroke(if (picked) 2.dp else 1.dp, if (picked) Berry else Muted.copy(alpha = .22f)),
                            modifier = Modifier.fillMaxWidth().onGloballyPositioned { bounds = it.boundsInRoot() }
                                .testTag("practice-label:${pair.id}").semantics { selected = picked; role = Role.Button }
                                .pointerInput(round.rules.id, pair.id) {
                                    detectDragGesturesAfterLongPress(onDragStart = { offset -> dragging = pair.id; selectedLabel = pair.id; point = bounds.topLeft + offset },
                                        onDrag = { change, amount -> change.consume(); point += amount },
                                        onDragCancel = { dragging = null },
                                        onDragEnd = {
                                            val target = if (viewport.contains(point)) targets.entries.firstOrNull { it.value.contains(point) }?.key else null
                                            dragging = null
                                            if (target != null) place(pair.id, target)
                                        })
                                }) {
                            Text(pair.label.say(), Modifier.padding(horizontal = 16.dp, vertical = 14.dp), fontSize = 15.sp, lineHeight = 23.sp, color = Ink)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val pair = round.pairs.first { it.id == (selectedLabel ?: round.pairs.first { p -> p.id !in progress.matched }.id) }
                            feedback = pair.hint
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("practice-hint")) { Text(words("看一眼提示", "Show a hint")) }
                        TextButton(onClick = { selectedLabel = null; feedback = null }, enabled = selectedLabel != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("practice-cancel")) { Text(words("放下纸签", "Put label down")) }
                    }
                }
                TextButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("practice-reset")) { Text(words("重理这一盘", "Restart this tray"), color = Muted) }
            }
        }
        dragging?.let { id ->
            Surface(color = Paper, shadowElevation = 6.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier
                .offset { IntOffset((point.x - origin.x - 80).roundToInt(), (point.y - origin.y - 30).roundToInt()) }.width(180.dp)) {
                Text(round.pairs.first { it.id == id }.label.say(), Modifier.padding(12.dp), fontSize = 14.sp, color = Ink)
            }
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, containerColor = Paper, shape = RoundedCornerShape(26.dp),
        title = { Text(words("把这一盘重新摆开？", "Lay out this tray again?")) },
        text = { Text(words("这一盘已归位的纸签会回到桌上。只重理练习片匣。", "The labels already placed in this tray will return to the desk. Only this practice tray is restarted."), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { progress = CaseSorting.restore(round.rules, null); store.save(progress); selectedLabel = null; feedback = null; reset = false }, modifier = Modifier.testTag("practice-reset-confirm")) { Text(words("重新摆开", "Lay out again")) } },
        dismissButton = { TextButton(onClick = { reset = false }, modifier = Modifier.testTag("practice-reset-cancel")) { Text(words("保留桌面", "Keep this tray")) } })
}
