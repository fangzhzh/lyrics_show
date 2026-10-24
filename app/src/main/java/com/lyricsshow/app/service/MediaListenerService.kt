package com.lyricsshow.app.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.lyricsshow.app.model.LyricManager

class MediaListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "MediaListenerService"
        var isConnected: Boolean = false
            private set
        var currentController: MediaController? = null
            private set

        /**
         * 控制当前媒体播放
         */
        fun togglePlayPause() {
            currentController?.let { controller ->
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls?.pause()
                } else {
                    controller.transportControls?.play()
                }
            }
        }

        fun skipToNext() {
            currentController?.transportControls?.skipToNext()
        }

        fun skipToPrevious() {
            currentController?.transportControls?.skipToPrevious()
        }

        fun seekTo(positionMs: Long) {
            currentController?.transportControls?.seekTo(positionMs)
        }
    }

    private var sessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "活跃媒体会话更新: count=${controllers?.size ?: 0}")
        updateActiveController(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            handleMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            handlePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Log.d(TAG, "当前媒体会话已销毁")
            currentController = null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.d(TAG, "NotificationListenerService 已连接")

        try {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            componentName = ComponentName(this, MediaListenerService::class.java)

            sessionManager?.let { sm ->
                val sessions = sm.getActiveSessions(componentName)
                updateActiveController(sessions)
                sm.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册 MediaSession 监听失败: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            currentController?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {
            Log.w(TAG, "注销监听失败: ${e.message}")
        }
        currentController = null
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return

        // 优先选择正在播放中的 controller
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        if (active != null) {
            if (active != currentController) {
                currentController?.unregisterCallback(controllerCallback)
                currentController = active
                active.registerCallback(controllerCallback)
            }

            handleMetadata(active.metadata)
            handlePlaybackState(active.playbackState)
        }
    }

    private fun handleMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val controller = currentController
        val state = controller?.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1.0f

        if (title.isNotBlank()) {
            Log.d(TAG, "检测到正在播放: $title - $artist (时长: $duration ms)")
            LyricManager.updateTrack(
                context = applicationContext,
                title = title,
                artist = artist,
                duration = duration,
                playing = isPlaying,
                position = position,
                speed = speed
            )
        }
    }

    private fun handlePlaybackState(state: PlaybackState?) {
        if (state == null) return
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val position = state.position
        val speed = state.playbackSpeed
        LyricManager.updatePlaybackState(playing = isPlaying, position = position, speed = speed)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // 作为后备机制：如果当前没有通过 MediaSession 捕获到正在播放的音乐
        if (currentController == null && sbn != null) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val category = sbn.notification.category

            // 检查是否可能为音乐播放器通知
            if (category == "transport" || extras.containsKey("android.mediaSession")) {
                if (title.isNotBlank() && title != LyricManager.currentTitle) {
                    LyricManager.updateTrack(
                        context = applicationContext,
                        title = title,
                        artist = text,
                        duration = 0L,
                        playing = true,
                        position = 0L
                    )
                }
            }
        }
    }
}
