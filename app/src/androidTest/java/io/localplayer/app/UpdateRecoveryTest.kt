package io.localplayer.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run each prepare/assert pair in separate instrumentations with am force-stop between them. */
class UpdateRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun fixture():Pair<File,AppRelease>{
        val sample=File(context.getExternalFilesDir(null),"next-update.apk")
        val info=context.packageManager.getPackageArchiveInfo(sample.path,PackageManager.GET_SIGNING_CERTIFICATES)!!
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        return sample to AppRelease(AppVersion(info.versionName!!,info.longVersionCode),"fixture","https://github.com/RoyalLiang/Yingxi-Studio/releases/download/v${info.versionName}/fixture.apk",sample.length(),hash(sample.readBytes()),hash(info.signingInfo!!.apkContentsSigners.single().toByteArray()))
    }
    @Test fun preparePausedTransfer(){
        val (sample,release)=fixture();val part=AppUpdates.partialFile(context,release);part.parentFile!!.mkdirs()
        sample.inputStream().use{input->part.outputStream().use{out->val bytes=ByteArray(327680);assertEquals(bytes.size,input.read(bytes));out.write(bytes)}}
        UpdateDownloads.complete(context,UpdateTransfer(release,progress=part.length().toFloat()/release.size))
    }
    @Test fun restorePausedTransferAfterProcessDeath(){
        UpdateDownloads.restore(context)
        val state=UpdateDownloads.state.value!!
        assertFalse(state.busy);assertNull(state.ready);assertTrue(state.progress>0f)
        assertEquals(327680L,AppUpdates.partialFile(context,state.release).length())
        val owner=ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync{val vm=SettingsViewModel(context);owner.put("settings",vm)}
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().runOnMainSync{val vm=owner.get("settings") as SettingsViewModel;assertEquals(state.release,vm.release);assertTrue(vm.progress!!>0);owner.clear()}
        AppUpdates.partialFile(context,state.release).delete();clear()
    }
    @Test fun prepareCompletedTransfer(){
        val (sample,release)=fixture();val ready=File(context.filesDir,"updates/recovery-${java.util.UUID.randomUUID()}.apk")
        ready.parentFile!!.mkdirs();sample.inputStream().use{input->ready.outputStream().use{input.copyTo(it)}}
        UpdateDownloads.complete(context,UpdateTransfer(release,progress=1f,ready=ready))
    }
    @Test fun restoreCompletedTransferAfterProcessDeath():Unit=runBlocking {
        UpdateDownloads.restore(context);val state=UpdateDownloads.state.value!!
        assertFalse(state.busy);assertTrue(state.ready!!.isFile)
        AppUpdates.verifyArchive(context,state.ready,state.release)
        val owner=ViewModelStore();var vm:SettingsViewModel?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync{vm=SettingsViewModel(context);owner.put("settings",vm!!)}
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        UpdateInstaller.verify(context,vm!!.ready!!,vm!!.release!!)
        InstrumentationRegistry.getInstrumentation().runOnMainSync{owner.clear()}
        state.ready.delete();clear()
    }
    private fun clear(){context.getSharedPreferences("update-transfer",Context.MODE_PRIVATE).edit().clear().commit();UpdateDownloads.update(null)}
}
