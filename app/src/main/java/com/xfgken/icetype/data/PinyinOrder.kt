package com.xfgken.icetype.data

import android.content.Context
import com.xfgken.icetype.rime.RimeEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 候选重排：让「与输入拼音完全一致」的候选和「模糊音派生」的候选**交替**出现。
 *
 * 为什么需要它：
 *   rime 开启模糊音（zh=z / n=l …）后，两个编码会合并成同一个候选池，按字频排序。
 *   高频字（正/整/政）会把本音字（增/赠/曾）压到后面，与主流输入法的观感不一致。
 *   这里按「单字拼音是否等于当前输入」把候选分成两组，再 1:1 交替排列，
 *   于是打 `zeng` 得到 增 / 正 / 赠 / 整 / 憎 / 政 …（两边都在前列）。
 *
 * 数据来源：assets/pinyin_single.txt（由 cn_dicts 抽取的「单字 → 拼音集合」，约 4 万条）。
 * 只对**单音节输入**生效；多音节（nihao）和英文候选不受影响。
 *
 * 注意：重排只改变「显示顺序」，点击时必须用 [Result.order] 映射回引擎索引，
 * 否则会选错候选。
 */
object PinyinOrder {

    /** list = 显示顺序的候选；order = 每个显示位置对应的**引擎索引**（null 表示未重排）。 */
    data class Result(val list: List<RimeEngine.Candidate>, val order: IntArray?)

    private var table: Map<String, Set<String>> = emptyMap()
    private val loaded = AtomicBoolean(false)

    /**
     * 供应用启动阶段在后台预载：首次解析要读 364KB 词表并建约 4 万条映射，
     * 放在主线程会在第一次上屏时卡一下。
     */
    fun preload(context: Context) = ensureLoaded(context)

    fun ensureLoaded(context: Context) {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            runCatching {
                context.assets.open("pinyin_single.txt").bufferedReader().useLines { lines ->
                    val m = HashMap<String, Set<String>>(60000)
                    lines.forEach { line ->
                        val i = line.indexOf('\t')
                        if (i <= 0) return@forEach
                        m[line.substring(0, i)] = line.substring(i + 1).split(',').toHashSet()
                    }
                    table = m
                }
                loaded.set(true)
            }
        }
    }

    fun reorder(
        context: Context,
        preedit: String,
        candidates: List<RimeEngine.Candidate>,
    ): Result {
        val identity = Result(candidates, null)
        if (candidates.size < 3) return identity
        // 多音节输入（xi'an / ni hao）不重排。
        // 以前这里把分隔符抹掉当单音节处理：xi'an → xian，于是「洗(xiǎn)」被判成本音
        // 提到第一位，把真正的「西安」挤下去，按空格甚至会选错字。
        if (preedit.contains("'") || preedit.contains(" ")) return identity
        val py = preedit
        if (py.length !in 2..7 || !py.all { it in 'a'..'z' }) return identity
        ensureLoaded(context)
        if (table.isEmpty()) return identity

        val exact = ArrayList<Int>()
        val fuzzy = ArrayList<Int>()
        candidates.forEachIndexed { i, c ->
            val isExact = c.text.length == 1 && table[c.text]?.contains(py) == true
            if (isExact) exact.add(i) else fuzzy.add(i)
        }
        if (exact.isEmpty() || fuzzy.isEmpty()) return identity

        val order = ArrayList<Int>(candidates.size)
        // 引擎自己排的第一名保持不动：它是词频最高、最合理的那一个，
        // 硬把它挤下去反而突兀（例如打 si 时「死」是本音，会被提到「是」前面）。
        val head = 0
        // 注意 MutableList<Int>.remove(Int) 是「按值删」，这里必须按下标删
        if (exact.isNotEmpty() && exact[0] == head) exact.removeAt(0) else fuzzy.removeAt(0)
        order.add(head)
        var a = 0
        var b = 0
        while (a < exact.size || b < fuzzy.size) {
            if (a < exact.size) order.add(exact[a++])
            if (b < fuzzy.size) order.add(fuzzy[b++])
        }
        if (order.indices.all { order[it] == it }) return identity
        return Result(order.map { candidates[it] }, order.toIntArray())
    }
}
