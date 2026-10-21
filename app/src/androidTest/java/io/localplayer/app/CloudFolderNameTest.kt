package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CloudFolderNameTest {
    @Test fun directoryNamesSurviveColdInitializationAndAreRemovedWithTheAccount() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val source="folder-name-"+UUID.randomUUID()
        try {
            AliyunCloud.initialize(app)
            AliyunCloud.rememberFolderName(source,"opaque-provider-id","漫长的季节")
            assertEquals("漫长的季节",AliyunCloud.folderName(source,"opaque-provider-id"))
            AliyunCloud.initialize(app)
            assertEquals("漫长的季节",AliyunCloud.folderName(source,"opaque-provider-id"))
            AliyunCloud.accountRemoved(source)
            assertEquals("opaque-provider-id",AliyunCloud.folderName(source,"opaque-provider-id"))
        }finally{AliyunCloud.accountRemoved(source)}
    }
}
