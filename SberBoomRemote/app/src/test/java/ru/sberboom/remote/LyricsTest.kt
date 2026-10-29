package ru.sberboom.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sberboom.remote.net.Lrc
import ru.sberboom.remote.net.LyricLine
import ru.sberboom.remote.net.Lyrics

/** Разбор LRC и выбор активной строки — порт поведения lyrics_client.py. */
class LyricsTest {

    @Test
    fun parsesTimestamps() {
        val lrc = """
            [00:12.34]Первая строка
            [00:15.00]Вторая строка
            [00:18.500]Третья
        """.trimIndent()
        val lines = Lrc.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals(12.34f, lines[0].timeSec, 0.01f)
        assertEquals("Первая строка", lines[0].text)
        assertEquals(18.5f, lines[2].timeSec, 0.01f)
    }

    @Test
    fun handlesMultiTimestampAndWordTags() {
        // Одна строка текста на несколько моментов + word-теги enhanced LRC.
        val lines = Lrc.parse("[00:10.00][01:30.00]Припев <00:11.00>слова")
        assertEquals(2, lines.size)
        assertEquals(10f, lines[0].timeSec, 0.01f)
        assertEquals("Припев слова", lines[0].text)
        assertEquals(90f, lines[1].timeSec, 0.01f)
    }

    @Test
    fun sortsOutOfOrderLines() {
        val lines = Lrc.parse("[00:20.00]B\n[00:10.00]A")
        assertEquals(listOf("A", "B"), lines.map { it.text })
    }

    @Test
    fun ignoresMetadataLines() {
        val lines = Lrc.parse("[ar:AC/DC]\n[ti:Thunderstruck]\n[00:05.00]Строка")
        assertEquals(1, lines.size)
        assertEquals("Строка", lines[0].text)
    }

    @Test
    fun detectsCreditLines() {
        assertTrue(Lrc.isCredit("[00:00.00] 作曲 : Кто-то"))
        assertTrue(Lrc.isCredit("[00:01.00]制作人：Другой"))
        assertFalse(Lrc.isCredit("[00:00.00] Это уже текст песни"))
    }

    @Test
    fun findsActiveLine() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(5f, "a"),
                LyricLine(10f, "b"),
                LyricLine(20f, "c"),
            ),
        )
        assertTrue(lyrics.isSynced)
        assertEquals(-1, lyrics.indexAt(1f))
        assertEquals(0, lyrics.indexAt(5.5f))
        assertEquals(1, lyrics.indexAt(12f))
        assertEquals(2, lyrics.indexAt(999f))
    }

    @Test
    fun plainOnlyIsNotSynced() {
        val lyrics = Lyrics(plain = "текст без таймстампов")
        assertFalse(lyrics.isSynced)
        assertEquals(-1, lyrics.indexAt(42f))
    }
}
