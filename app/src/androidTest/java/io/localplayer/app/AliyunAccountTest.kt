package io.localplayer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.localplayer.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AliyunAccountTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun credentialsAreEncryptedAndDeletionInvalidatesExistingConnector() {
        val store = SourceStore(context)
        val source = Source(id="test-cloud-"+UUID.randomUUID(), label="安全存储验证", protocol=Protocol.ALIYUN,
            host="openapi.alipan.com", root="/", username="test-user", domain="test-drive")
        val credentials = JSONObject().put("access","test-access-value").put("expiresAt",System.currentTimeMillis()+3600000).toString()
        try {
            store.save(source,credentials,true)
            assertEquals(credentials,store.password(source.id))
            val stored = context.getSharedPreferences("secrets-v1",Context.MODE_PRIVATE).getString(source.id,"")!!
            assertFalse(stored.contains("test-access-value")); assertFalse(stored.contains("expiresAt"))
            val session=AppConnectors().connect(source,Credentials(credentials),Cancellation())
            store.delete(source.id)
            session.use { try { it.list(""); fail("Deleted account must not use a stale playback credential") } catch (_:AliyunUnauthorized) {} }
            assertNull(store.password(source.id)); assertFalse(store.load().any { it.id==source.id })
        }finally{store.delete(source.id)}
    }
    @Test fun unsolicitedCallbackCannotCreateAnAccount() {
        val store=SourceStore(context);val before=store.load().map { it.id }
        val uri=Uri.Builder().scheme("ypauth${AliyunConfig.CLIENT_ID}").authority("callback").appendQueryParameter("code","untrusted-code").appendQueryParameter("state","untrusted-state").build()
        assertFalse(AliyunCloud.callback(Intent(Intent.ACTION_VIEW,uri)))
        assertFalse(AliyunCloud.callback(Intent().putExtra("code","untrusted-code")))
        assertEquals(before,store.load().map { it.id })
    }
    @Test fun missingAppDoesNotStartAuthorizationOrCreateSource() = runBlocking {
        assertEquals(CloudAppStatus.NOT_INSTALLED,AndroidCloudApps(context).status(CloudProvider.ALIYUNPAN))
        val before=SourceStore(context).load().map { it.id }
        try{AliyunCloud.authorize();fail("Missing official app must be reported")}catch(e:java.io.IOException){assertTrue(e.message!!.contains("未安装"))}
        assertEquals(before,SourceStore(context).load().map { it.id })
    }
}
