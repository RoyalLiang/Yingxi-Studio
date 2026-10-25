package io.localplayer.app

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import org.json.JSONArray
import org.json.JSONObject

internal data class SidecarAudio(val uri:Uri,val name:String) {
    companion object {
        private const val KEY="sidecar-audio"
        fun from(item:MediaItem):List<SidecarAudio> = runCatching {
            val list=JSONArray(item.mediaMetadata.extras?.getString(KEY)?:"[]")
            require(list.length()<=8)
            (0 until list.length()).map { i->list.getJSONObject(i).let{SidecarAudio(Uri.parse(it.getString("uri")),it.getString("name").take(240))} }
        }.getOrDefault(emptyList())
        fun attach(item:MediaItem,audio:List<SidecarAudio>):MediaItem {
            require(audio.size<=8)
            val list=JSONArray();audio.forEach{list.put(JSONObject().put("uri",it.uri.toString()).put("name",it.name))}
            val extras=Bundle(item.mediaMetadata.extras?:Bundle()).apply{putString(KEY,list.toString())}
            return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
        }
    }
}

/** All tracks use the primary video's clock, including seek, speed, pause and resume. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SidecarMediaSourceFactory(private val base:MediaSource.Factory,
    private val dataSource:androidx.media3.datasource.DataSource.Factory):MediaSource.Factory by base {
    private var subtitles:androidx.media3.extractor.text.SubtitleParser.Factory=androidx.media3.extractor.text.DefaultSubtitleParserFactory()
    override fun setSubtitleParserFactory(factory:androidx.media3.extractor.text.SubtitleParser.Factory):MediaSource.Factory {
        subtitles=factory;base.setSubtitleParserFactory(factory);return this
    }
    override fun createMediaSource(item:MediaItem):MediaSource {
        val captions=item.localConfiguration?.subtitleConfigurations.orEmpty()
        val managed=captions.filter { subtitles.supportsFormat(Format.Builder().setSampleMimeType(it.mimeType).build()) }
        val primary=base.createMediaSource(item.buildUpon().setSubtitleConfigurations(captions.filter{it !in managed}).build())
        val sources=mutableListOf(primary)
        SidecarAudio.from(item).forEach {
            val media=MediaItem.Builder().setUri(it.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build()).build()
            sources+=FilteringMediaSource(base.createMediaSource(media),C.TRACK_TYPE_AUDIO)
        }
        managed.forEach { caption ->
            val format=Format.Builder().setSampleMimeType(caption.mimeType).setLanguage(caption.language).setLabel(caption.label)
                .setSelectionFlags(caption.selectionFlags).setRoleFlags(caption.roleFlags).setId(caption.id).build()
            // A non-lazy extractor publishes the actual cue end time, including cues spanning a seek.
            val extractors=androidx.media3.extractor.ExtractorsFactory { arrayOf(androidx.media3.extractor.text.SubtitleExtractor(subtitles.create(format),format)) }
            sources+=androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSource,extractors)
                .createMediaSource(MediaItem.fromUri(caption.uri))
        }
        if(sources.size==1&&managed.isEmpty())return primary
        val merged=MergingMediaSource(true,false,*sources.toTypedArray())
        return object:androidx.media3.exoplayer.source.WrappingMediaSource(merged) {
            private fun original(timeline:androidx.media3.common.Timeline)=object:androidx.media3.exoplayer.source.ForwardingTimeline(timeline) {
                override fun getWindow(index:Int,window:androidx.media3.common.Timeline.Window,projection:Long):androidx.media3.common.Timeline.Window =
                    super.getWindow(index,window,projection).also{it.mediaItem=item}
            }
            override fun getMediaItem()=item
            override fun canUpdateMediaItem(mediaItem:MediaItem)=false
            override fun getInitialTimeline()=super.getInitialTimeline()?.let(::original)
            override fun onChildSourceInfoRefreshed(timeline:androidx.media3.common.Timeline){refreshSourceInfo(original(timeline))}
        }
    }
}

internal fun sidecarLanguage(name:String):String? {
    val text=name.lowercase(java.util.Locale.ROOT)
    return listOf("yue" to "yue|粤语(?:配音|音轨|版)?|粵語|广东话|廣東話|cantonese","cmn" to "cmn|国语(?:配音|音轨|版)?|國語|普通话|普通話|mandarin",
        "zh-Hant" to "cht|zh[-_]hant|zh[-_]tw|繁体(?:中文|字幕)?|繁體|繁中","zh-Hans" to "chs|zh[-_]hans|简体(?:中文|字幕)?|簡體|简中",
        "zh" to "zh|chi|zho|chinese|中文|汉语|漢語","en" to "en|eng|english|英语|英語|英文","ja" to "ja|jpn|japanese|日语|日語|日文",
        "ko" to "ko|kor|korean|韩语|韓語|韩文","fr" to "fr|fre|fra|french|法语|法語","de" to "de|ger|deu|german|德语|德語",
        "es" to "es|spa|spanish|西班牙语","it" to "it|ita|italian|意大利语","ru" to "ru|rus|russian|俄语|俄語",
        "pt" to "pt|por|portuguese|葡萄牙语","th" to "th|tha|thai|泰语|泰語","vi" to "vi|vie|vietnamese|越南语")
        .firstOrNull{(_,tags)->Regex("(?:^|[ ._\\-\\[\\]()])(?:$tags)(?=[ ._\\-\\[\\]()]|$)").containsMatchIn(text)}?.first
}

internal fun normalizedTrackLanguage(value:String?):String? {
    val tag=value?.trim()?.replace('_','-')?.takeIf{Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(it)}?:return null
    val parts=tag.split('-');val base=parts.first().lowercase(java.util.Locale.ROOT)
    if(base in setOf("und","zxx","mis"))return null
    val language=mapOf("eng" to "en","chi" to "zh","zho" to "zh","jpn" to "ja","kor" to "ko","fre" to "fr","fra" to "fr",
        "ger" to "de","deu" to "de","spa" to "es","ita" to "it","rus" to "ru","por" to "pt","tha" to "th","vie" to "vi",
        "ara" to "ar","hin" to "hi")[base]?:base
    return java.util.Locale.forLanguageTag((listOf(language)+parts.drop(1)).joinToString("-")).toLanguageTag()
}
