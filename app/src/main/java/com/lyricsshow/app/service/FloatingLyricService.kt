package com.lyricsshow.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lyricsshow.app.MainActivity
import com.lyricsshow.app.R
import com.lyricsshow.app.model.LrcLine
import com.lyricsshow.app.model.LyricManager
import com.lyricsshow.app.ui.LyricAdapter
import com.lyricsshow.app.ui.SearchLyricDialog
import com.lyricsshow.app.util.LocalDemoPlayer
import kotlin.math.abs

class FloatingLyricService : Service() {

    enum class FloatMode(val id: Int) {
        SINGLE_LINE(1), // 1. 一行模式
        LARGE(2),       // 2. 全屏/很大模式
        CIRCLE(3);      // 3. 小圈圈模式

        companion object {
            fun fromId(id: Int): FloatMode = values().firstOrNull { it.id == id } ?: SINGLE_LINE
        }
    }

    companion object {
        private const val TAG = "FloatingLyricService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TOGGLE_LOCK = "ACTION_TOGGLE_LOCK"
        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"

        var isRunning: Boolean = false
            private set
        var isLocked: Boolean = false
            private set
        var currentMode: FloatMode = FloatMode.SINGLE_LINE
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // 模式容器
    private lateinit var viewCircleMode: View
    private lateinit var viewSingleLineMode: View
    private lateinit var viewLargeMode: View

    // 一行模式组件
    private lateinit var tvSingleLyric: TextView
    private lateinit var btnSinglePlayPause: ImageView

    // 很大/全屏模式组件
    private lateinit var tvLargeSongTitle: TextView
    private lateinit var tvLargeSongArtist: TextView
    private lateinit var rvLargeLyrics: RecyclerView
    private lateinit var largeLyricAdapter: LyricAdapter
    private lateinit var tvLargeProgressTime: TextView
    private lateinit var tvLargeOffset: TextView
    private lateinit var btnLargePlayPause: ImageView

    private var userIsScrollingLyrics = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private val notificationUpdateRunnable = Runnable {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(1001, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "更新通知异常: ${e.message}")
        }
    }

    private val lyricListener = object : LyricManager.LyricListener {
        override fun onLyricProgress(
            currentText: String,
            nextText: String,
            currentIndex: Int,
            positionMs: Long,
            totalMs: Long
        ) {
            mainHandler.post {
                val text = if (currentText.isBlank()) "浮光歌词" else currentText
                tvSingleLyric.text = text

                if (currentMode == FloatMode.LARGE) {
                    largeLyricAdapter.setCurrentIndex(currentIndex)
                    if (!userIsScrollingLyrics && currentIndex in 0 until largeLyricAdapter.itemCount) {
                        val scrollTarget = (currentIndex - 2).coerceAtLeast(0)
                        rvLargeLyrics.smoothScrollToPosition(scrollTarget)
                    }
                    tvLargeProgressTime.text = "${formatTime(positionMs)} / ${formatTime(totalMs)}"
                }
            }
        }

        override fun onLyricsLoaded(lines: List<LrcLine>) {
            mainHandler.post {
                largeLyricAdapter.setLyrics(lines)
            }
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            mainHandler.post {
                val res = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                btnSinglePlayPause.setImageResource(res)
                btnLargePlayPause.setImageResource(res)
                debounceUpdateNotification()
            }
        }

        override fun onTrackChanged(title: String, artist: String) {
            mainHandler.post {
                val t = if (title.isNotBlank()) title else "浮光歌词"
                val a = if (artist.isNotBlank()) artist else "等待音乐播放..."
                tvLargeSongTitle.text = t
                tvLargeSongArtist.text = a
                debounceUpdateNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("floating_prefs", Context.MODE_PRIVATE)
        currentMode = FloatMode.fromId(prefs.getInt("float_mode", FloatMode.SINGLE_LINE.id))
        createNotificationChannel()
        initFloatingView()
        LyricManager.addListener(lyricListener)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCK -> {
                toggleLock()
            }
            ACTION_PLAY_PAUSE -> {
                togglePlayback()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1001,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1001, buildNotification())
        }
        return START_STICKY
    }

    private fun initFloatingView() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_LyricsShow)
            val inflater = LayoutInflater.from(themedContext)
            floatingView = inflater.inflate(R.layout.layout_floating_lyric, null)

            // 模式根容器
            viewCircleMode = floatingView!!.findViewById(R.id.viewCircleMode)
            viewSingleLineMode = floatingView!!.findViewById(R.id.viewSingleLineMode)
            viewLargeMode = floatingView!!.findViewById(R.id.viewLargeMode)

            // 一行模式组件
            tvSingleLyric = floatingView!!.findViewById(R.id.tvSingleLyric)
            btnSinglePlayPause = floatingView!!.findViewById(R.id.btnSinglePlayPause)
            tvSingleLyric.isSelected = true

            // 全屏/很大模式组件
            tvLargeSongTitle = floatingView!!.findViewById(R.id.tvLargeSongTitle)
            tvLargeSongArtist = floatingView!!.findViewById(R.id.tvLargeSongArtist)
            rvLargeLyrics = floatingView!!.findViewById(R.id.rvLargeLyrics)
            tvLargeProgressTime = floatingView!!.findViewById(R.id.tvLargeProgressTime)
            tvLargeOffset = floatingView!!.findViewById(R.id.tvLargeOffset)
            btnLargePlayPause = floatingView!!.findViewById(R.id.btnLargePlayPause)

            // 设置 RecyclerView
            rvLargeLyrics.layoutManager = LinearLayoutManager(this)
            largeLyricAdapter = LyricAdapter { clickedLine ->
                seekTo(clickedLine.timeMs)
            }
            rvLargeLyrics.adapter = largeLyricAdapter
            rvLargeLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    userIsScrollingLyrics = (newState == RecyclerView.SCROLL_STATE_DRAGGING)
                }
            })

            // 初始化布局参数
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("single_x", 40)
                y = prefs.getInt("single_y", 120)
            }

            setupModeSwitchButtons()
            setupDragAndTouch()

            windowManager?.addView(floatingView, layoutParams)
            applyMode(currentMode)
        } catch (e: Exception) {
            Log.e(TAG, "初始化悬浮窗异常: ${e.message}", e)
        }
    }

    /**
     * 切换 3 种悬浮模式：一行模式 / 全屏很大模式 / 小圈圈模式
     */
    fun switchMode(mode: FloatMode) {
        applyMode(mode)
    }

    private fun applyMode(mode: FloatMode) {
        currentMode = mode
        prefs.edit().putInt("float_mode", mode.id).apply()

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        viewCircleMode.visibility = if (mode == FloatMode.CIRCLE) View.VISIBLE else View.GONE
        viewSingleLineMode.visibility = if (mode == FloatMode.SINGLE_LINE) View.VISIBLE else View.GONE
        viewLargeMode.visibility = if (mode == FloatMode.LARGE) View.VISIBLE else View.GONE

        layoutParams?.let { lp ->
            when (mode) {
                FloatMode.CIRCLE -> {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = prefs.getInt("circle_x", 40)
                    lp.y = prefs.getInt("circle_y", 200)
                }
                FloatMode.SINGLE_LINE -> {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = prefs.getInt("single_x", 40)
                    lp.y = prefs.getInt("single_y", 120)
                }
                FloatMode.LARGE -> {
                    // 全屏/很大模式：覆盖屏幕 94% 宽度，85% 高度
                    lp.width = (screenWidth * 0.94f).toInt()
                    lp.height = (screenHeight * 0.85f).toInt()
                    lp.gravity = Gravity.CENTER
                    lp.x = 0
                    lp.y = 0

                    // 刷新当前歌词列表
                    largeLyricAdapter.setLyrics(LyricManager.lyricLines)
                    largeLyricAdapter.setCurrentIndex(LyricManager.currentLineIndex)
                }
            }
            windowManager?.updateViewLayout(floatingView, lp)
        }
    }

    private fun setupModeSwitchButtons() {
        // --- 模式 1 (一行模式) 的按钮 ---
        floatingView?.findViewById<View>(R.id.btnSingleToLarge)?.setOnClickListener {
            switchMode(FloatMode.LARGE)
            Toast.makeText(this, "已展开为全屏大视窗模式", Toast.LENGTH_SHORT).show()
        }

        floatingView?.findViewById<View>(R.id.btnSingleToCircle)?.setOnClickListener {
            switchMode(FloatMode.CIRCLE)
            Toast.makeText(this, "已缩小为小圈圈，点击可展开", Toast.LENGTH_SHORT).show()
        }

        btnSinglePlayPause.setOnClickListener {
            togglePlayback()
        }

        floatingView?.findViewById<View>(R.id.btnSingleClose)?.setOnClickListener {
            stopSelf()
        }

        // --- 模式 2 (全屏/很大模式) 的按钮 ---
        floatingView?.findViewById<View>(R.id.btnLargeToSingle)?.setOnClickListener {
            switchMode(FloatMode.SINGLE_LINE)
            Toast.makeText(this, "已切换为一行胶囊模式", Toast.LENGTH_SHORT).show()
        }

        floatingView?.findViewById<View>(R.id.btnLargeToCircle)?.setOnClickListener {
            switchMode(FloatMode.CIRCLE)
            Toast.makeText(this, "已缩小为小圈圈", Toast.LENGTH_SHORT).show()
        }

        floatingView?.findViewById<View>(R.id.btnLargeSearch)?.setOnClickListener {
            SearchLyricDialog.show(this)
        }

        floatingView?.findViewById<View>(R.id.btnLargeLock)?.setOnClickListener {
            toggleLock()
        }

        floatingView?.findViewById<View>(R.id.btnLargeClose)?.setOnClickListener {
            stopSelf()
        }

        // 媒体控制
        floatingView?.findViewById<View>(R.id.btnLargePrev)?.setOnClickListener {
            MediaListenerService.skipToPrevious()
        }

        btnLargePlayPause.setOnClickListener {
            togglePlayback()
        }

        floatingView?.findViewById<View>(R.id.btnLargeNext)?.setOnClickListener {
            MediaListenerService.skipToNext()
        }

        // 时间轴微调
        floatingView?.findViewById<View>(R.id.btnLargeOffsetMinus)?.setOnClickListener {
            LyricManager.adjustOffset(-500L)
            updateOffsetDisplay()
        }

        floatingView?.findViewById<View>(R.id.btnLargeOffsetPlus)?.setOnClickListener {
            LyricManager.adjustOffset(500L)
            updateOffsetDisplay()
        }

        tvLargeOffset.setOnClickListener {
            LyricManager.resetOffset()
            updateOffsetDisplay()
        }
    }

    private fun setupDragAndTouch() {
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var hasMoved = false

        // 统一触摸拖动监听
        floatingView?.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (abs(dx) > 8 || abs(dy) > 8) {
                        hasMoved = true
                        if (currentMode != FloatMode.LARGE) {
                            layoutParams?.x = initialX + dx
                            layoutParams?.y = initialY + dy
                            windowManager?.updateViewLayout(floatingView, layoutParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        // 单击小圈圈：展开为一行模式
                        if (currentMode == FloatMode.CIRCLE) {
                            switchMode(FloatMode.SINGLE_LINE)
                        }
                    } else {
                        // 记录坐标
                        layoutParams?.let { lp ->
                            if (currentMode == FloatMode.CIRCLE) {
                                prefs.edit().putInt("circle_x", lp.x).putInt("circle_y", lp.y).apply()
                            } else if (currentMode == FloatMode.SINGLE_LINE) {
                                prefs.edit().putInt("single_x", lp.x).putInt("single_y", lp.y).apply()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePlayback() {
        if (LocalDemoPlayer.isDemoPlaying()) {
            LocalDemoPlayer.toggleDemo()
        } else {
            MediaListenerService.togglePlayPause()
        }
    }

    private fun seekTo(positionMs: Long) {
        if (LocalDemoPlayer.isDemoPlaying()) {
            LocalDemoPlayer.seekTo(positionMs)
        } else {
            MediaListenerService.seekTo(positionMs)
        }
    }

    private fun updateOffsetDisplay() {
        val offset = LyricManager.offsetMs
        tvLargeOffset.text = if (offset == 0L) {
            "微调"
        } else {
            "${if (offset > 0) "+" else ""}${offset / 1000f}s"
        }
    }

    private var unlockBadgeView: View? = null
    private var badgeLayoutParams: WindowManager.LayoutParams? = null

    private fun toggleLock() {
        isLocked = !isLocked
        layoutParams?.let { lp ->
            if (isLocked) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                floatingView?.alpha = 0.85f
                windowManager?.updateViewLayout(floatingView, lp)
                showUnlockBadge()
                Toast.makeText(this, "已开启穿透防误触，点击屏幕小锁 🔒 即可随时解锁", Toast.LENGTH_LONG).show()
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                floatingView?.alpha = 1.0f
                windowManager?.updateViewLayout(floatingView, lp)
                hideUnlockBadge()
                Toast.makeText(this, "悬浮窗已解锁", Toast.LENGTH_SHORT).show()
            }
        }
        debounceUpdateNotification()
    }

    private fun showUnlockBadge() {
        if (unlockBadgeView != null) return
        try {
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_LyricsShow)
            unlockBadgeView = LayoutInflater.from(themedContext).inflate(R.layout.layout_unlock_badge, null)

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val badgeX = (layoutParams?.x ?: 60) + 260
            val badgeY = (layoutParams?.y ?: 120) - 20

            badgeLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = badgeX.coerceAtLeast(10)
                y = badgeY.coerceAtLeast(10)
            }

            unlockBadgeView?.setOnClickListener {
                toggleLock()
            }

            windowManager?.addView(unlockBadgeView, badgeLayoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "显示解锁小把手异常: ${e.message}", e)
        }
    }

    private fun hideUnlockBadge() {
        unlockBadgeView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "移除解锁小把手异常: ${e.message}")
            }
            unlockBadgeView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_lyrics_channel",
                "实时歌词悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮歌词服务后台运行"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val title = if (LyricManager.currentTitle.isNotBlank()) LyricManager.currentTitle else "浮光歌词"
        val text = if (LyricManager.currentLineIndex in LyricManager.lyricLines.indices) {
            LyricManager.lyricLines[LyricManager.currentLineIndex].text
        } else {
            "悬浮窗运行中..."
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockIntent = Intent(this, FloatingLyricService::class.java).apply {
            action = ACTION_TOGGLE_LOCK
        }
        val lockPi = PendingIntent.getService(
            this, 1, lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, FloatingLyricService::class.java).apply {
            action = ACTION_STOP
        }
        val closePi = PendingIntent.getService(
            this, 2, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockTitle = if (isLocked) "🔓 解锁悬浮窗" else "🔒 锁定悬浮窗"

        return NotificationCompat.Builder(this, "floating_lyrics_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentIntent(openPi)
            .addAction(0, lockTitle, lockPi)
            .addAction(0, "关闭悬浮窗", closePi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun debounceUpdateNotification() {
        mainHandler.removeCallbacks(notificationUpdateRunnable)
        mainHandler.postDelayed(notificationUpdateRunnable, 400)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%02d:%02d".format(min, sec)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isLocked = false
        hideUnlockBadge()
        LyricManager.removeListener(lyricListener)
        mainHandler.removeCallbacks(notificationUpdateRunnable)

        floatingView?.let {
            windowManager?.removeView(it)
            floatingView = null
        }
    }
}
