package com.xfgken.icetype.data

import android.content.Context
import android.content.SharedPreferences
import com.xfgken.icetype.core.AppLog
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 输入使用统计。
 *
 * 统计口径（与开发文档一致）：
 *  - 明确区分「中文字符 / 纯符号 / 表情 / 英文 / 数字」五类；
 *  - 空格、换行等空白**不计入**字符总量；
 *  - 删除文字不扣减历史累计（只增不减）；
 *  - 只保存统计数据，不保存任何实际输入的文本内容；
 *  - 数据只存本地应用私有 SharedPreferences，不联网。
 *
 * 按「天」分桶存储，便于支持 今日 / 昨日 / 近 N 天 / 全部 等时间范围查询。
 */
object UsageStatsStore {
    /** 一天的统计数据。 */
    data class DayStats(
        var cn: Long = 0,       // 中文字符
        var symbol: Long = 0,   // 纯符号
        var emoji: Long = 0,    // 表情
        var en: Long = 0,       // 英文
        var digit: Long = 0,    // 数字
        var durationMs: Long = 0, // 键盘使用时长（毫秒）
    ) {
        /** 累计输入总量 = 中文 + 符号 + 表情 + 英文 + 数字（不含空白）。 */
        val total: Long get() = cn + symbol + emoji + en + digit

        operator fun plus(o: DayStats): DayStats = DayStats(
            cn = cn + o.cn, symbol = symbol + o.symbol, emoji = emoji + o.emoji,
            en = en + o.en, digit = digit + o.digit, durationMs = durationMs + o.durationMs,
        )
    }

    private const val SP_NAME = "icetype_usage_stats"
    private const val KEY_START_DATE = "start_date"
    private const val KEY_PREFIX = "day_"

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) {
            sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            // 首次启用时记录统计起始日期（只写一次，之后不再改）
            if (sp!!.getString(KEY_START_DATE, null) == null) {
                val today = LocalDate.now().format(DATE_FMT)
                sp!!.edit().putString(KEY_START_DATE, today).apply()
                AppLog.i(AppLog.Category.UI, "输入统计已启用，起始日期 $today")
            }
        }
    }

    /** 统计起始日期（yyyy-MM-dd），供界面展示；未启用时为 null。 */
    fun startDate(): String? = sp?.getString(KEY_START_DATE, null)

    private fun todayKey(): String = "day_" + LocalDate.now().format(DATE_FMT)

    private fun dayKey(date: LocalDate): String = "day_" + date.format(DATE_FMT)

    /** 记录一段上屏文本：逐码点分类累加到「今天」。 */
    fun recordText(text: String) {
        if (text.isEmpty()) return
        val s = sp ?: return
        val day = load(todayKey())
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            when {
                isWhitespace(cp) -> {} // 空格 / 换行不计入
                isEmoji(cp) -> day.emoji++
                isChinese(cp) -> day.cn++
                isAsciiLetter(cp) -> day.en++
                isDigit(cp) -> day.digit++
                else -> day.symbol++
            }
            i += cc
        }
        save(todayKey(), day)
    }

    /** 累加键盘使用时长（毫秒）。 */
    fun addDuration(ms: Long) {
        if (ms <= 0) return
        val s = sp ?: return
        val day = load(todayKey())
        day.durationMs += ms
        save(todayKey(), day)
    }

    /** 查询最近 [days] 天（含今天）的累计。days <= 0 视为全部。 */
    fun querySince(days: Int): DayStats {
        val today = LocalDate.now()
        return if (days <= 0) {
            queryAll()
        } else {
            var acc = DayStats()
            for (d in 0 until days) {
                acc += load(dayKey(today.minusDays(d.toLong())))
            }
            acc
        }
    }

    /** 查询「昨日」单天统计。 */
    fun queryYesterday(): DayStats = load(dayKey(LocalDate.now().minusDays(1)))

    /** 全部历史累计（从起始日期到今天）。 */
    fun queryAll(): DayStats {
        val s = sp ?: return DayStats()
        val startStr = s.getString(KEY_START_DATE, null) ?: return DayStats()
        val start = runCatching { LocalDate.parse(startStr, DATE_FMT) }.getOrNull() ?: return DayStats()
        val today = LocalDate.now()
        var acc = DayStats()
        var d = start
        while (!d.isAfter(today)) {
            acc += load(dayKey(d))
            d = d.plusDays(1)
        }
        return acc
    }

    private fun load(key: String): DayStats {
        val raw = sp?.getString(key, null) ?: return DayStats()
        return runCatching {
            val o = JSONObject(raw)
            DayStats(
                cn = o.optLong("cn"), symbol = o.optLong("sym"), emoji = o.optLong("emo"),
                en = o.optLong("en"), digit = o.optLong("dig"), durationMs = o.optLong("dur"),
            )
        }.getOrElse { DayStats() }
    }

    private fun save(key: String, d: DayStats) {
        val o = JSONObject().apply {
            put("cn", d.cn); put("sym", d.symbol); put("emo", d.emoji)
            put("en", d.en); put("dig", d.digit); put("dur", d.durationMs)
        }
        sp?.edit()?.putString(key, o.toString())?.apply()
    }

    // —— 字符分类 ——
    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || Character.isWhitespace(cp)

    private fun isAsciiLetter(cp: Int): Boolean =
        (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code)

    private fun isDigit(cp: Int): Boolean = cp in '0'.code..'9'.code

    private fun isChinese(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||      // 基本汉字
        cp in 0x3400..0x4DBF ||      // 扩展 A
        cp in 0xF900..0xFAFF         // 兼容表意文字

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||    // 表情 / 象形 / 补充符号
        cp in 0x2600..0x27BF ||      // 杂项符号 / 装饰符号
        cp in 0x2B00..0x2BFF ||      // 杂项符号与箭头
        cp == 0xFE0F ||              // 变体选择符（emoji 呈现）
        cp == 0x200D                 // 零宽连接符 ZWJ
}