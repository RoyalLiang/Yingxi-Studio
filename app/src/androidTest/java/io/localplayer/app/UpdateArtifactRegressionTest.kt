package io.localplayer.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateArtifactRegressionTest {
    @Test fun failedOrCancelledDownloadCannotRemoveAnInstallerAlreadyOffered()=runBlocking{
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val sample=File(app.getExternalFilesDir(null),"next-update.apk");assertTrue(sample.isFile)
        val info=app.packageManager.getPackageArchiveInfo(sample.path,PackageManager.GET_SIGNING_CERTIFICATES)!!
        val hash=MessageDigest.getInstance("SHA-256").digest(sample.readBytes()).joinToString(""){"%02x".format(it)}
        val signer=MessageDigest.getInstance("SHA-256").digest(info.signingInfo!!.apkContentsSigners.single().toByteArray()).joinToString(""){"%02x".format(it)}
        val version=AppVersion(info.versionName!!,info.longVersionCode)
        val release=AppRelease(version,"Synthetic update","https://github.com/"+AppUpdates.REPOSITORY+"/releases/download/v"+version.name+"/yingxi-"+version.name+"-"+version.code+".apk",sample.length(),hash,signer)
        fun client()=AppUpdates(app){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getContentLengthLong()=sample.length()
            override fun getInputStream()=sample.inputStream()
        }}
        val first=client().download(release){}
        val uri=androidx.core.content.FileProvider.getUriForFile(app,app.packageName+".updates",first)
        try{
            var failed=false
            try{client().download(release.copy(sha256="0".repeat(64))){} }catch(_:Exception){failed=true}
            assertTrue(failed)
            assertTrue("A new failed download must preserve the APK already handed to Android",first.isFile)
            val bytes=app.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
            assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
            var cancelled=false
            try{client().download(release){throw kotlinx.coroutines.CancellationException("fixture cancellation")} }catch(_:kotlinx.coroutines.CancellationException){cancelled=true}
            assertTrue(cancelled);assertTrue(first.isFile)
        }finally{first.delete();AppUpdates.partialFile(app,release).delete()}
    }
}
