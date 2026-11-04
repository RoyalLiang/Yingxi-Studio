package io.localplayer.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun originalStreamPixels(scenario:ActivityScenario<PlaybackActivity>):IntArray? {
    fun surface(view:View):View? {
        if(view is PlayerView&&view.isShown)return view.videoSurfaceView
        if(view is ViewGroup)for(i in 0 until view.childCount)surface(view.getChildAt(i))?.let{return it}
        return null
    }
    var output:View?=null;scenario.onActivity{output=surface(it.window.decorView)}
    val bitmap=when(val view=output){
        is TextureView->{
            var image:Bitmap?=null
            InstrumentationRegistry.getInstrumentation().runOnMainSync{image=view.getBitmap(160,90)}
            image?:return null
        }
        is SurfaceView->{
            val image=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);val done=CountDownLatch(1);var status=-1
            try{PixelCopy.request(view,image,{status=it;done.countDown()},Handler(Looper.getMainLooper()))}
            catch(_:IllegalArgumentException){image.recycle();return null}
            if(!done.await(2,TimeUnit.SECONDS)||status!=PixelCopy.SUCCESS){image.recycle();return null}
            image
        }
        else->return null
    }
    return try{IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)}}finally{bitmap.recycle()}
}

/** Attempt every cleanup, especially credential removal, even if a UI release fails. */
internal fun cleanupOriginalStreamProbe(vararg actions:()->Unit) {
    var failure:AssertionError?=null
    for(action in actions)try{action()}catch(error:Throwable){
        if(failure==null)failure=AssertionError("Original stream fixture cleanup failed",error)
        else failure.addSuppressed(error)
    }
    failure?.let{throw it}
}
