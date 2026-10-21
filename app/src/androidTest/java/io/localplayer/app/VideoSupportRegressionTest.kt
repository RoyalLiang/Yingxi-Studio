package io.localplayer.app

import android.content.pm.ActivityInfo
import androidx.media3.common.*
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoSupportRegressionTest {
    private fun tracks(support:Int,selected:Boolean=false,codecs:String="hvc1.2.4.H1153.B0")=Tracks(ImmutableList.of(Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(codecs).build()),
        false,intArrayOf(support),booleanArrayOf(selected))))
    @Test fun hevcAboveAdvertisedLevelIsAttemptedWithoutAnUnsupportedOverlay() {
        for(codec in listOf("hvc1.2.4.H1153.B0","hvc1.2.4.H153.B0"))
            assertFalse(unsupportedVideo(tracks(C.FORMAT_EXCEEDS_CAPABILITIES,codecs=codec)))
        assertFalse(unsupportedVideo(tracks(C.FORMAT_EXCEEDS_CAPABILITIES,true)))
    }
    @Test fun selectedVideoCannotBeBlockedByTheCapabilitySummary() {
        assertFalse(unsupportedVideo(tracks(C.FORMAT_UNSUPPORTED_SUBTYPE,true)))
    }
    @Test fun genuinelyUnsupportedUnselectedTracksStillExplainTheFailure() {
        assertTrue(unsupportedVideo(tracks(C.FORMAT_UNSUPPORTED_TYPE)))
        assertTrue(unsupportedVideo(tracks(C.FORMAT_UNSUPPORTED_SUBTYPE)))
        assertFalse(unsupportedVideo(tracks(C.FORMAT_HANDLED)))
        assertFalse(unsupportedVideo(Tracks.EMPTY))
    }
    @Test fun displayShapeIncludesPixelAspect() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,videoOrientation(VideoSize(1080,1920)))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,videoOrientation(VideoSize(1920,1080)))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,videoOrientation(VideoSize(1080,1920,0,2f)))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,videoOrientation(VideoSize(1080,1080)))
        assertNull(videoOrientation(VideoSize.UNKNOWN))
        assertNull(videoOrientation(VideoSize(10,20,0,Float.NaN)))
    }
}
