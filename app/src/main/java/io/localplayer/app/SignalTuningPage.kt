package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.localplayer.core.SignalTuning
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable internal fun SignalTuningPage(openPerson: (String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    fun words(zh: String, en: String) = if (language == "zh") zh else en
    fun WorldText.say() = inLanguage(language)
    val world = remember(context) { StationWorld.load(context) }
    val signals = remember(context) { TuningSignals.load(context) }
    val store = remember(context) { TuningStore(context) }
    val initial = remember(store) { store.restore(signals) }
    var progress by remember { mutableStateOf(initial.first) }
    val signal = signals.first { it.rules.id == progress.round }
    val tuned = signal.rules.tuned(progress)
    val distance = abs(progress.position - signal.rules.target)
    val person = world.people.first { it.id == signal.line.author }
    var message by remember { mutableStateOf<WorldText?>(if (initial.second) WorldText("这段练习已重新整理，旋钮回到了起点。", "This practice has been refreshed; the dial is back at its starting mark.") else null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    val noise = remember(context) { TuningNoise(context) { sound = false; message = WorldText("声音已停下，仍可看着线条继续。", "Sound has stopped. You can keep following the lines.") } }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun stopSound() { noise.stop(); sound = false }
    fun save(position: Int) {
        progress = SignalTuning.move(signal.rules, progress, position)
        store.save(progress)
        if (signal.rules.tuned(progress)) { stopSound(); message = null } else noise.updateDistance(abs(progress.position - signal.rules.target))
    }
    DisposableEffect(noise) { onDispose { noise.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { stopSound() }
    BackHandler { stopSound(); back() }
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("signal-tuning")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stopSound(); back() }, modifier = Modifier.testTag("tuning-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, words("返回探索", "Back to Explore"), tint = Berry) }
            Text(words("调频寻声", "Tune for a trace"), Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(words("离线信号练习 · 第 ${signals.indexOf(signal) + 1} / ${signals.size} 段", "Offline signal practice · ${signals.indexOf(signal) + 1} / ${signals.size}"), fontSize = 12.sp, color = Berry, modifier = Modifier.testTag("tuning-round"))
            Text(signal.title.say(), fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 37.sp, color = Ink)
            Text(words("转动旋钮，或点下方步进键，让实线靠近虚线。短笺随时可以直接读。", "Turn the dial or use the step buttons to bring the solid line to the dashed one. You can read the note at any time."), fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
            SignalTrace(progress.position, signal.rules.target, signal.rules.id)
            val direction = if (progress.position < signal.rules.target) words("向高处调", "Tune higher") else words("向低处调", "Tune lower")
            val quality = when {
                tuned -> words("信号对上了", "The lines meet")
                distance <= 8 -> words("很近了", "Very close")
                distance <= 20 -> words("逐渐清晰", "Getting clearer")
                else -> words("杂讯未散", "Still a little static")
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(quality, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.testTag("tuning-quality"))
                Text(if (tuned) words("刻度 ${progress.position} · 已对准", "Mark ${progress.position} · Aligned") else words("刻度 ${progress.position} · 相差 $distance 格 · $direction", "Mark ${progress.position} · $distance marks apart · $direction"), fontSize = 13.sp, lineHeight = 20.sp, color = Muted, modifier = Modifier.testTag("tuning-position"))
            }
            TuningDial(signal.rules.id, progress.position, !tuned, words("调谐旋钮", "Tuning dial"), if (tuned) words("已对准", "Aligned") else direction, Modifier.align(Alignment.CenterHorizontally), ::save)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { save(progress.position - 5) }, enabled = !tuned && progress.position > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tuning-lower"), shape = RoundedCornerShape(16.dp)) { Text(words("低一点 −5", "Lower −5")) }
                OutlinedButton(onClick = { save(progress.position + 5) }, enabled = !tuned && progress.position < 100,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tuning-higher"), shape = RoundedCornerShape(16.dp)) { Text(words("高一点 +5", "Higher +5")) }
            }
            if (!tuned) TextButton(onClick = { message = WorldText("试着靠近刻度 ${signal.rules.target}，两条线就会合在一起。", "Try mark ${signal.rules.target}; the two lines will meet there.") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tuning-hint")) { Text(words("看一眼提示", "Show a hint")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(words("微弱底噪", "Gentle static"), fontSize = 15.sp, color = Ink)
                    Text(if (sound) words("已开启，离开即停", "On; stops when you leave") else words("默认静音", "Silent by default"), fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
                }
                Switch(checked = sound, enabled = !tuned, onCheckedChange = { enabled ->
                    if (!enabled) stopSound() else when (noise.start(distance)) {
                        NoiseStart.Started -> { sound = true; message = null }
                        NoiseStart.OtherAudio -> message = WorldText("已有音频在播放，练习保持静音。", "Audio is already playing. This practice will stay silent.")
                        NoiseStart.Unavailable -> message = WorldText("暂时无法播放底噪，仍可看线条调频。", "Static is unavailable. You can still tune by following the lines.")
                    }
                }, modifier = Modifier.testTag("tuning-sound").semantics { contentDescription = words("微弱底噪", "Gentle static") })
            }
            message?.let { Text(it.say(), fontSize = 14.sp, lineHeight = 22.sp, color = Berry, modifier = Modifier.testTag("tuning-message").semantics { liveRegion = LiveRegionMode.Polite }) }
            if (!progress.revealed) OutlinedButton(onClick = { progress = SignalTuning.read(signal.rules, progress); store.save(progress) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tuning-read"), shape = RoundedCornerShape(16.dp)) { Text(words("直接读短笺", "Read the note now")) }
            if (progress.revealed) {
                HorizontalDivider(color = Muted.copy(alpha = .16f))
                Text(if (tuned) words("留在频段里的短笺", "A note along the dial") else words("先读这张短笺", "Read this note first"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    StationPortrait(person.portrait, person.name.say(), Modifier.size(44.dp), false)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("“${signal.line.text.say()}”", fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp, color = Ink, modifier = Modifier.testTag("tuning-note"))
                        Text(person.name.say(), fontSize = 12.sp, color = Berry)
                    }
                }
                TextButton(onClick = { stopSound(); openPerson(person.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tuning-person")) { Text(words("查看人物档案", "Open character archive")) }
                Button(onClick = {
                    stopSound(); message = null
                    val next = signals[(signals.indexOf(signal) + 1) % signals.size]
                    progress = SignalTuning.restore(next.rules, null); store.save(progress)
                    scope.launch { scroll.scrollTo(0) }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tuning-next"), shape = RoundedCornerShape(16.dp)) { Text(words("换一段信号", "Try another signal")) }
            }
            TextButton(onClick = { stopSound(); reset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tuning-reset")) { Text(words("重新调这一段", "Restart this signal"), color = Muted) }
            Text(words("图形与短笺均在本机，不扫描附近设备。", "The graphics and notes are local. No nearby devices are scanned."), fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, containerColor = Paper, shape = RoundedCornerShape(26.dp),
        title = { Text(words("把旋钮拨回起点？", "Return the dial to its start?")) },
        text = { Text(words("只重调这一段练习，已打开的短笺会收起。", "Only this practice signal restarts. Its open note will fold away."), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { progress = SignalTuning.restore(signal.rules, null); store.save(progress); message = null; reset = false }, modifier = Modifier.testTag("tuning-reset-confirm")) { Text(words("拨回起点", "Return to start")) } },
        dismissButton = { TextButton(onClick = { reset = false }, modifier = Modifier.testTag("tuning-reset-cancel")) { Text(words("保留刻度", "Keep the mark")) } })
}

@Composable private fun SignalTrace(position: Int, target: Int, identity: String) {
    val berry = Berry; val pine = MaterialTheme.colorScheme.secondary; val muted = Muted
    val shift by animateFloatAsState((position - target).toFloat(), label = "signal position")
    Surface(color = GroupedSurface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, muted.copy(alpha = .14f))) {
        Canvas(Modifier.fillMaxWidth().height(156.dp).clearAndSetSemantics {}.testTag("tuning-trace")) {
            val margin = 18.dp.toPx(); val width = size.width - margin * 2
            val random = Random(identity.hashCode())
            repeat(65) {
                drawCircle(muted.copy(alpha = (abs(shift) / 100 * .3f).coerceIn(0f, .3f)), .7.dp.toPx(), Offset(margin + random.nextFloat() * width, margin + random.nextFloat() * (size.height - margin * 2)))
            }
            fun line(offset: Float): Path = Path().apply {
                for (i in 0..160) {
                    val x = margin + width * i / 160
                    val y = size.height * .5f + offset / 100 * size.height * .42f + sin(i / 160f * Math.PI * 6).toFloat() * size.height * .085f
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(line(0f), pine, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
            drawPath(line(shift), berry, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable private fun TuningDial(identity: String, position: Int, enabled: Boolean, label: String, state: String, modifier: Modifier, move: (Int) -> Unit) {
    val ink = Ink; val muted = Muted; val berry = Berry; val paper = Paper; val surface = GroupedSurface
    val angle by animateFloatAsState(position.toFloat(), label = "dial mark")
    Box(modifier.size(176.dp).testTag("tuning-dial").semantics(mergeDescendants = true) {
        contentDescription = label; stateDescription = "$position · $state"
        progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..100f, 99)
        if (enabled) setProgress { value -> if (!value.isFinite()) false else { move(value.toInt()); true } } else disabled()
    }.focusable().pointerInput(enabled, identity) {
        if (enabled) detectDragGestures(onDragStart = { point ->
            val vector = point - Offset(size.width / 2f, size.height / 2f)
            if (vector.getDistance() > size.width * .2f) SignalTuning.positionForVector(vector.x, vector.y)?.let(move)
        }, onDrag = { change, _ ->
            change.consume()
            val vector = change.position - Offset(size.width / 2f, size.height / 2f)
            if (vector.getDistance() > size.width * .2f) SignalTuning.positionForVector(vector.x, vector.y)?.let(move)
        })
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val radius = size.minDimension / 2 - 10.dp.toPx()
            repeat(21) { i ->
                val a = Math.toRadians(135 + i * 13.5)
                val unit = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawLine(muted.copy(alpha = .4f), center + unit * radius, center + unit * (radius - if (i % 5 == 0) 10.dp.toPx() else 5.dp.toPx()), 1.dp.toPx())
            }
            drawCircle(surface, radius * .72f)
            drawCircle(paper.copy(alpha = .4f), radius * .72f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            val a = Math.toRadians(135 + angle * 2.7)
            val unit = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(if (enabled) berry else muted, center + unit * radius * .51f, center + unit * radius * .65f, 3.dp.toPx(), StrokeCap.Round)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(position.toString(), fontFamily = FontFamily.Serif, fontSize = 30.sp, color = ink)
            Text("0–100", fontSize = 11.sp, color = muted)
        }
    }
}
