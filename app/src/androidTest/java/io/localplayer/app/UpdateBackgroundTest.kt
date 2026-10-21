package io.localplayer.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class UpdateBackgroundTest {
    private fun waitFor(label:String,test:()->Boolean){val until=System.currentTimeMillis()+60000;while(!test()){if(System.currentTimeMillis()>until)fail(label);Thread.sleep(50)}}
    @Test fun transferContinuesWhileBackgroundedAndScreenOffThenResumesAfterPause(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val sample=File(app.getExternalFilesDir(null),"next-update.apk");assertTrue(sample.isFile)
        val info=app.packageManager.getPackageArchiveInfo(sample.path,PackageManager.GET_SIGNING_CERTIFICATES)!!
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val release=AppRelease(AppVersion(info.versionName!!,info.longVersionCode),"fixture","https://github.com/RoyalLiang/Yingxi-Studio/releases/download/v${info.versionName}/fixture.apk",sample.length(),hash(sample.readBytes()),hash(info.signingInfo!!.apkContentsSigners.single().toByteArray()))
        val part=AppUpdates.partialFile(app,release);part.delete()
        val slow=AtomicBoolean(true)
        val factory=UpdateDownloads.clientFactory
        fun shell(command:String){instrumentation.uiAutomation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use{input->input.readBytes()}}}
        val owner=ViewModelStore()
        var vm:SettingsViewModel?=null
        var ready:File?=null
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            try {
                UpdateDownloads.clientFactory={context->AppUpdates(context){url->object:HttpURLConnection(URL(url)){
                    val offset get()=getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLong()?:0L
                    override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
                    override fun getResponseCode()=if(offset>0)206 else 200
                    override fun getContentLengthLong()=sample.length()-offset
                    override fun getHeaderField(name:String):String?=if(name=="Content-Range"&&offset>0)"bytes $offset-${sample.length()-1}/${sample.length()}"else null
                    override fun getInputStream():InputStream {
                        val input=sample.inputStream().apply{if(offset>0)assertEquals(offset,skip(offset))}
                        return object:InputStream(){override fun read()=input.read();override fun read(bytes:ByteArray,off:Int,len:Int):Int{if(slow.get())Thread.sleep(12);return input.read(bytes,off,minOf(len,8192))};override fun close()=input.close()}
                    }
                }}}
                scenario.onActivity{UpdateDownloads.start(it,release)}
                waitFor("Download started"){(UpdateDownloads.state.value?.progress?:0f)>.01f}
                val before=part.length()
                shell("input keyevent 3");shell("input keyevent 223")
                waitFor("Progress advances with screen off"){part.length()>before+1024*1024}
                assertTrue(UpdateDownloads.state.value!!.busy)
                instrumentation.runOnMainSync{vm=SettingsViewModel(app);owner.put("settings",vm!!)}
                waitFor("New page observes existing transfer"){vm?.busy==true&&(vm?.progress?:0f)>0f}
                UpdateDownloads.pause(app)
                waitFor("Pause keeps bytes"){UpdateDownloads.state.value?.busy==false}
                assertTrue(part.length()>before)
                // Let the cancelled blocking read release its shared writer before starting another transfer.
                Thread.sleep(100)
                shell("input keyevent 224");shell("wm dismiss-keyguard")
                scenario.onActivity{it.startActivity(android.content.Intent(it,MainActivity::class.java))}
                Thread.sleep(1000)
                scenario.onActivity{slow.set(false);UpdateDownloads.start(it,release)}
                waitFor("Resumed signed APK ready"){UpdateDownloads.state.value?.ready!=null}
                ready=UpdateDownloads.state.value!!.ready
                assertEquals(release.sha256,hash(ready!!.readBytes()))
                assertFalse(part.exists())
            }finally{
                shell("input keyevent 224");shell("wm dismiss-keyguard");UpdateDownloads.pause(app)
                instrumentation.runOnMainSync{owner.clear()}
                UpdateDownloads.clientFactory=factory
                ready?.delete();part.delete()
                app.getSharedPreferences("update-transfer",Context.MODE_PRIVATE).edit().clear().commit()
                UpdateDownloads.update(null)
            }
        }
    }
}
