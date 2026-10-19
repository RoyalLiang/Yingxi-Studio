package io.localplayer.app

import android.app.Activity
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import io.localplayer.core.Source
import java.util.UUID

data class PrivateSourceRequest(val source:Source,val id:String=UUID.randomUUID().toString())

/** This is a one-time authenticated open action, not a persistent source lock or incognito mode. */
@Composable
internal fun PrivateSourceAccessPrompt(vm:LibraryViewModel){
    val request=vm.privateRequest ?: return
    val context=LocalContext.current
    DisposableEffect(request.id){
        val activity=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()
        val cancellation=CancellationSignal()
        val authenticators=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(activity==null||activity.getSystemService(BiometricManager::class.java).canAuthenticate(authenticators)!=BiometricManager.BIOMETRIC_SUCCESS){
            vm.cancelPrivateAccess(request.id,"请先在系统设置中配置指纹或锁屏密码，再使用私密访问")
        }else{
            val prompt=BiometricPrompt.Builder(activity).setTitle("私密访问").setSubtitle(request.source.label)
                .setDescription("验证本次访问，然后打开来源。播放记录按正常规则保存。")
                .setAllowedAuthenticators(authenticators).build()
            prompt.authenticate(cancellation,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){vm.completePrivateAccess(request.id)}
                override fun onAuthenticationError(code:Int,message:CharSequence){
                    val canceled=code==BiometricPrompt.BIOMETRIC_ERROR_CANCELED||code==BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                    vm.cancelPrivateAccess(request.id,if(canceled)null else "系统验证未完成，请重试或使用锁屏密码")
                }
            })
        }
        onDispose{cancellation.cancel()}
    }
}
