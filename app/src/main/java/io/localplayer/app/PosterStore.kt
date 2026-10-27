package io.localplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A manifest is published only after its image is complete. App cache eviction cannot remove covers. */
internal class PosterStore(context:Context) {
    companion object {
        private val memory=object:android.util.LruCache<String,Bitmap>(8*1024*1024){
            override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
        }
    }
    private val root=File(context.filesDir,"media-posters-v2").apply{mkdirs()}
    fun metadata(key:String):JSONObject?=runCatching {
        JSONObject(AtomicFile(File(root,"$key.json")).openRead().bufferedReader().use{it.readText()})
    }.getOrNull()
    fun image(data:JSONObject):Bitmap?=runCatching {
        val name=data.getString("image")
        require(name.matches(Regex("[a-f0-9-]+\\.jpg")))
        val file=File(root,name)
        synchronized(memory){
            if(!file.isFile){memory.remove(file.path);null}
            else memory.get(file.path)?:MediaPosters.decode(file.readBytes())?.also{memory.put(file.path,it)}
        }
    }.getOrNull()
    fun save(key:String,bitmap:Bitmap,data:JSONObject) {
        val old=metadata(key)?.optString("image")
        val image=File(root,"$key-${UUID.randomUUID()}.jpg")
        try {
            image.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,88,it));it.fd.sync()}
            update(key,data.put("image",image.name))
        } catch(e:Exception){image.delete();throw e}
        old?.takeIf{it!=image.name&&it.matches(Regex("[a-f0-9-]+\\.jpg"))}?.let{deleteImage(it)}
        trim()
    }
    fun update(key:String,data:JSONObject) {
        val file=AtomicFile(File(root,"$key.json"));val out=file.startWrite()
        try{out.write(data.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun remove(key:String) {
        metadata(key)?.optString("image")?.takeIf{it.matches(Regex("[a-f0-9-]+\\.jpg"))}?.let{deleteImage(it)}
        AtomicFile(File(root,"$key.json")).delete()
    }
    private fun deleteImage(name:String)=synchronized(memory){val file=File(root,name);memory.remove(file.path);file.delete()}
    fun clear()=synchronized(memory){root.listFiles()?.forEach{memory.remove(it.path);it.delete()};Unit}
    private fun trim() {
        var bytes=0L
        root.listFiles()?.filter{it.extension=="json"}?.sortedByDescending{it.lastModified()}?.forEachIndexed { index,file ->
            val data=metadata(file.nameWithoutExtension)?:return@forEachIndexed
            bytes+=File(root,data.optString("image")).length()
            if(index>=256||bytes>128L*1024*1024)remove(file.nameWithoutExtension)
        }
        // Interrupted writes can leave an unreferenced image; leave current writes alone.
        val referenced=root.listFiles()?.filter{it.extension=="json"}?.mapNotNull{metadata(it.nameWithoutExtension)?.optString("image")}?.toSet().orEmpty()
        root.listFiles()?.filter{it.extension=="jpg"&&it.name !in referenced&&System.currentTimeMillis()-it.lastModified()>24L*60*60*1000}?.forEach{deleteImage(it.name)}
    }
}
