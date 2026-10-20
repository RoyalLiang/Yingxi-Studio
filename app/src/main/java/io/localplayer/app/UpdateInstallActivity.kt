package io.localplayer.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Stream a verified immutable payload into Android's own install session. */
class UpdateInstallActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayerTheme { Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) { CircularProgressIndicator();Spacer(Modifier.height(20.dp));Text("正在准备系统安装确认…") } } }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file=File(filesDir,"updates/"+intent.getStringExtra("file"))
                    require(file.canonicalFile.parentFile==File(filesDir,"updates").canonicalFile){"安装包路径无效"}
                    val release=AppRelease(AppVersion(intent.getStringExtra("version")?:"",intent.getLongExtra("code",0)),"","",file.length(),intent.getStringExtra("sha256")?:"",intent.getStringExtra("signer")?:"")
                    require(release.version.code>installedVersion(this@UpdateInstallActivity).code){"此安装包不是更新版本，请重新检查更新"}
                    val digest=MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
                    require(digest.digest().joinToString(""){"%02x".format(it)}==release.sha256){"安装包校验失败，请重新下载"}
                    AppUpdates.verifyArchive(this@UpdateInstallActivity,file,release)
                    val installer=packageManager.packageInstaller
                    val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(packageName);setSize(file.length());setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    val id=installer.createSession(params)
                    try {
                        installer.openSession(id).use { session ->
                            session.openWrite("base.apk",0,file.length()).use { output ->file.inputStream().use{it.copyTo(output,65536)};session.fsync(output) }
                            val callback=Intent(this@UpdateInstallActivity,UpdateInstallReceiver::class.java).setAction(packageName+".UPDATE_RESULT.$id")
                            val pending=PendingIntent.getBroadcast(this@UpdateInstallActivity,id,callback,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                            session.commit(pending.intentSender)
                        }
                    } catch(e:Exception) { runCatching{installer.abandonSession(id)};throw e }
                }
            } catch(e:Exception) { UpdateInstallResult.save(this@UpdateInstallActivity,e.message?:"无法准备安装，请重试",true) }
            finish()
        }
    }
    companion object {
        internal fun intent(context:Context,file:File,release:AppRelease)=Intent(context,UpdateInstallActivity::class.java)
            .putExtra("file",file.name).putExtra("version",release.version.name).putExtra("code",release.version.code)
            .putExtra("sha256",release.sha256).putExtra("signer",release.signer)
    }
}

class UpdateInstallReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java)
                if(confirmation==null)UpdateInstallResult.save(context,"系统未提供安装确认，请重试",true)
                else runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { UpdateInstallResult.save(context,"请回到版本页再次确认安装",true) }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstallResult.save(context,"更新安装完成",false)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallResult.save(context,"已取消安装，原有数据保留",false)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateInstallResult.save(context,"存储空间不足，请释放空间后重试",true)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateInstallResult.save(context,"系统阻止了安装，请检查安装应用的权限后重试",true)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateInstallResult.save(context,"系统报告安装包冲突，请重新检查更新并下载安装包",true)
            else -> UpdateInstallResult.save(context,"系统未完成安装，请重新检查更新后重试",true)
        }
    }
}

internal object UpdateInstallResult {
    fun save(context:Context,text:String,error:Boolean) { context.getSharedPreferences("update-install-result",Context.MODE_PRIVATE).edit().putString("text",text).putBoolean("error",error).apply() }
    fun take(context:Context):Pair<String,Boolean>? {
        val prefs=context.getSharedPreferences("update-install-result",Context.MODE_PRIVATE)
        val text=prefs.getString("text",null)?:return null
        val result=text to prefs.getBoolean("error",false)
        prefs.edit().clear().apply();return result
    }
}
