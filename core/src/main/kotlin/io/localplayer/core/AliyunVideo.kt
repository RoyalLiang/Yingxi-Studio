package io.localplayer.core

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Full provider playback URLs are short-lived secrets, never persisted or printed. */
class AliyunVideoVariant(val template:String,val url:String) {
    override fun toString()="AliyunVideoVariant($template,[redacted])"
}
class AliyunVideoInfo(val variants:List<AliyunVideoVariant>,val masterUrl:String?) {
    override fun toString()="AliyunVideoInfo(${variants.map{it.template}},master=${masterUrl!=null})"
    fun resolve(selection:String):String = if(selection=="auto")masterUrl?:throw IOException("平台暂未提供自适应播放列表")
        else variants.firstOrNull{it.template==selection}?.url?:throw IOException("该清晰度暂不可用，请选择原画或其他档位")
}
internal fun parseAliyunVideoInfo(body:JsonObject):AliyunVideoInfo {
    val info=body.get("video_preview_play_info")?.takeIf{it.isJsonObject}?.asJsonObject
        ?:return AliyunVideoInfo(emptyList(),null)
    fun https(value:String?):String?=value?.takeIf{it.length<=16384}?.toHttpUrlOrNull()?.takeIf{
        it.scheme=="https"&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null
    }?.toString()
    fun text(o:JsonObject,key:String)=o.get(key)?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
    val allowed=listOf("QHD","FHD","HD","SD","LD")
    val variants=info.get("live_transcoding_task_list")?.takeIf{it.isJsonArray}?.asJsonArray?.take(32)?.mapNotNull{value->
        val row=value.takeIf{it.isJsonObject}?.asJsonObject?:return@mapNotNull null
        val template=text(row,"template_id")?.takeIf{it in allowed}?:return@mapNotNull null
        if(text(row,"status")!="finished")return@mapNotNull null
        // preview_url may contain only a short trial; it is never a full playback substitute.
        val url=https(text(row,"url"))?:return@mapNotNull null
        AliyunVideoVariant(template,url)
    }?.distinctBy{it.template}?.sortedBy{allowed.indexOf(it.template)}.orEmpty()
    return AliyunVideoInfo(variants,https(text(info,"master_url")))
}
