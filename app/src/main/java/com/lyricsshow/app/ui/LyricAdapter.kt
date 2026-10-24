package com.lyricsshow.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lyricsshow.app.R
import com.lyricsshow.app.model.LrcLine

class LyricAdapter(
    private val onLineClick: (LrcLine) -> Unit
) : RecyclerView.Adapter<LyricAdapter.LyricViewHolder>() {

    var lines: List<LrcLine> = emptyList()
        private set

    var currentIndex: Int = -1
        private set

    fun setLyrics(newLines: List<LrcLine>) {
        lines = newLines
        currentIndex = -1
        notifyDataSetChanged()
    }

    fun setCurrentIndex(index: Int) {
        if (currentIndex != index) {
            val oldIndex = currentIndex
            currentIndex = index
            if (oldIndex in lines.indices) notifyItemChanged(oldIndex)
            if (currentIndex in lines.indices) notifyItemChanged(currentIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_immersive_lyric, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lines[position]
        holder.bind(line, position == currentIndex)
    }

    override fun getItemCount(): Int = lines.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLineText: TextView = itemView.findViewById(R.id.tvLineText)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos in lines.indices) {
                    onLineClick(lines[pos])
                }
            }
        }

        fun bind(line: LrcLine, isCurrent: Boolean) {
            tvLineText.text = line.text
            if (isCurrent) {
                tvLineText.setTextColor(Color.WHITE)
                tvLineText.textSize = 22f
                tvLineText.typeface = Typeface.DEFAULT_BOLD
                tvLineText.alpha = 1.0f
            } else {
                tvLineText.setTextColor(Color.parseColor("#73FFFFFF"))
                tvLineText.textSize = 16f
                tvLineText.typeface = Typeface.DEFAULT
                tvLineText.alpha = 0.7f
            }
        }
    }
}
