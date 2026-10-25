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
    if(Regex("(?:^|[ ._-])(?:zh|chs|cht|chi|zho|国语|粤语|普通话|中文)(?:[ ._-]|$)").containsMatchIn(text))return "zh"
    return listOf("en" to "en|eng|英语","ja" to "ja|jpn|日语","ko" to "ko|kor|韩语").firstOrNull{(_,tags)->Regex("(?:^|[ ._-])(?:$tags)(?:[ ._-]|$)").containsMatchIn(text)}?.first
}
