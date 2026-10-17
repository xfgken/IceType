package com.xfgken.iceime.data

import android.content.Context
import com.xfgken.iceime.core.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 联想词表（**前缀续写**）。
 *
 * 数据来自构建期脚本 scripts/gen-predict-lexicon.py 生成的 assets/predict/lexicon.txt：
 *   P<TAB>首字<TAB>词…     单字上屏后的续写（如「好」→ 好啊/好的/好多）
 *   W<TAB>前两字<TAB>词…   词上屏后的续写（如「你好」→ 你好吗/你好啊/你好坏）
 *
 * 说明：rime_ice 方案未启用 librime 的 predict_translator，librime 本身不提供「选词后联想」，
 * 因此用预生成的静态词表实现真实的联想，而不是放一个无效入口。
 *
 * 隐私：词表是只读静态词库，不包含、也不记录任何用户输入内容。
 */
object PredictLexicon {

    private const val ASSET_PATH = "predict/lexicon.txt"
    private const val MAX_WORDS = 6

    @Volatile
    private var single: Map<String, List<String>>? = null

    @Volatile
    private var prefix: Map<String, List<String>>? = null

    /** 按需加载（首次用到联想时加载，避免拖慢启动）。 */
    fun ensureLoaded(context: Context): Boolean {
        if (single != null && prefix != null) return true
        synchronized(this) {
            if (single != null && prefix != null) return true
            val started = System.currentTimeMillis()
            return try {
                val singleMap = HashMap<String, List<String>>(8192)
                val prefixMap = HashMap<String, List<String>>(65536)
                context.assets.open(ASSET_PATH).use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        reader.forEachLine { line ->
                            val parts = line.split('\t')
                            if (parts.size < 3) return@forEachLine
                            val words = parts[2].split(' ').filter { it.isNotEmpty() }
                            if (words.isEmpty()) return@forEachLine
                            when (parts[0]) {
                                "P" -> singleMap[parts[1]] = words
                                "W" -> prefixMap[parts[1]] = words
                            }
                        }
                    }
                }
                single = singleMap
                prefix = prefixMap
                AppLog.i(
                    AppLog.Category.ENGINE,
                    "联想词表已载入：单字 ${singleMap.size} 键、前缀 ${prefixMap.size} 键，" +
                        "用时 ${System.currentTimeMillis() - started} ms",
                )
                true
            } catch (t: Throwable) {
                AppLog.throwable(AppLog.Category.ERROR, "联想词表载入失败", t)
                single = emptyMap()
                prefix = emptyMap()
                false
            }
        }
    }

    /**
     * 取「接着往下写」的联想词。
     *
     * @param text 刚上屏的文本
     * @return 以 text 为前缀的词；text 为单字时返回以该字开头的词
     */
    fun wordsFor(text: String): List<String> {
        val committed = text.trim()
        if (committed.isEmpty()) return emptyList()
        val first = committed[0]
        if (!first.isCjk()) return emptyList()

        val result = LinkedHashSet<String>()
        if (committed.length >= 2) {
            val key = committed.take(2)
            prefix?.get(key)?.forEach { word ->
                if (word.length > committed.length && word.startsWith(committed)) {
                    // 只取「后半段」：上屏「你好」时给「吗 / 啊 / 坏」，
                    // 这样点击后拼起来才是「你好吗」，而不是「你好你好吗」。
                    result.add(word.substring(committed.length))
                }
            }
        } else {
            single?.get(committed)?.forEach { word ->
                if (word.length > committed.length && word.startsWith(committed)) {
                    result.add(word.substring(committed.length))
                } else if (word != committed) {
                    result.add(word)
                }
            }
        }
        return result.filter { it.isNotEmpty() }.take(MAX_WORDS)
    }

    private fun Char.isCjk(): Boolean = this.code in 0x4E00..0x9FFF

    val isLoaded: Boolean get() = single != null
}