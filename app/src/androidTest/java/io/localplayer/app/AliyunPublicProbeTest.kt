package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.AliyunApi
import io.localplayer.core.AliyunGrant
import io.localplayer.core.Cancellation
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Explicit diagnostic only: uses the public client identifier, never an account or a token. */
class AliyunPublicProbeTest {
    @Test fun officialNativeAuthorizationEntryAcceptsAndroidRequest() {
        assumeTrue("Manual public endpoint probe",InstrumentationRegistry.getArguments().getString("aliyun_public_probe")=="true")
        Cancellation().use { cancellation ->
            val redirect=AliyunApi().authorization(AliyunGrant.create(),true,cancellation)
            assertEquals("smartdrive",java.net.URI(redirect).scheme)
        }
    }
}
