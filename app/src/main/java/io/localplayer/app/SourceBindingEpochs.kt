package io.localplayer.app

import android.content.Context
import io.localplayer.core.Source

/** Authority comes only from SourceStore configuration writes, never from old media observations. */
internal object SourceBindingEpochs {
    private val lock=Any()
    private val observers=java.util.concurrent.CopyOnWriteArraySet<(String)->Unit>()
    fun observe(change:(String)->Unit):()->Unit {observers.add(change);return{observers.remove(change)}}
    private fun revoked(id:String){PlaybackRegistry.invalidateSource(id);observers.forEach{runCatching{it(id)}}}
    private fun prefs(context:Context)=context.getSharedPreferences("source-binding-epochs-v1",Context.MODE_PRIVATE)
    private fun identity(source:Source)="v2:"+MediaDataMigration.digest(mediaLocation(source)+"\u0000"+io.localplayer.core.Paths.clean(source.initialPath))
    fun current(context:Context,source:Source):Long=synchronized(lock){
        val p=prefs(context);val digest=p.getString("digest:${source.id}",null)
        if(digest==null){check(p.edit().putString("digest:${source.id}",identity(source)).putLong("epoch:${source.id}",0).commit());0L}
        else {check(digest==identity(source)&&!p.getBoolean("deleted:${source.id}",false)){"信源变更尚未完成，请重新保存配置"};p.getLong("epoch:${source.id}",0)}
    }
    fun record(context:Context,source:Source,previous:Source?){
        val changed=synchronized(lock){
            val p=prefs(context);val key=source.id;val stored=p.getString("digest:$key",null)?:previous?.let(::identity)
            val changed=(stored!=null&&stored!=identity(source))||p.getBoolean("deleted:$key",false)
            val digest=identity(source);val epoch=p.getLong("epoch:$key",0)+(if(changed)1 else 0)
            check(p.edit().putString("digest:$key",digest).putLong("epoch:$key",epoch).putBoolean("deleted:$key",false).commit())
            changed
        }
        if(changed)revoked(source.id)
    }
    fun deleted(context:Context,id:String){
        synchronized(lock){val p=prefs(context);check(p.edit().putLong("epoch:$id",p.getLong("epoch:$id",0)+1).putBoolean("deleted:$id",true).commit())}
        revoked(id)
    }
    fun verifyLease(context:Context,source:Source,epoch:Long)=synchronized(lock){
        val p=prefs(context)
        if(p.getBoolean("deleted:${source.id}",false)||p.getString("digest:${source.id}",null)!=identity(source)||p.getLong("epoch:${source.id}",0)!=epoch)throw java.io.IOException("信源身份或根目录已变化，请重新选择影片")
    }
    fun acceptsWrite(context:Context,ref:MediaRef,allowUnregistered:Boolean):Boolean {
        if(!LocalBindingEpochs.accepts(context,ref))return false
        val sourceId=ref.sourceId?:return true
        val source=SourceStore(context).configured().firstOrNull{it.id==sourceId}
        if(source==null)return synchronized(lock){allowUnregistered&&!prefs(context).contains("epoch:$sourceId")}
        return runCatching{matches(context,source,ref)&&ref.path?.let{io.localplayer.core.SourceRoot(source.initialPath).contains(it)}==true}.getOrDefault(false)
    }
    fun matches(context:Context,source:Source,ref:MediaRef)=ref.location?.let{it==mediaLocation(source)}!=false&&(ref.bindingEpoch?:0)==current(context,source)
}
