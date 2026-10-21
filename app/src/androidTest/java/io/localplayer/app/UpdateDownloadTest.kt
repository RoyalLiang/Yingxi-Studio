package io.localplayer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateDownloadTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun checksThenStreamsAndVerifiesHigherVersionSignedApk()=runBlocking {
        val sample=File(context.getExternalFilesDir(null),"next-update.apk")
        assertTrue("Push synthetic higher-version update APK before testing",sample.isFile)
        val archive=context.packageManager.getPackageArchiveInfo(sample.absolutePath,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)!!
        val version=AppVersion(archive.versionName!!,archive.longVersionCode)
        assertTrue(version.code>installedVersion(context).code)
        val signer=MessageDigest.getInstance("SHA-256").digest(archive.signingInfo!!.apkContentsSigners.single().toByteArray()).hex()
        val hash=sample.inputStream().use { input->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().hex() }
        val prefix="https://github.com/${AppUpdates.REPOSITORY}/releases/download/v${version.name}/"
        val assetName="yingxi-${version.name}-${version.code}.apk"
        val release=JSONObject().put("tag_name","v${version.name}").put("prerelease",true).put("body","Synthetic signed update")
            .put("assets",JSONArray().put(JSONObject().put("name",assetName).put("state","uploaded").put("digest","sha256:"+hash).put("size",sample.length()).put("browser_download_url",prefix+assetName)))
        val installed=installedVersion(context)
        val legacyName="yingxi-${installed.name}-${installed.code}.apk"
        val legacy=JSONObject().put("tag_name","v${installed.name}").put("prerelease",true)
            .put("assets",JSONArray().put(JSONObject().put("name",legacyName).put("size",1))) // Old digest-less asset cannot block a newer update.
        val requests=mutableListOf<String>()
        val client=AppUpdates(context){url->
            requests.add(url)
            val bytes=when { url.startsWith("https://api.github.com/")->JSONArray().put(release).put(legacy).toString().toByteArray();else->null }
            object:HttpURLConnection(URL(url)) {
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun connect(){}
                override fun getResponseCode()=200
                override fun getContentLengthLong()=bytes?.size?.toLong() ?: sample.length()
                override fun getInputStream():InputStream=bytes?.inputStream() ?: sample.inputStream()
            }
        }
        val directory=File(context.filesDir,"updates")
        var published:File?=null
        try {
            val candidate=client.check()!!
            assertEquals(version,candidate.version);assertEquals("Synthetic signed update",candidate.notes)
            var lastProgress=0f
            val downloaded=client.download(candidate){value->assertTrue(value>=lastProgress&&value<=1f);lastProgress=value}.also{published=it}
            assertEquals(1f,lastProgress);assertEquals(sample.length(),downloaded.length())
            assertTrue("Payload name must identify its verified version and digest",downloaded.name.contains(version.code.toString())&&downloaded.name.contains(hash))
            AppUpdates.verifyArchive(context,downloaded,candidate)
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.updates",downloaded)
            assertEquals("content",uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use{assertTrue(it.read()>=0)}
            var rejected=false
            try {client.download(candidate.copy(sha256="0".repeat(64))){} }catch(e:Exception){rejected=true}
            assertTrue("corrupt content must be rejected",rejected)
            assertTrue(downloaded.isFile);assertFalse(directory.listFiles().orEmpty().any{it.name.endsWith(".part")})
            var cancelled=false
            try { client.download(candidate){value->if(value>.01f)throw kotlinx.coroutines.CancellationException("cancel test")} }
            catch(e:kotlinx.coroutines.CancellationException){cancelled=true}
            assertTrue(cancelled)
            assertTrue(downloaded.isFile);assertTrue(AppUpdates.partialFile(context,candidate).length()>0)
            AppUpdates.partialFile(context,candidate).delete()
            assertEquals(4,requests.size);assertFalse(requests.any{it.endsWith(".json")})
        } finally {published?.delete()}
    }
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it)}
}
