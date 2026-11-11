package io.github.chenluke1975.hymnjukebox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun volumesFromOfficialIndex() {
        val v = Parsers.parseVolumes(res("goodland_index.html"))
        assertEquals(32, v.size)
        assertEquals(Parsers.FALLBACK_VOLUMES.map { it.courseId }.toSet(), v.map { it.courseId }.toSet())
        assertEquals(155, Parsers.findVolume(v, Book.HYMNS, 1)!!.courseId)
        assertEquals(165, Parsers.findVolume(v, Book.HYMNS, 123)!!.courseId)
        assertEquals(264, Parsers.findVolume(v, Book.HYMNS, 780)!!.courseId)
        assertEquals(371, Parsers.findVolume(v, Book.SUPPLEMENT, 101)!!.courseId)
        assertNull(Parsers.findVolume(v, Book.SUPPLEMENT, 50))
        assertEquals(407, Parsers.findVolume(v, Book.CHILDREN, 1232)!!.courseId)
        assertEquals(v.toSet(), Parsers.FALLBACK_VOLUMES.toSet())
    }

    @Test fun unitTitles() {
        val t = Parsers.parseUnitTitles(res("goodland_vol371.html"))
        assertEquals("基督的靈", t[101])
        assertEquals("七靈", t[102])
        assertFalse(t.containsKey(1001)) // "第1001-1039首" menu links are not units
    }

    @Test fun albumsFromCategory() {
        val a = Parsers.parseAlbums(res("store_category.html"))
        assertTrue(a.size >= 5)
        assertTrue(a.any { it.title.startsWith("9001-10A") && it.path.startsWith("/products/9001-10a") })
    }

    @Test fun albumDetail() {
        val d = Parsers.parseAlbumDetail(res("store_product.html"))
        assertEquals("https://cd.twgbr.org/9016-11A.mp3", d.mp3)
        assertEquals(12, d.tracks.size)
        assertEquals("1. 永活的故事（詩75）", d.tracks[0])
        assertEquals(HymnRef(Book.SUPPLEMENT, 461), Parsers.hymnRef(d.tracks[4]))
    }

    @Test fun albumDetailMetaFirst() {
        val d = Parsers.parseAlbumDetail(res("store_product2.html"))
        assertEquals("https://play.twgbr.org/9016-8A/9016-8A.mp3", d.mp3)
        assertTrue(d.tracks.size >= 5)
        assertEquals("1. 哦神，你是生命源頭（9）", d.tracks[0])
    }

    @Test fun refsAndHosts() {
        assertEquals(HymnRef(Book.HYMNS, 465), Parsers.hymnRef("葡萄一生的事（詩歌465首）"))
        assertEquals(HymnRef(Book.CHILDREN, 120), Parsers.hymnRef("主愛有多少（兒120）"))
        assertNull(Parsers.hymnRef("選擇作得勝者（唱詩人第八卷第二期）"))
        assertTrue(Sites.isOfficial("https://cd.twgbr.org/x.mp3"))
        assertTrue(Sites.isOfficial("https://www.mygoodland.video/course-detail/155/"))
        assertFalse(Sites.isOfficial("http://cd.twgbr.org/x.mp3"))
        assertFalse(Sites.isOfficial("https://twgbr.org.evil.com/x"))
        val d = Parsers.parseAlbumDetail("<a href=\"http://play.twgbr.org/9001-9A/9001-9A.mp3\">線上試聽</a>")
        assertNotNull(d.mp3)
        assertEquals("https://play.twgbr.org/9001-9A/9001-9A.mp3", d.mp3)
    }
}
