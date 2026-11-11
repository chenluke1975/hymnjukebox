package io.github.chenluke1975.hymnjukebox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads the public pages of the official 台灣福音書房 sites at run time.
 * Nothing (no audio, lyrics or scores) is bundled in the app; only page links and titles
 * are cached on the phone so the official sites are not asked again on every launch.
 */
class Catalog(context: Context) {
    private val dir = File(context.cacheDir, "catalog").apply { mkdirs() }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36 HymnJukebox/1.0"
        private const val WEEK = 7L * 24 * 3600 * 1000
        private val lock = Any()
        private var lastRequest = 0L
    }

    /** GET an official page politely (one request at a time, small gap, retry on "busy"). */
    @Throws(IOException::class)
    fun fetch(url: String): String {
        require(Sites.isOfficial(url)) { "not an official URL: $url" }
        var last: IOException? = null
        repeat(4) { attempt ->
            synchronized(lock) {
                val wait = lastRequest + 1200 - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastRequest = System.currentTimeMillis()
            }
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 25000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9")
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val body = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    if (!Parsers.isBusyPage(body)) return body
                    last = IOException("site busy")
                } finally { c.disconnect() }
            } catch (e: IOException) { last = e }
            Thread.sleep(2500L * (attempt + 1))
        }
        throw last ?: IOException("failed")
    }

    private fun cached(name: String, maxAge: Long = WEEK): String? {
        val f = File(dir, name)
        return if (f.isFile && System.currentTimeMillis() - f.lastModified() < maxAge) f.readText() else null
    }
    private fun store(name: String, text: String) { File(dir, name).writeText(text) }
    private fun stale(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    // ---------- 影音美地 詩歌伴奏 volumes ----------

    fun volumes(refresh: Boolean = false): List<Volume> {
        if (!refresh) cached("volumes.json")?.let { return volumesFromJson(it) }
        return try {
            val v = Parsers.parseVolumes(fetch(Sites.GOODLAND_INDEX))
            if (v.size < 10) throw IOException("index changed")
            store("volumes.json", volumesToJson(v)); v
        } catch (e: Exception) {
            stale("volumes.json")?.let { volumesFromJson(it) } ?: Parsers.FALLBACK_VOLUMES
        }
    }

    private fun volumesToJson(v: List<Volume>) = JSONArray().apply {
        v.forEach { put(JSONObject().put("b", it.book.name).put("f", it.from).put("t", it.to).put("id", it.courseId)) }
    }.toString()

    private fun volumesFromJson(s: String): List<Volume> {
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Volume(Book.valueOf(it.getString("b")), it.getInt("f"), it.getInt("t"), it.getInt("id"))
        }
    }

    /** Titles shown on one official volume page (some volumes only show "第N首"). */
    fun unitTitles(v: Volume): Map<Int, String> {
        val name = "vol-${v.courseId}.json"
        cached(name, 30 * WEEK)?.let { return mapFromJson(it) }
        val m = Parsers.parseUnitTitles(fetch(v.url))
        store(name, JSONObject().apply { m.forEach { (k, t) -> put(k.toString(), t) } }.toString())
        return m
    }

    private fun mapFromJson(s: String): Map<Int, String> {
        val o = JSONObject(s)
        return o.keys().asSequence().associate { it.toInt() to o.getString(it) }
    }

    // ---------- Store albums with free previews ----------

    fun albums(refresh: Boolean = false): List<Album> {
        if (!refresh) cached("albums.json")?.let { return albumsFromJson(it) }
        val all = LinkedHashMap<String, Album>()
        var error: Exception? = null
        for (cat in Sites.ALBUM_CATEGORIES) {
            try {
                val url = "${Sites.STORE}/categories/${URLEncoder.encode(cat, "UTF-8").replace("+", "%20")}?limit=72"
                Parsers.parseAlbums(fetch(url)).forEach { all.putIfAbsent(it.path, it) }
            } catch (e: Exception) { error = e }
        }
        if (all.isEmpty()) {
            stale("albums.json")?.let { return albumsFromJson(it) }
            throw IOException("cannot load albums", error)
        }
        val list = all.values.sortedBy { it.title }
        store("albums.json", JSONArray().apply {
            list.forEach { put(JSONObject().put("t", it.title).put("p", it.path)) }
        }.toString())
        return list
    }

    private fun albumsFromJson(s: String): List<Album> {
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Album(it.getString("t"), it.getString("p")) }
    }

    fun albumDetail(album: Album): AlbumDetail {
        val name = "album-" + album.path.hashCode().toUInt().toString(16) + ".json"
        cached(name, 4 * WEEK)?.let {
            val o = JSONObject(it)
            val t = o.getJSONArray("tracks")
            return AlbumDetail(o.optString("mp3").ifEmpty { null }, (0 until t.length()).map { i -> t.getString(i) })
        }
        val d = Parsers.parseAlbumDetail(fetch(album.url))
        store(name, JSONObject().put("mp3", d.mp3 ?: "").put("tracks", JSONArray(d.tracks)).toString())
        return d
    }

    fun cachedAlbumDetail(album: Album): AlbumDetail? {
        val name = "album-" + album.path.hashCode().toUInt().toString(16) + ".json"
        val s = stale(name) ?: return null
        val o = JSONObject(s)
        val t = o.getJSONArray("tracks")
        return AlbumDetail(o.optString("mp3").ifEmpty { null }, (0 until t.length()).map { i -> t.getString(i) })
    }
}
