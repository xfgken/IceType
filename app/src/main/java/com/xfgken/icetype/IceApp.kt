package com.xfgken.icetype

import android.app.Application
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.core.IcePrefs
import com.xfgken.icetype.ime.ImeStatus
import com.xfgken.icetype.data.ClipboardStore
import com.xfgken.icetype.data.EmojiData
import com.xfgken.icetype.data.PhraseStore
import com.xfgken.icetype.data.PinyinOrder
import com.xfgken.icetype.data.PredictLexicon
import com.xfgken.icetype.data.SymbolHistory
import com.xfgken.icetype.rime.RimeAssetInstaller
import com.xfgken.icetype.rime.RimeEngine
import com.xfgken.icetype.rime.RimeSelfTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        IcePrefs.init(this)
        ClipboardStore.init(this)
        PhraseStore.init(this)
        EmojiData.init(this)
        SymbolHistory.init(this)
        AppLog.i(AppLog.Category.INIT, "IceType 应用进程启动（应用级初始化完成）")
        // 把与「手感」相关的默认值写进日志，便于不打开界面就核对（新装应为 false / false）
        val feel = IcePrefs.current()
        AppLog.i(
            AppLog.Category.INIT,
            "声音与震动：按键音=${feel.soundEnabled}（音量 ${feel.keySoundVolume}）" +
                "，震动=${feel.vibrateEnabled}（强度 ${feel.vibrateStrength}）",
        )
        // 启动即记录一次输入法状态判定过程，便于核对检测是否准确
        AppLog.i(AppLog.Category.UI, "输入法状态判定：${ImeStatus.debugText(this)}")

        // 应用进程启动就挂上剪贴板监听：只要能读到剪贴板（本进程是当前输入法或前台应用），
        // 用户每复制一条就记录一条，不会出现「只剩最后一条」。
        runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.addPrimaryClipChangedListener {
                ClipboardStore.captureFromSystem(this@IceApp)
            }
            AppLog.i(AppLog.Category.CLIPBOARD, "应用进程已监听系统剪贴板")
        }.onFailure {
            AppLog.throwable(AppLog.Category.ERROR, "应用进程注册剪贴板监听失败", it)
        }

        // 应用启动即开始准备引擎：首次启动自动释放词库并部署，用户无需手动导入
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (!RimeAssetInstaller.isInstalled(this@IceApp)) {
                AppLog.i(AppLog.Category.DEPLOY, "检测到词库资源尚未释放，开始首次部署")
            }
            val startedAt = System.currentTimeMillis()
            val ok = RimeEngine.ensureReady(this@IceApp)
            val cost = System.currentTimeMillis() - startedAt
            AppLog.i(
                AppLog.Category.DEPLOY,
                if (ok) "应用启动阶段引擎准备完成（用时 ${cost} ms）"
                else "应用启动阶段引擎准备失败，等待用户重试",
            )
            if (ok) {
                // 自检只在「真的完整部署过一次」时跑（首次安装 / 重置 / 资源升级）。
                // 平时冷启动的增量校验只要几毫秒，没必要每次都建会话、打十个编码再翻页。
                // 需要随时手动验证时，仍可用「关于页 → 运行自检」或 --es probe。
                if (cost > SELF_TEST_MIN_COST_MS) {
                    RimeSelfTest.run(this@IceApp)
                } else {
                    AppLog.d(AppLog.Category.ENGINE, "本次启动无需重编译（${cost} ms），跳过引擎自检")
                }
            }
            // 预载联想词表与候选重排词表，避免首次用到时在主线程解析
            PredictLexicon.ensureLoaded(this@IceApp)
            PinyinOrder.preload(this@IceApp)
        }
    }

    companion object {
        /** 启动耗时超过该值，说明真的做过一次完整部署，才值得跑一次引擎自检。 */
        private const val SELF_TEST_MIN_COST_MS = 3000L

        lateinit var instance: IceApp
            private set
    }
}