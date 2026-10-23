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

/** Original byte-range reads and provider HLS are separate paths with separate lifetimes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackNetworkFactory(
    private val http:DataSource.Factory=OkHttpDataSource.Factory(OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).followSslRedirects(false).build()),
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
        private var delegate:DataSource?=null
        private var cancellation:Cancellation?=null
        private var cloud=false
        override fun addTransferListener(listener:TransferListener){listeners+=listener;delegate?.addTransferListener(listener)}
        override fun open(dataSpec:DataSpec):Long {
            check(delegate==null)
            if(dataSpec.uri.scheme=="localplayer"){
                val remote=RemoteDataSource.Factory().createDataSource();delegate=remote
                listeners.forEach(remote::addTransferListener)
                return remote.open(dataSpec)
            }
            cloud=true
            val started=System.nanoTime()
            val probe=PlaybackIoProbe.begin(PlaybackIoProbe.Operation.OPEN,"ALIYUN")
            var failure:IOException?=null
            try{
                val actual=if(dataSpec.uri.scheme==CLOUD_STREAM_SCHEME){
                    val token=Cancellation();cancellation=token
                    Uri.parse(resolve(dataSpec.uri,token))
                }else dataSpec.uri
                if(actual.scheme!="https"||actual.host.isNullOrBlank()||!actual.userInfo.isNullOrBlank())throw CloudStreamReadFailure(null)
                val source=http.createDataSource();delegate=source;listeners.forEach(source::addTransferListener)
                return source.open(dataSpec.withUri(actual))
            }catch(e:IOException){failure=sanitized(e);throw failure}finally{PlaybackIoProbe.end(probe,failure);PlaybackMetrics.opened(System.nanoTime()-started)}
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            val started=System.nanoTime()
            val probe=if(cloud)PlaybackIoProbe.begin(PlaybackIoProbe.Operation.READ)else null
            var failure:IOException?=null
            return try{
                val count=delegate?.read(buffer,offset,length)?:throw IOException("播放读取尚未打开")
                if(cloud&&count>0)PlaybackMetrics.read(count,System.nanoTime()-started)
                if(probe!=null)PlaybackIoProbe.progress(probe,count)
                count
            }catch(e:IOException){failure=if(cloud)sanitized(e)else e;throw failure}
            finally{if(probe!=null)PlaybackIoProbe.end(probe,failure)}
        }
        override fun getUri():Uri?=delegate?.uri
        override fun getResponseHeaders():Map<String,List<String>> =delegate?.responseHeaders.orEmpty()
        override fun close(){cancellation?.close();cancellation=null;val old=delegate;delegate=null;try{old?.close()}catch(e:IOException){if(cloud)throw sanitized(e)else throw e}}
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
