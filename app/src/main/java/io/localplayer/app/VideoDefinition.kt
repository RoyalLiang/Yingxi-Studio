package io.localplayer.app

/** Read-only size class of the decoded video, independent of orientation or filename. */
internal fun videoDefinition(width:Int,height:Int):String? {
    if(width<=0||height<=0)return null
    val long=maxOf(width,height)
    return when {
        long>=7680->"8K"
        long>=3840->"4K"
        long>=2048->"2K"
        long>=1920->"1080P"
        long>=1280->"720P"
        else->"${minOf(width,height)}P"
    }
}
