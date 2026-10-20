package io.localplayer.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.localplayer.core.Protocol
import io.localplayer.core.Source
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sources-v1", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("secrets-v1", Context.MODE_PRIVATE)
    private val presentation = context.getSharedPreferences("source-presentation-v1", Context.MODE_PRIVATE)
    private companion object { val orderLock=Any() }
    fun pinnedIds(): Set<String> = presentation.getStringSet("pinned",emptySet())?.toSet() ?: emptySet()
    fun load(): List<Source> = runCatching {
        val a = JSONArray(prefs.getString("sources", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Source(
            id=o.getString("id"), label=o.getString("label"), protocol=Protocol.valueOf(o.getString("protocol")),
            host=o.getString("host"), port=o.getInt("port"), root=o.getString("root"), username=o.optString("username"),
            domain=o.optString("domain"), trustedKey=o.optString("trustedKey").takeIf(String::isNotBlank),
            trustedAuthority=o.optString("trustedAuthority").takeIf(String::isNotBlank),initialPath=o.optString("initialPath"),secure=o.optBoolean("secure",true),userAgent=o.optString("userAgent"),referer=o.optString("referer")) } }
    }.getOrDefault(emptyList()).sortedBy { if(it.id in pinnedIds())0 else 1 }
    fun togglePinned(id:String):Boolean = synchronized(orderLock) {
        val existing=load().map{it.id}.toSet()
        if(id !in existing)return@synchronized false
        val pins=pinnedIds().intersect(existing).toMutableSet()
        if(id in pins)pins.remove(id) else { if(pins.size>=3)return@synchronized false;pins.add(id) }
        presentation.edit().putStringSet("pinned",pins).apply()
        true
    }
    fun save(source: Source, password: String, remember: Boolean, stillCurrent: () -> Boolean = { true }) = synchronized(orderLock) {
        // Encrypt before updating the profile. Never fall back to plaintext on Keystore failure.
        val ciphertext = if (remember) encrypt(password) else null
        if (!stillCurrent()) return@synchronized
        secrets.edit().apply { if (ciphertext == null) remove(source.id) else putString(source.id, ciphertext) }.apply()
        val sources = load().filterNot { it.id == source.id } + source
        val a=JSONArray()
        sources.forEach { s -> a.put(JSONObject().put("id",s.id).put("label",s.label).put("protocol",s.protocol.name)
            .put("host",s.host).put("port",s.port).put("root",s.root).put("username",s.username).put("domain",s.domain)
            .put("trustedKey",s.trustedKey ?: "").put("trustedAuthority",s.trustedAuthority ?: "").put("initialPath",s.initialPath).put("secure",s.secure).put("userAgent",s.userAgent).put("referer",s.referer)) }
        prefs.edit().putString("sources",a.toString()).apply()
    }
    fun delete(id: String) = synchronized(orderLock) {
        val a=JSONArray(prefs.getString("sources","[]"));val next=JSONArray()
        for(i in 0 until a.length()) if(a.getJSONObject(i).getString("id")!=id) next.put(a.getJSONObject(i))
        prefs.edit().putString("sources",next.toString()).apply();secrets.edit().remove(id).apply()
        synchronized(orderLock){presentation.edit().putStringSet("pinned",pinnedIds()-id).apply()}
    }
    fun password(id: String): String? = secrets.getString(id,null)?.let {
        try { decrypt(it) } catch (_: Exception) { throw java.io.IOException("本机凭据无法解密，请重新输入密码") }
    }
    fun hasPassword(id:String)=secrets.contains(id)
    fun forgetPosition(key:String){prefs.edit().remove("position-"+hash(key)).apply()}
    fun clearPositions(){prefs.edit().apply{prefs.all.keys.filter{it.startsWith("position-")}.forEach{remove(it)}}.apply()}
    fun rememberPosition(uriKey: String, position: Long) { prefs.edit().putLong("position-"+hash(uriKey),position).apply() }
    fun position(uriKey: String): Long = prefs.getLong("position-"+hash(uriKey),0)
    private fun hash(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("local-player-secrets-v1",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("local-player-secrets-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(s: String): String {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(c.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(s.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(s: String): String {
        val parts=s.split(':',limit=2);val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }
}
