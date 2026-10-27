package io.localplayer.app

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.animation.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.localplayer.core.ArchiveIdentity
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal class StationPreferences(context:Context,name:String="station-experience-v1") {
    private val p=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    init { if(!p.contains("first-day"))p.edit().putString("first-day",runCatching{java.time.Instant.ofEpochMilli(context.packageManager.getPackageInfo(context.packageName,0).firstInstallTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()}.getOrDefault(LocalDate.now().toString())).apply() }
    val day:Long get()=runCatching{ChronoUnit.DAYS.between(LocalDate.parse(p.getString("first-day",null)),LocalDate.now()).coerceAtLeast(0)+1}.getOrDefault(1)
    var greetings:Boolean get()=p.getBoolean("greetings",true);set(v){p.edit().putBoolean("greetings",v).apply()}
    var cabinetDiscoverySeen:Boolean get()=p.getBoolean("cabinet-first-light-seen",false);set(v){p.edit().putBoolean("cabinet-first-light-seen",v).apply()}
    var gearSound:Boolean get()=p.getBoolean("gear-sound",false);set(v){p.edit().putBoolean("gear-sound",v).apply()}
    fun claimGreeting():Boolean {
        val today=LocalDate.now().toString()
        if(!greetings||p.getString("greeted",null)==today)return false
        p.edit().putString("greeted",today).apply();return true
    }
    private val appContext=context.applicationContext
    private val world by lazy { StationWorld.load(appContext) }
    val dailyLines:List<WorldDiaryLine> by lazy {
        (world.dailyLines+world.people.flatMap { person->person.quotes.mapIndexed { index,text->
            WorldDiaryLine("quote:${person.id}:$index",person.id,text)
        }}).distinctBy{it.author to it.text}
    }
    // Refresh at home entry/resume, never on a timer while somebody is reading.
    fun diary(now:Long=System.currentTimeMillis(),force:Boolean=false):WorldDiaryLine {
        val current=dailyLines.firstOrNull{it.id==p.getString("diary-line",null)}
        val last=p.getLong("diary-at",0)
        if(!force&&current!=null&&now>=last&&now-last<30*60_000L)return current
        val authors=dailyLines.map{it.author}.distinct()
        val author=authors[(authors.indexOf(current?.author)+1).mod(authors.size)]
        val lines=dailyLines.filter{it.author==author}
        val index=p.getInt("diary-next:$author",0).mod(lines.size)
        val next=lines[index]
        p.edit().putString("diary-line",next.id).putLong("diary-at",now)
            .putInt("diary-next:$author",(index+1)%lines.size).apply()
        return next
    }
    fun diaryAuthor(line:WorldDiaryLine)=world.people.first{it.id==line.author}
    fun dailyLine(language:String):String=diary().text.inLanguage(language)
    val dailyLine:String get()=dailyLine(appContext.resources.configuration.locales[0].language)

    fun seekClick(context:Context){if(gearSound)context.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEY_CLICK,.18f)}
}

internal data class StationFinding(val media:MediaRef,val firstSeen:Long)
internal fun stationPath(media:MediaRef):String = media.path ?: media.localUri?.let { value->
    runCatching { DocumentsContract.getDocumentId(Uri.parse(value)) }.getOrNull()
} ?: media.title
internal fun archiveIdentity(media:MediaRef)=ArchiveIdentity.parse(media.title,stationPath(media))

/** A bounded local catalog of files actually observed through user-granted sources. No credentials. */
internal class StationInventory(context:Context,name:String="station-inventory-v1") {
    private val p=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    companion object { val revision=mutableIntStateOf(0);private val lock=Any() }
    fun entries():List<StationFinding> = synchronized(lock) {
        runCatching { val raw=p.getString("items","[]") ?: "[]";if(raw.length>2_097_152)return@runCatching emptyList()
            val a=JSONArray(raw);(0 until minOf(a.length(),512)).map { i->a.getJSONObject(i).let{StationFinding(MediaRef.parse(it.getString("media")),it.getLong("seen"))} }
        }.getOrDefault(emptyList())
    }
    fun observe(media:List<MediaRef>,seen:Long=System.currentTimeMillis()):Int = observeFindings(media.take(512).map{StationFinding(it,seen)})
    fun restore(history:List<HistoryEntry>):Int = observeFindings(history.asReversed().take(512).map{StationFinding(it.media,it.updatedAt)})
    private fun observeFindings(findings:List<StationFinding>):Int = synchronized(lock) {
        val old=entries();val byKey=old.associateBy{it.media.key}.toMutableMap();var added=0
        findings.forEach { finding->val ref=finding.media;val seen=finding.firstSeen;val previous=byKey[ref.key]
            if(previous==null||(previous.media.location!=null&&previous.media.location!=ref.location)||(previous.media.sizeBytes!=null&&ref.sizeBytes!=null&&previous.media.sizeBytes!=ref.sizeBytes)){byKey[ref.key]=StationFinding(ref,seen);added++}
            else if(previous.media!=ref)byKey[ref.key]=previous.copy(media=ref)
        }
        val next=byKey.values.sortedByDescending{it.firstSeen}.take(512)
        if(next!=old)save(next)
        added
    }
    fun reconcileSource(source:io.localplayer.core.Source)=synchronized(lock){val old=entries();val next=old.filter{it.media.sourceId!=source.id||it.media.location==null||it.media.location==mediaLocation(source)};if(next!=old)save(next)}
    fun removeSource(id:String)=synchronized(lock){save(entries().filterNot{it.media.sourceId==id})}
    fun removeTree(tree:Uri)=synchronized(lock){save(entries().filterNot { f->f.media.localUri?.let{u->runCatching{
        val uri=Uri.parse(u);uri.authority==tree.authority&&DocumentsContract.getTreeDocumentId(uri)==DocumentsContract.getTreeDocumentId(tree)
    }.getOrDefault(false)}==true })}
    private fun save(items:List<StationFinding>){val a=JSONArray();items.forEach{a.put(JSONObject().put("media",it.media.json()).put("seen",it.firstSeen))};p.edit().putString("items",a.toString()).apply();revision.intValue++}
    fun today():List<StationFinding> { val start=LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();return entries().filter{it.firstSeen>=start} }
}

@Composable internal fun StationCharacterRow(kind:String,name:String,line:String,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GroupedSurface).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        StationPortrait(kind,name,Modifier.size(54.dp),false)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(name,fontSize=13.sp,color=Muted)
            Text(line,fontSize=15.sp,lineHeight=22.sp,color=Ink)
        }
    }
}

/** Only the real buffering state controls this overlay; a character hint is optional and time bounded. */
@Composable internal fun StationBuffering(show:Boolean,allowHint:Boolean,details:@Composable ()->Unit={}) {
    val context=LocalContext.current
    var hint by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(playbackWaitTitle()) }
    LaunchedEffect(show) {
        while(show){status=playbackWaitTitle();delay(1000)}
    }
    var lastHint by remember { mutableLongStateOf(0L) }
    LaunchedEffect(show,allowHint) {
        hint=false
        if(show&&allowHint&&StationPreferences(context).greetings) {
            delay(2500)
            val now=android.os.SystemClock.elapsedRealtime()
            if(lastHint==0L||now-lastHint>=60_000){lastHint=now;hint=true;delay(4500);hint=false}
        }
    }
    if(show) BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).heightIn(max=maxHeight).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.testTag("station-decoding").clearAndSetSemantics {
                contentDescription="$status，正在缓冲";progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
            },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                StationDecodingMark()
                Text(status,color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Medium)
            }
            details()
        }
        // Reserve the center status region; adapt bubble width before hiding it on compact screens.
        val fontScale=androidx.compose.ui.platform.LocalDensity.current.fontScale
        val bubbleWidth=(maxWidth/2-140.dp*fontScale).coerceIn(150.dp,220.dp)
        androidx.compose.animation.AnimatedVisibility(hint&&maxWidth>=600.dp&&fontScale<=1.3f,modifier=Modifier.align(Alignment.CenterEnd).padding(end=24.dp).offset(y=16.dp),
            enter=slideInHorizontally{it}+fadeIn(),exit=fadeOut()+slideOutHorizontally{it}) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp),modifier=Modifier.testTag("station-buffering-bubble")) {
                Surface(color=Color(0xC9171C20),shape=RoundedCornerShape(15.dp)) {
                    Column(Modifier.widthIn(max=bubbleWidth).padding(horizontal=12.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text("迟灯",color=Color(0xFFC4C5CC),fontSize=11.sp)
                        Text("别急，先听它转完这一圈。",color=Color.White,fontSize=13.sp,lineHeight=18.sp)
                    }
                }
                androidx.compose.foundation.Canvas(Modifier.size(6.dp,10.dp)) {
                    val tail=androidx.compose.ui.graphics.Path().apply { moveTo(0f,0f);lineTo(size.width,size.height/2);lineTo(0f,size.height);close() }
                    drawPath(tail,Color(0xC9171C20))
                }
                Spacer(Modifier.width(8.dp))
                StationPortrait("keeper","迟灯",Modifier.size(36.dp),false)
            }
        }
    }
}

@Composable internal fun StationAtmosphereSettings() {
    val context=LocalContext.current
    val prefs=remember{StationPreferences(context)}
    var greetings by remember{mutableStateOf(prefs.greetings)}
    var sound by remember{mutableStateOf(prefs.gearSound)}
    Surface(modifier=Modifier.fillMaxWidth(),color=GroupedSurface,shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { Text("角色问候",Modifier.weight(1f));SettingsSwitch(greetings,{greetings=it;prefs.greetings=it},modifier=Modifier.testTag("station-greetings")) }
        Row(verticalAlignment=Alignment.CenterVertically) { Text("齿轮音效",Modifier.weight(1f));SettingsSwitch(sound,{sound=it;prefs.gearSound=it},modifier=Modifier.testTag("station-gear-sound")) }
    } }
    Surface(modifier=Modifier.fillMaxWidth(),color=GroupedSurface,shape=RoundedCornerShape(22.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("播放手势",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
        Text("头灯亮度（屏幕亮度）",fontSize=15.sp);Text("左侧上下滑动",fontSize=12.sp,color=Muted)
        Text("通讯器音量（媒体音量）",fontSize=15.sp);Text("右侧上下滑动",fontSize=12.sp,color=Muted)
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun StationAudioSettings() {
    val context=LocalContext.current
    val prefs=remember(context){PlaybackPreferences(context)}
    var language by remember{mutableStateOf(prefs.audioLanguage)}
    var rememberManual by remember{mutableStateOf(prefs.rememberManual)}
    Surface(modifier=Modifier.fillMaxWidth(),color=GroupedSurface,shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp)) {
            StationChoiceSetting("听音频段",listOf("follow" to "跟随","en" to "英文","zh" to "中文"),language?:"follow","audio-language") {
                language=it.takeUnless{value->value=="follow"};prefs.audioLanguage=language
            }
            Row(verticalAlignment=Alignment.CenterVertically){Text("记住手动选择",Modifier.weight(1f));SettingsSwitch(rememberManual,{rememberManual=it;prefs.rememberManual=it})}
        }
    }
}
