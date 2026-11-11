package io.github.chenluke1975.hymnjukebox

import java.net.URLDecoder

/** Which printed hymnal a number belongs to. Codes match the official abbreviations (詩/補/兒). */
enum class Book(val code: String, val zh: String, val en: String) {
    HYMNS("詩", "詩歌", "Hymns"),
    SUPPLEMENT("補", "補充本", "Supplement"),
    CHILDREN("兒", "兒童詩歌", "Children"),
    NEW_HYMNS("新", "新詩歌", "New Hymns");

    companion object {
        fun fromCode(c: String): Book? = entries.firstOrNull { it.code == c }
        /** Matches the volume titles used on mygoodland.video, e.g. 詩歌 / 補充本 / 兒童. */
        fun fromVolumeLabel(s: String): Book? = when (s) {
            "詩歌" -> HYMNS
            "補充本" -> SUPPLEMENT
            "兒童" -> CHILDREN
            else -> null
        }
    }
}

/** One "volume" page on the official 影音美地 site holding a range of hymn accompaniments. */
data class Volume(val book: Book, val from: Int, val to: Int, val courseId: Int) {
    val url: String get() = "${Sites.GOODLAND}/course-detail/$courseId/"
}

/** An album on the official store that has a free online preview (線上試聽) MP3. */
data class Album(val title: String, val path: String) {
    val url: String get() = Sites.STORE + path
    val sku: String get() = title.substringBefore(' ').trim()
}

data class AlbumDetail(val mp3: String?, val tracks: List<String>)

/** A hymn reference such as (詩75) or (補461) found in an album's track list. */
data class HymnRef(val book: Book, val number: Int)

object Sites {
    const val STORE = "https://www.twgbr.org.tw"
    const val GOODLAND = "https://www.mygoodland.video"
    const val GOODLAND_INDEX = "$GOODLAND/teachers-detail/33/"
    /** Store categories that list hymn albums with online previews. */
    val ALBUM_CATEGORIES = listOf(
        "中英文詩歌", "讚美詩集", "兒童影音", "英文詩歌",
    )

    /** Only official hosts may ever be opened or streamed. */
    fun isOfficial(url: String): Boolean {
        val host = Regex("^https://([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        return host == "twgbr.org.tw" || host.endsWith(".twgbr.org.tw") ||
            host == "twgbr.org" || host.endsWith(".twgbr.org") ||
            host == "mygoodland.video" || host.endsWith(".mygoodland.video")
    }
}

object Parsers {

    /** Built-in fallback copy of the official volume index (only page links, no content). */
    val FALLBACK_VOLUMES: List<Volume> = listOf(
        Volume(Book.HYMNS, 1, 100, 155), Volume(Book.HYMNS, 101, 200, 165),
        Volume(Book.HYMNS, 201, 300, 259), Volume(Book.HYMNS, 301, 400, 260),
        Volume(Book.HYMNS, 401, 500, 261), Volume(Book.HYMNS, 501, 600, 262),
        Volume(Book.HYMNS, 601, 700, 263), Volume(Book.HYMNS, 701, 799, 264),
        Volume(Book.SUPPLEMENT, 1, 37, 370), Volume(Book.SUPPLEMENT, 101, 150, 371),
        Volume(Book.SUPPLEMENT, 201, 258, 372), Volume(Book.SUPPLEMENT, 301, 349, 373),
        Volume(Book.SUPPLEMENT, 401, 470, 374), Volume(Book.SUPPLEMENT, 501, 543, 375),
        Volume(Book.SUPPLEMENT, 601, 629, 376), Volume(Book.SUPPLEMENT, 701, 762, 377),
        Volume(Book.SUPPLEMENT, 801, 880, 378), Volume(Book.SUPPLEMENT, 901, 930, 379),
        Volume(Book.SUPPLEMENT, 1001, 1005, 380),
        Volume(Book.CHILDREN, 1, 17, 395), Volume(Book.CHILDREN, 101, 124, 396),
        Volume(Book.CHILDREN, 201, 212, 397), Volume(Book.CHILDREN, 301, 323, 398),
        Volume(Book.CHILDREN, 401, 445, 399), Volume(Book.CHILDREN, 501, 524, 400),
        Volume(Book.CHILDREN, 601, 621, 401), Volume(Book.CHILDREN, 701, 719, 402),
        Volume(Book.CHILDREN, 801, 836, 403), Volume(Book.CHILDREN, 901, 920, 404),
        Volume(Book.CHILDREN, 1001, 1039, 405), Volume(Book.CHILDREN, 1101, 1118, 406),
        Volume(Book.CHILDREN, 1201, 1232, 407),
    )

    private val volumeLink = Regex(
        """course-detail/(\d+)/(?:%[0-9A-Fa-f]{2}|[^"'\s<>])*""",
    )
    private val volumeTitle = Regex("""^(詩歌|補充本|兒童)第(\d+)至(附?)(\d+)首""")

    /** Parses the 詩歌伴奏 index page on mygoodland.video into volumes. */
    fun parseVolumes(html: String): List<Volume> {
        val out = LinkedHashMap<Int, Volume>()
        for (m in volumeLink.findAll(html)) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val rest = m.value.substringAfter("/$id/", "")
            if (rest.isEmpty()) continue
            val title = try { URLDecoder.decode(rest, "UTF-8") } catch (_: Exception) { rest }
            val t = volumeTitle.find(title) ?: continue
            val book = Book.fromVolumeLabel(t.groupValues[1]) ?: continue
            val from = t.groupValues[2].toInt()
            // "701至附6首" = 701 to the end of the main hymns, then appendix 1-6.
            val to = if (t.groupValues[3] == "附") from + 98 else t.groupValues[4].toInt()
            out[id] = Volume(book, from, to, id)
        }
        return out.values.toList()
    }

    fun findVolume(volumes: List<Volume>, book: Book, number: Int): Volume? =
        volumes.firstOrNull { it.book == book && number in it.from..it.to }

    /** Lists albums on a store category page: product link + image alt text ("SKU title"). */
    fun parseAlbums(html: String): List<Album> {
        val re = Regex("""<a href="(/products/[^"?#]+)"[\s\S]{0,2500}?alt="([^"]+)"""")
        val out = LinkedHashMap<String, Album>()
        for (m in re.findAll(html)) {
            val path = m.groupValues[1]
            val title = htmlDecode(m.groupValues[2]).trim()
            if (title.isNotEmpty() && path !in out) out[path] = Album(title, path)
        }
        return out.values.toList()
    }

    private val mp3Re = Regex("""https?://(?:cd|play)\.twgbr\.org/[^"'<>\s]+?\.mp3""", RegexOption.IGNORE_CASE)

    /** Finds the official preview MP3 and the track list (曲目) on a product page. */
    fun parseAlbumDetail(html: String): AlbumDetail {
        val mp3 = mp3Re.find(html)?.value?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        // "曲目" also appears in the page's meta description, so try each occurrence and keep the best.
        var tracks: List<String> = emptyList()
        var from = 0
        repeat(6) {
            val start = html.indexOf("曲目", from)
            if (start < 0) return@repeat
            from = start + 2
            val t = tracksAfter(html, start)
            if (t.size > tracks.size) tracks = t
        }
        return AlbumDetail(mp3, tracks)
    }

    private fun tracksAfter(html: String, start: Int): List<String> {
        val ends = listOf("立即按讚", "顧客評價", "您可能喜歡", "</article>").map { html.indexOf(it, start) }.filter { it > start }
        val end = minOf(ends.minOrNull() ?: Int.MAX_VALUE, start + 30000, html.length)
        val text = htmlToText(html.substring(start, end))
        val line = Regex("""^\s*(\d{1,2})\s*[.．、]\s*(.+?)\s*$""")
        val out = mutableListOf<String>()
        for (l in text.lines()) {
            val m = line.find(l) ?: continue
            out += "${m.groupValues[1]}. ${m.groupValues[2].trim()}"
        }
        return out
    }

    /** Finds a hymn number reference like (詩75), （補461）, (兒120) or (詩歌465首). */
    fun hymnRef(track: String): HymnRef? {
        val m = Regex("""[（(]\s*(詩|補|兒)(?:歌|充本|童詩歌|童)?\s*(?:第)?\s*(\d{1,4})\s*首?\s*[)）]""").find(track)
            ?: return null
        val book = Book.fromCode(m.groupValues[1]) ?: return null
        return HymnRef(book, m.groupValues[2].toInt())
    }

    /** Unit titles on a volume page, e.g. 第101首　基督的靈 -> (101, 基督的靈). */
    fun parseUnitTitles(html: String): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (m in Regex("""title="第(\d+)首[.．]?\s*([^"]*)"""").findAll(html)) {
            val n = m.groupValues[1].toInt()
            if (n !in out) out[n] = htmlDecode(m.groupValues[2]).replace('\u3000', ' ').trim()
        }
        return out
    }

    fun isBusyPage(html: String): Boolean = html.contains("The site is now busy")

    fun htmlToText(s: String): String {
        val noScript = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        val withBreaks = noScript.replace(Regex("(?i)<\\s*(br|/p|/div|/li|p|li|div)[^>]*>"), "\n")
        return htmlDecode(withBreaks.replace(Regex("<[^>]+>"), ""))
            .lines().joinToString("\n") { it.trim() }
    }

    fun htmlDecode(s: String): String = s
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: it.value }
        .replace("&amp;", "&")
        .replace('\u00A0', ' ')
}
