package io.localplayer.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.security.MessageDigest

/** Prepare the system session in place, without an intermediate activity. */
internal object UpdateInstaller {
    fun verify(context:Context,file:File,release:AppRelease) {
        require(file.canonicalFile.parentFile==File(context.filesDir,"updates").canonicalFile){"安装包路径无效"}
        require(release.version.code>installedVersion(context).code){"此安装包不是更新版本，请重新检查更新"}
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        require(digest.digest().joinToString(""){"%02x".format(it)}==release.sha256){"安装包校验失败，请重新下载"}
        AppUpdates.verifyArchive(context,file,release)
    }
    fun install(context:Context,file:File,release:AppRelease) {
        verify(context,file,release)
        val installer=context.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply{
            setAppPackageName(context.packageName);setSize(file.length());setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id=installer.createSession(params)
        try {
            installer.openSession(id).use{session->
                session.openWrite("base.apk",0,file.length()).use{output->file.inputStream().use{it.copyTo(output,65536)};session.fsync(output)}
                val callback=Intent(context,UpdateInstallReceiver::class.java).setAction(context.packageName+".UPDATE_RESULT.$id")
                val pending=PendingIntent.getBroadcast(context,id,callback,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        }catch(e:Exception){runCatching{installer.abandonSession(id)};throw e}
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
