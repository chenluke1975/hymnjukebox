package io.github.chenluke1975.hymnjukebox

/** What we could read from an audio file's name and folders. */
data class ParsedName(val book: Book, val number: Int?, val title: String, val bookFromName: Boolean)

/**
 * Reads a hymn number (and optional title) from a file name such as
 * "001.mp3", "詩歌075.mp3", "075 主耶穌我愛你.mp3", "Hymn 12.mp3", "補461.m4a", "補充本461",
 * "兒1205", "大本123", "新詩歌12", "第75首 標題". The hymnal (詩歌/補充本/兒童/新詩歌) comes from
 * a prefix in the name, else from a keyword in the name, else from the nearest folder name,
 * else defaults to 詩歌.
 */
object HymnFileName {

    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mp4a", "3gp", "amr", "mka")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun stripExtension(name: String): String {
        val ext = extension(name)
        return if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) name.dropLast(ext.length + 1) else name
    }

    // Longest alternatives first so 兒童詩歌 / 新詩歌 win over 詩歌, 補充本 over 補.
    private const val BOOK_WORDS =
        "補充本詩歌|補充本|補充|兒童詩歌|兒童|新詩歌|大本詩歌|大本|詩歌本|詩歌|" +
            "new\\s*hymns?|supplement(?:al)?|children'?s?|kids|hymnal|hymns?|" +
            "補|兒|詩|新|sup|ch|h|s|c|n"

    /** Book prefix directly attached to a number: 補461, 詩歌075, Hymn 12, 兒童第12首, S461. */
    private val prefixed = Regex(
        "(?<![A-Za-z\\p{IsHan}])($BOOK_WORDS)\\s*[-_.#:：、]?\\s*(?:第|no\\.?\\s*|nr\\.?\\s*|#)?\\s*0*(\\d{1,4})(?!\\d)",
        RegexOption.IGNORE_CASE,
    )
    private val ordinal = Regex("第\\s*0*(\\d{1,4})\\s*首")
    private val plainNumber = Regex("(?<![0-9])0*(\\d{1,4})(?![0-9])")
    private val separators = " \t-_.,，、:：;；#()（）[]【】「」『』~～·・|/\\\\\"'".toSet()

    fun bookOfWord(w0: String): Book? {
        val w = w0.lowercase().replace(Regex("\\s+"), "")
        return when {
            w.startsWith("補") || w.startsWith("sup") || w == "s" -> Book.SUPPLEMENT
            w.startsWith("兒") || w.startsWith("child") || w == "kids" || w == "ch" || w == "c" -> Book.CHILDREN
            w.startsWith("新") || w.startsWith("newhymn") || w == "n" -> Book.NEW_HYMNS
            w.startsWith("詩") || w.startsWith("大本") || w.startsWith("hymn") || w == "h" -> Book.HYMNS
            else -> null
        }
    }

    /** A hymnal keyword anywhere in a folder or file name (no single characters, to avoid titles). */
    fun bookInText(t0: String): Book? {
        val t = t0.lowercase()
        return when {
            t.contains("補充") || Regex("\\bsupplement").containsMatchIn(t) -> Book.SUPPLEMENT
            t.contains("兒童") || Regex("\\bchildren|\\bkids\\b").containsMatchIn(t) -> Book.CHILDREN
            t.contains("新詩歌") || Regex("\\bnew\\s*hymn").containsMatchIn(t) -> Book.NEW_HYMNS
            t.contains("詩歌") || t.contains("大本") || Regex("\\bhymn").containsMatchIn(t) -> Book.HYMNS
            else -> null
        }
    }

    private fun validNumber(n: Int, prefixed: Boolean): Boolean =
        n in 1..1999 && (prefixed || n !in 1900..1999)

    private fun cleanTitle(s: String): String {
        var t = s.trim { it in separators || it.isWhitespace() }
        t = t.removePrefix("首").trim { it in separators || it.isWhitespace() }
        // Drop trailing noise like "(伴奏)" is kept; only strip trailing separators and bitrate tags.
        t = t.replace(Regex("(?i)\\b\\d{2,3}\\s*kbps\\b"), "").trim { it in separators || it.isWhitespace() }
        return t
    }

    private val noiseWords = setOf("track", "tr", "song", "audio", "music", "no", "nr", "第", "首", "錄音", "音軌", "曲", "disc", "cd")

    private fun meaningful(t: String): Boolean =
        t.any { it.isLetter() } && t.lowercase() !in noiseWords && bookOfWord(t) == null && bookInText(t)?.let { t.length > 4 } != false

    /**
     * @param fileName display name including extension
     * @param folders folder names from the chosen root down to the file's folder
     */
    fun parse(fileName: String, folders: List<String> = emptyList()): ParsedName {
        val base = stripExtension(fileName).replace('\u3000', ' ').trim()
        var book: Book? = null
        var number: Int? = null
        var before = ""
        var after = ""

        // 1. Book word glued to a number (strongest signal).
        for (m in prefixed.findAll(base)) {
            val n = m.groupValues[2].toInt()
            val b = bookOfWord(m.groupValues[1]) ?: continue
            // Single Latin letters (h/s/c/n) only count at the very start, e.g. "S461" not "Psalms 23".
            if (m.groupValues[1].length == 1 && m.groupValues[1][0].isLetter() && m.groupValues[1][0].code < 128 &&
                base.substring(0, m.range.first).any { it.isLetterOrDigit() }) continue
            if (!validNumber(n, true)) continue
            book = b; number = n
            before = base.substring(0, m.range.first)
            after = base.substring(m.range.last + 1)
            break
        }
        // 2. "第75首"
        if (number == null) ordinal.find(base)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (validNumber(n, true)) {
                number = n; before = base.substring(0, m.range.first); after = base.substring(m.range.last + 1)
            }
        }
        // 3. First plain number that looks like a hymn number.
        if (number == null) for (m in plainNumber.findAll(base)) {
            val n = m.groupValues[1].toInt()
            if (!validNumber(n, false)) continue
            // Skip bitrate-like "128kbps" and "320k".
            val tail = base.substring(m.range.last + 1).lowercase()
            if (tail.startsWith("kbps") || tail.startsWith("k ") || tail == "k") continue
            number = n
            before = base.substring(0, m.range.first)
            after = base.substring(m.range.last + 1)
            break
        }

        val bookFromName = book != null || bookInText(base) != null
        if (book == null) book = bookInText(base)
        if (book == null) for (f in folders.asReversed()) { book = bookInText(f); if (book != null) break }

        var title = if (number == null) cleanTitle(base) else cleanTitle(after)
        if (number != null && !meaningful(title)) {
            val b = cleanTitle(before)
            title = if (meaningful(b)) b else ""
        }
        if (number != null && title.isNotEmpty() && !meaningful(title)) title = ""
        return ParsedName(book ?: Book.HYMNS, number, title, bookFromName)
    }
}
