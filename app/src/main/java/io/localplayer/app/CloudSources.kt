package io.localplayer.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal enum class CloudProvider(val title:String,val packageName:String,val icon:Int,val home:String,val setup:String) {
    ALIYUNPAN("阿里云盘","com.alicloud.databox",R.drawable.cloud_aliyunpan,"https://www.alipan.com/","https://github.com/alibaba/aliyunpan-android-sdk"),
    BAIDU("百度网盘","com.baidu.netdisk",R.drawable.cloud_baidu,"https://pan.baidu.com/","https://yun.baidu.com/union/home")
}
internal enum class CloudAppStatus { AVAILABLE, NOT_INSTALLED, UNAVAILABLE }
internal enum class CloudLaunchResult { OPENED, NOT_INSTALLED, UNAVAILABLE }
internal interface CloudAppGateway {
    fun status(provider:CloudProvider):CloudAppStatus
    fun open(provider:CloudProvider):CloudLaunchResult
}
/** Launch only the named installed app; opening it is not an OAuth grant. */
internal class AndroidCloudApps(private val context:Context):CloudAppGateway {
    override fun status(provider:CloudProvider):CloudAppStatus = try {
        val info=context.packageManager.getApplicationInfo(provider.packageName,0)
        if(!info.enabled || info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED != 0 ||
            context.packageManager.getLaunchIntentForPackage(provider.packageName)==null) CloudAppStatus.UNAVAILABLE
        else CloudAppStatus.AVAILABLE
    } catch(_:PackageManager.NameNotFoundException){CloudAppStatus.NOT_INSTALLED}
      catch(_:SecurityException){CloudAppStatus.UNAVAILABLE}
    override fun open(provider:CloudProvider):CloudLaunchResult {
        val status=status(provider)
        if(status!=CloudAppStatus.AVAILABLE)return if(status==CloudAppStatus.NOT_INSTALLED)CloudLaunchResult.NOT_INSTALLED else CloudLaunchResult.UNAVAILABLE
        return try {
            val intent=context.packageManager.getLaunchIntentForPackage(provider.packageName) ?: return if(status(provider)==CloudAppStatus.NOT_INSTALLED)CloudLaunchResult.NOT_INSTALLED else CloudLaunchResult.UNAVAILABLE
            context.startActivity(intent.setPackage(provider.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CloudLaunchResult.OPENED
        } catch(_:ActivityNotFoundException){
            if(status(provider)==CloudAppStatus.NOT_INSTALLED)CloudLaunchResult.NOT_INSTALLED else CloudLaunchResult.UNAVAILABLE
        } catch(_:SecurityException){CloudLaunchResult.UNAVAILABLE}
    }
}
