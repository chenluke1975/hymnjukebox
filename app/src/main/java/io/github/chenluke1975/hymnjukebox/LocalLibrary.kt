package io.github.chenluke1975.hymnjukebox

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One audio file found in a folder the user picked. Only stored on the phone. */
data class LocalSong(
    val uri: String,
    val path: String,          // folder path below the picked folder + file name, for display
    val parsedBook: Book,
    val parsedNumber: Int?,
    val parsedTitle: String,
    val book: Book,            // effective (after manual fix)
    val number: Int?,
    val title: String,
    val manual: Boolean,
) {
    val fileName: String get() = path.substringAfterLast('/')
    fun label(ctx: Context): String {
        val head = if (number != null) ctx.getString(R.string.hymn_title, book.zh, number) else ctx.getString(R.string.no_number)
        return if (title.isNotEmpty()) "$head　$title" else head
    }
}

/**
 * Folders chosen with the system folder picker (Storage Access Framework). The app keeps a
 * persistable read permission for each folder, so no storage permission is needed.
 * The index is a JSON file in the app's private storage; manual fixes survive rescans.
 */
class LocalLibrary private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: LocalLibrary? = null
        fun get(ctx: Context): LocalLibrary =
            instance ?: synchronized(this) { instance ?: LocalLibrary(ctx.applicationContext).also { instance = it } }

        fun sortKey(s: LocalSong): String =
            "%d%05d%s".format(s.book.ordinal, s.number ?: 99999, s.path.lowercase())
    }

    private val prefs = ctx.getSharedPreferences("local", Context.MODE_PRIVATE)
    private val indexFile = File(ctx.filesDir, "local_index.json")
    private val overridesFile = File(ctx.filesDir, "local_overrides.json")

    @Volatile var songs: List<LocalSong> = emptyList(); private set
    private var loaded = false

    var preferLocal: Boolean
        get() = prefs.getBoolean("prefer_local", true)
        set(v) { prefs.edit().putBoolean("prefer_local", v).apply() }

    // ---------------- folders

    fun folders(): List<Uri> = JSONArray(prefs.getString("folders", "[]")).let { a ->
        (0 until a.length()).map { Uri.parse(a.getString(it)) }
    }

    fun addFolder(tree: Uri) {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val list = folders().map { it.toString() }.toMutableList()
        if (tree.toString() !in list) list += tree.toString()
        prefs.edit().putString("folders", JSONArray(list).toString()).apply()
    }

    fun removeFolder(tree: Uri) {
        runCatching { ctx.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val list = folders().map { it.toString() }.filter { it != tree.toString() }
        prefs.edit().putString("folders", JSONArray(list).toString()).apply()
        synchronized(this) {
            songs = songs.filter { !it.uri.startsWith(treePrefix(tree)) }
            saveIndex()
        }
    }

    private fun treePrefix(tree: Uri): String =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
            .substringBefore("/document/") + "/document/"

    fun folderName(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault(tree.toString())
        // e.g. "primary:Music/詩歌" -> "內部儲存空間/Music/詩歌"
        val (vol, path) = if (':' in id) id.substringBefore(':') to id.substringAfter(':') else "" to id
        val volName = if (vol == "primary") ctx.getString(R.string.internal_storage) else vol
        return listOf(volName, path).filter { it.isNotEmpty() }.joinToString("/")
    }

    // ---------------- index

    @Synchronized fun load(): List<LocalSong> {
        if (loaded) return songs
        loaded = true
        songs = runCatching {
            val a = JSONArray(indexFile.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val pb = Book.valueOf(o.getString("pb"))
                val pn = if (o.has("pn")) o.getInt("pn") else null
                val pt = o.optString("pt")
                withOverride(o.getString("u"), o.getString("p"), pb, pn, pt)
            }
        }.getOrDefault(emptyList()).sortedBy { sortKey(it) }
        return songs
    }

    private fun saveIndex() {
        val a = JSONArray()
        songs.forEach { s ->
            val o = JSONObject().put("u", s.uri).put("p", s.path).put("pb", s.parsedBook.name).put("pt", s.parsedTitle)
            if (s.parsedNumber != null) o.put("pn", s.parsedNumber)
            a.put(o)
        }
        indexFile.writeText(a.toString())
    }

    private fun overrides(): JSONObject = runCatching { JSONObject(overridesFile.readText()) }.getOrDefault(JSONObject())

    private fun withOverride(uri: String, path: String, pb: Book, pn: Int?, pt: String, ov: JSONObject = overrides()): LocalSong {
        val o = ov.optJSONObject(uri)
        if (o == null) return LocalSong(uri, path, pb, pn, pt, pb, pn, pt, false)
        val b = runCatching { Book.valueOf(o.getString("b")) }.getOrDefault(pb)
        val n = if (o.has("n") && !o.isNull("n")) o.getInt("n") else null
        val t = if (o.has("t")) o.getString("t") else pt
        return LocalSong(uri, path, pb, pn, pt, b, n, t, true)
    }

    /** Manual fix of number / hymnal / title. number = null marks it as "no number". */
    @Synchronized fun setManual(song: LocalSong, book: Book, number: Int?, title: String) {
        val ov = overrides()
        ov.put(song.uri, JSONObject().put("b", book.name).put("n", number ?: JSONObject.NULL).put("t", title.trim()))
        overridesFile.writeText(ov.toString())
        songs = songs.map { if (it.uri == song.uri) withOverride(it.uri, it.path, it.parsedBook, it.parsedNumber, it.parsedTitle, ov) else it }
            .sortedBy { sortKey(it) }
    }

    @Synchronized fun clearManual(song: LocalSong) {
        val ov = overrides()
        ov.remove(song.uri)
        overridesFile.writeText(ov.toString())
        songs = songs.map { if (it.uri == song.uri) withOverride(it.uri, it.path, it.parsedBook, it.parsedNumber, it.parsedTitle, ov) else it }
            .sortedBy { sortKey(it) }
    }

    fun find(book: Book, number: Int): LocalSong? = load().firstOrNull { it.book == book && it.number == number }

    fun byUri(uri: String): LocalSong? = load().firstOrNull { it.uri == uri }

    /** Songs with a number, in hymnal + number order (the order used for next / previous). */
    fun playable(): List<LocalSong> = load().filter { it.number != null }

    // ---------------- scanning

    private val wmaSupported: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { ci ->
                !ci.isEncoder && ci.supportedTypes.any { it.equals("audio/x-ms-wma", true) || it.equals("audio/wma", true) }
            }
        }.getOrDefault(false)
    }

    private fun isAudio(name: String, mime: String?): Boolean {
        val ext = HymnFileName.extension(name)
        if (ext == "wma") return wmaSupported
        if (ext in HymnFileName.AUDIO_EXTENSIONS) return true
        return mime != null && mime.startsWith("audio/") && !mime.contains("mpegurl") && !mime.contains("x-scpls")
    }

    /** Rescans every chosen folder (and subfolders). [progress] gets the number of files found so far. */
    fun scan(progress: (Int) -> Unit = {}): Pair<Int, List<String>> {
        val found = mutableListOf<Triple<String, String, List<String>>>() // uri, name, folders
        val errors = mutableListOf<String>()
        val cr = ctx.contentResolver
        for (tree in folders()) {
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val stack = ArrayDeque<Pair<String, List<String>>>()
                stack.add(rootId to listOf(folderName(tree).substringAfterLast('/')))
                var guard = 0
                while (stack.isNotEmpty() && guard++ < 20000) {
                    val (docId, dirs) = stack.removeLast()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    cr.query(children, arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            val mime = c.getString(2)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (!name.startsWith(".")) stack.add(id to dirs + name)
                            } else if (isAudio(name, mime)) {
                                found += Triple(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, dirs)
                                if (found.size % 25 == 0) progress(found.size)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                errors += folderName(tree)
            }
        }
        val ov = overrides()
        val list = found.distinctBy { it.first }.map { (uri, name, dirs) ->
            val p = HymnFileName.parse(name, dirs)
            withOverride(uri, (dirs.drop(1) + name).joinToString("/"), p.book, p.number, p.title, ov)
        }.sortedBy { sortKey(it) }
        synchronized(this) {
            songs = list
            loaded = true
            saveIndex()
        }
        progress(list.size)
        return list.size to errors
    }
}
