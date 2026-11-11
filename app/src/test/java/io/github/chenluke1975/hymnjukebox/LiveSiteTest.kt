package io.github.chenluke1975.hymnjukebox

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Hits the real official sites. Run with: LIVE=1 ./gradlew testReleaseUnitTest */
class LiveSiteTest {
    private fun get(url: String, range: Boolean = false): Pair<Int, String> {
        repeat(4) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36 HymnJukebox/1.0")
            if (range) c.setRequestProperty("Range", "bytes=0-4095")
            val code = c.responseCode
            val body = if (range) c.contentType ?: "" else c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            c.disconnect()
            if (range || !Parsers.isBusyPage(body)) return code to body
            Thread.sleep(4000)
        }
        error("busy: $url")
    }

    @Test fun live() {
        assumeTrue(System.getenv("LIVE") == "1")
        val vols = Parsers.parseVolumes(get(Sites.GOODLAND_INDEX).second)
        println("volumes: ${vols.size}")
        assertTrue(vols.size == 32)
        for (v in listOf(vols.first(), vols.first { it.book == Book.SUPPLEMENT }, vols.last())) {
            Thread.sleep(1200)
            val t = Parsers.parseUnitTitles(get(v.url).second)
            println("vol ${v.courseId} ${v.book} ${v.from}-${v.to}: ${t.size} units, e.g. ${t.entries.take(2)}")
            assertTrue(t.isNotEmpty())
        }
        val albums = mutableListOf<Album>()
        for (cat in Sites.ALBUM_CATEGORIES) {
            Thread.sleep(1500)
            val u = "${Sites.STORE}/categories/${URLEncoder.encode(cat, "UTF-8").replace("+", "%20")}?limit=72"
            val a = Parsers.parseAlbums(get(u).second)
            println("category $cat: ${a.size} albums")
            albums += a
        }
        assertTrue(albums.size > 50)
        var ok = 0
        for (a in albums.shuffled().take(6)) {
            Thread.sleep(1500)
            val d = Parsers.parseAlbumDetail(get(a.url).second)
            val mp3 = d.mp3
            val ct = if (mp3 != null) get(mp3, range = true) else (0 to "")
            println("${a.title}: mp3=$mp3 -> HTTP ${ct.first} ${ct.second}; tracks=${d.tracks.size}")
            if (mp3 != null && ct.first in 200..299 && ct.second.startsWith("audio")) ok++
        }
        println("playable previews: $ok / 6")
    }
}
