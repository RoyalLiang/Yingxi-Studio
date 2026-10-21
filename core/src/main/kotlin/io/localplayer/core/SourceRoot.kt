package io.localplayer.core

/** User-selected browsing boundary, relative to the connection's share/server root. */
class SourceRoot(path: String) {
    val path = Paths.clean(path)

    fun contains(candidate: String): Boolean {
        val clean = runCatching { Paths.clean(candidate) }.getOrElse { return false }
        return path.isEmpty() || clean == path || clean.startsWith("$path/")
    }

    fun clamp(candidate: String): String = Paths.clean(candidate).takeIf(::contains) ?: path

    fun parent(candidate: String): String? {
        val current = clamp(candidate)
        return if (current == path) null else clamp(current.substringBeforeLast('/', ""))
    }

    fun trail(candidate: String): List<String> {
        val current = clamp(candidate)
        val parts = current.removePrefix(path).trimStart('/').split('/').filter(String::isNotEmpty)
        return listOf(path) + parts.indices.map { index ->
            listOf(path, parts.take(index + 1).joinToString("/")).filter(String::isNotEmpty).joinToString("/")
        }
    }
}
