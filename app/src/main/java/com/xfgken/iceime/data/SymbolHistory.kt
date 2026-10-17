package com.xfgken.iceime.data

import android.content.Context

/**
 * 符号键盘的「最近使用」记录（本地，只保存符号本身，不涉及用户文本）。
 *
 * 只收录**真正的符号**（标点 / 数学符号 / 货币 / 表情等），
 * 汉字、字母、数字、空格一律不记 —— 否则打中文时那些字会跑到符号页的「最近使用」里。
 */
object SymbolHistory {

    private const val FILE = "icetype_symbol_history"
    private const val KEY = "recent"
    private const val MAX = 40
    private const val SEP = '\u0001'

    private var sp: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 单个码点是否属于「可当符号」的类别（标点 / 各类符号 / 表情）。 */
    private fun isSymbolCodePoint(cp: Int): Boolean {
        // 表情自带的组合符：变体选择符 / 零宽连接符 / 肤色修饰符
        if (cp == 0xFE0F || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF) {
            return true
        }
        return when (Character.getType(cp)) {
            Character.OTHER_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true

            else -> false
        }
    }

    /**
     * 是否算作可记录的符号。
     *
     * 用 Unicode 类别做**白名单**：汉字属于字母类，字母 / 数字也都不在白名单里，
     * 所以它们从根上就进不来。
     */
    fun isSymbol(symbol: String): Boolean {
        if (symbol.isEmpty() || symbol.isBlank()) return false
        if (symbol.length > 8) return false
        var hasVisible = false
        var i = 0
        while (i < symbol.length) {
            val cp = symbol.codePointAt(i)
            i += Character.charCount(cp)
            if (!isSymbolCodePoint(cp)) return false
            if (cp != 0xFE0F && cp != 0x200D && cp !in 0x1F3FB..0x1F3FF) hasVisible = true
        }
        return hasVisible
    }

    fun recent(): List<String> {
        val raw = sp?.getString(KEY, "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val all = raw.split(SEP).filter { it.isNotEmpty() }
        val kept = all.filter { isSymbol(it) }
        // 旧版本把中文等也记进来了：发现脏数据就顺手洗掉，
        // 不然它们会一直卡在「最近使用」里（只在读的时候过滤不够直观）。
        if (kept.size != all.size) {
            sp?.edit()?.putString(KEY, kept.joinToString(SEP.toString()))?.apply()
        }
        return kept
    }

    fun markUsed(symbol: String) {
        val prefs = sp ?: return
        if (!isSymbol(symbol)) return
        val list = recent().toMutableList()
        list.remove(symbol)
        list.add(0, symbol)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY, list.joinToString(SEP.toString())).apply()
    }

    fun clear() {
        sp?.edit()?.remove(KEY)?.apply()
    }
}
