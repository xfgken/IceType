package com.xfgken.iceime.rime

import android.content.Context
import com.xfgken.iceime.core.AppLog

/**
 * 引擎压力自测（仅调试用，正常运行路径不会调用）。
 *
 * 用 `am start -n com.xfgken.iceime/.MainActivity --ei stress 200` 触发：
 * 在后台线程反复「建会话 → 打字 → 取候选 → 翻页 → 上屏 → 清空 → 销毁会话」，
 * 用来暴露 librime / lua 过滤器在长时间使用下的崩溃（例如 luaL_checklstring 一类段错误）。
 *
 * 结果全部写进日志（分类 ENGINE）：崩溃时进程会直接挂掉，日志最后一行就是崩溃前的步骤。
 */
object RimeStress {

    /** 打这些拼音，覆盖普通词、长词、英文、单字母等情况。 */
    private val WORDS = listOf(
        "nihao", "zhongguo", "woshi", "shurufa", "pinyin", "shenme", "keyida", "zhege",
        "women", "xiexie", "duibuqi", "zaijian", "s", "a", "e", "hel", "goo", "lan",
        "tianqi", "mingtian", "pengyou", "gongzuo", "xuexiao", "shijian", "yinwei",
    )

    fun run(context: Context, rounds: Int) {
        run(context, rounds, "all")
    }

    /**
     * @param mode 限定只做某一类操作，便于定位崩溃点：
     *   `type` 只打字+取候选；`page` 打字+翻页；`commit` 打字+上屏；`session` 只建/销毁会话；`all` 全做。
     */
    fun run(context: Context, rounds: Int, mode: String) {
        val t = Thread({
            val t0 = System.currentTimeMillis()
            AppLog.i(AppLog.Category.ENGINE, "压力自测开始：$rounds 轮，模式=$mode")
            var ok = 0
            var failed = 0
            try {
                val ready = kotlinx.coroutines.runBlocking { RimeEngine.ensureReady(context) }
                if (!ready) {
                    AppLog.w(AppLog.Category.ENGINE, "压力自测中止：引擎未就绪")
                    return@Thread
                }
                var round = 0
                while (round < rounds) {
                    val session = RimeEngine.createSession()
                    if (session == 0L) {
                        failed++
                        round++
                        continue
                    }
                    var steps = 0
                    try {
                        for (w in WORDS) {
                            for (ch in w) {
                                RimeEngine.processKey(session, ch.code)
                                steps++
                            }
                            val ctx = RimeEngine.context(session)
                            steps++
                            if (mode != "type" && mode != "session" && ctx != null && ctx.candidates.isNotEmpty()) {
                                RimeEngine.changePage(session, false)
                                steps++
                                RimeEngine.changePage(session, true)
                                steps++
                            }
                            if ((mode == "commit" || mode == "all") && ctx != null && ctx.candidates.isNotEmpty()) {
                                if (round % 3 == 0) {
                                    RimeEngine.selectCandidate(session, 0)
                                    steps++
                                    RimeEngine.commit(session)
                                    steps++
                                }
                            }
                            if (mode != "session") {
                                RimeEngine.clearComposition(session)
                                steps++
                            }
                        }
                        ok++
                    } catch (e: Throwable) {
                        failed++
                        AppLog.throwable(
                            AppLog.Category.ERROR,
                            "压力自测第 ${round + 1} 轮异常（已执行 $steps 步）",
                            e,
                        )
                    } finally {
                        RimeEngine.destroySession(session)
                    }
                    round++
                    if (round % 20 == 0) {
                        AppLog.i(
                            AppLog.Category.ENGINE,
                            "压力自测进度 $round/$rounds，成功 $ok，失败 $failed",
                        )
                    }
                }
            } catch (e: Throwable) {
                AppLog.throwable(AppLog.Category.ERROR, "压力自测整体异常", e)
            } finally {
                AppLog.i(
                    AppLog.Category.ENGINE,
                    "压力自测结束：成功 $ok 轮，失败 $failed 轮，耗时 ${System.currentTimeMillis() - t0}ms",
                )
            }
        }, "IceTypeRimeStress")
        t.isDaemon = true
        t.start()
    }
}