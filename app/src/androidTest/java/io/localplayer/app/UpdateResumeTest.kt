package io.localplayer.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateResumeTest {
    private fun fixture():Pair<Context,AppRelease> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.getExternalFilesDir(null),"next-update.apk");assertTrue("Provision higher-version APK",file.isFile)
        val info=context.packageManager.getPackageArchiveInfo(file.path,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)!!
        fun hash(bytes:ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        return context to AppRelease(AppVersion(info.versionName!!,info.longVersionCode),"fixture","https://github.com/RoyalLiang/Yingxi-Studio/releases/download/v${info.versionName}/fixture.apk",file.length(),hash(file.readBytes()),hash(info.signingInfo!!.apkContentsSigners.single().toByteArray()))
    }
    @Test fun validRangeResumesAtPersistedOffsetAndVerifiesSignedArtifact():Unit=runBlocking {
        val (context,release)=fixture()
        val sample=File(context.getExternalFilesDir(null),"next-update.apk")
        val part=AppUpdates.partialFile(context,release);part.parentFile!!.mkdirs()
        sample.inputStream().use{input->part.outputStream().use{out->val bytes=ByteArray(327680);assertEquals(bytes.size,input.read(bytes));out.write(bytes)}}
        val offset=part.length()
        val client=AppUpdates(context){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode():Int {assertEquals("bytes=$offset-",getRequestProperty("Range"));assertEquals("identity",getRequestProperty("Accept-Encoding"));return 206}
            override fun getContentLengthLong()=release.size-offset
            override fun getHeaderField(name:String):String?=if(name=="Content-Range")"bytes $offset-${release.size-1}/${release.size}"else null
            override fun getInputStream()=sample.inputStream().apply{assertEquals(offset,skip(offset))}
        }}
        val values=mutableListOf<Float>()
        val ready=client.download(release){values+=it}
        try{assertEquals(sample.length(),ready.length());assertFalse(part.exists());assertTrue(values.first()>0);assertEquals(1f,values.last());assertTrue(values.zipWithNext().all{it.first<=it.second})}finally{ready.delete();part.delete()}
    }
    @Test fun ignoredRangeRestartsSafelyAndMalformedRangeCannotAppend():Unit=runBlocking {
        val (context,release)=fixture()
        val sample=File(context.getExternalFilesDir(null),"next-update.apk")
        val part=AppUpdates.partialFile(context,release);part.parentFile!!.mkdirs();part.writeBytes(ByteArray(300000){7})
        val client=AppUpdates(context){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getContentLengthLong()=release.size
            override fun getInputStream()=sample.inputStream()
        }}
        client.download(release){}.delete()
        part.writeBytes(ByteArray(300000){7})
        val malformed=AppUpdates(context){url->object:HttpURLConnection(URL(url)){
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=206
            override fun getHeaderField(name:String):String?=if(name=="Content-Range")"bytes 0-${release.size-1}/${release.size}"else null
            override fun getInputStream():InputStream=error("Malformed range must not open body")
        }}
        try{malformed.download(release){};fail("Expected rejected range")}catch(_:IOException){assertEquals(300000L,part.length())}finally{part.delete()}
    }
    @Test fun interruptionKeepsBytesAcrossDownloaderRecreation():Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=File(context.filesDir,"updates").apply{mkdirs()}
        directory.listFiles()?.filter{it.name.endsWith(".part")}?.forEach{it.delete()}
        val release=AppRelease(AppVersion("99.0.0-test",installedVersion(context).code+100),"",
            "https://github.com/RoyalLiang/Yingxi-Studio/releases/download/v99.0.0-test/yingxi-test.apk",
            1024*1024,"1".repeat(64),"2".repeat(64))
        fun client()=AppUpdates(context){url->object:HttpURLConnection(URL(url)) {
            override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getContentLengthLong()=release.size
            override fun getInputStream()=object:InputStream(){
                var count=0
                override fun read():Int {if(count++>=327680)throw IOException("fixture connection lost");return 42}
            }
        }}
        repeat(2) {
            try {client().download(release){};fail("Expected interrupted fixture")}
            catch(e:IOException){assertEquals("fixture connection lost",e.message)}
            val partials=directory.listFiles()!!.filter{it.name.endsWith(".part")}
            assertEquals("Keep one stable partial after interruption",1,partials.size)
            assertTrue("Downloaded bytes survive failure",partials.single().length()>=262144)
        }
        directory.listFiles()?.filter{it.name.endsWith(".part")}?.forEach{it.delete()}
    }
}
