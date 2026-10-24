package com.lyricsshow.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lyricsshow.app.R
import com.lyricsshow.app.model.LrcLine
import com.lyricsshow.app.model.LyricManager
import com.lyricsshow.app.service.FloatingLyricService
import com.lyricsshow.app.service.MediaListenerService
import com.lyricsshow.app.util.LocalDemoPlayer

class ImmersiveLyricActivity : AppCompatActivity() {

    private lateinit var tvImmTitle: TextView
    private lateinit var tvImmArtist: TextView
    private lateinit var tvProgressTime: TextView
    private lateinit var tvOffsetStatus: TextView
    private lateinit var rvLyrics: RecyclerView
    private lateinit var adapter: LyricAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private lateinit var btnImmPlayPause: ImageView
    private lateinit var btnToggleFloating: TextView

    private var userIsScrolling = false

    private val lyricListener = object : LyricManager.LyricListener {
        override fun onTrackChanged(title: String, artist: String) {
            runOnUiThread {
                tvImmTitle.text = if (title.isBlank()) "浮光歌词" else title
                tvImmArtist.text = if (artist.isBlank()) "等待音乐播放..." else artist
            }
        }

        override fun onLyricsLoaded(lines: List<LrcLine>) {
            runOnUiThread {
                adapter.setLyrics(lines)
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                btnImmPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }

        override fun onLyricProgress(
            currentText: String,
            nextText: String,
            currentIndex: Int,
            positionMs: Long,
            totalMs: Long
        ) {
            runOnUiThread {
                adapter.setCurrentIndex(currentIndex)
                if (!userIsScrolling && currentIndex in 0 until adapter.itemCount) {
                    val targetPos = (currentIndex - 2).coerceAtLeast(0)
                    rvLyrics.smoothScrollToPosition(targetPos)
                }

                tvProgressTime.text = "${formatTime(positionMs)} / ${formatTime(totalMs)}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_immersive_lyric)

        initViews()
        LyricManager.addListener(lyricListener)
    }

    private fun initViews() {
        tvImmTitle = findViewById(R.id.tvImmTitle)
        tvImmArtist = findViewById(R.id.tvImmArtist)
        tvProgressTime = findViewById(R.id.tvProgressTime)
        tvOffsetStatus = findViewById(R.id.tvOffsetStatus)
        rvLyrics = findViewById(R.id.rvLyrics)
        btnImmPlayPause = findViewById(R.id.btnImmPlayPause)
        btnToggleFloating = findViewById(R.id.btnToggleFloating)

        layoutManager = LinearLayoutManager(this)
        rvLyrics.layoutManager = layoutManager

        adapter = LyricAdapter { clickedLine ->
            // 点击歌词行进行进度跳转
            if (LocalDemoPlayer.isDemoPlaying()) {
                LocalDemoPlayer.seekTo(clickedLine.timeMs)
            } else {
                MediaListenerService.seekTo(clickedLine.timeMs)
            }
        }
        rvLyrics.adapter = adapter

        // 监听用户手指触摸滚动，避免干扰自动滚动
        rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                userIsScrolling = (newState == RecyclerView.SCROLL_STATE_DRAGGING)
            }
        })

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnImmPlayPause.setOnClickListener {
            if (LocalDemoPlayer.isDemoPlaying()) {
                LocalDemoPlayer.toggleDemo()
            } else {
                MediaListenerService.togglePlayPause()
            }
        }

        findViewById<ImageView>(R.id.btnImmPrev).setOnClickListener {
            MediaListenerService.skipToPrevious()
        }

        findViewById<ImageView>(R.id.btnImmNext).setOnClickListener {
            MediaListenerService.skipToNext()
        }

        findViewById<View>(R.id.btnImmSearch).setOnClickListener {
            SearchLyricDialog.show(this)
        }

        // 悬浮窗开关
        updateFloatingButtonText()
        btnToggleFloating.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先在主界面授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (FloatingLyricService.isRunning) {
                stopService(Intent(this, FloatingLyricService::class.java))
            } else {
                val serviceIntent = Intent(this, FloatingLyricService::class.java).apply {
                    action = FloatingLyricService.ACTION_START
                }
                startForegroundService(serviceIntent)
            }
            rvLyrics.postDelayed({ updateFloatingButtonText() }, 300)
        }

        // 微调按钮
        findViewById<TextView>(R.id.btnOffsetMinus).setOnClickListener {
            LyricManager.adjustOffset(-500L)
            updateOffsetDisplay()
        }

        findViewById<TextView>(R.id.btnOffsetPlus).setOnClickListener {
            LyricManager.adjustOffset(500L)
            updateOffsetDisplay()
        }

        tvOffsetStatus.setOnClickListener {
            LyricManager.resetOffset()
            updateOffsetDisplay()
        }
    }

    private fun updateOffsetDisplay() {
        val offset = LyricManager.offsetMs
        tvOffsetStatus.text = if (offset == 0L) {
            "微调"
        } else {
            "${if (offset > 0) "+" else ""}${offset / 1000f}s"
        }
    }

    private fun updateFloatingButtonText() {
        btnToggleFloating.text = if (FloatingLyricService.isRunning) "悬浮窗:开" else "悬浮窗:关"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    override fun onDestroy() {
        super.onDestroy()
        LyricManager.removeListener(lyricListener)
    }
}
