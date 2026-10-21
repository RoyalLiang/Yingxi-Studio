package io.localplayer.app
import android.content.Context

class PlaybackPreferences(context:Context){
    private val prefs=context.getSharedPreferences("playback-settings-v1",Context.MODE_PRIVATE)
    var rememberManual:Boolean
        get()=prefs.getBoolean("remember-manual",true)
        set(value){prefs.edit().putBoolean("remember-manual",value).apply()}
    var speed:Float
        get()=prefs.getFloat("speed",1f).takeIf{it.isFinite()&&it in .5f..2f} ?: 1f
        set(value){prefs.edit().putFloat("speed",value.coerceIn(.5f,2f)).apply()}
    var audioLanguage:String?
        get()=prefs.getString("audio-language",null)
        set(value){prefs.edit().putString("audio-language",value).apply()}
    var textLanguage:String?
        get()=if(rememberManual)prefs.getString("text-language",null)else null
        set(value){prefs.edit().putString("text-language",value).apply()}
    var subtitlesDisabled:Boolean
        get()=rememberManual&&prefs.getBoolean("subtitles-disabled",false)
        set(value){prefs.edit().putBoolean("subtitles-disabled",value).apply()}
}
