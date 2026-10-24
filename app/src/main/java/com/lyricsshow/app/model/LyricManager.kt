package com.lyricsshow.app.model

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lyricsshow.app.util.LrcParser
import com.lyricsshow.app.util.LyricFetcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object LyricManager {
    interface LyricListener {
        fun onTrackChanged(title: String, artist: String) {}
        fun onPlaybackStateChanged(isPlaying: Boolean) {}
        fun onLyricProgress(
            currentText: String,
            nextText: String,
            currentIndex: Int,
            positionMs: Long,
            totalMs: Long
        ) {}
        fun onLyricsLoaded(lines: List<LrcLine>) {}
    }

    private val listeners = CopyOnWriteArrayList<LyricListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    var currentTitle: String = ""
        private set
    var currentArtist: String = ""
        private set
    var isPlaying: Boolean = false
        private set
    var durationMs: Long = 0L
        private set
    var basePositionMs: Long = 0L
        private set
    var lastUpdateTime: Long = 0L
        private set
    var playbackSpeed: Float = 1.0f
        private set
    var offsetMs: Long = 0L

    var lyricLines: List<LrcLine> = emptyList()
        private set
    var currentLineIndex: Int = -1
        private set

    // 播放进度高频更新任务
    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                tick()
                mainHandler.postDelayed(this, 80) // ~12fps 歌词刷新率，省电且平滑
            }
        }
    }

    fun addListener(listener: LyricListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // 立即回调当前状态
            listener.onTrackChanged(currentTitle, currentArtist)
            listener.onPlaybackStateChanged(isPlaying)
            listener.onLyricsLoaded(lyricLines)
            tickForListener(listener)
        }
    }

    fun removeListener(listener: LyricListener) {
        listeners.remove(listener)
    }

    /**
     * 更新曲目信息
     */
    fun updateTrack(
        context: Context,
        title: String,
        artist: String,
        duration: Long = 0L,
        playing: Boolean = true,
        position: Long = 0L,
        speed: Float = 1.0f
    ) {
        val titleChanged = title != currentTitle || artist != currentArtist

        currentTitle = title
        currentArtist = artist
        durationMs = duration
        isPlaying = playing
        basePositionMs = position
        lastUpdateTime = SystemClock.elapsedRealtime()
        playbackSpeed = speed

        if (titleChanged) {
            lyricLines = emptyList()
            currentLineIndex = -1
            notifyTrackChanged(title, artist)

            // 异步获取歌词
            executor.execute {
                val lrcText = LyricFetcher.getLyrics(context, title, artist)
                val parsed = if (!lrcText.isNullOrBlank()) {
                    LrcParser.parse(lrcText)
                } else {
                    listOf(LrcLine(0L, if (title.isBlank()) "等待音乐播放..." else "$title - 暂无歌词"))
                }

                mainHandler.post {
                    // 确认仍然是当前歌曲
                    if (currentTitle == title && currentArtist == artist) {
                        lyricLines = parsed
                        notifyLyricsLoaded(parsed)
                        tick()
                    }
                }
            }
        } else {
            notifyPlaybackStateChanged(playing)
        }

        startOrStopTicker()
    }

    /**
     * 仅更新播放进度与状态（无需重新获取歌词）
     */
    fun updatePlaybackState(playing: Boolean, position: Long, speed: Float = 1.0f, duration: Long = durationMs) {
        isPlaying = playing
        basePositionMs = position
        lastUpdateTime = SystemClock.elapsedRealtime()
        playbackSpeed = speed
        if (duration > 0) durationMs = duration

        notifyPlaybackStateChanged(playing)
        startOrStopTicker()
        tick()
    }

    /**
     * 手动注入歌词与曲目（用于内置测试、本地试听）
     */
    fun setManualTrack(title: String, artist: String, lrcContent: String, duration: Long) {
        currentTitle = title
        currentArtist = artist
        durationMs = duration
        basePositionMs = 0L
        lastUpdateTime = SystemClock.elapsedRealtime()
        isPlaying = true
        lyricLines = LrcParser.parse(lrcContent)
        currentLineIndex = -1

        notifyTrackChanged(title, artist)
        notifyPlaybackStateChanged(true)
        notifyLyricsLoaded(lyricLines)
        startOrStopTicker()
        tick()
    }

    /**
     * 调整歌词同步时间轴偏移（毫秒）
     */
    fun adjustOffset(deltaMs: Long) {
        offsetMs += deltaMs
        tick()
    }

    fun resetOffset() {
        offsetMs = 0L
        tick()
    }

    /**
     * 计算当前精确播放时间（毫秒）
     */
    fun getCurrentPosition(): Long {
        if (!isPlaying) {
            return (basePositionMs + offsetMs).coerceAtLeast(0L)
        }
        val elapsed = (SystemClock.elapsedRealtime() - lastUpdateTime) * playbackSpeed
        return (basePositionMs + elapsed.toLong() + offsetMs).coerceAtLeast(0L)
    }

    private fun startOrStopTicker() {
        mainHandler.removeCallbacks(tickerRunnable)
        if (isPlaying) {
            mainHandler.post(tickerRunnable)
        }
    }

    private fun tick() {
        val currentPos = getCurrentPosition()
        val index = if (lyricLines.isNotEmpty()) {
            LrcParser.findLineIndex(lyricLines, currentPos)
        } else {
            -1
        }

        currentLineIndex = index
        val currentText = if (index in lyricLines.indices) lyricLines[index].text else if (currentTitle.isNotBlank()) currentTitle else "暂无歌词"
        val nextText = if (index + 1 in lyricLines.indices) lyricLines[index + 1].text else ""

        for (listener in listeners) {
            listener.onLyricProgress(currentText, nextText, index, currentPos, durationMs)
        }
    }

    private fun tickForListener(listener: LyricListener) {
        val currentPos = getCurrentPosition()
        val index = currentLineIndex
        val currentText = if (index in lyricLines.indices) lyricLines[index].text else if (currentTitle.isNotBlank()) currentTitle else "暂无歌词"
        val nextText = if (index + 1 in lyricLines.indices) lyricLines[index + 1].text else ""
        listener.onLyricProgress(currentText, nextText, index, currentPos, durationMs)
    }

    private fun notifyTrackChanged(title: String, artist: String) {
        for (l in listeners) l.onTrackChanged(title, artist)
    }

    private fun notifyPlaybackStateChanged(playing: Boolean) {
        for (l in listeners) l.onPlaybackStateChanged(playing)
    }

    private fun notifyLyricsLoaded(lines: List<LrcLine>) {
        for (l in listeners) l.onLyricsLoaded(lines)
    }
}
