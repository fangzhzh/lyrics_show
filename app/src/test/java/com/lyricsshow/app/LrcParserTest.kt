package com.lyricsshow.app

import com.lyricsshow.app.util.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun testParseStandardLrc() {
        val lrc = """
            [00:00.00]歌曲：晴天
            [00:04.50]故事的小黄花
            [00:07.80]从出生那年就飘着
            [00:11.20]童年的荡秋千
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(4, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals("歌曲：晴天", lines[0].text)
        assertEquals(4500L, lines[1].timeMs)
        assertEquals("故事的小黄花", lines[1].text)
        assertEquals(7800L, lines[2].timeMs)
        assertEquals(11200L, lines[3].timeMs)
    }

    @Test
    fun testFindLineIndex() {
        val lrc = """
            [00:00.00]前奏
            [00:05.00]第一句
            [00:10.00]第二句
            [00:15.00]第三句
        """.trimIndent()

        val lines = LrcParser.parse(lrc)

        // 播放开始前
        assertEquals(0, LrcParser.findLineIndex(lines, 0L))
        assertEquals(0, LrcParser.findLineIndex(lines, 2000L))

        // 刚好5秒
        assertEquals(1, LrcParser.findLineIndex(lines, 5000L))
        // 7秒处于第一句
        assertEquals(1, LrcParser.findLineIndex(lines, 7500L))

        // 12秒处于第二句
        assertEquals(2, LrcParser.findLineIndex(lines, 12000L))

        // 超过最后一句
        assertEquals(3, LrcParser.findLineIndex(lines, 30000L))
    }

    @Test
    fun testMultiTimestampLine() {
        val lrc = """
            [00:01.00][00:05.00]重复副歌
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("重复副歌", lines[0].text)
        assertEquals(5000L, lines[1].timeMs)
        assertEquals("重复副歌", lines[1].text)
    }
}
