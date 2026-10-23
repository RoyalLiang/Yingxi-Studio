package io.localplayer.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class LibraryAppearance(val label:String) { SYSTEM("跟随系统"),DAY("日间"),NIGHT("守夜") }
internal class AppearancePreferences(context:Context) {
    internal val store=context.applicationContext.getSharedPreferences("library-appearance",Context.MODE_PRIVATE)
    var mode:LibraryAppearance
        get()=LibraryAppearance.entries.firstOrNull{it.name==store.getString("mode",null)}?:LibraryAppearance.SYSTEM
        set(value){store.edit().putString("mode",value.name).apply()}
}
@Composable internal fun libraryDarkMode():Boolean {
    val context=LocalContext.current
    val prefs=remember(context){AppearancePreferences(context)}
    var mode by remember(prefs){mutableStateOf(prefs.mode)}
    DisposableEffect(prefs){
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="mode")mode=prefs.mode}
        prefs.store.registerOnSharedPreferenceChangeListener(listener)
        onDispose{prefs.store.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return when(mode){LibraryAppearance.SYSTEM->isSystemInDarkTheme();LibraryAppearance.DAY->false;LibraryAppearance.NIGHT->true}
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NightWatchSettings() {
    val context=LocalContext.current
    val prefs=remember(context){AppearancePreferences(context)}
    var mode by remember{mutableStateOf(prefs.mode)}
    Surface(color=GroupedSurface,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().testTag("night-watch-settings")){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("守夜人模式",fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                LibraryAppearance.entries.forEach{value->
                    FilterChip(selected=mode==value,onClick={mode=value;prefs.mode=value},label={Text(value.label)},modifier=Modifier.testTag("appearance-${value.name.lowercase()}"))
                }
            }
        }
    }
}
