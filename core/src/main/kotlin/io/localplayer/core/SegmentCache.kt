package io.localplayer.core

import java.io.File
import java.io.IOException
import java.io.DataInputStream
import java.security.MessageDigest

/** Only complete, verified original-byte segments enter this private, bounded cache. */
class SegmentCache(private val directory: File, private val maxBytes: Long = 256L * 1024 * 1024,
                   private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
                   private val now: () -> Long = System::currentTimeMillis) {
    init { require(maxBytes > 0 && maxAgeMs > 0) }
    @Volatile private var generation = 0L
    @Synchronized fun generation(): Long = generation
    private fun name(identity: String, start: Long, length: Int) = digest("$identity:$start:$length".toByteArray()) + ".part"
    @Synchronized fun get(identity: String, start: Long, length: Int): ByteArray? {
        val file = File(directory, name(identity, start, length))
        return try {
            trim(0)
            if (!file.isFile) return null
            if (now() - file.lastModified() > maxAgeMs || file.length() != length.toLong() + 32) {
                file.delete(); return null
            }
            val expected = ByteArray(32)
            val content = ByteArray(length)
            DataInputStream(file.inputStream()).use { input ->
                input.readFully(expected)
                input.readFully(content)
            }
            if (!MessageDigest.isEqual(expected, sha(content))) {
                file.delete(); return null
            }
            file.setLastModified(now())
            content
        } catch (_: IOException) { null }
    }
    @Synchronized fun put(identity: String, start: Long, bytes: ByteArray, expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        if (bytes.size.toLong() + 32 > maxBytes) return
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            trim(bytes.size.toLong() + 32)
            val file = File(directory, name(identity, start, bytes.size))
            temporary = File.createTempFile("pending-", ".tmp", directory)
            temporary.outputStream().use { it.write(sha(bytes)); it.write(bytes) }
            if (!temporary.renameTo(file)) return
            file.setLastModified(now())
            trim(0)
        } catch (_: IOException) { /* A full or evicted cache must not interrupt playback. */ }
        finally { temporary?.delete() }
    }
    @Synchronized fun clear() { generation++; files().forEach { it.delete() } }
    @Synchronized fun bytes(): Long { trim(0); return files().sumOf { it.length() } }
    private fun files() = directory.listFiles()?.filter { it.isFile && (it.name.endsWith(".part") || it.name.startsWith("pending-")) }.orEmpty()
    private fun trim(reserve: Long) {
        val files = files().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (now() - file.lastModified() > maxAgeMs || total > maxBytes - reserve || file.name.startsWith("pending-")) {
                val length = file.length()
                if (file.delete()) total -= length
            }
        }
    }
    companion object {
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun digest(bytes: ByteArray): String = sha(bytes).joinToString("") { "%02x".format(it) }
    }
}
