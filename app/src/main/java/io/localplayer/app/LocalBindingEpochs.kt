package io.localplayer.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Revoked tree grants never become valid again for an older playback request. */
internal object LocalBindingEpochs {
    private val lock=Any()
    private fun key(uri:Uri):String = MediaDataMigration.digest(uri.authority.orEmpty()+"/"+runCatching {
        if(DocumentsContract.isTreeUri(uri))DocumentsContract.getTreeDocumentId(uri) else uri.toString()
    }.getOrDefault(uri.toString()))
    fun current(context:Context,uri:Uri):Long=synchronized(lock){context.getSharedPreferences("local-binding-epochs",0).getLong(key(uri),0)}
    fun accepts(context:Context,ref:MediaRef):Boolean=ref.localUri?.let { current(context,Uri.parse(it))==(ref.bindingEpoch?:0L) }?:true
    fun revoke(context:Context,tree:Uri){
        synchronized(lock){val p=context.getSharedPreferences("local-binding-epochs",0);val k=key(tree);check(p.edit().putLong(k,p.getLong(k,0)+1).commit())}
        PlaybackRegistry.invalidateLocal()
    }
}
