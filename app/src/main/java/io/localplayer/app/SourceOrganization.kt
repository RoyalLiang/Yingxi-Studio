package io.localplayer.app

import android.content.Context
import io.localplayer.core.Paths
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SourceGroup(val id: String, val name: String)
data class SourceBookmark(val sourceId: String, val path: String, val label: String, val groupId: String? = null, val favorite: Boolean = false) {
    val key get() = "$sourceId:$path"
}

/** Display organization only. Passwords and source identity remain owned by SourceStore. */
class SourceOrganization(context: Context) {
    private val prefs = MigratingPreferences.open(context,"source-organization-v1")
    fun groups(): List<SourceGroup> = JSONArray(prefs.getString("groups", "[]")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { SourceGroup(it.getString("id"), it.getString("name")) }
    }
    fun bookmarks(): List<SourceBookmark> = JSONArray(prefs.getString("bookmarks", "[]")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { SourceBookmark(it.getString("source"), Paths.clean(it.getString("path")), it.getString("label"), it.optString("group").takeIf(String::isNotEmpty), it.optBoolean("favorite")) }
    }
    fun group(name: String, id: String? = null) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 40) { "分册名称需为 1–40 个字符" }
        val rows = groups()
        require(rows.none { it.id != id && it.name.equals(clean, true) }) { "已有同名分册" }
        saveGroups(rows.filterNot { it.id == id } + SourceGroup(id ?: UUID.randomUUID().toString(), clean))
    }
    fun deleteGroup(id: String) {
        saveGroups(groups().filterNot { it.id == id })
        saveBookmarks(bookmarks().map { if (it.groupId == id) it.copy(groupId = null) else it })
    }
    fun put(item: SourceBookmark) {
        require(item.groupId == null || groups().any { it.id == item.groupId }) { "分册已不存在" }
        val normalized = item.copy(path = Paths.clean(item.path))
        saveBookmarks(bookmarks().filterNot { it.key == normalized.key } + normalized)
    }
    fun remove(key: String) = saveBookmarks(bookmarks().filterNot { it.key == key })
    fun removeSource(sourceId: String) = saveBookmarks(bookmarks().filterNot { it.sourceId == sourceId })
    fun moveSourceEntry(sourceId: String, oldPath: String, newPath: String, label: String) {
        if (oldPath == newPath) return
        val rows = bookmarks()
        val old = rows.firstOrNull { it.sourceId == sourceId && it.path == oldPath } ?: return
        val target = rows.firstOrNull { it.sourceId == sourceId && it.path == newPath }
        val moved = old.copy(path = newPath, label = label, favorite = old.favorite || target?.favorite == true,
            groupId = target?.groupId ?: old.groupId)
        saveBookmarks(rows.filterNot { it.key == old.key || it.key == moved.key } + moved)
    }
    private fun saveGroups(rows: List<SourceGroup>) {
        val a = JSONArray(); rows.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString("groups", a.toString()).apply()
    }
    private fun saveBookmarks(rows: List<SourceBookmark>) {
        val a = JSONArray(); rows.forEach { a.put(JSONObject().put("source", it.sourceId).put("path", it.path).put("label", it.label).put("group", it.groupId ?: "").put("favorite", it.favorite)) }
        prefs.edit().putString("bookmarks", a.toString()).apply()
    }
}
