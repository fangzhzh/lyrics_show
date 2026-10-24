package com.lyricsshow.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.lyricsshow.app.R
import com.lyricsshow.app.model.LyricManager
import com.lyricsshow.app.util.LyricFetcher
import java.util.concurrent.Executors

object SearchLyricDialog {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, defaultKeyword: String = LyricManager.currentTitle) {
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_LyricsShow)
        val dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_search_lyric, null)

        val etKeyword = dialogView.findViewById<EditText>(R.id.etSearchKeyword)
        val btnSearch = dialogView.findViewById<Button>(R.id.btnDoSearch)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.pbSearching)
        val lvResults = dialogView.findViewById<ListView>(R.id.lvSearchResults)
        val tvEmptyHint = dialogView.findViewById<TextView>(R.id.tvEmptyHint)

        etKeyword.setText(LyricFetcher.cleanSongTitle(defaultKeyword))
        etKeyword.setSelection(etKeyword.text.length)

        val candidates = mutableListOf<LyricFetcher.SearchCandidate>()
        val adapter = object : ArrayAdapter<LyricFetcher.SearchCandidate>(
            themedContext,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            candidates
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position) ?: return view

                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)

                text1.text = item.title
                text1.setTextColor(Color.WHITE)
                text1.textSize = 15f

                text2.text = "${item.artist} ${item.album}"
                text2.setTextColor(Color.parseColor("#99FFFFFF"))
                text2.textSize = 12f

                return view
            }
        }
        lvResults.adapter = adapter

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle("🔍 搜索匹配歌词")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        fun doSearch() {
            val query = etKeyword.text.toString().trim()
            if (query.isBlank()) return

            progressBar.visibility = View.VISIBLE
            tvEmptyHint.visibility = View.GONE
            candidates.clear()
            adapter.notifyDataSetChanged()

            executor.execute {
                val list = LyricFetcher.searchCandidates(query)
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    candidates.clear()
                    candidates.addAll(list)
                    adapter.notifyDataSetChanged()

                    if (list.isEmpty()) {
                        tvEmptyHint.text = "未找到相关歌词，请尝试更换关键词"
                        tvEmptyHint.visibility = View.VISIBLE
                    } else {
                        tvEmptyHint.visibility = View.GONE
                    }
                }
            }
        }

        btnSearch.setOnClickListener {
            doSearch()
        }

        lvResults.setOnItemClickListener { _, _, position, _ ->
            val selected = candidates.getOrNull(position) ?: return@setOnItemClickListener
            progressBar.visibility = View.VISIBLE
            Toast.makeText(context, "正在下载并关联歌词...", Toast.LENGTH_SHORT).show()

            executor.execute {
                val lrcText = selected.rawLrcFetcher()
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    if (!lrcText.isNullOrBlank()) {
                        val activeTitle = LyricManager.currentTitle.ifBlank { selected.title }
                        val activeArtist = LyricManager.currentArtist.ifBlank { selected.artist }

                        // 缓存到本地
                        LyricFetcher.saveManualLyric(context, activeTitle, activeArtist, lrcText)

                        // 立即应用到当前播放
                        LyricManager.setManualTrack(
                            title = activeTitle,
                            artist = activeArtist,
                            lrcContent = lrcText,
                            duration = LyricManager.durationMs
                        )

                        Toast.makeText(context, "已成功绑定歌词: ${selected.title}", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "下载此歌词失败，请尝试其他选项", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        dialog.show()

        // 默认自动搜索一次
        if (etKeyword.text.isNotBlank()) {
            doSearch()
        }
    }
}
