package com.xfgken.iceime

import android.app.Application
import com.xfgken.iceime.core.AppLog
import com.xfgken.iceime.core.IcePrefs
import com.xfgken.iceime.ime.ImeStatus
import com.xfgken.iceime.data.ClipboardStore
import com.xfgken.iceime.data.EmojiData
import com.xfgken.iceime.data.PhraseStore
import com.xfgken.iceime.data.PredictLexicon
import com.xfgken.iceime.data.SymbolHistory
import com.xfgken.iceime.rime.RimeAssetInstaller
import com.xfgken.iceime.rime.RimeEngine
import com.xfgken.iceime.rime.RimeSelfTest
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
            val ok = RimeEngine.ensureReady(this@IceApp)
            AppLog.i(
                AppLog.Category.DEPLOY,
                if (ok) "应用启动阶段引擎准备完成" else "应用启动阶段引擎准备失败，等待用户重试",
            )
            if (ok) {
                // 引擎就绪后立即自检一次：确认真能出候选（把结果写进日志，便于排查）
                RimeSelfTest.run(this@IceApp)
            }
            // 预载联想词表，避免首次用到联想时在主线程解析
            PredictLexicon.ensureLoaded(this@IceApp)
        }
    }

    companion object {
        lateinit var instance: IceApp
            private set
    }
}