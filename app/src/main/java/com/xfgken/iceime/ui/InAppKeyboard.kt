package com.xfgken.iceime.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xfgken.iceime.R
import com.xfgken.iceime.core.AppLog
import com.xfgken.iceime.core.IcePrefs
import com.xfgken.iceime.data.PredictLexicon
import com.xfgken.iceime.data.SymbolHistory
import com.xfgken.iceime.ime.KeyAction
import com.xfgken.iceime.ime.KeyDef
import com.xfgken.iceime.ime.KeyRow
import com.xfgken.iceime.ime.KeySoundPlayer
import com.xfgken.iceime.ime.KeyboardLayouts
import com.xfgken.iceime.ime.KeyboardTheme
import com.xfgken.iceime.ime.KeyboardView
import com.xfgken.iceime.ime.LayoutType
import com.xfgken.iceime.rime.RimeEngine

/**
 * App 内键盘控制器。
 *
 * 用途：**不需要启用 / 切换系统输入法**，也能在 App 内部（测试页）使用完整键盘。
 *
 * 实现方式与输入法服务共用同一套东西：
 * · 布局：`KeyboardLayouts`（字母 / 符号 / 数字）
 * · 引擎：`RimeEngine` 会话（与输入法里同一个 librime）
 * · 外观：`KeyboardTheme`（同一套 M3 令牌）
 *
 * 区别只在于「上屏目标」：输入法服务写进 `InputConnection`，
 * 这里写进自己的 `text` 缓冲区，因此**仅在 App 内有效**，不会影响其它应用。
 */
class InAppKeyboardController(private val context: Context) {

    /** 状态变化通知（Compose 侧据此刷新）。 */
    var onChanged: (() -> Unit)? = null

    var layoutType: LayoutType = LayoutType.CHINESE_PINYIN
        private set
    var shiftActive: Boolean = false
        private set
    private var shiftLocked = false
    private var symbolCategory = 1

    /** 已上屏文本（App 内缓冲区）。 */
    var text: String = ""
        private set

    /** Rime 组合串（拼音）。App 内键盘把拼音显示在测试框里，与主流输入法一致。 */
    var preedit: String = ""
        private set

    private var candidates: List<RimeEngine.Candidate> = emptyList()
    private var highlighted = 0
    private var predictionWords: List<String> = emptyList()
    private var session = 0L

    init {
        runCatching { KeySoundPlayer.init(context) }
        runCatching { SymbolHistory.init(context) }
    }

    // —— 引擎会话 ——

    fun attachSession() {
        if (session != 0L) return
        RimeEngine.cleanupStaleSessions()
        session = RimeEngine.createSession()
        if (session != 0L) {
            RimeEngine.setOption(session, "ascii_mode", layoutType == LayoutType.ENGLISH)
            AppLog.i(AppLog.Category.INPUT, "App 内键盘会话已创建")
        }
        refreshCandidates()
    }

    fun release() {
        if (session != 0L) {
            runCatching { RimeEngine.destroySession(session) }
            session = 0L
        }
    }

    // —— 布局 ——

    fun rows(): List<KeyRow> = when (layoutType) {
        LayoutType.CHINESE_PINYIN -> KeyboardLayouts.chinesePinyin(actionLabel(), currentShiftMode())
        LayoutType.ENGLISH -> KeyboardLayouts.english(shiftActive, actionLabel())
        LayoutType.SYMBOLS -> KeyboardLayouts.symbols(symbolCategory, SymbolHistory.recent(), actionLabel())
        LayoutType.NUMBERS -> KeyboardLayouts.numbers(actionLabel())
    }

    /** 大写键状态：正在输入拼音时变成「词」（分词）。 */
    private fun currentShiftMode(): KeyboardLayouts.ShiftMode = when {
        preedit.isNotEmpty() -> KeyboardLayouts.ShiftMode.SEGMENT
        shiftLocked -> KeyboardLayouts.ShiftMode.LOCK
        shiftActive -> KeyboardLayouts.ShiftMode.ONCE
        else -> KeyboardLayouts.ShiftMode.OFF
    }

    private fun actionLabel(): String = "发送"

    // —— 候选栏 ——

    fun isPrediction(): Boolean = predictionWords.isNotEmpty() && candidates.isEmpty()

    // —— 按键 ——

    fun onKey(key: KeyDef) {
        if (predictionWords.isNotEmpty() && key.action !is KeyAction.OpenPanel) {
            predictionWords = emptyList()
        }
        when (val action = key.action) {
            is KeyAction.Rime -> sendToRime(action.keycode, action.modifier)
            KeyAction.SegmentSyllable -> handleSegment()
            is KeyAction.Commit -> insertText(action.text)
            KeyAction.Space -> handleSpace()
            KeyAction.Backspace -> handleBackspace()
            KeyAction.Enter -> handleEnter()
            KeyAction.Shift -> {
                if (shiftActive && !shiftLocked) {
                    shiftLocked = true
                } else if (shiftLocked) {
                    shiftLocked = false
                    shiftActive = false
                } else {
                    shiftActive = true
                }
                notifyChanged()
            }

            KeyAction.ClearComposition -> {
                if (session != 0L) RimeEngine.clearComposition(session)
                refreshCandidates()
            }

            is KeyAction.SwitchLayout -> {
                layoutType = action.target
                shiftActive = false
                notifyChanged()
            }

            KeyAction.BackToKeyboard -> {
                layoutType = if (layoutType == LayoutType.SYMBOLS || layoutType == LayoutType.NUMBERS) {
                    LayoutType.CHINESE_PINYIN
                } else {
                    LayoutType.CHINESE_PINYIN
                }
                notifyChanged()
            }

            is KeyAction.SymbolCategory -> {
                if (action.index < 0) {
                    layoutType = LayoutType.CHINESE_PINYIN
                } else {
                    symbolCategory = action.index
                }
                notifyChanged()
            }

            is KeyAction.PageCandidates -> {
                if (session != 0L) RimeEngine.changePage(session, backward = !action.next)
                refreshCandidates()
            }

            KeyAction.ToggleChineseEnglish -> {
                layoutType = if (layoutType == LayoutType.CHINESE_PINYIN) {
                    LayoutType.ENGLISH
                } else {
                    LayoutType.CHINESE_PINYIN
                }
                if (session != 0L) {
                    RimeEngine.commitComposition(session)
                    RimeEngine.commit(session)?.let { if (it.isNotEmpty()) appendText(it) }
                    RimeEngine.setOption(session, "ascii_mode", layoutType == LayoutType.ENGLISH)
                }
                notifyChanged()
                refreshCandidates()
            }

            KeyAction.HideKeyboard -> notifyChanged()
            // 面板 / 设置按钮在 App 内键盘里不做操作
            else -> Unit
        }
    }

    private fun sendToRime(keycode: Int, modifier: Int) {
        // 大写锁定 / 一次性大写：直接输出大写字母，不进拼音
        val wantUpper = shiftLocked || shiftActive
        if (wantUpper && keycode in 'a'.code..'z'.code) {
            insertText(keycode.toChar().uppercaseChar().toString())
            if (shiftActive && !shiftLocked) {
                shiftActive = false
                notifyChanged()
            }
            return
        }
        if (layoutType == LayoutType.ENGLISH && !IcePrefs.current().englishCandidates && keycode in 0x20..0x7E) {
            val ch = keycode.toChar()
            insertText(if (shiftActive) ch.uppercaseChar().toString() else ch.toString())
            return
        }
        if (session == 0L && RimeEngine.isReady) attachSession()
        if (session == 0L) {
            if (keycode in 0x20..0x7E) {
                val ch = keycode.toChar()
                insertText(if (shiftActive) ch.uppercaseChar().toString() else ch.toString())
            }
            return
        }
        val consumed = RimeEngine.processKey(session, keycode, modifier)
        if (consumed) {
            afterRimeInput()
        } else if (keycode in 0x20..0x7E) {
            val ch = keycode.toChar()
            insertText(if (shiftActive) ch.uppercaseChar().toString() else ch.toString())
        }
    }

    /** 分词：插入音节分隔符 `'`（ni → ni' → 继续输入 hao 得到「你好」）。 */
    private fun handleSegment() {
        if (session == 0L) return
        RimeEngine.processKey(session, '\''.code, 0)
        afterRimeInput()
    }

    private fun afterRimeInput() {
        val committed = RimeEngine.commit(session)
        if (!committed.isNullOrEmpty()) {
            appendText(committed)
            offerPrediction(committed)
        }
        if (shiftActive && !shiftLocked) {
            shiftActive = false
            notifyChanged()
        }
        refreshCandidates()
    }

    private fun handleSpace() {
        if (session != 0L && RimeEngine.processKey(session, RimeEngine.Key.SPACE, 0)) {
            afterRimeInput()
            return
        }
        insertText(" ")
    }

    private fun handleBackspace() {
        if (session != 0L) {
            val ctx = RimeEngine.context(session)
            if (ctx != null && (ctx.preedit.isNotEmpty() || ctx.candidates.isNotEmpty())) {
                RimeEngine.processKey(session, RimeEngine.Key.BACKSPACE, 0)
                afterRimeInput()
                return
            }
        }
        if (text.isNotEmpty()) {
            text = text.dropLast(1)
            notifyChanged()
        }
    }

    private fun handleEnter() {
        if (session != 0L) {
            val ctx = RimeEngine.context(session)
            val raw = ctx?.preedit?.replace(" ", "")?.replace("'", "").orEmpty()
            if (raw.isNotEmpty()) {
                // 与输入法一致：回车提交原始拼音，而不是候选词
                RimeEngine.clearComposition(session)
                appendText(raw)
                preedit = ""
                candidates = emptyList()
                notifyChanged()
                return
            }
        }
        appendText("\n")
    }

    private fun insertText(value: String) {
        if (value.isEmpty()) return
        if (session != 0L) {
            RimeEngine.commitComposition(session)
            RimeEngine.commit(session)?.let { if (it.isNotEmpty()) appendText(it) }
        }
        appendText(value)
        if (value.length == 1) SymbolHistory.markUsed(value)
        refreshCandidates()
    }

    private fun appendText(value: String) {
        text += value
        notifyChanged()
    }

    /** 点击候选 / 联想词。 */
    fun pick(index: Int) {
        feedback()
        if (isPrediction()) {
            val word = predictionWords.getOrNull(index) ?: return
            predictionWords = emptyList()
            appendText(word)
            notifyChanged()
            return
        }
        if (session == 0L) return
        if (RimeEngine.selectCandidate(session, index)) afterRimeInput()
    }

    fun clear() {
        text = ""
        preedit = ""
        candidates = emptyList()
        predictionWords = emptyList()
        if (session != 0L) RimeEngine.clearComposition(session)
        notifyChanged()
        refreshCandidates()
    }

    // —— 候选刷新 ——

    private fun refreshCandidates() {
        if (session == 0L) {
            preedit = ""
            candidates = emptyList()
            notifyChanged()
            return
        }
        val ctx = RimeEngine.context(session)
        if (ctx == null) {
            preedit = ""
            candidates = emptyList()
            notifyChanged()
            return
        }
        preedit = ctx.preedit.replace(" ", "")
        candidates = ctx.candidates
        highlighted = ctx.highlighted
        if (ctx.preedit.isEmpty() && ctx.candidates.isEmpty() && predictionWords.isEmpty()) {
            highlighted = 0
        }
        notifyChanged()
    }

    private fun offerPrediction(committed: String) {
        if (layoutType != LayoutType.CHINESE_PINYIN || committed.isEmpty()) {
            predictionWords = emptyList()
            return
        }
        if (committed.all { it.code < 128 }) {
            predictionWords = emptyList()
            return
        }
        PredictLexicon.ensureLoaded(context)
        val words = PredictLexicon.wordsFor(committed)
        predictionWords = if (words.size >= 2) words else emptyList()
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    // —— 反馈（与输入法保持一致：按键音 + 震动）——

    fun feedback() {
        val settings = IcePrefs.current()
        if (settings.soundEnabled) {
            val volume = (settings.keySoundVolume / 100f).coerceIn(0f, 1f)
            if (volume > 0f) runCatching { KeySoundPlayer.play(volume) }
        }
        if (settings.vibrateEnabled) {
            runCatching {
                val vibrator = context.getSystemService(Vibrator::class.java) ?: return@runCatching
                if (!vibrator.hasVibrator()) return@runCatching
                // 与输入法里的震动挡位（0–50）保持一致
                val level = settings.vibrateStrength.coerceIn(0, 50)
                val amplitude = if (level == 0) 1 else (level * 255f / 50f).toInt().coerceIn(1, 255)
                val duration = (10L + level * 30L / 50L).coerceIn(10L, 40L)
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            }
        }
    }
}

/** 预览用按钮栏（原生 View）：与真实键盘按钮栏**同一套配色、尺寸、圆角**，纯静态不可操作。 */
private class PreviewBarView(context: Context) : android.widget.LinearLayout(context) {
    private val d = resources.displayMetrics.density
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        // 与真实工具栏一致：左侧 6dp 内边距、右侧不留、底透明（露出键盘底色）
        setPadding((6 * d).toInt(), 0, 0, 0)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    fun apply(funcKeyBg: Int, tint: Int, cornerDp: Int) {
        removeAllViews()
        val size = (36 * d).toInt()
        val gap = (6 * d).toInt()
        val radius = cornerDp.coerceIn(0, 18) * d
        fun round(): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(funcKeyBg)
                cornerRadius = radius
            }
        fun addIcon(res: Int) {
            val iv = android.widget.ImageButton(context)
            iv.setImageResource(res)
            iv.setColorFilter(tint)
            iv.background = round()
            val p = (8 * d).toInt()
            iv.setPadding(p, p, p, p)
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.marginEnd = gap
            iv.layoutParams = lp
            addView(iv)
        }
        addIcon(R.drawable.ic_brand)
        addView(android.view.View(context), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
        val en = android.widget.TextView(context)
        en.text = "EN"
        en.gravity = android.view.Gravity.CENTER
        en.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        en.setTextColor(tint)
        en.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        en.background = round()
        val enLp = android.widget.LinearLayout.LayoutParams(size, size)
        enLp.marginEnd = gap
        en.layoutParams = enLp
        addView(en)
        addIcon(R.drawable.ic_emoji)
        addIcon(R.drawable.ic_clipboard)
        addIcon(R.drawable.ic_phrase)
        addIcon(R.drawable.ic_chevron_down)
    }
}

/**
 * 实时预览：与真实键盘**同一外观**（同一套主题配色、同样尺寸与圆角），
 * 只是不参与任何输入：所有回调为空，也不会去初始化 Rime 引擎。
 *
 * 结构也与真实键盘一致：顶行 50dp（按钮栏）+ 键盘区，整体在一个原生容器里拼好。
 */
@Composable
fun InAppKeyboardTest(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = rememberSettings()
    val controller = remember { InAppKeyboardController(context) }
    val d = context.resources.displayMetrics.density
    val barHeightDp = 50
    val maxRows = 5
    val keyboardHeightDp = maxRows * settings.keyHeightDp + (maxRows + 1) * settings.keyGapDp
    val themeKey = listOf(
        settings.themeMode,
        settings.accentArgb,
        settings.keyboardBgArgb,
        settings.keyBgArgb,
        settings.funcKeyBgArgb,
        settings.keyTextArgb,
        settings.keyHeightDp,
        settings.keyGapDp,
        settings.keyCornerDp,
        settings.barCornerDp,
        settings.blindTyping,
    ).joinToString("|")
    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height((barHeightDp + keyboardHeightDp).dp),
            factory = { ctx ->
                val palette = KeyboardTheme.palette(ctx)
                android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundColor(palette.keyboardBg)
                    addView(PreviewBarView(ctx), previewLp(ctx, barHeightDp))
                    addView(KeyboardView(ctx), previewLp(ctx, keyboardHeightDp))
                }
            },
            update = { root ->
                if (themeKey.isEmpty()) return@AndroidView
                val palette = KeyboardTheme.palette(context)
                root.setBackgroundColor(palette.keyboardBg)
                val bar = root.getChildAt(0) as PreviewBarView
                bar.apply(palette.funcKeyBg, palette.onSurfaceVariant, settings.barCornerDp)
                (bar.layoutParams as android.widget.LinearLayout.LayoutParams).height =
                    (barHeightDp * d).toInt()
                val kb = root.getChildAt(1) as KeyboardView
                (kb.layoutParams as android.widget.LinearLayout.LayoutParams).height =
                    (keyboardHeightDp * d).toInt()
                kb.onKey = null
                kb.onKeyFeedback = null
                kb.onSwipeCancel = null
                kb.style = KeyboardTheme.keyboardStyle(context)
                kb.hideLetterLabels = settings.blindTyping
                kb.fixedHeightPx = (keyboardHeightDp * d).toInt()
                kb.setRows(controller.rows())
                root.requestLayout()
            },
        )
    }
}

/** 预览容器里按 dp 算行高的辅助。 */
private fun previewLp(ctx: Context, heightDp: Number): android.widget.LinearLayout.LayoutParams =
    android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        (heightDp.toFloat() * ctx.resources.displayMetrics.density).toInt(),
    )
