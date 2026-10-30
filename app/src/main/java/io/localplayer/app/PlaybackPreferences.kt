package io.localplayer.app
import android.content.Context

class PlaybackPreferences(context:Context,name:String="playback-settings-v1"){
    private val prefs=MigratingPreferences.open(context,name)
    var rememberManual:Boolean
        get()=prefs.getBoolean("remember-manual",true)
        set(value){prefs.edit().putBoolean("remember-manual",value).apply()}
    var speed:Float
        get()=prefs.getFloat("speed",1f).takeIf{it.isFinite()&&it in .25f..4f} ?: 1f
        set(value){prefs.edit().putFloat("speed",value.coerceIn(.25f,4f)).apply()}
    var audioLanguage:String?
        get()=prefs.getString("preferred-audio-language",null)
        set(value){prefs.edit().putString("preferred-audio-language",value).remove("audio-language").apply()}
    var rememberedAudioLanguage:String?
        get()=if(rememberManual)prefs.getString("audio-language",null)else null
        set(value){prefs.edit().putString("audio-language",value).apply()}
    val effectiveAudioLanguage:String? get()=rememberedAudioLanguage?:audioLanguage
    var textLanguage:String?
        get()=if(rememberManual)prefs.getString("text-language",null)else null
        set(value){prefs.edit().putString("text-language",value).apply()}
    var subtitlesDisabled:Boolean
        get()=rememberManual&&prefs.getBoolean("subtitles-disabled",false)
        set(value){prefs.edit().putBoolean("subtitles-disabled",value).apply()}
}
