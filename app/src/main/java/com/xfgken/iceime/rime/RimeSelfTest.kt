package com.xfgken.iceime.rime

import android.content.Context
import com.xfgken.iceime.core.AppLog

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
                    "，lan→${lanCandidates.joinToString("/")}（含「栏」=$lanHasBar）" +
                    "，goo→${gooCandidates.joinToString("/")}" +
                    "，goo第2页→${gooPage2.joinToString("/")}（有英文单词=$gooHasEnglish）" +
                    "，good→${goodCandidates.joinToString("/")}（含good=$goodHasEnglish）" +
                    "，hel→${helCandidates.take(6).joinToString("/")}" +
                    "，wen→${wenCandidates.take(8).joinToString("/")}",
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