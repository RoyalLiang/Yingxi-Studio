package io.localplayer.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class UpdateTransfer(val release:AppRelease,val busy:Boolean=false,val progress:Float=0f,val ready:File?=null,val message:String="",val error:Boolean=false)

/** One process-wide owner, with durable release identity and on-disk bytes across recreation. */
internal object UpdateDownloads {
    private val mutable=MutableStateFlow<UpdateTransfer?>(null)
    val state=mutable.asStateFlow()
    private var restored=false
    internal var clientFactory:(Context)->AppUpdates={AppUpdates(it)}
    private fun preferences(context:Context)=context.getSharedPreferences("update-transfer",Context.MODE_PRIVATE)
    fun restore(context:Context) {
        if(restored)return
        restored=true
        runCatching {
            val prefs=preferences(context)
            val raw=JSONObject(prefs.getString("release",null)?:return)
            val release=AppRelease(AppVersion(raw.getString("name"),raw.getLong("code")),raw.getString("notes"),raw.getString("url"),raw.getLong("size"),raw.getString("sha256"),raw.getString("signer"))
            if(release.version.code<=installedVersion(context).code){prefs.edit().clear().commit();return}
            val directory=File(context.filesDir,"updates").canonicalFile
            val ready=prefs.getString("ready",null)?.let(::File)?.takeIf{it.isFile&&it.canonicalFile.parentFile==directory&&it.name.endsWith(".apk")}
            val fraction=(AppUpdates.partialFile(context,release).length().toFloat()/release.size).coerceIn(0f,1f)
            mutable.value=UpdateTransfer(release,progress=if(ready!=null)1f else fraction,ready=ready,message=if(ready!=null)"安装包已保存，可以安装更新"else"下载已暂停，已保存进度，点击继续下载")
        }
    }
    private fun save(context:Context,value:UpdateTransfer) {
        val release=value.release
        val raw=JSONObject().put("name",release.version.name).put("code",release.version.code).put("notes",release.notes).put("url",release.url).put("size",release.size).put("sha256",release.sha256).put("signer",release.signer)
        check(preferences(context).edit().putString("release",raw.toString()).putString("ready",value.ready?.absolutePath).commit()){"无法保存下载进度"}
    }
    fun start(context:Context,release:AppRelease) {
        restore(context)
        if(mutable.value?.busy==true)return
        val value=UpdateTransfer(release,progress=(AppUpdates.partialFile(context,release).length().toFloat()/release.size).coerceIn(0f,1f),message="正在下载安装包…")
        save(context,value)
        mutable.value=value.copy(busy=true)
        try{ContextCompat.startForegroundService(context,Intent(context,UpdateDownloadService::class.java))}
        catch(e:Exception){mutable.value=value.copy(error=true,message="无法启动后台下载，请重试");throw e}
    }
    fun pause(context:Context){context.stopService(Intent(context,UpdateDownloadService::class.java))}
    fun update(value:UpdateTransfer?){mutable.value=value}
    fun complete(context:Context,value:UpdateTransfer){save(context,value);mutable.value=value}
}

/** User-started APK transfer continues while the Activity is backgrounded or the screen is off. */
class UpdateDownloadService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var lock:PowerManager.WakeLock?=null
    private var lastPercent=-1
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        UpdateDownloads.restore(this)
        if(intent?.action==PAUSE){pause();return START_NOT_STICKY}
        val initial=UpdateDownloads.state.value
        if(initial==null){stopSelf();return START_NOT_STICKY}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"应用更新下载",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        startForeground(NOTIFICATION,notification(initial.progress))
        if(job?.isActive==true)return START_NOT_STICKY
        val running=initial.copy(busy=true,ready=null,error=false,message="正在下载安装包，可切换应用或熄屏")
        UpdateDownloads.update(running)
        lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Yingxi:UpdateDownload").apply{setReferenceCounted(false);acquire(31*60*1000L)}
        job=scope.launch {
            try {
                val ready=withTimeout(30*60*1000L) {
                    var attempt=0
                    while(true) {
                        try {return@withTimeout UpdateDownloads.clientFactory(this@UpdateDownloadService).download(initial.release){value->
                            withContext(Dispatchers.Main){
                                UpdateDownloads.update(running.copy(progress=value))
                                val percent=(value*100).toInt()
                                if(percent!=lastPercent){lastPercent=percent;if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(value))}
                            }
                        }} catch(e:IOException){
                            if(++attempt>=3)throw e
                            delay(1000L*attempt)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                UpdateDownloads.complete(this@UpdateDownloadService,running.copy(busy=false,progress=1f,ready=ready,message="安装包已校验，可以安装更新"))
            } catch(e:TimeoutCancellationException){paused("下载已暂停，已保存进度，点击继续下载")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){paused((e.message?:"下载失败")+"；已保存进度，可继续下载",true)}
            finally {releaseLock();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        return START_NOT_STICKY
    }
    private fun paused(message:String="下载已暂停，已保存进度，点击继续下载",error:Boolean=false) {
        UpdateDownloads.state.value?.let{value->
            val progress=(AppUpdates.partialFile(this,value.release).length().toFloat()/value.release.size).coerceIn(0f,1f)
            UpdateDownloads.update(value.copy(busy=false,progress=progress,message=message,error=error))
        }
    }
    private fun pause(){paused();job?.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onTimeout(startId:Int,fgsType:Int){pause()}
    override fun onDestroy(){if(UpdateDownloads.state.value?.busy==true)paused();scope.cancel();releaseLock();super.onDestroy()}
    private fun releaseLock(){lock?.let{if(it.isHeld)it.release()};lock=null}
    private fun notification(progress:Float):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,1,Intent(this,UpdateDownloadService::class.java).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_brand_foreground).setContentTitle("映汐放映室 · 下载更新")
            .setContentText("已下载 ${(progress*100).toInt()}% · 中断后可继续").setProgress(100,(progress*100).toInt(),false).setOngoing(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null,"暂停",pause).build()).setOnlyAlertOnce(true).build()
    }
    companion object {private const val CHANNEL="app-updates";private const val NOTIFICATION=7301;private const val PAUSE="io.localplayer.app.PAUSE_UPDATE"}
}
