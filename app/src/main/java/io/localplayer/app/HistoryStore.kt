package io.localplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import io.localplayer.core.Source

fun mediaLocation(source:Source):String {
    val identity=(listOf(source.protocol.name,source.authority,source.root,source.username,source.domain,source.trustedKey ?: "")+
        if(source.protocol==io.localplayer.core.Protocol.WEBDAV)listOf(source.secure.toString()) else emptyList()).joinToString("\u0000")
    return "v1:"+MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}
}

/** Stable media identity only; no credentials or temporary playback tokens. */
data class MediaRef(val key:String,val title:String,val sourceLabel:String,val sourceId:String?=null,
    val path:String?=null,val localUri:String?=null,val sizeBytes:Long?=null,val location:String?=null,val localParentUri:String?=null) {
    fun json()=JSONObject().put("key",key).put("title",title).put("sourceLabel",sourceLabel)
        .put("sourceId",sourceId).put("path",path).put("localUri",localUri).put("sizeBytes",sizeBytes).put("location",location).put("localParentUri",localParentUri).toString()
    companion object {
        fun parse(text:String):MediaRef { val o=JSONObject(text)
            fun optional(name:String)=o.optString(name).takeIf { it.isNotBlank()&&it!="null" }
            return MediaRef(o.getString("key"),o.getString("title"),o.getString("sourceLabel"),
                optional("sourceId"),optional("path"),optional("localUri"),
                if(o.has("sizeBytes")&&!o.isNull("sizeBytes"))o.getLong("sizeBytes")else null,optional("location"),optional("localParentUri"))
        }
    }
}
data class HistoryEntry(val media:MediaRef,val positionMs:Long,val durationMs:Long,val updatedAt:Long,val completed:Boolean) {
    val resumeMs get()=if(completed)0L else positionMs.coerceAtLeast(0)
    val progress get()=if(durationMs>0)(positionMs.toFloat()/durationMs).coerceIn(0f,1f)else 0f
}
data class HistoryToken(val clearRevision:Long,val itemRevision:Long)

class HistoryStore(context:Context,name:String="history-v1") {
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    companion object { private val lock=Any() }
    fun entries():List<HistoryEntry> { synchronized(lock) {
        val text=prefs.getString("entries","[]") ?: "[]"
        try {
            if(text.length>1_048_576)throw IOException("播放记录过大")
            val a=JSONArray(text)
            return (0 until a.length()).map { i -> val o=a.getJSONObject(i)
                HistoryEntry(MediaRef.parse(o.getString("media")),o.getLong("position"),o.getLong("duration"),o.getLong("updated"),o.optBoolean("completed"))
            }.sortedByDescending { it.updatedAt }.take(100)
        }catch(e:Exception){throw IOException("播放记录无法读取，原数据已保留；可清空记录后重试",e)}
    } }
    fun token(key:String)=synchronized(lock){HistoryToken(prefs.getLong("clear-revision",0),prefs.getLong("revision-"+hash(key),0))}
    fun update(media:MediaRef,token:HistoryToken,position:Long,duration:Long,completed:Boolean):Boolean { synchronized(lock) {
        if(token!=this.token(media.key))return false
        val all=entries();val previous=all.firstOrNull { it.media.key==media.key }
        val knownDuration=duration.takeIf { it>0 } ?: previous?.durationMs ?: 0
        val pos=position.coerceAtLeast(0).let { if(knownDuration>0)it.coerceAtMost(knownDuration)else it }
        val entry=HistoryEntry(media,pos,knownDuration,System.currentTimeMillis(),completed)
        prefs.edit().putString("entries",encode((listOf(entry)+all.filterNot { it.media.key==media.key }).take(100))).apply()
        return true
    } }
    fun resumePosition(media:MediaRef,legacy:Long=0):Long { synchronized(lock) {
        val entry=entries().firstOrNull { it.media.key==media.key }
        if(entry!=null){
            if(media.location!=null&&entry.media.location!=null&&media.location!=entry.media.location)return 0
            if(media.sizeBytes!=null&&entry.media.sizeBytes!=null&&media.sizeBytes!=entry.media.sizeBytes)return 0
            return entry.resumeMs
        }
        val t=token(media.key)
        return if(t.clearRevision>0||t.itemRevision>0)0 else legacy.coerceAtLeast(0)
    } }
    fun remove(key:String){ synchronized(lock) {
        val next=entries().filterNot { it.media.key==key }
        prefs.edit().putString("entries",encode(next)).putLong("revision-"+hash(key),token(key).itemRevision+1).apply()
    } }
    fun clear(){ synchronized(lock) {
        val next=prefs.getLong("clear-revision",0)+1
        prefs.edit().clear().putLong("clear-revision",next).putString("entries","[]").apply()
    } }
    private fun encode(entries:List<HistoryEntry>)=JSONArray().apply{entries.forEach{e ->
        put(JSONObject().put("media",e.media.json()).put("position",e.positionMs).put("duration",e.durationMs).put("updated",e.updatedAt).put("completed",e.completed))
    }}.toString()
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
}
