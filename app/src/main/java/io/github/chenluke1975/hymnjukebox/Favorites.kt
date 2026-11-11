package io.github.chenluke1975.hymnjukebox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A favorite is either a numbered hymn (opened on 影音美地) or a store album preview. */
data class Favorite(val kind: String, val title: String, val book: Book? = null, val number: Int = 0, val path: String = "") {
    val key: String get() = when (kind) {
        KIND_HYMN -> "h:${book?.name}:$number"
        KIND_LOCAL -> "l:$path"
        else -> "a:$path"
    }

    companion object {
        const val KIND_HYMN = "hymn"
        const val KIND_ALBUM = "album"
        const val KIND_LOCAL = "local"
        fun local(s: LocalSong, label: String) = Favorite(KIND_LOCAL, label, s.book, s.number ?: 0, s.uri)
        fun hymn(book: Book, number: Int, title: String = "") = Favorite(KIND_HYMN, title, book, number)
        fun album(a: Album) = Favorite(KIND_ALBUM, a.title, path = a.path)
    }
}

class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<Favorite> {
        val a = JSONArray(prefs.getString("list", "[]"))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            try {
                Favorite(
                    o.getString("k"), o.optString("t"),
                    o.optString("b").takeIf { it.isNotEmpty() }?.let { Book.valueOf(it) },
                    o.optInt("n"), o.optString("p"),
                )
            } catch (_: Exception) { null }
        }
    }

    private fun save(list: List<Favorite>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("k", it.kind).put("t", it.title).put("b", it.book?.name ?: "")
                .put("n", it.number).put("p", it.path))
        }
        prefs.edit().putString("list", a.toString()).apply()
    }

    fun contains(f: Favorite) = all().any { it.key == f.key }

    /** Adds or removes; returns true if it is now a favorite. */
    fun toggle(f: Favorite): Boolean {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.key == f.key }
        return if (i >= 0) { list.removeAt(i); save(list); false } else { list.add(0, f); save(list); true }
    }
}
