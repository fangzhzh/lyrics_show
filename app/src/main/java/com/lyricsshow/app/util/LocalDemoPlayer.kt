package com.lyricsshow.app.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lyricsshow.app.model.LyricManager

/**
 * 内置测试播放器：用于无需打开第三方音乐软件即可立即测试实时歌词
 */
object LocalDemoPlayer {
    private var isPlaying = false
    private var startTimestamp = 0L
    private var currentPosition = 0L
    private const val DEMO_DURATION = 120_000L // 2分钟

    private val mainHandler = Handler(Looper.getMainLooper())

    private const val DEMO_TITLE = "晴天 (测试示例)"
    private const val DEMO_ARTIST = "周杰伦"

    private const val DEMO_LRC = """
[00:00.00]晴天 - 周杰伦
[00:02.00]词：周杰伦 曲：周杰伦
[00:04.50]故事的小黄花
[00:07.80]从出生那年就飘着
[00:11.20]童年的荡秋千
[00:14.50]随记忆一直晃到现在
[00:18.00]Re So So Si Do Si La
[00:20.50]So La Si Si Si Si La Si La So
[00:24.00]吹着前奏望着天空
[00:27.50]我想起花瓣试着掉落
[00:31.00]为你翘课的那一天
[00:34.20]花落的那一天
[00:37.50]教室的哪一间
[00:40.80]我怎么看不见
[00:44.00]消失的下雨天
[00:47.50]我好想再淋一遍
[00:51.00]没想到失去的勇气我还留着
[00:57.50]好想再问一遍
[01:01.00]你会等待还是离开
[01:07.50]刮风这天我试过握着你手
[01:13.80]但偏偏 雨渐渐
[01:17.20]大到我看你不见
[01:21.00]还要多久我才能在你身边
[01:27.20]等到放晴的那天也许我会比较好一点
[01:34.00]从前从前有个人爱你很久
[01:40.50]但偏偏 风渐渐
[01:43.80]把距离吹得好远
[01:47.50]好不容易又能再多爱一天
[01:54.00]但故事的最后你好像还是说了拜拜
"""

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                val elapsed = SystemClock.elapsedRealtime() - startTimestamp
                currentPosition = elapsed % DEMO_DURATION
                LyricManager.updatePlaybackState(
                    playing = true,
                    position = currentPosition,
                    speed = 1.0f,
                    duration = DEMO_DURATION
                )
                mainHandler.postDelayed(this, 100)
            }
        }
    }

    fun isDemoPlaying(): Boolean = isPlaying

    fun toggleDemo() {
        if (isPlaying) {
            pauseDemo()
        } else {
            startDemo()
        }
    }

    fun startDemo() {
        isPlaying = true
        startTimestamp = SystemClock.elapsedRealtime() - currentPosition
        LyricManager.setManualTrack(
            title = DEMO_TITLE,
            artist = DEMO_ARTIST,
            lrcContent = DEMO_LRC,
            duration = DEMO_DURATION
        )
        mainHandler.post(progressRunnable)
    }

    fun pauseDemo() {
        isPlaying = false
        mainHandler.removeCallbacks(progressRunnable)
        LyricManager.updatePlaybackState(playing = false, position = currentPosition)
    }

    fun stopDemo() {
        isPlaying = false
        currentPosition = 0L
        mainHandler.removeCallbacks(progressRunnable)
    }

    fun seekTo(positionMs: Long) {
        currentPosition = positionMs.coerceIn(0L, DEMO_DURATION)
        startTimestamp = SystemClock.elapsedRealtime() - currentPosition
        LyricManager.updatePlaybackState(playing = isPlaying, position = currentPosition)
    }
}
