package com.xfgken.icetype.ime

import android.content.Context
import com.xfgken.icetype.core.AppLog

/**
 * 剪贴板「选词」页用的智能分词。
 *
 * 目标：把一段文字切成「一个个词块」——词语/句式合在一起，而不是逐字切。
 *
 * 做法（完全离线、无第三方库）：
 *  1. **整体不拆**的优先识别：网址、邮箱、@某人、#话题#、数字（含单位/百分比/日期）、英文单词、表情；
 *  2. **中文**用**正向最大匹配**（最多 6 个字）去查内置词表 [WORD_ASSET]：
 *     命中就整词成块（如「我们」「旅游」），没命的连续汉字退化成单字成块（保证一定能选）；
 *  3. 空白并进相邻词块，避免出现看不见的空块。
 *
 * 词表由雾凇拼音官方词库按词频抽取（约 2.6 万条常用词，244KB），随 APK 内置、离线可用。
 */
internal object TextSegmenter {

    /** 内置词表（assets 里的纯文本，一行一个词）。 */
    private const val WORD_ASSET = "segment_words.txt"

    /** 一个词块最长 6 个字（和词表里收录的最长词一致）。 */
    private const val MAX_WORD = 6

    private val URL = Regex("(?:https?://|www\\.)[^\\s\\u4e00-\\u9fff]+", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val TOPIC = Regex("#[^#\\s]{1,30}#")
    private val MENTION = Regex("@[A-Za-z0-9_\\u4e00-\\u9fff-]{1,20}")
    private val NUMBER = Regex(
        "\\d+(?:[.,]\\d+)*(?:%|‰|％)?(?:年月|年|月|日|号|时|分|秒|元|块|个|只|条|张|次|人|岁|" +
            "km|cm|mm|kg|g|ml|GB|MB|KB|GHz|MHz|Hz|℃|°C|°)?",
        RegexOption.IGNORE_CASE,
    )
    private val TIME = Regex("\\d{1,2}:\\d{2}(?::\\d{2})?")
    private val ENGLISH = Regex("[A-Za-z][A-Za-z'’-]*")

    /** 网址等结尾常跟着的句读，匹配到要去掉（留给下一个词块）。 */
    private const val TRAILING = "，。！？、；：,.!?;:）)】」』》>”\"'…"

    @Volatile
    private var words: Set<String>? = null

    /** 把 [text] 切成词块列表（按原顺序，拼起来等于原文）。 */
    /**
     * 返回 [text] 末尾**一个「字形簇」**的起始下标。
     *
     * 退格用：一个表情往往不止一个 UTF-16 单元（😀 = 2 个，👨‍👩‍👧‍👦 = 十几个），
     * 按 1 个单元删会「删不干净、要点好几次」，所以这里整体算一个。
     */
    fun lastGraphemeStart(text: String): Int {
        if (text.isEmpty()) return 0
        return try {
            val it = android.icu.text.BreakIterator.getCharacterInstance()
            it.setText(text)
            it.last()
            val prev = it.previous()
            if (prev < 0 || prev >= text.length) text.length - 1 else prev
        } catch (t: Throwable) {
            // 退路：按码点（至少不会把一个表情劈成两半）
            text.length - Character.charCount(Character.codePointBefore(text, text.length))
        }
    }

    /**
     * 退格要删掉的「一个整体」的起始下标。
     *
     * · 微信 / QQ 这类**表情码**（[亲亲]、[微笑]）整体算一个 ——
     *   它们在输入框里其实是 4 个字符的纯文本，按字符删就会「点 4 次才删掉」；
     * · 其它按「字形簇」（😀 这种表情、组合字符、汉字、字母）。
     */
    fun backspaceUnitStart(text: String): Int {
        if (text.isEmpty()) return 0
        // 微信 / QQ 表情码：[中文...]
        if (text.endsWith("]")) {
            val open = text.lastIndexOf('[', text.length - 1)
            if (open >= 0) {
                val inner = text.substring(open + 1, text.length - 1)
                // 里面只允许中文（表情码都是中文名），长度 1~8，避免误伤正常输入
                if (inner.isNotEmpty() && inner.length <= 8 && inner.all { it.code > 0x2E80 }) {
                    return open
                }
            }
        }
        return lastGraphemeStart(text)
    }

    fun segment(context: Context, text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val dict = load(context)
        val pieces = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]

            // 1) 空白：先单独成块，最后再并进相邻词块
            if (c.isWhitespace()) {
                var j = i
                while (j < text.length && text[j].isWhitespace()) j++
                pieces.add(text.substring(i, j))
                i = j
                continue
            }

            // 2) 整体不拆的：网址 / 邮箱 / 话题 / @人 / 数字 / 英文
            val fixed: String? = matchAt(URL, text, i)
                ?: matchAt(EMAIL, text, i)
                ?: matchAt(TOPIC, text, i)
                ?: matchAt(MENTION, text, i)
                ?: matchAt(TIME, text, i)
                ?: matchAt(NUMBER, text, i)
                ?: matchAt(ENGLISH, text, i)
            if (fixed != null) {
                var word = fixed.orEmpty()
                while (word.isNotEmpty() && TRAILING.contains(word.last())) {
                    word = word.dropLast(1)
                }
                if (word.isNotEmpty()) {
                    pieces.add(word)
                    i += word.length
                    continue
                }
            }

            // 3) 表情（含肤色 / 变体选择符，整体一块）
            val cp = text.codePointAt(i)
            if (cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF) {
                var j = i + Character.charCount(cp)
                while (j < text.length &&
                    (text[j] == '\uFE0F' || text[j].code in 0x1F3FB..0x1F3FF)
                ) {
                    j++
                }
                pieces.add(text.substring(i, j))
                i = j
                continue
            }

            // 4) 中文：正向最大匹配
            if (c in '\u4e00'..'\u9fff') {
                var len = minOf(MAX_WORD, text.length - i)
                var hit = 0
                while (len >= 2) {
                    if (dict.contains(text.substring(i, i + len))) {
                        hit = len
                        break
                    }
                    len--
                }
                if (hit > 0) {
                    pieces.add(text.substring(i, i + hit))
                    i += hit
                } else {
                    pieces.add(c.toString())
                    i++
                }
                continue
            }

            // 5) 其它（标点、生僻符号）：一个字一块
            pieces.add(c.toString())
            i++
        }
        return mergeBlanks(pieces)
    }

    /** 在 [from] 处尝试匹配，必须正好从该位置开始才算命中。 */
    private fun matchAt(regex: Regex, text: String, from: Int): String? {
        val m = regex.find(text, from) ?: return null
        if (m.range.first != from) return null
        return m.value
    }

    /** 把纯空白的块并进相邻块（前导空白并到后一个块上）。 */
    private fun mergeBlanks(pieces: List<String>): List<String> {
        val merged = ArrayList<String>(pieces.size)
        for (p in pieces) {
            if (p.isBlank() && merged.isNotEmpty() && merged.last().isNotBlank()) {
                merged[merged.size - 1] = merged.last() + p
            } else {
                merged.add(p)
            }
        }
        if (merged.size >= 2 && merged[0].isBlank()) {
            val head = merged.removeAt(0)
            merged[0] = head + merged[0]
        }
        return merged.filter { it.isNotEmpty() }
    }

    /** 读一次词表并缓存（244KB，首次约几十毫秒，之后零开销）。 */
    private fun load(context: Context): Set<String> {
        words?.let { return it }
        synchronized(this) {
            words?.let { return it }
            val set = HashSet<String>(32768)
            runCatching {
                context.assets.open(WORD_ASSET).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val w = line.trim()
                        if (w.isNotEmpty()) set.add(w)
                    }
                }
                AppLog.i(AppLog.Category.UI, "分词词表已载入：${set.size} 个词")
            }.onFailure {
                AppLog.i(AppLog.Category.UI, "分词词表载入失败（退化为单字成块）：$it")
            }
            words = set
            return set
        }
    }
}