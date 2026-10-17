package com.xfgken.iceime.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地设置（SharedPreferences）。
 *
 * 输入法服务需要在 onStartInputView 里**同步**读取键盘参数，因此这里不使用异步的 DataStore，
 * 而是用 SharedPreferences + 内存快照 + StateFlow 通知 Compose 页面。
 */
object IcePrefs {

    private const val FILE = "icetype_settings"

    // 外观
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    data class Settings(
        // —— 键盘与输入 ——
        val chineseDefault: Boolean = true,
        val candidateBarVisible: Boolean = true,
        /** 盲打模式：隐藏 26 个字母键上的字母（按键本身仍在，靠位置盲打）。 */
        val blindTyping: Boolean = false,
        val showNumberRow: Boolean = false,
        val keyHeightDp: Int = 42,
        val keyGapDp: Int = 4,
        val keyCornerDp: Int = 12, // M3 ShapeTokens.CornerMedium
        /** 按钮栏（工具栏 / 候选栏 ✕）按钮的圆角；与键盘按键分开调节，默认 18dp（正好是正圆）。 */
        val barCornerDp: Int = 18,
        /** 候选词字号（sp），默认 21。 */
        val candidateTextSp: Int = 21,
        val autoCapitalize: Boolean = false,
        val enterKeyForNewline: Boolean = false,
        // —— 外观主题 ——
        val themeMode: Int = THEME_SYSTEM,
        val useDynamicColor: Boolean = true,
        val accentArgb: Int? = null,
        val keyboardBgArgb: Int? = null,
        val keyBgArgb: Int? = null,
        val funcKeyBgArgb: Int? = null,
        val keyTextArgb: Int? = null,
        // —— 声音与震动 ——
        val soundEnabled: Boolean = true,
        /** 按键音音量（0–100），0 为静音。 */
        val keySoundVolume: Int = 60,
        val vibrateEnabled: Boolean = true,
        /** 震动挡位（0–50，1 挡最轻、50 挡最强；0 表示几乎无感）。 */
        val vibrateStrength: Int = 25,
        // —— 剪贴板 ——
        val clipboardPanelEnabled: Boolean = true,
        val clipboardHistoryEnabled: Boolean = true, // 默认开启（仅本地保存，可在设置里关闭）
        val clipboardHistoryLimit: Int = 1000,
        // —— 常用语 / 表情 ——
        val phraseEnabled: Boolean = true,
        val emojiEnabled: Boolean = true,
        // —— 词库与语言 ——
        val englishCandidates: Boolean = true,
        /** 英文候选优先：通过 rime_ice.custom.yaml 补丁提升英文词典权重。 */
        val englishCandidatesPriority: Boolean = false,
        // —— 游戏键盘 ——
        /** 游戏键盘：横屏时整个键盘悬浮、等比缩小，并可拖动移动（默认开启）。 */
        val gameKeyboard: Boolean = true,
        /** 横屏悬浮键盘大小（百分比，95 = 默认；只影响横屏悬浮键盘，竖屏无关）。 */
        val keyboardScalePercent: Int = 95,
        /**
         * 悬浮键盘上次位置（占可移动范围的比例 0~1）。
         * x 默认 0.5（居中）；y 用 -1 表示「还没设过」→ 默认贴底（0 偏移）。
         */
        val gameFloatXFrac: Float = 0.5f,
        val gameFloatYFrac: Float = -1f,
        val schemaId: String = "rime_ice",
        // —— 隐私与学习 ——
        val learningEnabled: Boolean = true,
        // —— 部署状态 ——
        val deployedAssetVersion: Int = 0,
        val lastDeployOk: Boolean = false,
        val lastDeployMessage: String = "",
    )

    private lateinit var sp: SharedPreferences
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        migrateClipboardHistory()
        _state.value = read()
        AppLog.i(AppLog.Category.INIT, "设置已载入")
    }

    /**
     * 一次性迁移：早期版本的「保存剪贴板历史」默认是关闭的，且会被显式写进设置里，
     * 于是升级后仍然是关闭 → 剪贴板面板永远只有「当前内容」一条（用户反馈的现象）。
     * 这里在首次启动新版本时把它打开一次，之后用户自己在设置里关掉就尊重用户选择。
     */
    private fun migrateClipboardHistory() {
        if (sp.getBoolean("clipboardHistoryMigrated", false)) return
        sp.edit()
            .putBoolean("clipboardHistoryEnabled", true)
            .putBoolean("clipboardHistoryMigrated", true)
            .apply()
        AppLog.i(AppLog.Category.CLIPBOARD, "首次升级：剪贴板历史已默认开启")
    }

    /** 同步读取当前快照（输入法服务使用）。 */
    fun current(): Settings = if (::sp.isInitialized) _state.value else Settings()

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            chineseDefault = sp.getBoolean("chineseDefault", d.chineseDefault),
            candidateBarVisible = sp.getBoolean("candidateBarVisible", d.candidateBarVisible),
            blindTyping = sp.getBoolean("blindTyping", d.blindTyping),
            showNumberRow = sp.getBoolean("showNumberRow", d.showNumberRow),
            keyHeightDp = sp.getInt("keyHeightDp", d.keyHeightDp),
            keyGapDp = sp.getInt("keyGapDp", d.keyGapDp),
            keyCornerDp = sp.getInt("keyCornerDp", d.keyCornerDp),
            barCornerDp = sp.getInt("barCornerDp", d.barCornerDp),
            candidateTextSp = sp.getInt("candidateTextSp", d.candidateTextSp),
            autoCapitalize = sp.getBoolean("autoCapitalize", d.autoCapitalize),
            enterKeyForNewline = sp.getBoolean("enterKeyForNewline", d.enterKeyForNewline),
            themeMode = sp.getInt("themeMode", d.themeMode),
            // 动态颜色（Material You）已改为强制开启，不再提供开关
            useDynamicColor = true,
            accentArgb = sp.optInt("accentArgb"),
            keyboardBgArgb = sp.optInt("keyboardBgArgb"),
            keyBgArgb = sp.optInt("keyBgArgb"),
            funcKeyBgArgb = sp.optInt("funcKeyBgArgb"),
            keyTextArgb = sp.optInt("keyTextArgb"),
            soundEnabled = sp.getBoolean("soundEnabled", d.soundEnabled),
            keySoundVolume = sp.getInt("keySoundVolume", d.keySoundVolume),
            vibrateEnabled = sp.getBoolean("vibrateEnabled", d.vibrateEnabled),
            vibrateStrength = sp.getInt("vibrateStrength", d.vibrateStrength).coerceIn(0, 50),
            clipboardPanelEnabled = sp.getBoolean("clipboardPanelEnabled", d.clipboardPanelEnabled),
            clipboardHistoryEnabled = sp.getBoolean("clipboardHistoryEnabled", d.clipboardHistoryEnabled),
            clipboardHistoryLimit = sp.getInt("clipboardHistoryLimit", d.clipboardHistoryLimit)
                .coerceIn(10, 5000),
            phraseEnabled = sp.getBoolean("phraseEnabled", d.phraseEnabled),
            emojiEnabled = sp.getBoolean("emojiEnabled", d.emojiEnabled),
            englishCandidates = sp.getBoolean("englishCandidates", d.englishCandidates),
            englishCandidatesPriority = sp.getBoolean(
                "englishCandidatesPriority",
                d.englishCandidatesPriority,
            ),
            schemaId = sp.getString("schemaId", d.schemaId) ?: d.schemaId,
            gameKeyboard = sp.getBoolean("gameKeyboard", d.gameKeyboard),
            keyboardScalePercent = sp.getInt("keyboardScalePercentV2", d.keyboardScalePercent),
            gameFloatXFrac = sp.getFloat("gameFloatXFrac4", d.gameFloatXFrac),
            gameFloatYFrac = sp.getFloat("gameFloatYFrac5", d.gameFloatYFrac),
            learningEnabled = sp.getBoolean("learningEnabled", d.learningEnabled),
            deployedAssetVersion = sp.getInt("deployedAssetVersion", 0),
            lastDeployOk = sp.getBoolean("lastDeployOk", false),
            lastDeployMessage = sp.getString("lastDeployMessage", "") ?: "",
        )
    }

    private fun SharedPreferences.optInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    /** 通用写入：lambda 直接编辑 Editor，然后刷新快照。 */
    fun edit(action: (SharedPreferences.Editor) -> Unit) {
        if (!::sp.isInitialized) return
        val editor = sp.edit()
        action(editor)
        editor.apply()
        _state.value = read()
    }

    // —— 语义化 setter ——

    fun setThemeMode(mode: Int) = edit { it.putInt("themeMode", mode) }

    /** 动态颜色（Material You）开关；默认开启。 */
    fun setDynamicColor(enabled: Boolean) = edit { it.putBoolean("useDynamicColor", enabled) }

    /** 按钮栏按钮圆角（dp）。 */
    fun setBarCorner(dp: Int) = edit { it.putInt("barCornerDp", dp) }

    fun setKeyHeight(dp: Int) = edit { it.putInt("keyHeightDp", dp) }

    /** 盲打模式（隐藏 26 个字母键的字母标签）。 */
    fun setBlindTyping(enabled: Boolean) = edit { it.putBoolean("blindTyping", enabled) }
    /** 游戏键盘开关：横屏时键盘悬浮缩小 + 可拖动（默认开启）。 */
    fun setGameKeyboard(enabled: Boolean) = edit { it.putBoolean("gameKeyboard", enabled) }

    /** 横屏悬浮键盘大小（百分比，95 = 默认；只影响横屏悬浮键盘）。 */
    fun setKeyboardScale(percent: Int) = edit { it.putInt("keyboardScalePercentV2", percent) }

    /** 记住悬浮键盘的位置（存比例，换屏幕尺寸也不会跑偏）。 */
    fun setGameFloatPos(xFrac: Float, yFrac: Float) = edit {
        it.putFloat("gameFloatXFrac4", xFrac).putFloat("gameFloatYFrac5", yFrac)
    }


    fun setKeyCorner(dp: Int) = edit { it.putInt("keyCornerDp", dp) }

    /** 候选词字号（sp）。 */
    fun setCandidateTextSize(sp: Int) = edit { it.putInt("candidateTextSp", sp) }

    fun setAccent(argb: Int?) = edit { e -> if (argb == null) e.remove("accentArgb") else e.putInt("accentArgb", argb) }

    // —— 输入法是否正在屏幕上 ——
    // 比时间戳可靠得多：系统把键盘收走（或切到别的输入法）时会通知隐藏，
    // 因此「此刻在屏」是真凭实据 —— 键盘都显示着，必然是当前选中的输入法。
    fun setImeShown(shown: Boolean) {
        if (!::sp.isInitialized) return
        val cur = if (shown) System.currentTimeMillis() else 0L
        if (sp.getLong("imeShownAt", 0L) == cur) return
        edit { it.putLong("imeShownAt", cur) }
    }

    fun imeShownAt(): Long =
        if (::sp.isInitialized) sp.getLong("imeShownAt", 0L) else 0L

    /** 本输入法是否正在显示（进程内即可判断，不需要跨进程）。 */
    fun imeShown(): Boolean = imeShownAt() > 0L

    fun setKeyboardBg(argb: Int?) = edit { e -> if (argb == null) e.remove("keyboardBgArgb") else e.putInt("keyboardBgArgb", argb) }

    fun setKeyBg(argb: Int?) = edit { e -> if (argb == null) e.remove("keyBgArgb") else e.putInt("keyBgArgb", argb) }

    fun setFuncKeyBg(argb: Int?) = edit { e -> if (argb == null) e.remove("funcKeyBgArgb") else e.putInt("funcKeyBgArgb", argb) }

    fun setKeyText(argb: Int?) = edit { e -> if (argb == null) e.remove("keyTextArgb") else e.putInt("keyTextArgb", argb) }

    fun setDeployResult(version: Int, ok: Boolean, message: String) = edit {
        it.putInt("deployedAssetVersion", version)
        it.putBoolean("lastDeployOk", ok)
        it.putString("lastDeployMessage", message)
    }

}