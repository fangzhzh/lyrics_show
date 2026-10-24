package com.lyricsshow.app.ui

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

/**
 * 自定义 LinearLayoutManager：
 * 保证选中的歌词项无论向上还是向下滚动，都能平滑定位于 RecyclerView 的正中间，
 * 从而在当前播放句子的下方保留充分的提前量，并提供沉浸美观的歌词居中视觉。
 */
class CenterLayoutManager : LinearLayoutManager {

    constructor(context: Context) : super(context)
    constructor(context: Context, orientation: Int, reverseLayout: Boolean) : super(context, orientation, reverseLayout)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State?,
        position: Int
    ) {
        val smoothScroller = CenterSmoothScroller(recyclerView.context)
        smoothScroller.targetPosition = position
        startSmoothScroll(smoothScroller)
    }

    private class CenterSmoothScroller(context: Context) : LinearSmoothScroller(context) {

        override fun calculateDtToFit(
            viewStart: Int,
            viewEnd: Int,
            boxStart: Int,
            boxEnd: Int,
            snapPreference: Int
        ): Int {
            // boxStart 是 layoutManager.paddingTop
            // boxEnd 是 layoutManager.height - layoutManager.paddingBottom
            // 将 item 的中心 (viewStart + viewEnd) / 2 对齐到视口可见中心 (boxStart + boxEnd) / 2
            val boxCenter = boxStart + (boxEnd - boxStart) / 2
            val viewCenter = viewStart + (viewEnd - viewStart) / 2
            return boxCenter - viewCenter
        }

        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
            // 平滑温和的滚动速率 (数值越大越平缓流畅)
            return 45f / displayMetrics.densityDpi
        }
    }
}
