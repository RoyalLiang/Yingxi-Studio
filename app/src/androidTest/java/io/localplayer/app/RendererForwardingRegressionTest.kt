package io.localplayer.app

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.Renderer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RendererForwardingRegressionTest {
    private class Probe:BaseRenderer(C.TRACK_TYPE_VIDEO) {
        var speeds:Pair<Float,Float>?=null
        var scheduling:Pair<Long,Long>?=null
        var startStream=0
        var releases=0
        override fun getName()="ForwardingProbe"
        override fun supportsFormat(format:Format)=C.FORMAT_HANDLED
        override fun render(positionUs:Long,elapsedRealtimeUs:Long){}
        override fun isReady()=true
        override fun isEnded()=false
        override fun setPlaybackSpeed(currentPlaybackSpeed:Float,targetPlaybackSpeed:Float){speeds=currentPlaybackSpeed to targetPlaybackSpeed}
        override fun getDurationToProgressUs(positionUs:Long,elapsedRealtimeUs:Long):Long {scheduling=positionUs to elapsedRealtimeUs;return 123}
        override fun supportsResetPositionWithoutKeyFrameReset(positionUs:Long)=positionUs==42L
        override fun enableMayRenderStartOfStream(){startStream++}
        override fun onRelease(){releases++}
    }

    @Test fun nativeAndSoftwareWrappersForwardJavaDefaultRendererBehavior() {
        val factory=HdrRenderersFactory(InstrumentationRegistry.getInstrumentation().targetContext)
        for(native in listOf(true,false)) {
            val probe=Probe()
            val wrapper=if(native)factory.nativeGate(probe)else ForwardingPlaybackRenderer(probe)
            wrapper.setPlaybackSpeed(1.5f,2f);assertEquals(1.5f to 2f,probe.speeds)
            assertEquals(123L,wrapper.getDurationToProgressUs(12,34));assertEquals(12L to 34L,probe.scheduling)
            assertTrue(wrapper.supportsResetPositionWithoutKeyFrameReset(42))
            assertFalse(wrapper.supportsResetPositionWithoutKeyFrameReset(43))
            wrapper.enableMayRenderStartOfStream();assertEquals(1,probe.startStream)
            wrapper.release();assertEquals(1,probe.releases)
            // Future Media3 upgrades must not silently introduce another inherited no-op.
            val inherited=Renderer::class.java.methods.filter{it.isDefault}.filter {
                wrapper.javaClass.getMethod(it.name,*it.parameterTypes).declaringClass==Renderer::class.java
            }
            assertTrue("Unforwarded Java defaults: ${inherited.map{it.name}}",inherited.isEmpty())
        }
    }
}
