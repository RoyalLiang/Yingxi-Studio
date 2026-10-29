package io.localplayer.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap

/** Only these non-secret domains participate in the data transaction. Other keys stay in place. */
internal object MigratingPreferences {
    val addedScopes=setOf("library-appearance","cloud-playback")
    val scopes=setOf("station-inventory-v1","playback-settings-v1","media-enrichment-v1","source-organization-v1","source-presentation-v1","station-reading-v1","station-practice-v1","station-tuning-v1","station-experience-v1")+addedScopes
    private val bridges=ConcurrentHashMap<String,Bridge>()
    fun included(scope:String,key:String)=scope in scopes&&when(scope){
        "station-experience-v1"->key=="gear-sound"||key=="diary-line"||key=="diary-at"||key.startsWith("diary-next:")
        "library-appearance"->key=="mode"
        "cloud-playback"->key in setOf("workers","segment-cache","next-episode")
        else->true
    }
    fun open(context:Context,name:String):SharedPreferences {
        val original=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        return if(name in scopes)bridges.computeIfAbsent(context.getDatabasePath(MEDIA_DATABASE).path+":"+name){Bridge(context.applicationContext,name,original)}else original
    }
    internal fun notifyRestored(context:Context,scope:String,keys:Set<String>){bridges[context.getDatabasePath(MEDIA_DATABASE).path+":"+scope]?.notify(keys)}
    fun encode(value:Any):String {
        val type=when(value){is String->"string";is Boolean->"boolean";is Int->"int";is Long->"long";is Float->"float";is Set<*>->"set";else->error("Unsupported preference type")}
        return JSONObject().put("type",type).put("value",if(value is Set<*>)JSONArray(value.filterIsInstance<String>().sorted())else value).toString()
    }
    fun decode(raw:String):Any {val o=JSONObject(raw);return when(o.getString("type")){
        "string"->o.getString("value");"boolean"->o.getBoolean("value");"int"->o.getInt("value");"long"->o.getLong("value");"float"->o.getDouble("value").toFloat();"set"->o.getJSONArray("value").let{a->(0 until a.length()).map{a.getString(it)}.toSet()};else->error("Unsupported preference type")
    }}
    private class Bridge(context:Context,private val scope:String,private val original:SharedPreferences):SharedPreferences {
        private val session=MediaDataMigration.session(context)
        private val listeners=CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
        fun notify(keys:Set<String>){
            val action={keys.forEach{key->listeners.forEach{it.onSharedPreferenceChanged(this,key)}}}
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action()else android.os.Handler(android.os.Looper.getMainLooper()).post{action()}
        }
        override fun getAll():MutableMap<String,*> = session.preferenceValues(scope,original).toMutableMap()
        override fun contains(key:String?)=all.containsKey(key)
        override fun getString(key:String?,defValue:String?):String?=all[key]?.let{it as String}?:defValue
        override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>?=(all[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()?:defValues?.toMutableSet()
        override fun getInt(key:String?,defValue:Int)=(all[key] as? Int)?:defValue
        override fun getLong(key:String?,defValue:Long)=(all[key] as? Long)?:defValue
        override fun getFloat(key:String?,defValue:Float)=(all[key] as? Float)?:defValue
        override fun getBoolean(key:String?,defValue:Boolean)=(all[key] as? Boolean)?:defValue
        override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?){listener?.let{listeners.add(it)}}
        override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?){listeners.remove(listener)}
        override fun edit():SharedPreferences.Editor=object:SharedPreferences.Editor {
            private val changes=linkedMapOf<String,Any?>();private var clear=false
            private fun put(key:String?,value:Any?):SharedPreferences.Editor {requireNotNull(key);changes[key]=value;return this}
            override fun putString(key:String?,value:String?)=put(key,value)
            override fun putStringSet(key:String?,values:MutableSet<String>?)=put(key,values?.toSet())
            override fun putInt(key:String?,value:Int)=put(key,value)
            override fun putLong(key:String?,value:Long)=put(key,value)
            override fun putFloat(key:String?,value:Float)=put(key,value)
            override fun putBoolean(key:String?,value:Boolean)=put(key,value)
            override fun remove(key:String?)=put(key,null)
            override fun clear():SharedPreferences.Editor {clear=true;return this}
            override fun commit():Boolean {
                val keys=if(clear)all.keys.filterNotNull().toSet()+changes.keys else changes.keys
                session.writePreferences(scope,original,changes,clear)
                this@Bridge.notify(keys)
                return true
            }
            override fun apply(){commit()}
        }
    }
}
