package io.localplayer.core

import java.io.File
import java.io.IOException
import java.io.DataInputStream
import java.security.MessageDigest

/** Only complete, verified original-byte segments enter this private, bounded cache. */
class SegmentCache(private val directory: File, private val maxBytes: Long = 256L * 1024 * 1024,
                   private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
                   private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val file: File, var length: Long, var lastModified: Long)

    init { require(maxBytes > 0 && maxAgeMs > 0) }
    @Volatile private var generation = 0L
    private val entries = LinkedHashMap<String, Entry>()
    private var indexed = false
    private var totalBytes = 0L

    @Synchronized fun generation(): Long = generation
    private fun name(identity: String, start: Long, length: Int) = digest("$identity:$start:$length".toByteArray()) + ".part"

    private fun ensureIndexed() {
        if (indexed) return
        entries.clear(); totalBytes = 0L
        val listed = directory.listFiles().orEmpty()
        listed.filter { it.isFile && it.name.startsWith("pending-") }.forEach { it.delete() }
        listed.filter { it.isFile && it.name.endsWith(".part") }.sortedBy { it.lastModified() }.forEach { file ->
            val len = file.length()
            entries[file.name] = Entry(file, len, file.lastModified())
            totalBytes += len
        }
        indexed = true
    }

    private fun evict(key: String) {
        val removed = entries.remove(key) ?: return
        totalBytes = (totalBytes - removed.length).coerceAtLeast(0L)
    }

    fun get(identity: String, start: Long, length: Int): ByteArray? {
        val content = ByteArray(length)
        return if (getInto(identity, start, length, content, 0)) content else null
    }

    internal fun getInto(identity: String, start: Long, length: Int, dest: ByteArray, destOffset: Int = 0): Boolean {
        val key = name(identity, start, length)
        val expectedLength = length.toLong() + 32L
        val readGen: Long
        val file: File
        synchronized(this) {
            ensureIndexed()
            trim(0)
            readGen = generation
            val tracked = entries[key]
            file = tracked?.file ?: File(directory, key)
            if (!file.isFile) {
                if (tracked != null) evict(key)
                return false
            }
            val actualLength = file.length()
            val modTime = tracked?.lastModified ?: file.lastModified()
            if (now() - modTime > maxAgeMs || actualLength != expectedLength) {
                file.delete(); evict(key); return false
            }
            if (tracked == null) {
                entries[key] = Entry(file, actualLength, modTime)
                totalBytes += actualLength
            }
        }
        val expected = ByteArray(32)
        try {
            DataInputStream(file.inputStream()).use { input ->
                input.readFully(expected)
                input.readFully(dest, destOffset, length)
            }
        } catch (_: IOException) { return false }
        val actualDigest = shaSlice(dest, destOffset, length)
        if (!MessageDigest.isEqual(expected, actualDigest)) {
            synchronized(this) { file.delete(); evict(key) }
            return false
        }
        val touchAt = now()
        synchronized(this) {
            if (generation != readGen) return false
            entries.remove(key)?.let { current ->
                current.lastModified = touchAt
                entries[key] = current
                file.setLastModified(touchAt)
            }
        }
        return true
    }

    fun put(identity: String, start: Long, bytes: ByteArray, expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        val newSize = bytes.size.toLong() + 32L
        if (newSize > maxBytes) return
        val key = name(identity, start, bytes.size)
        val contentDigest = sha(bytes)
        synchronized(this) {
            if (expectedGeneration != generation) return
            if (!directory.isDirectory && !directory.mkdirs()) return
            ensureIndexed()
            trim(newSize)
        }
        val pending: File
        var cleanup = true
        try {
            pending = File.createTempFile("pending-", ".tmp", directory)
        } catch (_: IOException) { return }
        try {
            pending.outputStream().use { it.write(contentDigest); it.write(bytes) }
            val stamp = now()
            synchronized(this) {
                if (expectedGeneration != generation) return
                ensureIndexed()
                evict(key)
                trim(newSize)
                val file = File(directory, key)
                if (!pending.renameTo(file)) return
                cleanup = false
                file.setLastModified(stamp)
                entries[key] = Entry(file, newSize, stamp)
                totalBytes += newSize
                trim(0)
            }
        } catch (_: IOException) { /* A full or evicted cache must not interrupt playback. */ }
        finally { if (cleanup) pending.delete() }
    }

    @Synchronized fun clear() {
        generation++
        entries.clear(); totalBytes = 0L; indexed = true
        files().forEach { it.delete() }
    }

    @Synchronized fun bytes(): Long {
        ensureIndexed(); trim(0); return totalBytes
    }

    private fun files() = directory.listFiles()?.filter { it.isFile && (it.name.endsWith(".part") || it.name.startsWith("pending-")) }.orEmpty()
    private fun trim(reserve: Long) {
        val currentNow = now()
        val expired = entries.entries.filter { currentNow - it.value.lastModified > maxAgeMs }.map { it.key }
        for (key in expired) {
            val entry = entries[key] ?: continue
            if (entry.file.delete() || !entry.file.exists()) evict(key)
        }
        while (totalBytes > maxBytes - reserve && entries.isNotEmpty()) {
            val oldest = entries.entries.minByOrNull { it.value.lastModified } ?: break
            if (oldest.value.file.delete() || !oldest.value.file.exists()) evict(oldest.key)
            else break
        }
    }
    companion object {
        private fun sha(bytes: ByteArray) = shaSlice(bytes, 0, bytes.size)
        private fun shaSlice(bytes: ByteArray, offset: Int, length: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").apply { update(bytes, offset, length) }.digest()
        fun digest(bytes: ByteArray): String = sha(bytes).joinToString("") { "%02x".format(it) }
    }
}
