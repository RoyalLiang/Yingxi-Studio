package io.localplayer.app

import android.content.Context
import android.os.Build
import android.view.Surface
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Only signed, build-pinned code is loaded. The child owns a separate FFmpeg namespace. */
internal class MpvRuntime private constructor(private val bridge:Class<*>) {
    private val methods=java.util.concurrent.ConcurrentHashMap(bridge.methods.associateBy{it.name})
    private fun call(name:String,vararg args:Any?):Any? {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"Native mpv calls must use the playback worker"}
        return methods.getValue(name).invoke(null,*args)
    }
    fun create(context:Context){call("create",context.applicationContext)}
    fun init(){call("init")}
    fun destroy(){call("destroy")}
    fun attach(surface:Surface){call("attachSurface",surface)}
    fun detach(){call("detachSurface")}
    fun command(vararg args:String){call("command",args)}
    fun option(name:String,value:String){check(call("setOptionString",name,value)==0){"兼容播放初始化失败"}}
    fun get(name:String)=call("getPropertyString",name) as String?
    fun set(name:String,value:String){call("setPropertyString",name,value)}

    companion object {
        @Volatile private var loaded:MpvRuntime?=null
        @Synchronized fun load(context:Context):MpvRuntime {
            loaded?.let{return it}
            val spec=JSONObject(context.assets.open("mpv-native.json").bufferedReader().use{it.readText()})
            // A 32-bit APK can run on a 64-bit device. Identify the installed split,
            // rather than selecting the device's first preferred ABI.
            val installed=File(context.applicationInfo.nativeLibraryDir,"libyingxi_mpv_mpv.so")
            val fingerprint=sha(installed)
            val abi=Build.SUPPORTED_ABIS.firstOrNull{spec.has(it)&&spec.getJSONObject(it).optString("libmpv.so")==fingerprint}
                ?:error("此架构缺少兼容播放组件")
            val entries=spec.getJSONObject(abi)
            val dex=context.assets.open("mpv-bridge.jar").use{it.readBytes()}
            val digest=context.assets.open("mpv-bridge.sha256").bufferedReader().use{it.readText().trim()}
            check(shaBytes(dex)==digest){"兼容播放组件校验失败"}
            val dir=File(context.codeCacheDir,"mpv-$digest-$abi").apply{check(mkdirs()||isDirectory)}
            entries.keys().forEach { name ->
                require(Regex("lib[\\w+]+\\.so").matches(name))
                val source=File(context.applicationInfo.nativeLibraryDir,"libyingxi_mpv_"+name.removePrefix("lib"))
                val expected=entries.getString(name)
                check(sha(source)==expected){"兼容播放组件校验失败"}
                verifiedFileFromFile(dir,name,source,expected)
            }
            val jar=verifiedFile(dir,"bridge.jar",dex,digest)
            val loader=DexClassLoader(jar.path,dir.path,dir.path,context.classLoader)
            val type=Class.forName("is.xyz.mpv.MPVLib",true,loader)
            check(type.classLoader===loader){"兼容播放组件隔离失败"}
            return MpvRuntime(type).also{loaded=it}
        }
        private fun shaBytes(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        private fun sha(file:File):String {
            val md=MessageDigest.getInstance("SHA-256")
            val buf=ByteArray(64*1024)
            file.inputStream().use { input ->
                while(true){val n=input.read(buf);if(n<0)break;md.update(buf,0,n)}
            }
            return md.digest().joinToString(""){"%02x".format(it)}
        }
        private fun verifiedFileFromFile(dir:File,name:String,source:File,digest:String):File {
            val file=File(dir,name)
            if(file.exists()&&!file.canWrite()&&file.length()==source.length()&&sha(file)==digest)return file
            check(!file.exists()||file.delete())
            // Android 14+: mark read-only immediately after opening, before writing code.
            FileOutputStream(file).use { output ->
                check(file.setReadOnly())
                val buf=ByteArray(64*1024)
                source.inputStream().use { input ->
                    while(true){val n=input.read(buf);if(n<0)break;output.write(buf,0,n)}
                }
                output.fd.sync()
            }
            check(sha(file)==digest)
            return file
        }
        private fun verifiedFile(dir:File,name:String,data:ByteArray,digest:String):File {
            val file=File(dir,name)
            if(file.exists()&&!file.canWrite()&&file.length()==data.size.toLong()&&sha(file)==digest)return file
            check(!file.exists()||file.delete())
            // Android 14+: mark read-only immediately after opening, before writing code.
            FileOutputStream(file).use { output -> check(file.setReadOnly());output.write(data);output.fd.sync() }
            check(sha(file)==digest)
            return file
        }
    }
}
