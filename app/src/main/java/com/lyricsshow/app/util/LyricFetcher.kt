package com.lyricsshow.app.util

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object LyricFetcher {
    private const val TAG = "LyricFetcher"

    data class SearchCandidate(
        val title: String,
        val artist: String,
        val album: String,
        val rawLrcFetcher: () -> String?
    )

    /**
     * 判断是否为广告
     */
    fun isAd(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("advertisement") || 
               lower.contains("广告") || 
               lower.contains("spotify • 1 of") ||
               lower.contains("sponsored")
    }

    /**
     * 获取歌词，多级降级搜索策略：
     * 1. 过滤广告
     * 2. 本地缓存精准匹配
     * 3. 精确搜索：歌名 + 歌手（酷狗 -> 网易云 -> LRCLIB）
     * 4. 宽泛搜索：仅清洗后歌名（解决 Spotify 英文名与原曲中文名不一致问题，如 Stefanie Sun vs 孙燕姿）
     * 5. 原始歌名搜索（应对特殊标题）
     */
    fun getLyrics(context: Context, rawTitle: String, rawArtist: String): String? {
        if (rawTitle.isBlank()) return null

        if (isAd(rawTitle)) {
            Log.d(TAG, "检测到广告音频，跳过歌词请求: $rawTitle")
            return "[00:00.00] 正在播放广告..."
        }

        val cleanTitle = cleanSongTitle(rawTitle)
        val cleanArtist = cleanArtistName(rawArtist)

        val cacheKey = md5("${cleanTitle}_${cleanArtist}")
        val cacheDir = File(context.cacheDir, "lyrics").apply { mkdirs() }
        val cacheFile = File(cacheDir, "$cacheKey.lrc")

        // 1. 本地缓存读取
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val cached = cacheFile.readText()
                if (cached.isNotBlank()) {
                    Log.d(TAG, "命中本地缓存歌词: $cleanTitle - $cleanArtist")
                    return cached
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取缓存失败: ${e.message}")
            }
        }

        var lrc: String? = null

        // 2. 第一轮：精确搜索 (歌名 + 歌手)
        if (cleanArtist.isNotBlank() && cleanArtist != "未知" && cleanArtist != "Unknown") {
            val fullQuery = "$cleanTitle $cleanArtist"
            Log.d(TAG, "第一轮精确搜索: $fullQuery")
            lrc = fetchFromKugou(fullQuery)
            if (lrc.isNullOrBlank()) lrc = fetchFromNetease(fullQuery)
            if (lrc.isNullOrBlank()) lrc = fetchFromLrcLib(cleanTitle, cleanArtist)
        }

        // 3. 第二轮：宽泛搜索 (仅清洗后的歌名)
        // 解决 Spotify/海外版常使用英文名 (如 "比较幸福 Stefanie Sun", 酷狗/网易云按孙燕姿索引)
        if (lrc.isNullOrBlank() && cleanTitle.isNotBlank()) {
            Log.d(TAG, "第二轮宽泛搜索(仅歌名): $cleanTitle")
            lrc = fetchFromKugou(cleanTitle)
            if (lrc.isNullOrBlank()) lrc = fetchFromNetease(cleanTitle)
            if (lrc.isNullOrBlank()) lrc = fetchFromLrcLib(cleanTitle, "")
        }

        // 4. 第三轮：原始字符串搜索 (防止过度清洗)
        if (lrc.isNullOrBlank() && rawTitle != cleanTitle) {
            Log.d(TAG, "第三轮原始标题搜索: $rawTitle")
            lrc = fetchFromKugou(rawTitle)
            if (lrc.isNullOrBlank()) lrc = fetchFromNetease(rawTitle)
        }

        // 写入本地缓存
        if (!lrc.isNullOrBlank()) {
            try {
                cacheFile.writeText(lrc)
                Log.d(TAG, "已成功缓存歌词: $cleanTitle - $cleanArtist")
            } catch (e: Exception) {
                Log.w(TAG, "保存缓存失败: ${e.message}")
            }
        } else {
            Log.w(TAG, "未能搜索到歌词: $rawTitle ($rawArtist)")
        }

        return lrc
    }

    /**
     * 手动搜索候选歌曲列表（供用户手动选择）
     */
    fun searchCandidates(query: String): List<SearchCandidate> {
        val result = mutableListOf<SearchCandidate>()
        val cleanQuery = cleanSongTitle(query)
        if (cleanQuery.isBlank()) return emptyList()

        try {
            // 从酷狗搜索前 5 条
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$encoded&page=1&pagesize=5"
            val json = httpGet(searchUrl)
            if (!json.isNullOrBlank()) {
                val root = JSONObject(json)
                val infoArr = root.optJSONObject("data")?.optJSONArray("info")
                if (infoArr != null) {
                    for (i in 0 until infoArr.length()) {
                        val item = infoArr.getJSONObject(i)
                        val songName = item.optString("songname")
                        val singerName = item.optString("singername")
                        val albumName = item.optString("album_name")
                        val hash = item.optString("hash")

                        if (hash.isNotBlank() && songName.isNotBlank()) {
                            result.add(
                                SearchCandidate(
                                    title = songName,
                                    artist = singerName,
                                    album = if (albumName.isNotBlank()) "《$albumName》" else "",
                                    rawLrcFetcher = { downloadKugouLyricByHash(hash) }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "搜索候选歌曲异常: ${e.message}")
        }

        return result
    }

    /**
     * 手动绑定并保存歌词到缓存
     */
    fun saveManualLyric(context: Context, title: String, artist: String, lrcText: String) {
        val cleanTitle = cleanSongTitle(title)
        val cleanArtist = cleanArtistName(artist)
        val cacheKey = md5("${cleanTitle}_${cleanArtist}")
        val cacheDir = File(context.cacheDir, "lyrics").apply { mkdirs() }
        val cacheFile = File(cacheDir, "$cacheKey.lrc")
        try {
            cacheFile.writeText(lrcText)
            Log.d(TAG, "手动关联并保存歌词成功: $cleanTitle - $cleanArtist")
        } catch (e: Exception) {
            Log.e(TAG, "手动保存歌词失败: ${e.message}")
        }
    }

    private fun fetchFromKugou(query: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$encodedQuery&page=1&pagesize=1"
            val searchJson = httpGet(searchUrl) ?: return null

            val rootObj = JSONObject(searchJson)
            val infoArr = rootObj.optJSONObject("data")?.optJSONArray("info") ?: return null
            if (infoArr.length() == 0) return null

            val hash = infoArr.getJSONObject(0).optString("hash")
            if (hash.isBlank()) return null

            return downloadKugouLyricByHash(hash)
        } catch (e: Exception) {
            Log.w(TAG, "酷狗搜索异常 ($query): ${e.message}")
        }
        return null
    }

    private fun downloadKugouLyricByHash(hash: String): String? {
        try {
            val lyricSearchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=$hash"
            val lyricSearchJson = httpGet(lyricSearchUrl) ?: return null
            val candidates = JSONObject(lyricSearchJson).optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val firstCand = candidates.getJSONObject(0)
            val id = firstCand.optString("id")
            val accessKey = firstCand.optString("accesskey")
            if (id.isBlank() || accessKey.isBlank()) return null

            val downloadUrl = "http://krcs.kugou.com/download?ver=1&client=mobi&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val downloadJson = httpGet(downloadUrl) ?: return null
            val base64Content = JSONObject(downloadJson).optString("content")

            if (base64Content.isNotBlank()) {
                val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
                val lrcText = String(decodedBytes, Charsets.UTF_8)
                if (lrcText.isNotBlank()) {
                    return lrcText
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载酷狗歌词失败: ${e.message}")
        }
        return null
    }

    private fun fetchFromNetease(query: String): String? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://music.163.com/api/cloudsearch/pc?s=$encodedQuery&type=1&offset=0&limit=1"
            val searchJson = httpGet(searchUrl) ?: return null

            val songsArray = JSONObject(searchJson).optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songsArray.length() == 0) return null

            val songId = songsArray.getJSONObject(0).optLong("id", -1L)
            if (songId <= 0) return null

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
            val lyricJson = httpGet(lyricUrl) ?: return null

            val lrcContent = JSONObject(lyricJson).optJSONObject("lrc")?.optString("lyric")
            if (!lrcContent.isNullOrBlank() && lrcContent != "null") {
                return lrcContent
            }
        } catch (e: Exception) {
            Log.w(TAG, "网易云搜索异常 ($query): ${e.message}")
        }
        return null
    }

    private fun fetchFromLrcLib(title: String, artist: String): String? {
        try {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://lrclib.net/api/search?q=$encoded"
            val searchJson = httpGet(searchUrl) ?: return null
            val arr = JSONArray(searchJson)
            if (arr.length() > 0) {
                for (i in 0 until arr.length()) {
                    val synced = arr.getJSONObject(i).optString("syncedLyrics")
                    if (synced.isNotBlank() && synced != "null") {
                        return synced
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB搜索异常 ($title): ${e.message}")
        }
        return null
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; LyricsShow/1.0)")
            conn.setRequestProperty("Accept", "*/*")

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun cleanSongTitle(raw: String): String {
        var title = raw.trim()
        // 移除括号中的常见现场/混音/重制/伴奏说明: (Live), [Remastered], (feat. XXX), (Cover), (伴奏)
        title = title.replace(Regex("(?i)[\\(\\[\\【（].*?(?:live|remaster|mix|feat|ft\\.|version|edit|edition|cover|伴奏|重制|现场|官方).*?[\\)\\]\\】）]"), "")
        // 移除末尾通过 "-" 连接的修饰语: "晴天 - 伴奏", "Shape of You - Acoustic"
        title = title.replace(Regex("(?i)[\\-–—]\\s*(?:live|remaster|mix|acoustic|demo|edit|version|cover|伴奏|重制|现场).*$"), "")
        // 移除括号及其内容（如果剩余文本长度 >= 2）
        val withoutParens = title.replace(Regex("[\\(\\[\\【（].*?[\\)\\]\\】）]"), "").trim()
        if (withoutParens.length >= 2) {
            title = withoutParens
        }
        return title.trim(' ', '-', '_', '•', '·', ':')
    }

    fun cleanArtistName(raw: String): String {
        var artist = raw.trim()
        val split = artist.split(Regex("[,/&、]"))
        if (split.isNotEmpty() && split[0].isNotBlank()) {
            artist = split[0].trim()
        }
        return artist
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
