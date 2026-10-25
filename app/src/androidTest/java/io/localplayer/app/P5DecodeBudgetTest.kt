package io.localplayer.app

import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class P5DecodeBudgetTest {
    @Test fun measureDecodeWithoutGpuMapping() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"dv5-profile5-4k.mp4");assertTrue(file.isFile)
        for(threads in listOf(0,4,8)) {
            val input=MpvInput.open(context,Uri.fromFile(file));val engine=MpvRuntime.load(context)
            try {
                engine.create(context)
                mapOf("config" to "no","vo" to "null","hwdec" to "no","aid" to "no",
                    "untimed" to "yes","vd-lavc-threads" to threads.toString(),"idle" to "yes",
                    "keep-open" to "yes","access-references" to "no","msg-level" to "all=no").forEach{(k,v)->engine.option(k,v)}
                engine.init()
                val start=SystemClock.elapsedRealtime()
                engine.command("loadfile",input.path)
                while(SystemClock.elapsedRealtime()-start<30000&&engine.get("eof-reached")!="yes")Thread.sleep(10)
                val elapsed=SystemClock.elapsedRealtime()-start
                assertEquals("Decode must finish", "yes",engine.get("eof-reached"))
                assertTrue("Decoded output must retain 4K width",(engine.get("video-params/w")?.toIntOrNull()?:0)>=3840)
                assertTrue("Decode must reach the clip end",(engine.get("time-pos")?.toDoubleOrNull()?:0.0)>3.8)
                println("P5_DECODE_BUDGET threads=$threads wall_ms=$elapsed video_pts=${engine.get("video-pts")} position=${engine.get("time-pos")} width=${engine.get("video-params/w")} codec=${engine.get("video-codec")} dropped=${engine.get("decoder-frame-drop-count")}")
            } finally {engine.destroy();input.close()}
        }
    }
}
