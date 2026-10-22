package io.localplayer.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.localplayer.core.AliyunReadOptions
import io.localplayer.core.SegmentCache
import java.io.File

/** Video bytes stay in Android's private, automatically evictable cache directory. */
internal class CloudReadPreferences(context: Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("cloud-playback",Context.MODE_PRIVATE)
    var workers:Int
        get()=prefs.getInt("workers",0).coerceIn(0,3)
        set(value){require(value in 0..3);prefs.edit().putInt("workers",value).apply()}
    var cacheEnabled:Boolean
        get()=prefs.getBoolean("segment-cache",true)
        set(value){prefs.edit().putBoolean("segment-cache",value).apply()}
    var nextEpisode:Boolean
        get()=prefs.getBoolean("next-episode",true)
        set(value){prefs.edit().putBoolean("next-episode",value).apply()}
    fun unmeteredWifi():Boolean = runCatching {
        val manager=app.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true&&!manager.isActiveNetworkMetered
    }.getOrDefault(false)
    fun options():AliyunReadOptions {
        val automatic=if(unmeteredWifi())3 else 2
        return AliyunReadOptions(workers.takeIf{it!=0}?:automatic,if(cacheEnabled)cache(app)else null)
    }
    companion object {
        private var store:SegmentCache?=null
        @Synchronized fun cache(context:Context):SegmentCache = store?:SegmentCache(File(context.applicationContext.cacheDir,"cloud-video-segments")).also{store=it}
    }
}
