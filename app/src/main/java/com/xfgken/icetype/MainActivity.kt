package com.xfgken.icetype

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.ui.IceTypeApp
import com.xfgken.icetype.ui.SettingsPage

/** 应用主入口：只负责承载 Compose 主页面。 */
class MainActivity : ComponentActivity() {

    /** 外部要求打开的页面（键盘 / 面板通过 Intent extra 传进来）。 */
    private val pageRequest = mutableStateOf<SettingsPage?>(null)

    /**
     * 跳页请求的序号。
     *
     * 因为 Activity 可能是**已经在运行**的实例（SINGLE_TOP + CLEAR_TOP），
     * 新 Intent 只会走到 [onNewIntent]，所以用一个序号通知 Compose 去切页；
     * 序号为 0 表示「没有外部跳页请求」（首次进入由 initialPage 决定）。
     */
    private val pageToken = mutableStateOf(0L)

    private var scrollBottom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 诊断入口：`am start ... --es page THEME` 可直接打开指定设置页
        // （用于在不点屏幕的情况下检查某个页面/预览的运行时状态）
        pageRequest.value = parsePage(intent?.getStringExtra("page"))
        scrollBottom = intent?.getBooleanExtra("scrollBottom", false) == true
        setContent {
            IceTypeApp(
                initialPage = pageRequest.value,
                pageRequestToken = pageToken.value,
                initialScrollBottom = scrollBottom,
            )
        }
        // 诊断入口：`--ez dump true` 时把页面渲染结果按行统计写进日志
        // （用于在不截屏、不点屏幕的情况下确认某个区域到底画出来了没有）
        if (intent?.getBooleanExtra("dump", false) == true) {
            window.decorView.postDelayed({ dumpPixels() }, 5000)
        }
        // 诊断入口：`--ei stress 200` 在后台跑引擎压力自测（查 librime / lua 崩溃）
        // 可加 `--es stressMode type|page|commit|session` 只跑某一类操作
        // 诊断入口：`--es probe zeng` 逐页打印该编码的全部候选（查排序/词库问题）
        intent?.getStringExtra("probe")?.takeIf { it.isNotBlank() }?.let { word ->
            com.xfgken.icetype.rime.RimeSelfTest.probeWord(applicationContext, word)
        }
        val stressRounds = intent?.getIntExtra("stress", 0) ?: 0
        if (stressRounds > 0) {
            val mode = intent?.getStringExtra("stressMode") ?: "all"
            com.xfgken.icetype.rime.RimeStress.run(applicationContext, stressRounds, mode)
        }
    }

    /**
     * 已经在前台时又收到「打开 App 某页」：直接切到那一页。
     *
     * 如果本来就在那一页，page 不会变 → 界面完全不动（这就是「应该没反应」）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pageRequest.value = parsePage(intent.getStringExtra("page"))
        pageToken.value += 1
        AppLog.i(AppLog.Category.UI, "已在 App 内，收到跳页请求：${intent.getStringExtra("page") ?: "(主页)"}")
    }

    private fun parsePage(name: String?): SettingsPage? = name?.let { n ->
        SettingsPage.entries.firstOrNull { it.name.equals(n, ignoreCase = true) }
            ?.also { AppLog.i(AppLog.Category.UI, "打开设置页 ${it.name}") }
    }

    /** 把当前窗口的渲染结果按行采样，输出「主色 + 变化比例」，用于定位某个区块是否绘制。 */
    private fun dumpPixels() {
        runCatching {
            val view = window.decorView
            if (view.width <= 0 || view.height <= 0) {
                AppLog.w(AppLog.Category.UI, "像素诊断：窗口尺寸无效")
                return
            }
            val bmp = android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            view.draw(android.graphics.Canvas(bmp))
            AppLog.i(AppLog.Category.UI, "像素诊断：窗口 ${bmp.width}x${bmp.height}")
            var y = 0
            while (y < bmp.height) {
                val colors = HashMap<Int, Int>()
                var total = 0
                var x = 0
                while (x < bmp.width) {
                    val c = bmp.getPixel(x, y)
                    colors[c] = (colors[c] ?: 0) + 1
                    total++
                    x += 4
                }
                val dom = colors.maxByOrNull { it.value }!!
                val busy = 100 - dom.value * 100 / total
                if (busy > 3) {
                    AppLog.i(
                        AppLog.Category.UI,
                        "像素 y=$y 主色=#%08X 变化=%d%% 色数=%d".format(dom.key, busy, colors.size),
                    )
                }
                y += 12
            }
        }.onFailure { AppLog.throwable(AppLog.Category.UI, "像素诊断失败", it) }
    }
}