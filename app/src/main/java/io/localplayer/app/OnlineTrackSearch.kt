package io.localplayer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.localplayer.core.MediaIdentity

/** Explicit browser search; no audio catalogue API or automatic download is implied. */
internal fun onlineTrackSearch(context:Context,media:MediaRef?,audio:Boolean) {
    if(media==null||SealedMedia.protects(media))return
    val title=MediaIdentity.parse(media.title)
    val query=listOfNotNull(title.title.takeIf{it.isNotBlank()},title.year?.toString(),if(audio)"配音 外挂音轨"else"字幕 SRT ASS").joinToString(" ")
    val uri=Uri.Builder().scheme("https").authority("www.bing.com").path("search").appendQueryParameter("q",query).build()
    runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        .onFailure{Toast.makeText(context,"没有可打开网页的浏览器",Toast.LENGTH_LONG).show()}
}
