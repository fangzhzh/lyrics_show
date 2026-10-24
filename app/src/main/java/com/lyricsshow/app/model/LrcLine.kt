package com.lyricsshow.app.model

/**
 * 单行歌词数据模型
 * @param timeMs 歌词时间戳（毫秒）
 * @param text 歌词文本
 * @param translation 歌词翻译（可选）
 */
data class LrcLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
) : Comparable<LrcLine> {
    override fun compareTo(other: LrcLine): Int {
        return this.timeMs.compareTo(other.timeMs)
    }
}
