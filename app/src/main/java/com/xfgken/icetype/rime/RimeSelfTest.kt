package com.xfgken.icetype.rime

import android.content.Context
import com.xfgken.icetype.core.AppLog

/**
 * 引擎自检。
 *
 * 目的：不依赖任何界面操作，直接走一遍「建会话 → 送按键 → 取候选 → 关会话」的完整链路，
 * 用来验证中文输入是否真的可用（这条链路一旦断掉，表现就是「能上屏字母但没有候选」）。
 *
 * 调用点：
 *  - 应用启动、引擎就绪后自动跑一次（只写日志）；
 *  - 「关于 IceType」页面提供手动执行并展示结果。
 *
 * 隐私：自检使用固定的测试串（nihao / hello），不涉及任何用户输入内容。
 */
object RimeSelfTest {

    data class Result(
        val ok: Boolean,
        val engineVersion: String,
        val schemaId: String,
        val candidates: List<String>,
        val committed: String,
        val englishCandidates: List<String>,
        val message: String,
    ) {
    }

    /**
     * 诊断探针：把给定字符串当作拼音逐字输入，然后**逐页**打印候选。
     * 用途：排查「某个编码的真实候选被模糊音派生词挤到后面 / 完全消失」这类排序问题。
     */
    fun probeWord(context: Context, word: String, maxPages: Int = 10) {
        Thread {
            val ready = kotlinx.coroutines.runBlocking { RimeEngine.ensureReady(context) }
            if (!ready) {
                AppLog.w(AppLog.Category.ENGINE, "探针[$word] 中止：引擎未就绪")
                return@Thread
            }
            val session = RimeEngine.createSession()
            if (session == 0L) {
                AppLog.w(AppLog.Category.ENGINE, "探针[$word] 中止：创建会话失败")
                return@Thread
            }
            try {
                RimeEngine.setOption(session, "ascii_mode", false)
                for (ch in word) {
                    RimeEngine.processKey(session, ch.code)
                }
                val firstCands = RimeEngine.context(session)?.candidates ?: emptyList()
                val all = mutableListOf<String>()
                var page = 0
                while (page < maxPages) {
                    val ctx = RimeEngine.context(session) ?: break
                    val cands = ctx.candidates.map { it.text }
                    if (cands.isEmpty()) break
                    all += cands
                    AppLog.i(
                        AppLog.Category.ENGINE,
                        "探针[$word] 第${page + 1}页（每页${cands.size}个）：${cands.joinToString("/")}",
                    )
                    if (ctx.isLastPage) break
                    RimeEngine.changePage(session, false)
                    page++
                }
                AppLog.i(
                    AppLog.Category.ENGINE,
                    "探针[$word] 合计 ${all.size} 个候选，全量：${all.joinToString("/")}",
                )
                // 同时打印「界面重排后」的前 12 个，便于不打开界面就核对效果
                if (firstCands.isNotEmpty()) {
                    val ordered = com.xfgken.icetype.data.PinyinOrder.reorder(
                        context, word, firstCands,
                    )
                    AppLog.i(
                        AppLog.Category.ENGINE,
                        "探针[$word] 重排后前12：${ordered.list.take(12).joinToString("/") { it.text }}",
                    )
                }
            } catch (t: Throwable) {
                AppLog.throwable(AppLog.Category.ENGINE, "探针[$word] 异常", t)
            } finally {
                RimeEngine.destroySession(session)
            }
        }.start()
    }

    fun run(context: Context, quiet: Boolean = false): Result {
        if (!RimeEngine.isReady) {
            val r = Result(false, RimeEngine.engineVersion, "", emptyList(), "", emptyList(), "引擎尚未就绪")
            if (!quiet) AppLog.w(AppLog.Category.ENGINE, "引擎自检跳过：引擎未就绪")
            return r
        }
        val session = RimeEngine.createSession()
        if (session == 0L) {
            val r = Result(false, RimeEngine.engineVersion, "", emptyList(), "", emptyList(), "创建会话失败")
            AppLog.w(AppLog.Category.ENGINE, "引擎自检失败：创建会话失败")
            return r
        }
        try {
            RimeEngine.setOption(session, "ascii_mode", false)
            for (ch in "nihao") {
                RimeEngine.processKey(session, ch.code)
            }
            val ctx = RimeEngine.context(session)
            val candidates = ctx?.candidates?.map { it.text } ?: emptyList()

            var committed = ""
            if (RimeEngine.selectCandidate(session, 0)) {
                committed = RimeEngine.commit(session) ?: ""
            }

            RimeEngine.clearComposition(session)
            for (ch in "hel") {
                RimeEngine.processKey(session, ch.code)
            }
            val englishCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()

            // 词库抽查：输入 lan，看候选里是否包含「栏」（用于核对词库是否完整）
            RimeEngine.clearComposition(session)
            for (ch in "lan") {
                RimeEngine.processKey(session, ch.code)
            }
            val lanCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()
            val lanHasBar = lanCandidates.any { it == "栏" }

            // 中英混输抽查：输入 goo，看候选里有没有英文单词（melt_eng 次翻译器是否生效）
            RimeEngine.clearComposition(session)
            for (ch in "goo") {
                RimeEngine.processKey(session, ch.code)
            }
            val gooCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()
            // 再翻一页，判断英文单词是「被排到后面」还是「压根没有」
            RimeEngine.changePage(session, false)
            val gooPage2 = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()
            val gooHasEnglish = (gooCandidates + gooPage2).any { it.length > 1 && it.all { ch -> ch in 'a'..'z' } }

            // 精确输入 good：验证英文次翻译器是否至少能出完整单词
            RimeEngine.clearComposition(session)
            for (ch in "good") {
                RimeEngine.processKey(session, ch.code)
            }
            val goodCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()
            val goodHasEnglish = goodCandidates.any { it.equals("good", ignoreCase = true) }

            // 简拼抽查：hel 应当仍是中文（合理/荷兰），用来确认英文没有把简拼挤掉
            RimeEngine.clearComposition(session)
            for (ch in "hel") {
                RimeEngine.processKey(session, ch.code)
            }
            val helCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()

            // 用户点名的抽查：wen 必须中文优先（文/问/温…），不能被 went 之类的英文挤掉
            RimeEngine.clearComposition(session)
            for (ch in "wen") {
                RimeEngine.processKey(session, ch.code)
            }
            val wenCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()

            // 模糊音探针：zongguo（zh=z 生效时首屏应有「中国」）、ni（n=l 生效时应出现「里 / 李」）
            RimeEngine.clearComposition(session)
            for (ch in "zongguo") {
                RimeEngine.processKey(session, ch.code)
            }
            val zongguoCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()
            val fuzzyHasZh = zongguoCandidates.take(20).any { it == "中国" }
            RimeEngine.clearComposition(session)
            for (ch in "si") {
                RimeEngine.processKey(session, ch.code)
            }
            val siCandidates = RimeEngine.context(session)?.candidates?.map { it.text }
                ?: emptyList()
            val fuzzyHasSh = siCandidates.take(20).any { it == "是" || it == "十" || it == "事" }

            val schemaId = RimeEngine.currentSchema(session)
            val ok = candidates.isNotEmpty() && committed.isNotEmpty()
            val result = Result(
                ok = ok,
                engineVersion = RimeEngine.engineVersion,
                schemaId = schemaId,
                candidates = candidates,
                committed = committed,
                englishCandidates = englishCandidates,
                message = if (ok) "" else "中文候选或上屏为空，请检查词库部署",
            )
            AppLog.i(
                AppLog.Category.ENGINE,
                "引擎自检" + (if (ok) "通过" else "未通过") +
                    "：方案=$schemaId，nihao→${candidates.take(3).joinToString("/")}，上屏=\"$committed\"" +
                    "，hel→${englishCandidates.take(3).joinToString("/")}" +
                    "，lan→${lanCandidates.joinToString("/")}（含「栏」=$lanHasBar；开启模糊音时可能被挤出首屏）" +
                    "，goo→${gooCandidates.joinToString("/")}" +
                    "，goo第2页→${gooPage2.joinToString("/")}（有英文单词=$gooHasEnglish）" +
                    "，good→${goodCandidates.joinToString("/")}（含good=$goodHasEnglish）" +
                    "，hel→${helCandidates.take(6).joinToString("/")}" +
                    "，wen→${wenCandidates.take(8).joinToString("/")}" +
                    "｜模糊音（平翘舌，强制开启）" +
                    "：zongguo→${zongguoCandidates.take(6).joinToString("/")}（含中国=$fuzzyHasZh）" +
                    "，si→${siCandidates.take(8).joinToString("/")}（含是/十/事=$fuzzyHasSh）",
            )
            return result
        } catch (t: Throwable) {
            AppLog.throwable(AppLog.Category.ENGINE, "引擎自检异常", t)
            return Result(
                false, RimeEngine.engineVersion, "", emptyList(), "", emptyList(),
                "${t.javaClass.simpleName}: ${t.message}",
            )
        } finally {
            RimeEngine.destroySession(session)
        }
    }
}