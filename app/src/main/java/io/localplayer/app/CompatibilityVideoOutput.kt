package io.localplayer.app

import android.view.TextureView

/** MediaSession transports Surface, so local SDR rendering binds TextureView ownership directly. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object CompatibilityVideoOutput {
    private var player:java.lang.ref.WeakReference<CompatibilityPlayer>?=null
    fun register(value:CompatibilityPlayer){player=java.lang.ref.WeakReference(value)}
    fun unregister(value:CompatibilityPlayer){if(player?.get()===value)player=null}
    fun attach(view:TextureView){player?.get()?.attachTexture(view)}
    fun detach(view:TextureView){player?.get()?.clearVideoTextureView(view)}
}
