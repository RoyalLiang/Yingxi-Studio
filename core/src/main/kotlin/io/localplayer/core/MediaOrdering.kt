package io.localplayer.core

import java.util.Locale

/** One order for directory browsing and playback queues. Missing birth time stays unknown. */
object MediaOrdering {
    fun compareNames(first: String, second: String): Int {
        val a = first.lowercase(Locale.ROOT)
        val b = second.lowercase(Locale.ROOT)
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val ai = i
                val bj = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                val x = a.substring(ai, i).trimStart('0').ifEmpty { "0" }
                val y = b.substring(bj, j).trimStart('0').ifEmpty { "0" }
                val result = x.length.compareTo(y.length).takeIf { it != 0 } ?: x.compareTo(y)
                if (result != 0) return result
            } else {
                val result = a[i].compareTo(b[j])
                if (result != 0) return result
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun compare(nameA: String, timeA: Long?, pathA: String, nameB: String, timeB: Long?, pathB: String): Int =
        compareNames(nameA, nameB).takeIf { it != 0 }
            ?: (timeA ?: Long.MAX_VALUE).compareTo(timeB ?: Long.MAX_VALUE).takeIf { it != 0 }
            ?: pathA.compareTo(pathB)

    val entries: Comparator<Entry> = Comparator { a, b ->
        b.directory.compareTo(a.directory).takeIf { it != 0 }
            ?: compare(a.name, a.createdAt, a.path, b.name, b.createdAt, b.path)
    }
}
