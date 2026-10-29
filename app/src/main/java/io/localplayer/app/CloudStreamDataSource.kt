package io.localplayer.app

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.localplayer.core.Cancellation
import io.localplayer.core.Protocol
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val CLOUD_STREAM_SCHEME="localplayer-stream"
internal fun Uri.isPlayerNetworkUri()=scheme=="localplayer"||scheme==CLOUD_STREAM_SCHEME
internal class CloudStreamReadFailure(val status:Int?):IOException(if(status!=null)"云盘播放片段读取失败（$status）"else"云盘播放片段暂不可用")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CloudStreamErrorPolicy:androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info:androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo):Long {
        val status=generateSequence<Throwable>(info.exception){it.cause}.take(12).filterIsInstance<CloudStreamReadFailure>().firstOrNull()?.status
        // A rejected signature requires a fresh complete source, not repeated stale segment requests.
        return if(status==401||status==403)androidx.media3.common.C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }
}

private val playbackHttp by lazy { OkHttpDataSource.Factory(OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).followSslRedirects(false).build()) }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class OpenResources {
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    private val source=java.util.concurrent.atomic.AtomicReference<DataSource?>()
    private val token=java.util.concurrent.atomic.AtomicReference<Cancellation?>()
    fun source(value:DataSource){source.set(value);if(closed.get()){runCatching{source.getAndSet(null)?.close()};throw IOException("播放请求已撤销")}}
    fun token(value:Cancellation){token.set(value);if(closed.get()){token.getAndSet(null)?.close();throw IOException("播放请求已撤销")}}
    fun close(){closed.set(true);runCatching{source.getAndSet(null)?.close()};runCatching{token.getAndSet(null)?.close()}}
}

/** Original byte-range reads and provider HLS are separate paths with separate lifetimes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackNetworkFactory(
    private val http:DataSource.Factory=playbackHttp,
    private val owner:Uri?=null,
    private val resolve:(Uri,Cancellation)->String={uri,cancellation->
        val lease=PlaybackRegistry.get(uri)
        if(lease.source.protocol!=Protocol.ALIYUN)throw IOException("该信源没有平台清晰度")
        val info=AliyunCloud.videoInfo(lease.source,lease.path,cancellation)
        val selection=uri.getQueryParameter("quality").orEmpty()
        if(selection=="auto")lease.adaptive.resolve(info)else info.resolve(selection)
    }
):DataSource.Factory {
    override fun createDataSource():DataSource=object:DataSource {
        private val listeners=mutableListOf<TransferListener>()
        @Volatile private var delegate:DataSource?=null
        private var revocation:java.io.Closeable?=null
        private var opening:OpenResources?=null
        private fun verify(){owner?.takeIf{it.isPlayerNetworkUri()}?.let{PlaybackRegistry.get(it)}}
        private var cancellation:Cancellation?=null
        private var cloud=false
        override fun addTransferListener(listener:TransferListener){listeners+=listener;delegate?.addTransferListener(listener)}
        override fun open(dataSpec:DataSpec):Long {
            check(delegate==null)
            val owned=OpenResources();opening=owned
            verify()
            owner?.takeIf{it.isPlayerNetworkUri()}?.let{uri->revocation=PlaybackRegistry.get(uri).onRevoked{owned.close()}}
            if(dataSpec.uri.scheme=="localplayer"){
                val remote=RemoteDataSource.Factory().createDataSource();delegate=remote;owned.source(remote)
                listeners.forEach(remote::addTransferListener)
                return remote.open(dataSpec)
            }
            cloud=true
            val started=System.nanoTime()
            val probe=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.OPEN,"ALIYUN")
            var failure:IOException?=null
            try{
                val actual=if(dataSpec.uri.scheme==CLOUD_STREAM_SCHEME){
                    val token=Cancellation();cancellation=token;owned.token(token)
                    Uri.parse(resolve(dataSpec.uri,token))
                }else dataSpec.uri
                if(actual.scheme!="https"||actual.host.isNullOrBlank()||!actual.userInfo.isNullOrBlank())throw CloudStreamReadFailure(null)
                val source=http.createDataSource();delegate=source;owned.source(source);listeners.forEach(source::addTransferListener)
                verify();val length=source.open(dataSpec.withUri(actual));verify();return length
            }catch(e:IOException){runCatching{close()};failure=sanitized(e);throw failure}finally{PlaybackIoProbe.end(probe,failure);PlaybackMetrics.opened(System.nanoTime()-started)}
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            val started=System.nanoTime()
            val probe=if(cloud)PlaybackIoProbe.begin(PlaybackIoProbe.Operation.READ)else null
            var failure:IOException?=null
            return try{
                verify()
                val count=delegate?.read(buffer,offset,length)?:throw IOException("播放读取尚未打开")
                verify()
                if(cloud&&count>0)PlaybackMetrics.read(count,System.nanoTime()-started)
                if(probe!=null)PlaybackIoProbe.progress(probe,count)
                count
            }catch(e:IOException){runCatching{close()};failure=if(cloud)sanitized(e)else e;throw failure}
            finally{if(probe!=null)PlaybackIoProbe.end(probe,failure)}
        }
        override fun getUri():Uri?=delegate?.uri
        override fun getResponseHeaders():Map<String,List<String>> =delegate?.responseHeaders.orEmpty()
        override fun close(){revocation?.close();revocation=null;val owned=opening;opening=null;delegate=null;cancellation=null;owned?.close()}
        private fun sanitized(error:IOException):IOException {
            // Never put provider signed URLs, headers or nested DataSpecs into public errors.
            return when(error){
                is CloudStreamReadFailure->error
                is HttpDataSource.InvalidResponseCodeException->CloudStreamReadFailure(error.responseCode)
                else->CloudStreamReadFailure(null)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class CatalogDataSource(private val session:MediaDataSession,private val ref:MediaRef,private val delegate:DataSource):DataSource by delegate {
    override fun open(spec:DataSpec):Long=checked{delegate.open(spec)}
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int=checked{delegate.read(buffer,offset,length)}
    private fun <T> checked(action:()->T):T=try{session.checkLocator(ref);val result=action();session.checkLocator(ref);result}catch(e:Exception){runCatching{delegate.close()};throw e}
}

/** Each MediaSource owns its HLS child requests, including absolute HTTPS segment URLs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackSourcesFactory(private val context:android.content.Context):androidx.media3.exoplayer.source.MediaSource.Factory {
    private var drm:androidx.media3.exoplayer.drm.DrmSessionManagerProvider?=null
    private var policy:androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy=CloudStreamErrorPolicy()
    private fun factory(owner:Uri?,ref:MediaRef?=null):androidx.media3.exoplayer.source.MediaSource.Factory {
        val original=androidx.media3.datasource.DefaultDataSource.Factory(context,PlaybackNetworkFactory(owner=owner))
        val guarded=DataSource.Factory{val source=original.createDataSource();if(ref==null)source else CatalogDataSource(MediaDataMigration.session(context),ref,source)}
        val delegate=androidx.media3.exoplayer.source.DefaultMediaSourceFactory(guarded,PlaybackExtractors(context)).setLoadErrorHandlingPolicy(policy).also{drm?.let(it::setDrmSessionManagerProvider)}
        return SidecarMediaSourceFactory(delegate,guarded,context)
    }
    override fun setDrmSessionManagerProvider(provider:androidx.media3.exoplayer.drm.DrmSessionManagerProvider):androidx.media3.exoplayer.source.MediaSource.Factory {drm=provider;return this}
    override fun setLoadErrorHandlingPolicy(value:androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy):androidx.media3.exoplayer.source.MediaSource.Factory {policy=value;return this}
    override fun getSupportedTypes()=factory(null).supportedTypes
    override fun createMediaSource(item:androidx.media3.common.MediaItem):androidx.media3.exoplayer.source.MediaSource {
        val ref=item.mediaMetadata.extras?.getString("media-ref")?.let(MediaRef::parse)
        ref?.let{MediaDataMigration.session(context).checkLocator(it)}
        return factory(item.localConfiguration?.uri,ref).createMediaSource(item)
    }
}
