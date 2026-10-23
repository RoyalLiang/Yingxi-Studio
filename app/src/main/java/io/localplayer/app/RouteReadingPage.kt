package io.localplayer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.RecordVerdict
import io.localplayer.core.RouteReading
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RouteReadingPage(back: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val stackedChoices = LocalDensity.current.fontScale > 1.2f || LocalConfiguration.current.screenWidthDp < 360
    fun words(zh: String, en: String) = if (language == "zh") zh else en
    fun WorldText.say() = inLanguage(language)
    val rounds = remember(context) { RouteRecords.load(context) }
    val store = remember(context) { RouteReadingStore(context) }
    val initial = remember(store) { store.restore(rounds) }
    var progress by remember { mutableStateOf(initial.first) }
    var refreshed by remember { mutableStateOf(initial.second) }
    val round = rounds.first { it.rules.id == progress.round }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val source = round.records.firstOrNull { it.id == sourceId }
    if (source != null) {
        RouteOriginalPage(source, language) { sourceId = null }
        return
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("route-reading")) {
        RoutePageBar(words("路标对读", "Read the waymarks"), "reading-back", back)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(words("对读 ${rounds.indexOf(round) + 1} / ${rounds.size}", "Pair ${rounds.indexOf(round) + 1} / ${rounds.size}"),
                fontSize = 12.sp, color = Berry, modifier = Modifier.testTag("reading-round"))
            Text(round.title.say(), fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 37.sp, color = Ink)
            Text(words("弥途摊开两张纸签。逐条判断：这句话已有依据，还是仍待核实？", "Mitu lays out two slips. Does each claim have evidence, or does it still need checking?"),
                fontSize = 14.sp, lineHeight = 22.sp, color = Muted)
            if (refreshed) Text(words("这组纸签已重新整理，从头对读即可。", "These slips have changed. Begin this pair again."),
                fontSize = 14.sp, lineHeight = 22.sp, color = Berry, modifier = Modifier.testTag("reading-refreshed"))
            round.records.forEachIndexed { index, record ->
                Surface(color = GroupedSurface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(words("纸签${if (index == 0) "一" else "二"}", "Slip ${index + 1}"), fontSize = 12.sp, color = Muted)
                        Text(record.claim.say(), fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 29.sp,
                            color = Ink, modifier = Modifier.testTag("reading-claim:${record.id}"))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (stackedChoices) 1 else 2) {
                            RecordVerdict.entries.forEach { verdict ->
                                val selected = progress.choices[record.id] == verdict
                                OutlinedButton(onClick = {
                                    progress = RouteReading.choose(round.rules, progress, record.id, verdict); store.save(progress)
                                }, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, if (selected) Berry else Muted.copy(alpha = .25f)),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Berry.copy(alpha = .09f) else GroupedSurface,
                                        contentColor = if (selected) Berry else Ink),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reading-choice:${record.id}:${verdict.name}")
                                        .semantics { this.selected = selected }) {
                                    Text(if (verdict == RecordVerdict.Confirmed) words("已核", "Established") else words("待核", "Unverified"), fontSize = 14.sp)
                                }
                            }
                        }
                        if (record.id in progress.revealed) {
                            HorizontalDivider(color = Muted.copy(alpha = .16f))
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                val choice = progress.choices[record.id]
                                Text(if (choice == null) words("先看依据", "Read the evidence first")
                                    else if (choice == record.verdict) words("与记录相符", "Matches the record")
                                    else words("再对一眼，仍可改选", "Look once more; you can change your choice"),
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Berry,
                                    modifier = Modifier.testTag("reading-feedback:${record.id}"))
                                Text(if (record.verdict == RecordVerdict.Confirmed) words("原文支持：已核", "Evidence: established")
                                    else words("原文支持：待核", "Evidence: unverified"), fontSize = 12.sp, color = Muted)
                                Text(record.reason.say(), fontSize = 14.sp, lineHeight = 23.sp, color = Ink,
                                    modifier = Modifier.testTag("reading-reason:${record.id}"))
                            }
                        }
                        TextButton(onClick = { sourceId = record.id }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("reading-source:${record.id}")) { Text(words("查看原始记录", "Open the original record")) }
                    }
                }
            }
            if (progress.revealed.size < round.records.size) TextButton(onClick = {
                progress = RouteReading.read(round.rules, progress); store.save(progress)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reading-evidence")) {
                Text(words("直接看两张纸签的依据", "Read both explanations now"))
            }
            if (round.rules.complete(progress)) Text(words("两张纸签都对上了。纸面归纸面，没走过的路仍留虚线。", "Both slips match their records. Paper stays paper; unwalked routes stay dotted."),
                fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp, color = Ink,
                modifier = Modifier.testTag("reading-complete").semantics { liveRegion = LiveRegionMode.Polite })
            Button(onClick = {
                val next = rounds[(rounds.indexOf(round) + 1) % rounds.size]
                progress = RouteReading.restore(next.rules, null); store.save(progress); sourceId = null; refreshed = false
                scope.launch { scroll.scrollTo(0) }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reading-next"), shape = RoundedCornerShape(16.dp)) {
                Text(words("再对读一组", "Read another pair"))
            }
            TextButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reading-reset")) {
                Text(words("重摆这组纸签", "Lay out this pair again"))
            }
            Text(words("离线纸签练习；不核验真实路线，不解锁章节，也不改变图谱。", "Offline paper practice. It does not verify a real route, unlock chapters or change the atlas."),
                fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, containerColor = GroupedSurface, shape = RoundedCornerShape(26.dp),
        title = { Text(words("重新摊开这组纸签？", "Lay out these slips again?")) },
        text = { Text(words("本组的选择与展开的依据会收起，原始记录与图谱保持原样。", "Choices and explanations for this pair will be put away. Original records and the atlas stay as they are.")) },
        confirmButton = { TextButton(onClick = {
            progress = RouteReading.restore(round.rules, null); store.save(progress); reset = false; refreshed = false
            scope.launch { scroll.scrollTo(0) }
        }, modifier = Modifier.testTag("reading-reset-confirm")) { Text(words("重新摆好", "Lay them out")) } },
        dismissButton = { TextButton(onClick = { reset = false }, modifier = Modifier.testTag("reading-reset-cancel")) { Text(words("留在这里", "Keep my place")) } })
}

@Composable private fun RoutePageBar(title: String, tag: String, back: () -> Unit) {
    val zh = LocalConfiguration.current.locales[0].language == "zh"
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, modifier = Modifier.testTag(tag)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (zh) "返回" else "Back", tint = Berry) }
        Text(title, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
    }
}

@Composable private fun RouteOriginalPage(record: RouteRecord, language: String, back: () -> Unit) {
    fun words(zh: String, en: String) = if (language == "zh") zh else en
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing).testTag("reading-original")) {
        RoutePageBar(words("原始记录", "Original record"), "reading-original-back", back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(record.term.name.inLanguage(language), fontFamily = FontFamily.Serif, fontSize = 27.sp, lineHeight = 36.sp, color = Ink)
            Text(when {
                record.sourceField == "proposal" -> words("词条演进提案 · 尚未采用", "Term proposal · not adopted")
                record.term.status == "rumor" -> words("作者设定已采用 · 站内路标与路线待核", "Adopted canon · station waymarks and access unverified")
                else -> words("词条正式设定 · 不代表新增路线已核", "Established term · does not verify any new route")
            }, fontSize = 13.sp, lineHeight = 21.sp, color = Berry, modifier = Modifier.testTag("reading-source-status"))
            Text(record.original.inLanguage(language), fontSize = 16.sp, lineHeight = 27.sp, color = Ink, modifier = Modifier.testTag("reading-original-text"))
            Text(words("随包废土词典 · 当前版本原文", "Bundled wasteland lexicon · original text in this version"), fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
        }
    }
}
