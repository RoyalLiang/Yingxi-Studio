package io.localplayer.app

import android.content.Context
import android.net.Uri
import io.localplayer.core.*
import kotlinx.coroutines.ensureActive

internal enum class FilmAccess { READY, UNBOUND, SOURCE_REMOVED, AUTHORITY_CHANGED, PERMISSION, MISSING, VERSION_CHANGED, TRUST_REQUIRED, UNKNOWN }

/** Checks the exact file without treating discovery, a matching title or a timeout as proof. */
internal object LibraryRepair {
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun inspect(context:Context,ref:MediaRef,credentials:Credentials?,connector:Connector=AppConnectors()):FilmAccess {
        if(ref.localUri==null&&ref.sourceId==null)return FilmAccess.UNBOUND
        if(ref.sourceId!=null&&SourceStore(context).load().none{it.id==ref.sourceId})return FilmAccess.SOURCE_REMOVED
        if(!SourceBindingEpochs.acceptsWrite(context,ref,false))return if(ref.localUri!=null)FilmAccess.PERMISSION else FilmAccess.AUTHORITY_CHANGED
        val coroutine=kotlinx.coroutines.currentCoroutineContext()
        val cancellation=Cancellation();val signal=android.os.CancellationSignal()
        val watcher=coroutine[kotlinx.coroutines.Job]!!.invokeOnCompletion(onCancelling=true,invokeImmediately=true){cancellation.close();signal.cancel()}
        return try {
            MediaDataMigration.session(context).checkLocator(ref)
            val size=if(ref.localUri!=null){
                val descriptor=context.contentResolver.openAssetFileDescriptor(Uri.parse(ref.localUri),"r",signal)?:return FilmAccess.MISSING
                descriptor.use{it.length.takeIf{n->n>=0}}
            }else{
                val source=SourceStore(context).load().firstOrNull{it.id==ref.sourceId}?:return FilmAccess.SOURCE_REMOVED
                if(credentials==null&&source.username.isNotEmpty())return FilmAccess.PERMISSION
                connector.connect(source,credentials?:Credentials(""),cancellation).use{session->session.open(requireNotNull(ref.path)).use{file->
                    val one=ByteArray(1);if(file.size>0&&file.readAt(0,one,0,1)!=1)throw java.io.IOException("Incomplete read")
                    file.size
                }}
            }
            coroutine.ensureActive()
            if(!SourceBindingEpochs.acceptsWrite(context,ref,false))return FilmAccess.AUTHORITY_CHANGED
            if(ref.sizeBytes!=null&&size!=null&&ref.sizeBytes!=size)FilmAccess.VERSION_CHANGED else FilmAccess.READY
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:SecurityException){FilmAccess.PERMISSION}
        catch(_:java.io.FileNotFoundException){FilmAccess.MISSING}
        catch(_:HostKeyRequired){FilmAccess.TRUST_REQUIRED}
        catch(_:Exception){FilmAccess.UNKNOWN}
        finally{watcher.dispose();cancellation.close()}
    }
}
