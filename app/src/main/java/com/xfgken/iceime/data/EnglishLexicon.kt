package com.xfgken.iceime.data

import android.content.Context
import com.xfgken.iceime.core.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 英文词表（**前缀补全**）。
 *
 * 数据来自随包资源 `rime/en_dicts/en.dict.yaml`（雾凇拼音自带的英文词库，格式为 `词条<TAB>编码`）。
 * librime 的 melt_eng 只有在「中文模式下混输」时才作为次翻译器参与候选；英文键盘走的是
 * ascii 模式（直接上屏、不产生候选），所以「英文候选」这一开关此前等于没有效果。
 *
 * 这里把词表读进内存并按**前 2 个字母**建索引，英文键盘下按已输入的字母做前缀补全：
 * 候选显示完整单词，点选时只补全「还没打的那一段」，因此不需要删除任何已上屏字符，
 * 在任何输入框里都安全。
 *
 * 隐私：只读静态词库，不包含也不记录任何用户输入内容。
 */
object EnglishLexicon {

    private const val ASSET_PATH = "rime/en_dicts/en.dict.yaml"

    /** 索引键长度：只取前 2 个字母，既够用又省内存。 */
    private const val KEY_LEN = 2

    @Volatile
    private var index: HashMap<String, List<String>>? = null

    /** 已载入的词条数量（供设置页/日志显示）。 */
    @Volatile
    var wordCount: Int = 0
        private set

    /** 按需加载（首次进入英文键盘、且英文候选开启时调用）。 */
    fun ensureLoaded(context: Context): Boolean {
        index?.let { return true }
        synchronized(this) {
            index?.let { return true }
            val started = System.currentTimeMillis()
            return try {
                val map = HashMap<String, MutableList<String>>(1024)
                var count = 0
                context.assets.open(ASSET_PATH).use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        reader.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith("#") || line.startsWith("---")) {
                                return@forEachLine
                            }
                            val word = line.substringBefore('\t').trim()
                            if (!word.matches(Regex("[A-Za-z][A-Za-z'\\-]{1,23}"))) return@forEachLine
                            val key = word.lowercase().take(KEY_LEN)
                            if (key.length < KEY_LEN) return@forEachLine
                            map.getOrPut(key) { ArrayList() }.add(word)
                            count++
                        }
                    }
                }
                // 同一前缀下：先短词（更像"补全"），再按词典顺序
                map.values.forEach { it.sortWith(compareBy({ w -> w.length }, { w -> w })) }
                index = HashMap(map)
                wordCount = count
                AppLog.i(
                    AppLog.Category.ENGINE,
                    "英文词表已载入：$count 个词、${map.size} 个前缀，用时 " +
                        "${System.currentTimeMillis() - started} ms",
                )
                true
            } catch (t: Throwable) {
                AppLog.throwable(AppLog.Category.ERROR, "英文词表载入失败", t)
                false
            }
        }
    }

    /**
     * 前缀补全。
     * @param prefix 已经打出来的字母
     * @return 完整单词列表（最多 [limit] 个），不含与 prefix 相同的那一项
     */
    fun suggest(prefix: String, limit: Int = 8): List<String> {
        val idx = index ?: return emptyList()
        if (prefix.length < KEY_LEN) return emptyList()
        val lower = prefix.lowercase()
        val bucket = idx[lower.take(KEY_LEN)] ?: return emptyList()
        val out = ArrayList<String>(limit)
        for (w in bucket) {
            if (w.length <= prefix.length) continue
            if (!w.lowercase().startsWith(lower)) continue
            out.add(w)
            if (out.size >= limit) break
        }
        return out
    }
}
