package com.lyricsshow.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.button.MaterialButton
import com.lyricsshow.app.model.LyricManager
import com.lyricsshow.app.service.FloatingLyricService
import com.lyricsshow.app.ui.ImmersiveLyricActivity
import com.lyricsshow.app.util.LocalDemoPlayer

class MainActivity : AppCompatActivity() {

    private lateinit var ivOverlayStatus: ImageView
    private lateinit var tvOverlayHint: TextView
    private lateinit var btnGrantOverlay: Button

    private lateinit var ivMediaStatus: ImageView
    private lateinit var tvMediaHint: TextView
    private lateinit var btnGrantMedia: Button

    private lateinit var btnToggleFloatingService: MaterialButton
    private lateinit var btnTestDemo: MaterialButton
    private lateinit var btnOpenImmersive: MaterialButton

    private lateinit var tvMainSongTitle: TextView
    private lateinit var tvMainSongArtist: TextView
    private lateinit var tvMainCurrentLyric: TextView
    private lateinit var tvMainNextLyric: TextView
    private lateinit var tvMainOffset: TextView

    private val lyricListener = object : LyricManager.LyricListener {
        override fun onTrackChanged(title: String, artist: String) {
            runOnUiThread {
                if (title.isNotBlank()) {
                    tvMainSongTitle.text = title
                    tvMainSongArtist.text = if (artist.isNotBlank()) artist else "未知歌手"
                } else {
                    tvMainSongTitle.text = "尚未检测到播放中的音乐"
                    tvMainSongArtist.text = "打开手机音乐App（网易云/QQ音乐等）播放任意歌曲即可"
                }
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
                tvMainCurrentLyric.text = if (currentText.isBlank()) "[ 等待歌词同步... ]" else currentText
                if (nextText.isNotBlank()) {
                    tvMainNextLyric.visibility = View.VISIBLE
                    tvMainNextLyric.text = "下一句：$nextText"
                } else {
                    tvMainNextLyric.visibility = View.GONE
                }
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                updateDemoButtonState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        LyricManager.addListener(lyricListener)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateFloatingButtonState()
        updateDemoButtonState()
        updateOffsetDisplay()
    }

    private fun initViews() {
        ivOverlayStatus = findViewById(R.id.ivOverlayStatus)
        tvOverlayHint = findViewById(R.id.tvOverlayHint)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)

        ivMediaStatus = findViewById(R.id.ivMediaStatus)
        tvMediaHint = findViewById(R.id.tvMediaHint)
        btnGrantMedia = findViewById(R.id.btnGrantMedia)

        btnToggleFloatingService = findViewById(R.id.btnToggleFloatingService)
        btnTestDemo = findViewById(R.id.btnTestDemo)
        btnOpenImmersive = findViewById(R.id.btnOpenImmersive)

        tvMainSongTitle = findViewById(R.id.tvMainSongTitle)
        tvMainSongArtist = findViewById(R.id.tvMainSongArtist)
        tvMainCurrentLyric = findViewById(R.id.tvMainCurrentLyric)
        tvMainNextLyric = findViewById(R.id.tvMainNextLyric)
        tvMainOffset = findViewById(R.id.tvMainOffset)
    }

    private fun setupListeners() {
        // 授权悬浮窗
        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // 授权通知监听
        btnGrantMedia.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // 切换悬浮窗服务
        btnToggleFloatingService.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启【显示在其他应用上】权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            if (FloatingLyricService.isRunning) {
                if (FloatingLyricService.isLocked) {
                    val unlockIntent = Intent(this, FloatingLyricService::class.java).apply {
                        action = FloatingLyricService.ACTION_TOGGLE_LOCK
                    }
                    startService(unlockIntent)
                } else {
                    stopService(Intent(this, FloatingLyricService::class.java))
                }
            } else {
                val serviceIntent = Intent(this, FloatingLyricService::class.java).apply {
                    action = FloatingLyricService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }

            btnToggleFloatingService.postDelayed({ updateFloatingButtonState() }, 300)
        }

        // 测试演示播放
        btnTestDemo.setOnClickListener {
            LocalDemoPlayer.toggleDemo()
            updateDemoButtonState()
        }

        // 沉浸式全屏歌词
        btnOpenImmersive.setOnClickListener {
            startActivity(Intent(this, ImmersiveLyricActivity::class.java))
        }

        // 微调控制
        findViewById<Button>(R.id.btnMainOffsetMinus).setOnClickListener {
            LyricManager.adjustOffset(-500L)
            updateOffsetDisplay()
        }

        findViewById<Button>(R.id.btnMainOffsetPlus).setOnClickListener {
            LyricManager.adjustOffset(500L)
            updateOffsetDisplay()
        }

        tvMainOffset.setOnClickListener {
            LyricManager.resetOffset()
            updateOffsetDisplay()
        }

        findViewById<View>(R.id.btnMainManualSearch).setOnClickListener {
            com.lyricsshow.app.ui.SearchLyricDialog.show(this)
        }
    }

    private fun checkPermissions() {
        // 悬浮窗权限检查
        val canOverlay = Settings.canDrawOverlays(this)
        if (canOverlay) {
            ivOverlayStatus.setImageResource(R.drawable.ic_check)
            tvOverlayHint.text = "已授权：可正常弹出悬浮窗"
            tvOverlayHint.setTextColor(Color.parseColor("#4CAF50"))
            btnGrantOverlay.visibility = View.GONE
        } else {
            ivOverlayStatus.setImageResource(R.drawable.ic_warning)
            tvOverlayHint.text = "未授权：点击右侧按钮开启权限"
            tvOverlayHint.setTextColor(Color.parseColor("#FF9800"))
            btnGrantOverlay.visibility = View.VISIBLE
        }

        // 媒体监听权限检查
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        val canListenMedia = enabledPackages.contains(packageName)
        if (canListenMedia) {
            ivMediaStatus.setImageResource(R.drawable.ic_check)
            tvMediaHint.text = "已授权：可自动同步第三方音乐进度"
            tvMediaHint.setTextColor(Color.parseColor("#4CAF50"))
            btnGrantMedia.visibility = View.GONE
        } else {
            ivMediaStatus.setImageResource(R.drawable.ic_warning)
            tvMediaHint.text = "未授权：请在系统设置中允许【浮光歌词】读取通知"
            tvMediaHint.setTextColor(Color.parseColor("#FF9800"))
            btnGrantMedia.visibility = View.VISIBLE
        }
    }

    private fun updateFloatingButtonState() {
        if (FloatingLyricService.isRunning) {
            if (FloatingLyricService.isLocked) {
                btnToggleFloatingService.text = "🔓 悬浮窗已锁定 (点击解锁)"
                btnToggleFloatingService.setBackgroundColor(Color.parseColor("#FFA000"))
            } else {
                btnToggleFloatingService.text = "关闭桌面悬浮歌词"
                btnToggleFloatingService.setBackgroundColor(Color.parseColor("#E53935"))
            }
        } else {
            btnToggleFloatingService.text = "开启桌面悬浮歌词"
            btnToggleFloatingService.setBackgroundColor(Color.parseColor("#2196F3"))
        }
    }

    private fun updateDemoButtonState() {
        if (LocalDemoPlayer.isDemoPlaying()) {
            btnTestDemo.text = "⏸ 暂停测试播放"
        } else {
            btnTestDemo.text = "▶ 立即测试播放 (周杰伦-晴天)"
        }
    }

    private fun updateOffsetDisplay() {
        val offset = LyricManager.offsetMs
        tvMainOffset.text = if (offset == 0L) {
            "0.0s (复位)"
        } else {
            "${if (offset > 0) "+" else ""}${offset / 1000f}s (点击复位)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LyricManager.removeListener(lyricListener)
    }
}
