package com.lyricsshow.app.util

import com.lyricsshow.app.model.LrcLine
import java.util.regex.Pattern

object LrcParser {
    // 匹配类似 [01:23.45] 或 [01:23.456] 或 [01:23]
    private val TIME_TAG_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]")
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)

    /**
     * 解析 LRC 格式文本
     */
    fun parse(lrcContent: String?): List<LrcLine> {
        if (lrcContent.isNullOrBlank()) {
            return emptyList()
        }

        val resultList = mutableListOf<LrcLine>()
        var offsetMs: Long = 0

        val lines = lrcContent.split("\n")
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 检查全局 offset 偏移
            val offsetMatcher = OFFSET_PATTERN.matcher(line)
            if (offsetMatcher.find()) {
                offsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                continue
            }

            // 提取所有时间标签
            val matcher = TIME_TAG_PATTERN.matcher(line)
            val timeTags = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(3)

                val ms = when (msStr?.length) {
                    1 -> msStr.toLong() * 100
                    2 -> msStr.toLong() * 10
                    3 -> msStr.toLong()
                    else -> 0L
                }

                val totalMs = min * 60_000L + sec * 1_000L + ms
                timeTags.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (timeTags.isNotEmpty()) {
                val lyricText = line.substring(lastMatchEnd).trim()
                for (time in timeTags) {
                    val adjustedTime = (time + offsetMs).coerceAtLeast(0L)
                    resultList.add(LrcLine(timeMs = adjustedTime, text = lyricText))
                }
            }
        }

        // 按时间排序
        resultList.sort()
        return resultList
    }

    /**
     * 二分查找当前播放进度对应的歌词索引
     */
    fun findLineIndex(lines: List<LrcLine>, currentTimeMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (currentTimeMs < lines.first().timeMs) return 0

        var low = 0
        var high = lines.size - 1
        var candidate = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val midTime = lines[mid].timeMs

            if (midTime <= currentTimeMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return candidate
    }
}
