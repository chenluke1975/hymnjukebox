package io.github.chenluke1975.hymnjukebox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HymnFileNameTest {
    private fun check(file: String, book: Book, number: Int?, title: String = "", folders: List<String> = emptyList()) {
        val p = HymnFileName.parse(file, folders)
        assertEquals("book of $file", book, p.book)
        assertEquals("number of $file", number, p.number)
        assertEquals("title of $file", title, p.title)
    }

    @Test fun plainNumbers() {
        check("001.mp3", Book.HYMNS, 1)
        check("075.mp3", Book.HYMNS, 75)
        check("0780.m4a", Book.HYMNS, 780)
        check("12.flac", Book.HYMNS, 12)
    }

    @Test fun bookPrefixes() {
        check("詩歌075.mp3", Book.HYMNS, 75)
        check("詩075.mp3", Book.HYMNS, 75)
        check("詩歌 第75首.mp3", Book.HYMNS, 75)
        check("大本123.mp3", Book.HYMNS, 123)
        check("大本詩歌-123.ogg", Book.HYMNS, 123)
        check("補461.mp3", Book.SUPPLEMENT, 461)
        check("補充本461.mp3", Book.SUPPLEMENT, 461)
        check("補充本_0461.wav", Book.SUPPLEMENT, 461)
        check("兒1205.mp3", Book.CHILDREN, 1205)
        check("兒童詩歌 12.opus", Book.CHILDREN, 12)
        check("兒童第12首.mp3", Book.CHILDREN, 12)
        check("新詩歌12.mp3", Book.NEW_HYMNS, 12)
        check("新詩歌 第 3 首 主愛.mp3", Book.NEW_HYMNS, 3, "主愛")
        check("Hymn 12.mp3", Book.HYMNS, 12)
        check("hymns_012.mp3", Book.HYMNS, 12)
        check("Hymn #12 - Jesus Loves Me.mp3", Book.HYMNS, 12, "Jesus Loves Me")
        check("Supplement 461.mp3", Book.SUPPLEMENT, 461)
        check("Children 1205.mp3", Book.CHILDREN, 1205)
        check("New Hymns 7.mp3", Book.NEW_HYMNS, 7)
        check("S461.mp3", Book.SUPPLEMENT, 461)
        check("C1205.mp3", Book.CHILDREN, 1205)
        check("H075.mp3", Book.HYMNS, 75)
    }

    @Test fun titles() {
        check("075 主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("075-主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("075_主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("075.主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("詩歌075 主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("補461 十架窄路.mp3", Book.SUPPLEMENT, 461, "十架窄路")
        check("補充本461【十架窄路】.mp3", Book.SUPPLEMENT, 461, "十架窄路")
        check("主耶穌我愛你-075.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("主耶穌我愛你（詩歌075）.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("第75首 主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("01 - 詩歌075 - 主耶穌我愛你.mp3", Book.HYMNS, 75, "主耶穌我愛你")
        check("075 主耶穌我愛你 128kbps.mp3", Book.HYMNS, 75, "主耶穌我愛你")
    }

    @Test fun folders() {
        check("461.mp3", Book.SUPPLEMENT, 461, folders = listOf("Music", "補充本"))
        check("12.mp3", Book.CHILDREN, 12, folders = listOf("兒童詩歌"))
        check("12.mp3", Book.NEW_HYMNS, 12, folders = listOf("新詩歌"))
        check("75 標題.mp3", Book.HYMNS, 75, "標題", folders = listOf("詩歌 1-100"))
        check("461.mp3", Book.SUPPLEMENT, 461, folders = listOf("詩歌", "補充本"))  // nearest folder wins
        check("461.mp3", Book.SUPPLEMENT, 461, folders = listOf("Supplement"))
        check("補461.mp3", Book.SUPPLEMENT, 461, folders = listOf("詩歌"))          // name beats folder
        check("075.mp3", Book.HYMNS, 75, folders = listOf("Download"))
    }

    @Test fun noNumber() {
        val p = HymnFileName.parse("主耶穌我愛你.mp3")
        assertNull(p.number)
        assertEquals("主耶穌我愛你", p.title)
        assertNull(HymnFileName.parse("Psalms.mp3").number)
        assertNull(HymnFileName.parse("2023 特會.mp3").number)    // a year is not a hymn number
        assertNull(HymnFileName.parse("錄音 12345.mp3").number)   // too long
        assertEquals(461, HymnFileName.parse("2023 補461.mp3").number)
    }

    @Test fun lettersAreNotPrefixesMidWord() {
        check("Psalms 23.mp3", Book.HYMNS, 23, "Psalms")  // "s" of Psalms is not 補充本
        check("Track 05.mp3", Book.HYMNS, 5)
    }

    @Test fun extensionsAndBookWords() {
        assertEquals("mp3", HymnFileName.extension("a.b.MP3"))
        assertEquals("075 主", HymnFileName.stripExtension("075 主.opus"))
        assertTrue("flac" in HymnFileName.AUDIO_EXTENSIONS)
        assertFalse("txt" in HymnFileName.AUDIO_EXTENSIONS)
        assertEquals(Book.SUPPLEMENT, HymnFileName.bookInText("補充本詩歌"))
        assertEquals(Book.CHILDREN, HymnFileName.bookInText("兒童詩歌"))
        assertEquals(Book.NEW_HYMNS, HymnFileName.bookInText("新詩歌"))
        assertEquals(Book.HYMNS, HymnFileName.bookInText("大本詩歌"))
        assertNull(HymnFileName.bookInText("Download"))
    }
}
