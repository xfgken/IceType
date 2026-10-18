package com.xfgken.icetype.ime

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xfgken.icetype.R
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.core.IcePrefs
import com.xfgken.icetype.data.ClipboardStore
import com.xfgken.icetype.data.EmojiData
import com.xfgken.icetype.data.EnglishLexicon
import com.xfgken.icetype.data.PhraseStore
import com.xfgken.icetype.data.PinyinOrder
import com.xfgken.icetype.data.PredictLexicon
import com.xfgken.icetype.data.SymbolHistory
import com.xfgken.icetype.rime.RimeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * IceType 输入法服务。
 *
 * 职责：
 *  - 依据 EditorInfo 选择键盘布局与主操作键；
 *  - 把按键送入 Rime 引擎并处理上屏/候选/翻页；
 *  - 维护剪贴板、常用语、表情面板；
 *  - 严格遵循文档 11 节的系统集成与隐私约束（密码框不做学习、不存历史）。
 */
class IceInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var keyboardView: KeyboardView? = null
    private var candidateBar: CandidateBarView? = null
    private var panelView: PanelView? = null
    private var toolbar: LinearLayout? = null

    /** 中英切换按钮的两副面孔：中文态显示 EN 文字，英文态显示中文字形图标。 */
    private var langLabel: android.widget.TextView? = null
    private var langIcon: android.widget.ImageView? = null

    /** 中英切换按钮当前显示的是不是中文形态；null 表示还没摆过（首次不做动画）。 */
    private var langShownChinese: Boolean? = null
    /**
     * 最近一次「字母键盘」的语言（中文拼音 / 英文）。
     *
     * 数字键盘、符号页不是字母布局，但它们发生时会重建工具栏并刷新中英切换按钮；
     * 如果直接用 `layoutType == CHINESE_PINYIN` 判断，进数字/符号页就会被误判成
     * “切到英文”，按钮凭空旋转切换一次。所以这里记下最后一次字母布局的语言。
     */
    private var lastLetterLayout: LayoutType = LayoutType.CHINESE_PINYIN
    private var actionRow: android.widget.FrameLayout? = null
    private var root: LinearLayout? = null

    private var session: Long = 0L
    private var isPasswordField = false
    private var shiftActive = false
    private var shiftLocked = false
    private var layoutType = LayoutType.CHINESE_PINYIN

    /** 进数字键盘之前用的是哪种字母键盘（点完数字页的符号要回到它）。 */
    /** 数字键盘左列那几个符号：只有它们上屏后才自动回字母键盘。 */
    private val NUMBER_LEFT_SYMBOLS = setOf("@", "%", "-", "−")

    private var lettersBeforeNumbers = LayoutType.CHINESE_PINYIN

    /** 进符号键盘前是哪套字母键盘（点 ✕ 时回到它，而不是硬回中文）。 */
    private var lettersBeforeSymbols = LayoutType.CHINESE_PINYIN

    /** 符号入口按钮内部的「服务号图标 / ✕」两副图标。 */
    private var symbolBtnIconNormal: android.widget.ImageView? = null
    private var symbolBtnIconClose: android.widget.ImageView? = null
    private var symbolBtnWrapper: View? = null

    /** 上一次的符号按钮形态（true = 正在显示 ✕），用于只在该变的时候播动画。 */
    private var symbolBtnActive: Boolean? = null
    private var symbolCategory = 0
    private var panelVisible = false

    /** 大写锁定（快速双击大写键）：锁定后输入的都是纯大写字母，不进拼音。 */
    private var capsLocked = false

    /** 上一次点大写键的时间，用于识别"快速双击"。 */
    private var lastShiftTapAt = 0L

    /** 当前展开的面板类型（用于按钮栏再次点击时收起）。 */
    private var panelType: PanelType? = null

    /** 面板按钮（表情 / 剪贴板 / 常用语），展开时旋转成 ✕。 */
    private val panelButtons = LinkedHashMap<PanelType, android.view.View>()
    private val panelButtonIcons = HashMap<PanelType, Int>()

    /** 面板按钮里的两副图标（原图标 / ✕）：只转它们，外框实色底不动。 */
    private val panelButtonViews =
        HashMap<PanelType, Pair<android.widget.ImageView, android.widget.ImageView>>()

    /** 每个面板按钮当前是否处于「展开」形态，避免重复播放动画。 */
    private val panelButtonActive = HashMap<PanelType, Boolean>()

    /** 展开态的 ✕ 颜色：浅浅的红色（Material red 300）。 */
    private val PANEL_CROSS_COLOR = 0xFFE57373.toInt()

    /** 选词后展示的联想词；为空表示当前不在联想态。 */
    private var predictionWords: List<String> = emptyList()

    /** 输入 @ 之后的邮箱后缀候选：(显示文本, 需要补全上屏的文本)。 */
    private var emailSuggestions: List<Pair<String, String>> = emptyList()

    /**
     * 邮箱候选模式（**显式状态**，不再靠“输入框里有 @”推断）。
     *
     * 只有用户刚用键盘打出一个 `@` 才会置为 true；
     * 一旦开始拼音组合 / 光标移走 / 切键盘 / 面板打开 / 用户点 ✕ / 清空输入，
     * 就立刻退出，绝不会因为输入框里残留的 `@` 又自己冒出来。
     */
    private var emailMode = false

    /**
     * 「这次的 @ 已经关了」标记。
     * 关掉后即使输入框里还留着 @ 也不会重开，直到用户重新打一个新的 @。
     */
    private var emailBlocked = false

    /** 记下当前生效的是哪一个 @（局部名 + @，如 xfgken@），用来防止其它位置的 @ 抢占候选。 */
    private var emailLocalPart = ""

    /** 最近一次自己向输入框写文字的时间：区分“自己写字引起的光标移动”与“用户手动移动光标”。 */
    private var lastTextCommitAt = 0L

    /** 英文候选：(完整单词, 需要补全上屏的剩余字母)。 */
    private var englishSuggestions: List<Pair<String, String>> = emptyList()

    /** 用户是否点过候选栏右侧的 ✕（英文候选被主动关闭，敲字/切键盘后恢复）。 */
    private var suggestionsDismissed = false

    /** 系统剪贴板监听器：每复制一次立刻记录一次（见 [registerClipboardWatcher]）。 */
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    /** 剪贴板候选：候选栏当前显示的剪贴板文本；null 表示不显示。 */
    private var clipboardSuggestionText: String? = null

    /** 被用户点 ✕ 关闭过、或打字让位消费过的剪贴板内容；持久化到 IcePrefs，切换输入法/服务重启后仍记住。 */
    private var clipboardSuggestionDismissed: String?
        get() = prefs.clipboardDismissedText
        set(value) { IcePrefs.setClipboardDismissedText(value) }

    /** 最近一次引擎上下文，用于判断那一行该显示候选还是工具栏。 */
    private var lastPreedit: String = ""

    /**
     * 光标在「正在输入的拼音」内部的偏移（由 [onUpdateSelection] 维护）。
     * -1 表示光标不在拼音里（分词就按老规矩追加到末尾）。
     */
    private var composingCursor: Int = -1
    private var lastCandidates: List<RimeEngine.Candidate> = emptyList()

    private var engineRequested = false

    private val prefs get() = IcePrefs.current()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        IcePrefs.init(applicationContext)
        ClipboardStore.init(applicationContext)
        PhraseStore.init(applicationContext)
        EmojiData.init(applicationContext)
        SymbolHistory.init(applicationContext)
        // 按键音播放器（自己合成的声音，可调音量）
        KeySoundPlayer.init(applicationContext)
        // 每次复制都记录进历史（不能只在打开面板时读一次，否则只留最后一条）
        registerClipboardWatcher()
        AppLog.i(AppLog.Category.UI, "输入法服务已创建")
    }

    // ——————————————————————— 游戏键盘（横屏悬浮） ———————————————————————

    /**
     * 悬浮卡片的宽度 = **竖屏键盘的宽度**（屏幕短边）。
     *
     * 横屏时若把键盘铺满整块宽屏，每一行会被横向拉长（按键变成扁条）；
     * 而用「横向缩放」去压窄又会把字一起压扁。所以这里**完全不缩放**，
     * 只把卡片的**布局宽度限制成竖屏那块键盘的宽度** ——
     * 这样按键尺寸、字形、整块大小和竖屏完全一致，也不会变形。
     */
    private fun portraitKeyboardWidth(): Int {
        val dm = resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels)
    }

    /** 设置里的「横屏悬浮键盘大小」→ 缩放系数（100% = 1.0，只用于横屏悬浮键盘）。 */
    private fun keyboardSizeScale(): Float =
        (prefs.keyboardScalePercent.coerceIn(50, 150) / 100f)

    /** 悬浮卡片最终用的缩放倍数 = 悬浮基准比例 × 设置里的「横屏悬浮键盘大小」。 */
    private fun floatScale(): Float = gameFloatScale * keyboardSizeScale()

    /**
     * 按设置里的「横屏悬浮键盘大小」缩放。
     *
     * **只影响横屏时的悬浮（游戏）键盘**：
     * · 悬浮：整块卡片等比缩放，倍数 = 悬浮基准比例 × 设置值（由 applyFloatTransform 套用）；
     * · 竖屏 / 关闭悬浮：缩放与平移全部还原成 1，键盘和以前**一模一样**，不受这个设置影响。
     */
    private fun applyKeyboardSize(floating: Boolean = gameFloatActive) {
        val c = root ?: return
        val box = kbBox ?: return
        if (floating) {
            applyFloatTransform()
            return
        }
        // 非悬浮：完全还原（不做任何缩放）
        for (v in floatParts) {
            v.pivotX = 0f
            v.pivotY = 0f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        }
        val bg = if (keyboardBgColor != 0) keyboardBgColor else KeyboardTheme.palette(this).keyboardBg
        c.setBackgroundColor(bg)
        box.background = null
        c.requestLayout()
        c.invalidate()
    }

    /**
     * 悬浮时整块再统一缩小的比例（**长宽同一个比例，等比，不会变形**）。
     *
     * 卡片宽度已经是「和竖屏键盘同宽」，这里再整体等比缩 14.5% → 0.855。
     */
    private val gameFloatScale = 0.855f

    /** 容器（根布局）原来的高度参数，退出悬浮时还原。 */
    private val WindowWrap = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * 悬浮窗口宽度 = 键盘卡片的自然宽度（未缩放）。
     *
     * 悬浮时卡片宽度恒为「竖屏键盘宽（屏幕短边）」，**不能取父容器宽度**——
     * 横屏下父容器是整屏宽（2656），会把窗口算得过大（表现：键盘显示不全）。
     */
    private fun floatWinWidth(): Int = portraitKeyboardWidth()

    /**
     * 卡片的**自然高度**（未缩放、不受父布局约束）。
     *
     * 悬浮时窗口比卡片小，如果直接用父布局给的约束量，卡片会被压缩、
     * 键盘会变形，所以这里用 UNSPECIFIED 单独量一次真实高度。
     */
    /** 自然高度缓存（按宽度缓存）；卡片布局真变化时由布局回调置 -1 作废。 */
    private var naturalH = 0
    private var naturalHWW = -1

    private fun naturalCardHeight(): Int {
        val box = kbBox ?: return 0
        val w = if (box.width > 0) box.width else portraitKeyboardWidth()
        // 缓存很重要：对**已经贴在窗口上**的卡片调 measure()，会给它打上
        // 「需要重新布局」标记 → 触发 kbBox 的布局回调 → 拖动中会被重摆一下（弹一下）。
        if (naturalHWW == w && naturalH > 0) return naturalH
        runCatching {
            box.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
        }
        val h = box.measuredHeight
        val out = if (h > 0) h else box.height
        if (out > 0) {
            naturalH = out
            naturalHWW = w
        }
        return out
    }

    /** 悬浮窗口高度 = 键盘卡片的自然高度（未缩放）。 */
    private fun floatWinHeight(): Int = naturalCardHeight()

    /** 悬浮键盘**可见**大小（缩放之后），可移动范围按它算。 */
    private fun floatVisWidth(): Int = (floatWinWidth() * floatScale()).toInt().coerceAtLeast(1)

    private fun floatVisHeight(): Int = (floatWinHeight() * floatScale()).toInt().coerceAtLeast(1)

    /** 窗口尺寸还没量出来时的重试计数（防止窗口被设成几个像素、键盘看不见）。 */
    private var floatFrameRetry = 0

    /** 上一次套用的窗口尺寸（尺寸一变就重新按默认位置摆一次）。 */
    private var lastFrameW = -1
    private var lastFrameH = -1

    private fun scheduleFloatFrame() {
        if (floatFrameRetry >= 20 || !gameFloatActive) return
        floatFrameRetry++
        root?.postDelayed({
            if (gameFloatActive) {
                if (floatWinWidth() < 200 || floatWinHeight() < 100) scheduleFloatFrame()
                else placeFloatFromPrefs()
            }
        }, 32)
    }

    /**
     * 悬浮键盘 = **窗口本身就是键盘**。
     *
     * 为什么必须这样（别的悬浮窗被压扁的根因）：
     * 系统判断「键盘占了多大地方」只看**输入法窗口的边框**，不看键盘画在哪。
     * 以前把窗口撑满整屏（2656×1220），系统就认为键盘盖住了全屏，
     * 于是任何点过输入框的应用窗口都会被 resize / 顶走 —— 跟键盘离它多远完全无关。
     *
     * 现在窗口大小 = 键盘卡片本身，位置直接改窗口坐标，窗口 gravity 从「贴底」改成浮动定位：
     * 这样系统给下面应用的键盘避让区域 = 窗口矩形 ∩ 应用矩形，不重叠就是 0，
     * 别的小窗完全不受影响（主流输入法的悬浮键盘就是这么做的）。
     */
    /**
     * 保证悬浮位置有效：未初始化(<0)或尺寸变化时，按「水平居中、整块键盘贴底」重算一次。
     *
     * 建弹窗之前必须先调它，否则窗口会先用 -1 出现在左上角、再由下一帧挪过去
     * （表现就是「弹出来是平移出来的、还显示不全」）。
     */
    private fun ensureFloatPos(visW: Int, visH: Int) {
        val sizeChanged = visW != lastFrameW || visH != lastFrameH
        if (sizeChanged) {
            lastFrameW = visW
            lastFrameH = visH
        }
        // 注意：sizeChanged 只在「用户还没插手」时才能触发重摆，
        // 否则一按下把手（弹窗临时变大→尺寸变化）就会把键盘重置回居中——表现就是「乱弹/左右跳」
        val neverPlaced = !floatUserMoved && (prefs.gameFloatYFrac < 0f || sizeChanged)
        if (neverPlaced || floatDx < 0f) floatDx = maxFloatDx() / 2f
        if (neverPlaced || floatDy < 0f) {
            floatDy = (floatParentHeight() - visH).toFloat().coerceIn(floatDyMin(), floatDyMax())
        }
        clampFloatPos()
        // 不是拖动中 → 保证整块键盘可见（拖出屏只是临时姿态，重新弹出必须完整）
        if (!draggingFloat) floatDy = floatDy.coerceAtMost(floatDyFullVisibleMax())
    }

    /**
     * 同步卡片在弹窗里的**显式尺寸**。
     *
     * 为什么必须显式：弹窗比卡片矮时，`WRAP_CONTENT` 会被父布局的 AT_MOST 约束压扁，
     * 键盘的下半部分就会被裁掉（显示不完整）。所以每次尺寸变化都把真实自然尺寸写进去。
     */
    private fun syncCardSize(box: android.view.View, visW: Int, visH: Int) {
        // 父容器可能是输入法窗口里的 LinearLayout，也可能是弹窗里的 EggRootLayout（也是 LinearLayout）
        val lp = box.layoutParams ?: return
        if (lp !is LinearLayout.LayoutParams && lp !is android.widget.FrameLayout.LayoutParams) return
        val wantW = portraitKeyboardWidth()
        val wantH = naturalCardHeight().coerceAtLeast(200)
        if (lp.width != wantW || lp.height != wantH) {
            lp.width = wantW
            lp.height = wantH
            box.layoutParams = lp
        }
    }

    /**
     * **只改窗口坐标**（拖动时专用）。
     *
     * 尺寸沿用当前值，一个字节都不变 —— 这样就不会触发「卡片重新布局 + 表面重设尺寸」，
     * 也就不会出现「窗口与键盘不同帧生效」导致的弹跳。
     */
    private fun applyFloatWindowPos() {
        val holder = floatPopup ?: return
        val wm = popupWindowManager() ?: return
        val w = floatVisWidth()
        val h = floatVisHeight()
        if (w < 200 || h < 100) return
        val lp = newPopupParams()
        lp.width = w
        lp.height = h
        lp.x = floatDx.toInt()
        lp.y = (floatDy + popSlideInY).toInt()
        runCatching { wm.updateViewLayout(holder, lp) }
    }

    /** 弹出动画：从下方（键盘高度处）滑到最终位置，190ms + EmphasizedDecelerate。 */
    private fun startFloatSlideIn(visH: Int) {
        popSlideAnim?.let { runCatching { it.cancel() } }
        popSlideInY = visH.toFloat()
        applyFloatWindowPos()
        val anim = android.animation.ValueAnimator.ofFloat(popSlideInY, 0f)
        anim.duration = 190L
        anim.interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        anim.addUpdateListener { va ->
            popSlideInY = va.animatedValue as Float
            applyFloatWindowPos()
        }
        anim.start()
        popSlideAnim = anim
    }

    private fun applyFloatWindowFrame(floating: Boolean) {
        if (!floating) {
            // 退出悬浮：输入法窗口交回框架（onConfigureWindow 里走 super，恢复标准形态）
            root?.let { c ->
                c.layoutParams?.let { clp ->
                    if (clp.height != WindowWrap) {
                        clp.height = WindowWrap
                        c.layoutParams = clp
                    }
                }
            }
            return
        }
        val holder = floatPopup ?: return
        val wm = popupWindowManager() ?: return
        val box = kbBox ?: return
        val visW = floatVisWidth()
        val visH = floatVisHeight()
        if (visW < 200 || visH < 100) {
            // 布局还没量完 → 绝不能把窗口设成 0/1px（键盘会整块看不见），等布局回来再套
            scheduleFloatFrame()
            return
        }
        // 窗口尺寸**恒等于键盘可见尺寸**：全程不留拖动余量、不做视图平移，
        // 所以任何时刻都不会出现「尺寸变化」，也就没有跨帧错位导致的弹跳。
        ensureFloatPos(visW, visH)
        syncCardSize(box, visW, visH)
        val lp = newPopupParams()
        lp.width = visW
        lp.height = visH
        lp.x = floatDx.toInt()
        lp.y = floatDy.toInt()
        runCatching { wm.updateViewLayout(holder, lp) }
    }

    private fun screenHeightPx(): Int = resources.displayMetrics.heightPixels

    /** 顶部把手（缩放后）的可见高度。 */
    private fun handleVisibleHeight(): Float =
        (dragHandle?.height ?: dp(gameHandleDp)).toFloat() * floatScale()

    /**
     * 弹出时的「整块键盘都可见」下界。
     *
     * 拖动时允许把键盘拖出屏幕（最多只剩把手 + 按钮栏），但那只是**临时姿态**；
     * 下次弹出时必须整块可见，否则用户看到的就是「键盘被切掉 / 显示不完整」。
     */
    private fun floatDyFullVisibleMax(): Float =
        ((screenHeightPx() - floatBottomInsetPx()).toFloat() - floatVisHeight())
            .coerceAtLeast(floatDyMin())

    /** 按钮栏（把手下面那一行：工具栏 / 候选栏，缩放后）的可见高度。 */
    private fun actionRowVisibleHeight(): Float =
        (actionRow?.height ?: dp(BAR_HEIGHT_DP)).toFloat() * floatScale()

    /** 悬浮 / 退出悬浮时切换卡片的布局宽度。 */
    private fun applyFloatWidth(floating: Boolean) {
        val box = kbBox ?: return
        val target = if (floating) portraitKeyboardWidth() else ViewGroup.LayoutParams.MATCH_PARENT
        when (val lp = box.layoutParams) {
            // 卡片在输入法窗口里（LinearLayout）或已经搬进独立弹窗（FrameLayout）
            is LinearLayout.LayoutParams -> if (lp.width != target) {
                lp.width = target
                box.layoutParams = lp
                box.requestLayout()
            }
            is android.widget.FrameLayout.LayoutParams -> if (lp.width != target) {
                lp.width = target
                box.layoutParams = lp
                box.requestLayout()
            }
            else -> Unit
        }
    }

    /** 悬浮卡片的圆角（dp）。 */
    private val gameCardCornerDp = 18

    /** 悬浮时顶部那条「系统导航条式」把手的显示高度（dp）。 */
    private val gameHandleDp = 20

    /** 当前是否处于悬浮状态（横屏 + 开关开启）。 */
    private var gameFloatActive = false

    /** 悬浮位置偏移（拖动时更新），-1 表示还没初始化。 */
    private var floatDx = -1f
    private var floatDy = -1f


    /** 进入悬浮：把卡片从输入法窗口搬进独立弹窗（视图树与状态不变，只是换了父窗口）。 */
    /**
     * ## 悬浮键盘 = 微信输入法式结构（独立弹窗）
     *
     * 系统/厂商（MIUI 小窗避让）判断「键盘出现了、要占地方」是看**输入法窗口**的，
     * 输入法没法一边显示一边声明「我没显示」；主流输入法（微信）的做法是：
     * · 输入法窗口保持**整屏宽 + 贴底 + 透明**这种最标准、最熟悉的形态；
     * · 键盘本体画在一个**独立的小窗口**里（TYPE_APPLICATION_ATTACHED_DIALOG，
     *   挂在输入法窗口的 token 上，不需要任何新权限）。
     * 这样系统看到的始终是「一块正常贴底的输入法」，小窗就不会被避让逻辑改大小。
     *
     * 窗口尺寸**恒等于键盘可见尺寸**，拖动时只改坐标 —— 尺寸永不变，
     * 所以不会出现「窗口与键盘不同帧生效」导致的弹跳（前面几轮踩过的坑）。
     */
    private var floatPopup: android.widget.LinearLayout? = null

    /** 是否正在拖动把手（决定要不要跳过「整块可见」钳制）。 */
    private var draggingFloat = false

    /**
     * 悬浮弹出时的**滑入偏移**（px，只在弹出动画期间非 0）。
     *
     * 竖屏弹出时系统自带「从下方滑出」的动画；悬浮键盘是独立窗口，没有这个动画，
     * 所以这里自己做一份：只动窗口坐标（绝不动尺寸），因此不会引入弹跳。
     */
    private var popSlideInY = 0f

    /** 弹出动画（保留引用，避免被回收 / 重复启动）。 */
    private var popSlideAnim: android.animation.ValueAnimator? = null

    /** 位移是否已超过「手指抖动」阈值（超过才算真的开始拖）。 */
    private var dragArmed = false

    /**
     * 手指抖动容差（px）：小于它一律不算拖动。
     *
     * ★ 必须用 get() 取值：写成 `= dp(12)` 会在**服务构造**时执行，
     *   那时 Service 还没挂 Context，`resources` 是 null → 一创建输入法就闪退。
     */
    private val dragSlopPx: Float get() = dp(12).toFloat()

    /** 独立弹窗用的 WindowManager。 */
    private fun popupWindowManager(): android.view.WindowManager? =
        getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager

    /**
     * 独立弹窗的窗口参数：TYPE_APPLICATION_ATTACHED_DIALOG 挂在输入法窗口的 token 上，
     * 不需要任何新权限；坐标以**屏幕左上角**为原点（与输入法窗口不同，别混）。
     */
    private fun newPopupParams(): android.view.WindowManager.LayoutParams {
        val lp = android.view.WindowManager.LayoutParams()
        lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.format = android.graphics.PixelFormat.TRANSLUCENT
        lp.setTitle("IceTypeFloat")
        lp.flags = (android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        lp.token = window?.window?.decorView?.windowToken
        return lp
    }

    private fun enterFloatPopup() {
        if (floatPopup != null) {
            applyFloatWindowFrame(true)
            return
        }
        val box = kbBox ?: return
        val wm = popupWindowManager() ?: return
        if (window?.window?.decorView?.windowToken == null) return
        // 先把卡片宽度定成悬浮宽度，再取尺寸（否则会拿到「铺满整行」的宽度）
        applyFloatWidth(true)
        // 容器必须是 EggRootLayout：彩蛋的点击火花（彩色小球）是画在**根容器**上的
        // （dispatchTouchEvent 偷看坐标 → dispatchDraw 在最上层画火花）。
        // 键盘悬浮时会被搬进这个弹窗，若这里用普通容器，彩蛋期间点按键就没有小球。
        val holder = EggRootLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            effectEnabled = { eggRunning }
        }
        (box.parent as? android.view.ViewGroup)?.removeView(box)
        // 宽度写悬浮宽度、高度写**卡片自然高度**：
        // 不能写 WRAP_CONTENT（弹窗比卡片矮，会被 AT_MOST 约束压扁 → 键盘显示不全）
        holder.addView(
            box,
            LinearLayout.LayoutParams(
                portraitKeyboardWidth(),
                naturalCardHeight().coerceAtLeast(200),
            ),
        )
        // 先用兜底尺寸把位置算好再建窗口：窗口一出现就在正确位置（不做「平移出场」）
        val visW = floatVisWidth().let { if (it >= 200) it else portraitKeyboardWidth() }
        val visH = floatVisHeight().let { if (it >= 100) it else (kbBox?.height ?: 0).coerceAtLeast(400) }
        ensureFloatPos(visW, visH)
        val lp = newPopupParams()
        lp.width = visW
        lp.height = visH
        lp.x = floatDx.toInt()
        lp.y = floatDy.toInt()
        val ok = runCatching { wm.addView(holder, lp) }.isSuccess
        if (!ok) {
            // 加不上就退回输入法窗口内（保证键盘还能用，不会整块消失）
            AppLog.i(AppLog.Category.UI, "悬浮键盘弹窗创建失败，回退到输入法窗口内显示")
            (box.parent as? android.view.ViewGroup)?.removeView(box)
            holder.removeAllViews()
            return
        }
        floatPopup = holder
        eggRoot = holder // 火花画在这个（弹窗）容器上
        applyFloatTransform()
        applyFloatWindowFrame(true)
        // 与竖屏一致的「向上滑出」弹出动画（只改坐标，不改尺寸）
        startFloatSlideIn(visH)
    }

    /**
     * 退出悬浮：弹窗移除、卡片搬回输入法窗口内。
     *
     * 注意：这里**不套任何缩放/平移**（缩放由 applyKeyboardSize 统一负责）——
     * 之前多调了一次 applyFloatTransform，把竖屏键盘按悬浮比例缩小、还挪到了左上角。
     */
    private fun exitFloatPopup() {
        eggRoot = root as? EggRootLayout // 火花画回输入法窗口的容器
        val holder = floatPopup
        popSlideAnim?.let { runCatching { it.cancel() } }
        popSlideAnim = null
        popSlideInY = 0f
        floatPopup = null
        if (holder != null) {
            runCatching { popupWindowManager()?.removeViewImmediate(holder) }
        }
        val box = kbBox
        val container = root
        if (box != null && container != null && box.parent !== container) {
            (box.parent as? android.view.ViewGroup)?.removeView(box)
            container.addView(
                box,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** 悬浮「卡片」：把手 + 按钮栏 + 面板 + 键盘都在里面，悬浮时整体缩放/平移。 */
    private var kbBox: LinearLayout? = null

    /** 键盘底色（悬浮卡片的底色用它）。 */
    private var keyboardBgColor = 0

    /** 顶部拖动把手（按住它拖整个键盘）。 */
    private var dragHandle: android.view.View? = null

    /** 需要一起等比缩放 / 平移的视图：把手 + 按钮栏那一行 + 面板 + 键盘。 */
    private val floatParts = ArrayList<android.view.View>()

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartDx = 0f
    private var dragStartDy = 0f

    /**
     * 给顶部把手上色（浅色 / 深色各一套）。
     *
     * 单独抽出来是为了**主题切换后能重新上色** —— 以前只在创建时取一次色，
     * 切到深色模式后把手还是浅色，看起来「没跟着深色」。
     */
    private fun styleDragHandle(handle: android.view.View) {
        val palette = KeyboardTheme.palette(this)
        val frame = handle as? android.widget.FrameLayout ?: return
        val pill = frame.getChildAt(0) ?: return
        pill.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(KeyboardTheme.withAlpha(palette.onSurfaceVariant, 0x55))
            cornerRadius = dp(2).toFloat()
        }
        frame.setBackgroundColor(KeyboardTheme.withAlpha(palette.onSurfaceVariant, 0x14))
    }

    /** 建顶部把手：一条像系统导航条那样的条（中间一个小圆角条），按住可拖整个键盘。 */
    private fun buildDragHandle(): android.view.View {
        val pill = android.view.View(this)
        return android.widget.FrameLayout(this).apply {
            isClickable = true
            addView(
                pill,
                android.widget.FrameLayout.LayoutParams(dp(64), dp(4)).apply { gravity = Gravity.CENTER },
            )
            styleDragHandle(this)
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 一开始拖就归用户管（否则窗口提交时会又按「默认位置」重算）
                        floatUserMoved = true
                        draggingFloat = true
                        // 注意：这里**不扩窗**。扩窗（窗口位置/尺寸变了）和视图平移是异步生效的，
                        // 一按下就扩、一松手就收，单纯「点一下」就会看到弹两下。
                        // 改成第一次真正产生位移时才扩窗（见 ACTION_MOVE）。
                        dragArmed = false
                        dragStartRawX = ev.rawX
                        dragStartRawY = ev.rawY
                        dragStartDx = floatDx
                        dragStartDy = floatDy
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 手指抖动容差：位移没超阈值就完全不动（点一下把手 = 什么都不发生）
                        if (!dragArmed) {
                            val jx = ev.rawX - dragStartRawX
                            val jy = ev.rawY - dragStartRawY
                            if (kotlin.math.abs(jx) < dragSlopPx && kotlin.math.abs(jy) < dragSlopPx) return@setOnTouchListener true
                            // 超阈值：以「当前手指位置」为新起点（键盘不会突然跳一截），这时才扩窗
                            dragStartRawX = ev.rawX
                            dragStartRawY = ev.rawY
                            dragStartDx = floatDx
                            dragStartDy = floatDy
                            dragArmed = true
                        }
                        // ★ 拖动 = **只改窗口坐标**（纯位置变化），全程不改窗口尺寸、不做视图平移。
                        //
                        // 为什么不能改尺寸：尺寸变化会让卡片重新布局 + 表面重设尺寸，
                        // 和坐标变化不是同帧生效 → 拖动一开始、以及松手收回余量时都会「弹一下」。
                        // 实测结论：只变位置时拖动全程丝滑（用户实测「移移正常了」），
                        // 所以这里干脆不留余量、不做平移，窗口与键盘永远严格重合。
                        val wantX = (dragStartDx + (ev.rawX - dragStartRawX)).coerceIn(0f, maxFloatDx())
                        // 上：把手顶到系统状态栏下边缘就停；下：最多拖到只剩把手
                        val wantY = (dragStartDy + (ev.rawY - dragStartRawY))
                            .coerceIn(floatDyMin(), floatDyMax())
                        if (wantX != floatDx || wantY != floatDy) {
                            floatDx = wantX
                            floatDy = wantY
                            applyFloatWindowPos()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 松手：把拖动结果提交到窗口并落盘（拖动过程中不写存储，与 Xime 一致）
                        floatUserMoved = true
                        draggingFloat = false
                        // 松手：尺寸与坐标都不动（拖动中已是最终状态），只把位置落盘
                        val mx = maxFloatDx()
                        val lo = floatDyMin()
                        val hi = floatDyMax()
                        IcePrefs.setGameFloatPos(
                            if (mx > 0f) (floatDx / mx).coerceIn(0f, 1f) else 0.5f,
                            if (hi - lo > 0f) ((floatDy - lo) / (hi - lo)).coerceIn(0f, 1f) else -1f,
                        )
                        true
                    }
                    else -> false
                }
            }
        }
    }



    /** 本次弹出里用户是否手动拖过（拖过就尊重当前偏移，不再用记住的比例）。 */
    private var floatUserMoved = false

    /**
     * 按「记住的位置」摆好卡片。
     *
     * 注意：必须在布局真正完成后再调用（否则拿到的卡片尺寸还是 0，
     * 会算出「贴左上角」）。因此它在 post 里 + kbBox 布局变化回调里各调一次。
     */
    private fun placeFloatFromPrefs() {
        if (floatWinWidth() < 200 || floatWinHeight() < 100) {
            scheduleFloatFrame()
            return
        }
        val mx = maxFloatDx()
        val lo = floatDyMin()
        val hi = floatDyMax()
        if (!floatUserMoved) {
            floatDx = (prefs.gameFloatXFrac * mx).coerceIn(0f, mx)
            val yFrac = prefs.gameFloatYFrac
            floatDy =
                if (yFrac < 0f) {
                    // 还没设过 → 默认：整块键盘都露出来、贴着底部
                    (floatParentHeight() - floatVisHeight()).toFloat().coerceIn(lo, hi)
                } else {
                    (lo + yFrac * (hi - lo)).coerceIn(lo, hi)
                }
        } else {
            clampFloatPos()
        }
        applyFloatTransform()
        applyFloatWindowFrame(true)
    }

    /** 左右可移动范围：0 ~ 屏宽 − 键盘可见宽度（弹窗 x 也是从屏幕左边算）。 */
    private fun maxFloatDx(): Float =
        (screenWidthPx().toFloat() - floatVisWidth().toFloat()).coerceAtLeast(0f)

    /**
     * 窗口 y 坐标的可用区域高度。
     *
     * 注意：输入法窗口的 y 是**相对「状态栏以下的父区域」**的（实测 frame y = 父区 top + y），
     * 所以这里要减掉状态栏高度，y = 0 正好就是「把手顶到状态栏下边缘」。
     */
    /**
     * 弹窗坐标空间里的**状态栏底部**（px）。
     *
     * 重要：`TYPE_APPLICATION_ATTACHED_DIALOG` 弹窗的 y 原点是**屏幕顶部**
     * （实测 parent=[0,0][2656,1155]），和输入法窗口（parent 从 150 开始）不一样，
     * 所以上界必须是状态栏高度，否则把手会被状态栏盖住点不到。
     */
    private fun floatTopInsetPx(): Int {
        val ins = floatPopup?.rootWindowInsets
        val v = ins?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
        return (if (v > 0) v else systemStatusBarHeight()).coerceAtLeast(0)
    }

    /** 弹窗坐标空间里的**底部系统栏高度**（px）：键盘底边不要压到导航条上。 */
    private fun floatBottomInsetPx(): Int {
        val ins = floatPopup?.rootWindowInsets
        return (ins?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0)
            .coerceAtLeast(0)
    }

    private fun floatParentHeight(): Float =
        (screenHeightPx() - floatTopInsetPx() - floatBottomInsetPx()).toFloat().coerceAtLeast(1f)


    /**
     * **上边界**：把手顶到系统状态栏下边缘就不能再上。
     *（弹窗 y 从屏幕顶部算，所以这里是状态栏高度，不是 0）
     */
    private fun floatDyMin(): Float = floatTopInsetPx().toFloat()

    /** **下边界**：键盘可以移出可视区，最多到「只剩顶部把手」（把手下边缘贴住底部系统栏上沿）。 */
    private fun floatDyMax(): Float =
        ((screenHeightPx() - floatBottomInsetPx()).toFloat()
            - handleVisibleHeight() - actionRowVisibleHeight())
            .coerceAtLeast(floatDyMin())

    private fun screenWidthPx(): Int = resources.displayMetrics.widthPixels

    /** 把当前坐标夹回允许范围。 */
    private fun clampFloatPos() {
        floatDx = floatDx.coerceIn(0f, maxFloatDx())
        floatDy = floatDy.coerceIn(floatDyMin(), floatDyMax())
    }

    /**
     * 悬浮时的**等比缩放**（位置交给窗口本身，这里不做平移）。
     *
     * 锚点取左上角：缩放后的可见键盘就落在窗口左上角那一块
     * （窗口 = 卡片自然尺寸，所以可见区域 = 窗口宽高 × floatScale）。
     */
    private fun applyFloatTransform() {
        for (v in floatParts) {
            v.pivotX = 0f
            v.pivotY = 0f
            v.scaleX = floatScale()
            v.scaleY = floatScale()
            // 不做任何平移：窗口坐标就是键盘位置，窗口与键盘永远严格重合
            v.translationX = 0f
            v.translationY = 0f
        }
        root?.invalidate()
    }



    /**
     * 按「开关 + 是否横屏」决定要不要悬浮。
     *
     * 竖屏 / 关闭时把缩放与平移全部复原、把手隐藏 —— 对原有键盘行为零影响。
     */
    private fun applyGameFloat() {
        val c = root ?: return
        val landscape =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val want = prefs.gameKeyboard && landscape
        // 每次弹出都从「空闲」开始：万一下拉过程中途被打断，余量一定要收回
        draggingFloat = false
        applyKeyboardSize(want)
        // 容器始终留出底部导航条高度的空白：悬浮时键盘在独立弹窗里，
        // 输入法窗口就只剩这条透明薄条（与微信输入法的形态一致），窗口不会被判成 0 高度
        c.setPadding(0, 0, 0, navBarInsetPx)
        if (want) enterFloatPopup() else exitFloatPopup()
        applyFloatWindowFrame(want)
        // 把手 / 按钮栏都铺满整宽（与键盘区域同宽），不留左右空隙
        for (v in listOfNotNull(dragHandle, actionRow)) {
            val lp = v.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.leftMargin != 0 || lp.rightMargin != 0) {
                lp.leftMargin = 0
                lp.rightMargin = 0
                v.layoutParams = lp
            }
        }
        applyFloatBackgrounds(want && dragHandle != null)
        if (want == gameFloatActive && dragHandle?.visibility ==
            (if (want) android.view.View.VISIBLE else android.view.View.GONE)
        ) {
            if (want) {
                // 尺寸可能变了（横竖屏切换），按新尺寸重新摆一次
                applyFloatWidth(true)
                if (floatPopup == null) enterFloatPopup()
                placeFloatFromPrefs()
            }
            return
        }
        gameFloatActive = want
        dragHandle?.visibility = if (want) android.view.View.VISIBLE else android.view.View.GONE
        // 悬浮时把卡片宽度限制成竖屏键盘的宽度（不缩放，所以不会变形）
        applyFloatWidth(want)
        // 底色：悬浮时「容器全透明 + 卡片有圆角底色」（这样卡片外面能看见后面的应用）；
        // 竖屏时恢复成原来的样子（容器铺键盘底色、卡片透明）
        applyFloatBackgrounds(want)
        if (!want) {
            floatUserMoved = false
            floatFrameRetry = 0
            // 竖屏 / 关闭悬浮：缩放与平移全部还原（键盘和以前一模一样）
            applyKeyboardSize(false)
        } else {
            // 默认位置：水平居中（之后就随用户左右拖动）
            applyFloatWidth(true)
            // 本次弹出一律按「记住的位置」来（用户这次拖过之前都用记录值）
            floatUserMoved = false
            c.post { placeFloatFromPrefs() }
            applyFloatTransform()
        }
        c.requestLayout()
        c.invalidate()
        AppLog.i(
            AppLog.Category.UI,
            if (want) "游戏键盘：横屏悬浮已开启（卡片宽 ${portraitKeyboardWidth()}px，等比 $gameFloatScale，可任意拖动）" else "游戏键盘：已退出悬浮",
        )
        // 临时几何日志（定位尺寸/位置问题用，确认无误后会删）——延迟到布局跑完再打
        c.postDelayed({
            val dm = resources.displayMetrics
            AppLog.i(
                AppLog.Category.UI,
                "悬浮几何：root=${c.width}x${c.height} 屏=${dm.widthPixels}x${dm.heightPixels} " +
                    "卡片=${kbBox?.width}x${kbBox?.height} 缩放=${floatScale()}（悬浮基准 $gameFloatScale × 大小 ${keyboardSizeScale()}）" +
                    "窗口=${floatWinWidth()}x${floatWinHeight()} 可见=${floatVisWidth()}x${floatVisHeight()} " +
                    "位置=${floatDx.toInt()},${floatDy.toInt()} " +
                    "上界=${floatDyMin().toInt()} 下界=${floatDyMax().toInt()} " +
                    "把手=${dragHandle?.width}(${dragHandle?.visibility}) 行=${actionRow?.width} 键盘=${keyboardView?.width}",
            )
        }, 600)
    }

    /**
     * 悬浮相关底色：
     *
     * · 悬浮时：根容器**全透明**（否则会有一整层底色挡住后面的应用），卡片自己带圆角底色 + 细描边，
     *   看上去就是主流输入法那种「一张浮在应用上面的小键盘」；
     * · 非悬浮（竖屏 / 关闭）：根容器铺键盘底色、卡片透明 —— 和以前完全一致。
     */
    private fun applyFloatBackgrounds(floating: Boolean) {
        val c = root ?: return
        val box = kbBox ?: return
        if (!floating) {
            c.setBackgroundColor(if (keyboardBgColor != 0) keyboardBgColor else android.graphics.Color.TRANSPARENT)
            box.background = null
            box.clipToOutline = false
            return
        }
        c.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val palette = KeyboardTheme.palette(this)
        box.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (keyboardBgColor != 0) keyboardBgColor else palette.keyboardBg)
            cornerRadius = dp(gameCardCornerDp).toFloat()
            // 不加描边：卡片四周（含按钮栏左右、键盘顶部那条边）会露出一条细线
        }
        // 让卡片里的内容（含顶部把手那条）跟着圆角裁切，不然方角会顶出圆角外
        box.clipToOutline = true
    }

    /**
     * 悬浮时：不让应用被键盘挤压，并且**只在键盘实际覆盖的矩形内**接收触摸，
     * 键盘以外的透明区域全部透传给下面的应用（玩游戏时这样才能点得到游戏）。
     */
    /** 底部系统栏（导航条）高度，非悬浮时给容器留白用。 */
    private var navBarInsetPx = 0

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!gameFloatActive) return
        val vw = floatVisWidth()
        val vh = floatVisHeight()
        if (vw <= 0 || vh <= 0) return
        // ★ 核心：声明「本窗口没有遮挡任何内容」——这是主流输入法悬浮键盘的做法。
        //
        // 系统侧 DisplayPolicy.getImeSourceFrameProvider() 会给后面的窗口算「键盘避让矩形」，
        // 算法是：避让矩形 = 输入法窗口矩形 **再按这里上报的值从顶部内缩**。
        // 所以：
        //   · 报「窗口高度」→ 内缩掉整个窗口 → 避让矩形为空 → 任何应用窗口都不会被顶/压扁 ✓
        //   · 报 0          → 一点不内缩 → 整个窗口都算「盖住」→ 后面的窗口被挤 ✗（之前就是这么错的）
        // AOSP 自己在抽离/全屏模式里也是这么写的：
        //   "we just say the window isn't covering any content so we don't impact whatever is behind."
        val winH = (window?.window?.decorView?.height ?: 0).takeIf { it > 0 } ?: vh
        outInsets.contentTopInsets = winH
        outInsets.visibleTopInsets = winH
        // 触摸只在键盘可见的那一块生效；窗口里空出来的部分透传给下面的应用
        // 键盘已经搬到独立弹窗里了：输入法窗口本身**不接收任何触摸**，
        // 这样它后面/下面的应用（游戏、其它悬浮窗）完全不受影响
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
    }

    /**
     * 框架每次「配置窗口」都会把输入法窗口设回 `MATCH_PARENT × (WRAP_CONTENT|MATCH_PARENT)`，
     * 所以悬浮键盘的尺寸必须在这里设置，否则会被框架覆盖回去（时好时坏的隐藏原因）。
     */
    override fun onConfigureWindow(
        win: android.view.Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        if (gameFloatActive) {
            // 微信式：输入法窗口保持最标准的「整屏宽 + 贴底 + 透明」形态，
            // 键盘本体在独立弹窗里 —— 系统/厂商看到的就是一块正常贴底的输入法
            win.setGravity(Gravity.BOTTOM)
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
    }

    override fun onWindowHidden() {
        // 键盘收起：独立弹窗必须一起收掉，否则会在屏幕上留下一个飘着的键盘
        exitFloatPopup()
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 深色 / 浅色模式切换也会走到这里：先把主题（含悬浮卡片底色、把手颜色）重套一遍
        applyTheme()
        applyLayout()
        // 横竖屏切换后视图会重新布局，延后一帧再套（否则拿到的尺寸还是旧的）
        root?.post { applyGameFloat() }
    }

    override fun onCreateInputView(): View {
        // 输入法窗口自身不要底色（悬浮时窗口外的区域必须能看见后面的应用）
        runCatching {
            window?.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
            )
        }

        // 重新创建输入视图：上一轮的独立弹窗作废（避免留下孤儿窗口）
        floatPopup?.let { old -> runCatching { popupWindowManager()?.removeViewImmediate(old) } }
        floatPopup = null

        val palette = KeyboardTheme.palette(this)

        val container = EggRootLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 整个键盘的左上 / 右上圆角（可在「主题外观」里调节，像 iPhone 键盘那样）
            applyKeyboardTopCorners(this, palette.keyboardBg)
            // 点击火花特效的开关：只在彩蛋开启时才有（关闭时等于不生效）
            effectEnabled = { eggRunning }
            // 记下来：彩蛋期间点击产生的彩色小球火花，画在这个容器最上层
            eggRoot = this
        }

        // 悬浮键盘的「卡片」容器：把手 + 按钮栏那一行 + 面板 + 键盘 都在这里面，
        // 悬浮时给它圆角底色（一张卡片），竖屏时完全透明（外观和以前一模一样）。
        kbBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        // 布局尺寸变化（首次布局 / 横竖屏切换 / 面板张开）后重新摆一次位置。
        //
        // ★ 拖动中绝不能重摆：placeFloatFromPrefs() 会把「窗口内平移量」清零，
        //   卡片会瞬间弹回窗口左上角 —— 表现就是「一碰把手键盘弹一下」。
        kbBox?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            naturalHWW = -1 // 卡片尺寸真的变了（面板张开等）→ 自然高度缓存作废
            if (gameFloatActive && !draggingFloat) placeFloatFromPrefs()
        }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 右侧不留 padding：让最右边那个按钮的右边缘对齐键盘字母 P 的右边缘
            // （按钮自带 6dp 的 end 间距，正好等于键盘按键的间隔留白）
            setPadding(dp(6), 0, 0, 0)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        buildToolbar(toolbar!!)

        candidateBar = CandidateBarView(this).apply {
            style = KeyboardTheme.candidateStyle(this@IceInputMethodService)
            onCandidate = { index -> onCandidatePicked(index) }
            onPage = { next -> handlePage(next) }
            // 联想态右侧的 ✕：结束联想并恢复工具栏
            onDismiss = {
                playToolbarSound()
                predictionWords = emptyList()
                AppLog.i(AppLog.Category.UI, "用户关闭联想")
                updateCandidates()
            }
            // 候选态右侧的 ✕：清空当前正在输入的拼音 / 关闭邮箱候选 / 关闭英文候选
            onClear = {
                playToolbarSound()
                val hadEmail = emailSuggestions.isNotEmpty()
                // 邮箱候选：关闭就是**退出邮箱模式**，并且这个 @ 不再自动重开
                // （不再只是把 UI 藏起来，下一帧也不会又被算出来）
                if (emailMode || hadEmail) exitEmailMode(block = true)
                emailSuggestions = emptyList()
                englishSuggestions = emptyList()
                // 两种情况都要"关掉就不再自动冒出来"：
                // ① @ 触发的邮箱候选（下一帧又会按输入框里的 "@xxx" 重新算出来）
                // ② 英文（非拼音）模式的前缀补全
                if (hadEmail || layoutType != LayoutType.CHINESE_PINYIN) {
                    suggestionsDismissed = true
                    lastPreedit = ""
                    lastCandidates = emptyList()
                }
                session.takeIf { it != 0L }?.let { RimeEngine.clearComposition(it) }
                AppLog.i(AppLog.Category.UI, "用户清空当前拼音/关闭候选")
                updateCandidates()
            }
            // 长按候选 → 冷词菜单（降频 / 隐藏 / 取消）
            onLongPressCandidate = { index, centerX -> showColdWordMenu(index, centerX) }
            // 滑动诊断：确认横向滑动是否真的进来了、可滚动上限多大
            onSwipe = { dx, maxScroll ->
                AppLog.i(
                    AppLog.Category.UI,
                    "候选栏滑动：位移 ${dx.toInt()}px，可滚动上限 ${maxScroll.toInt()}px",
                )
            }
            // 剪贴板候选：点文字上屏，点 ✕ 关闭
            onClipboardCommit = { commitClipboardSuggestion() }
            onClipboardDismiss = { dismissClipboardSuggestion() }
        }

        // 工具栏与候选栏共用同一行：空闲时显示工具栏按钮，输入时整行变为候选栏。
        // 工具栏不使用整体圆角容器——每个按钮各自有圆形涟漪作为点击态（M3 IconButton）。
        actionRow = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(
                toolbar,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                candidateBar,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        kbBox?.addView(
            actionRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP)),
        )

        keyboardView = KeyboardView(this).apply {
            style = KeyboardTheme.keyboardStyle(this@IceInputMethodService)
            onKey = { key -> handleKey(key) }
            onKeyFeedback = { key -> playFeedback(key) }
            // 滑动误触：把刚才那一下输入撤掉（等价于按一次退格）
            onSwipeCancel = {
                AppLog.i(AppLog.Category.UI, "滑动手势：已撤销这次误触输入的字符")
                handleBackspace()
            }
        }
        kbBox?.addView(
            keyboardView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        panelView = PanelView(this).apply {
            visibility = View.GONE
            onInsertText = { text -> insertPlainText(text) }
            // 选词回滑：删掉刚输入的字
            onDeleteText = { handleBackspace() }
            onClose = { hidePanel() }
        }
        kbBox?.addView(
            panelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // 面板**放在键盘上面**：
        // 平时面板打开时键盘是隐藏的（位置看起来一样），而面板内编辑时键盘会一起显示，
        // 这样键盘就在原位（下面）、输入框在键盘上方，符合直觉。
        keyboardView?.let { kv ->
            val box = kbBox
            if (box != null) {
                box.removeView(panelView)
                box.addView(panelView, box.indexOfChild(kv))
            }
        }

        // 手势导航栏区域：给键盘底部留出系统栏高度，避免按键被系统条覆盖。
        // 注意：WindowInsets 是异步投递的，如果只依赖回调，**首次弹出**时 padding 还是 0，
        // 键盘会被系统导航条盖住一截（表现为「第一次没完全弹出，第二次才正常」）。
        // 因此这里先同步按系统资源给出一个初始值，回调再把真实值取「较大者」修正。
        val initialBottom = systemNavigationBarHeight()
        navBarInsetPx = initialBottom
        container.setPadding(0, 0, 0, initialBottom)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bottom = maxOf(
                systemNavigationBarHeight(),
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
            )
            navBarInsetPx = bottom
            // 悬浮时容器不要底部留白（键盘自己摆位置，留白会变成一块空白）
            if (!gameFloatActive && v.paddingBottom != bottom) v.setPadding(0, 0, 0, bottom)
            insets
        }

        // —— 游戏键盘：顶部把手 + 需要一起等比缩放的视图（竖屏时把手隐藏、变换归零）——
        dragHandle = buildDragHandle().also { handle ->
            kbBox?.addView(
                handle,
                0,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(gameHandleDp)),
            )
            handle.visibility = android.view.View.GONE
        }
        // 整个卡片一起等比缩放 / 平移（悬浮时）
        kbBox?.let {
            container.addView(
                it,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        floatParts.clear()
        kbBox?.let { floatParts.add(it) }
        keyboardBgColor = palette.keyboardBg

        root = container
        // 布局完成后再判定要不要悬浮（此刻才拿得到真实尺寸）
        container.post { applyGameFloat() }
        applyLayout()
        AppLog.i(AppLog.Category.UI, "键盘视图已创建，布局=$layoutType")
        // 输入视图可能因为横竖屏切换而重建：彩蛋开着的话，立刻把旋转挂到**这一套新视图**上
        if (eggRunning) {
            keyboardView?.startEgg()
            panelView?.startEgg()
        }
        popSlideInY = 0f
        return container
    }

    // —— 工具栏（顺序严格按文档 5.1：设置 → 表情 → 中英切换 → 剪贴板 → 收起） ——

    private fun buildToolbar(bar: LinearLayout) {
        // 重建按钮：清掉旧引用与动画状态，重新按当前面板状态刷新
        panelButtons.clear()
        panelButtonActive.clear()
        // 按钮是新建的（图标都是原图标的初始形态），清掉旧的图标引用
        panelButtonViews.clear()
        // 左侧：品牌按钮。
        // **双击** → 右边推出一颗同尺寸的圆（深色/浅色切换），同时自己旋转着变成叉；
        // **单击** → 进入设置；已展开时点那个叉 → 收起切换圆，并且逆时针转回品牌图标。
        // 长按触发彩蛋的逻辑一点没变。
        val brand = iconSwitchButton(
            R.drawable.ic_brand,
            R.drawable.ic_close,
            PANEL_CROSS_COLOR,
            R.string.kbd_settings,
        ) { onBrandClick() }
        brandIconNormal = brand.getChildAt(0) as? android.widget.ImageView
        brandIconAlt = brand.getChildAt(1) as? android.widget.ImageView
        bar.addView(brand)
        attachEggTrigger(brand)
        toolbarBrand = brand

        // 深浅切换圆：默认宽度 0（完全收起），双击品牌按钮时用宽度动画「推」出来。
        // 里面是两副图标（月亮 / 太阳），点它时用同一套逆时针旋转 + 渐变动效互换。
        val mode = iconSwitchButton(
            R.drawable.ic_mode_dark,
            R.drawable.ic_mode_light,
            KeyboardTheme.palette(this).onSurfaceVariant,
            R.string.kbd_toggle_theme,
        ) { toggleDarkLight() }
        modeIconMoon = mode.getChildAt(0) as? android.widget.ImageView
        modeIconSun = mode.getChildAt(1) as? android.widget.ImageView
        val modeLp = mode.layoutParams as LinearLayout.LayoutParams
        modeLp.width = if (brandExpanded) dp(36) else 0
        modeLp.marginEnd = if (brandExpanded) dp(6) else 0
        mode.layoutParams = modeLp
        bar.addView(mode)
        toolbarModeToggle = mode
        updateModeIcon()
        if (brandExpanded) {
            brandIconNormal?.visibility = View.GONE
            brandIconAlt?.visibility = View.VISIBLE
        }
        // 中间弹性空白，把其余按钮全部推到右侧
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // 右侧依次：中英切换(zh/en) → 表情 → 剪贴板 → 常用语 → 收起键盘，间距一致
        langToolButton {
            toggleChineseEnglish()
        }.also { bar.addView(it) }
        // 「服务号」图标按钮：字母页显示服务号图标（点它进符号键盘），
        // 进符号页后变成 ✕（点它回 26 字母键盘），位置在中英切换与表情之间。
        bar.addView(symbolToolButton())
        bar.addView(panelToolButton(PanelType.EMOJI, R.drawable.ic_emoji, R.string.kbd_emoji))
        bar.addView(panelToolButton(PanelType.CLIPBOARD, R.drawable.ic_clipboard, R.string.kbd_clipboard))
        bar.addView(panelToolButton(PanelType.PHRASE, R.drawable.ic_phrase, R.string.kbd_phrase))
        bar.addView(toolButton(R.drawable.ic_chevron_down, R.string.kbd_hide) {
            requestHideSelf(0)
            AppLog.i(AppLog.Category.UI, "用户收起键盘")
        })
        // 工具栏重建后，按当前状态恢复按钮图标（若面板正展开则显示 ✕）
        updatePanelButtonStates()
        updateSymbolButtonState()
        // 工具栏重建会换掉所有按钮（旧按钮上的旋转动画随之消失）——
        // 彩蛋开着的时候必须把旋转重新挂上，否则"看过别的页面后按钮栏就不转了"。
        if (eggRunning) startToolbarEgg()
    }

    /** 面板按钮：点击展开/收起，展开时按钮旋转 135° 并变成 ✕。 */
    private fun panelToolButton(type: PanelType, iconRes: Int, descRes: Int): View {
        val palette = KeyboardTheme.palette(this)
        // 结构与中英切换按钮完全一致：
        // 外框（实色底 + 涟漪）**固定不动**，里面放两副图标（原图标 / ✕），
        // 切换时只让里面的 SVG 逆时针旋转进出，外框实色底绝不跟着转。
        val density = resources.displayMetrics.density
        val half = 18f * density
        val radius = (prefs.barCornerDp * density).coerceIn(0f, half)
        val shape = android.graphics.drawable.GradientDrawable().apply {

            setColor(palette.funcKeyBg)

            cornerRadius = radius

        }
        val wrapper = android.widget.FrameLayout(this).apply {
            // 去掉安卓默认的点击涟漪（"按一下泛一下"的那种系统效果）：
            // 这里只用实色圆角底，按下时由旋转/Q 弹动效给出反馈。
            // 系统触摸提示音也关掉：只播我们自己的按键音，不然一声变两声。
            isSoundEffectsEnabled = false
            background = shape
            setOnClickListener {
                playToolbarSound()
                togglePanel(type)
            }
            contentDescription = getString(descRes)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) }
        }
        val iconNormal = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(palette.onSurfaceVariant)
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val iconClose = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(PANEL_CROSS_COLOR)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            visibility = View.GONE
        }
        wrapper.addView(
            iconNormal,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        wrapper.addView(
            iconClose,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        panelButtons[type] = wrapper
        panelButtonIcons[type] = iconRes
        panelButtonViews[type] = iconNormal to iconClose
        return wrapper
    }

    /**
     * 同步三个面板按钮的形态：
     * 面板展开时按钮旋转 135° 并换成 ✕（旋转带 Q 弹回弹、颜色用浅红）；
     * 收起时转回原角度与原图标。
     */
    /**
     * 共用的「逆时针旋转切换」动效（中英切换按钮与三个面板按钮同一套）：
     * 旧内容逆时针转 90° 并淡出；新内容从另一侧逆时针转回 0° 并淡入。
     * 只转传进来的这两个 View —— 外框（实色底）保持不动。
     */
    /**
     * 符号入口按钮（中英切换与表情之间）。
     * 结构与面板按钮 / 中英切换按钮完全一致：外框（实色圆角底）固定不动，
     * 只在两副图标（服务号图标 / ✕）之间做「逆时针旋转 + Q 弹」切换，
     * 手感与旁边按钮一模一样。图标比其它按钮的 24dp 略小一号（18dp）。
     */
    private fun symbolToolButton(): View {
        val palette = KeyboardTheme.palette(this)
        val density = resources.displayMetrics.density
        val half = 18f * density
        val radius = (prefs.barCornerDp * density).coerceIn(0f, half)
        val shape = android.graphics.drawable.GradientDrawable().apply {

            setColor(palette.funcKeyBg)

            cornerRadius = radius

        }
        val wrapper = android.widget.FrameLayout(this).apply {
            // 与旁边按钮一致：无系统涟漪、无系统触摸提示音（只播自带的按钮栏音效）
            isSoundEffectsEnabled = false
            background = shape
            contentDescription = getString(R.string.kbd_symbols_entry)
            setOnClickListener {
                playToolbarSound()
                // 面板（剪贴板 / 常用语 / 表情）开着的时候点它，先把面板关掉，
                // 否则面板会把键盘盖住，旁边的面板按钮也一直挂着 ✕。
                if (panelVisible) hidePanel()
                if (layoutType == LayoutType.SYMBOLS) {
                    // 叉：回到 26 个字母键盘（回到进符号页之前的那套中/英键盘）
                    exitEmailMode(block = true)
                    layoutType = lettersBeforeSymbols
                    shiftActive = false
                    applyLayout()
                    AppLog.i(AppLog.Category.UI, "按钮栏退出符号键盘")
                } else {
                    lettersBeforeSymbols = layoutType
                    exitEmailMode(block = true)
                    layoutType = LayoutType.SYMBOLS
                    shiftActive = false
                    applyLayout()
                    AppLog.i(AppLog.Category.UI, "按钮栏进入符号键盘")
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) }
        }
        // 图标 18dp（其它按钮是 24dp / 内边距 8dp），比自己上一版再小一圈
        val side = dp(18)
        val iconParams = android.widget.FrameLayout.LayoutParams(side, side).apply {
            gravity = Gravity.CENTER
        }
        val iconNormal = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_service_account)
            setColorFilter(palette.onSurfaceVariant)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val iconClose = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(PANEL_CROSS_COLOR)
            // 与面板按钮的 ✕ 一模一样：CENTER 保持 24dp 原尺寸，
            // 不会被 FIT_CENTER 撑满 36dp 按钮（那才会显得大）
            scaleType = android.widget.ImageView.ScaleType.CENTER
            visibility = View.GONE
        }
        wrapper.addView(iconNormal, iconParams)
        // ✕ 与其它按钮栏按钮的 ✕ 一样大（铺满 36dp 按钮，图标本身 24dp 居中），
        // 只有「服务号」图标是缩小版
        wrapper.addView(
            iconClose,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        symbolBtnWrapper = wrapper
        symbolBtnIconNormal = iconNormal
        symbolBtnIconClose = iconClose
        symbolBtnActive = null
        return wrapper
    }

    /** 同步符号入口按钮形态：符号页显示 ✕，其余页显示服务号图标（首次不动画）。 */
    private fun updateSymbolButtonState() {
        val normal = symbolBtnIconNormal ?: return
        val close = symbolBtnIconClose ?: return
        val active = layoutType == LayoutType.SYMBOLS
        val previous = symbolBtnActive
        if (previous == null || previous == active) {
            normal.visibility = if (active) View.GONE else View.VISIBLE
            close.visibility = if (active) View.VISIBLE else View.GONE
            listOf(normal, close).forEach {
                it.rotation = 0f
                it.alpha = 1f
            }
        } else if (active) {
            switchIconViews(normal, close, bouncy = true)
        } else {
            switchIconViews(close, normal, bouncy = true)
        }
        symbolBtnActive = active
    }

    private fun switchIconViews(outgoing: View, incoming: View, bouncy: Boolean = false) {
        val duration = 170L
        outgoing.animate()
            .rotation(-90f).alpha(0f)
            .setDuration(if (bouncy) 150L else duration)
            .setInterpolator(
                if (bouncy) {
                    android.view.animation.AccelerateInterpolator()
                } else {
                    android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
                },
            )
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.rotation = 0f
                outgoing.alpha = 1f
            }
            .start()
        incoming.visibility = View.VISIBLE
        incoming.rotation = 90f
        incoming.alpha = 0f
        incoming.animate()
            .rotation(0f).alpha(1f)
            .setDuration(if (bouncy) 240L else duration)
            .setInterpolator(
                if (bouncy) {
                    // Q 弹：转到位再回过一点头（面板按钮用的就是这个手感）
                    android.view.animation.OvershootInterpolator(2.2f)
                } else {
                    android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)
                },
            )
            .start()
    }

    /**
     * 同步三个面板按钮的形态：面板展开时显示 ✕，收起时显示原图标。
     * 与中英切换按钮**完全相同的逻辑与动画**：只让里面的 SVG 逆时针旋转，
     * 外框的实色底永远不转（所以不会出现"整块底色跟着转"）。
     */
    private fun updatePanelButtonStates() {
        panelButtons.forEach { (type, _) ->
            val views = panelButtonViews[type] ?: return@forEach
            val (iconNormal, iconClose) = views
            val active = panelVisible && panelType == type
            val previous = panelButtonActive[type]
            if (previous == null || previous == active) {
                // 首次 / 状态没变：直接摆好，不播动画
                iconNormal.visibility = if (active) View.GONE else View.VISIBLE
                iconClose.visibility = if (active) View.VISIBLE else View.GONE
                listOf(iconNormal, iconClose).forEach {
                    it.rotation = 0f
                    it.alpha = 1f
                }
            } else if (active) {
                switchIconViews(iconNormal, iconClose, bouncy = true)
            } else {
                switchIconViews(iconClose, iconNormal, bouncy = true)
            }
            panelButtonActive[type] = active
        }
    }

    // ————————————————————— 彩蛋 —————————————————————
    // 长按按钮栏最左侧的「设置」图标 2 秒触发：按钮栏全部按钮 + 键盘上的
    // 26 个字母 / 大小写 / 删除 / 回车 / 空格 一起疯狂旋转，并伴随随机大小的震动，
    // 持续 6 秒后自动完全恢复。整个效果是纯展示，不改变任何状态、不影响任何功能
    // （只借用按键的绘制角度与按钮的 rotation 属性，结束时全部归零）。

    private var eggRunning = false
    private val eggHandler = Handler(Looper.getMainLooper())
    private var eggVibration: Runnable? = null
    private val eggRandom = java.util.Random()

    /** 按钮栏按钮的持续旋转动画（关闭彩蛋时要全部取消）。 */
    private val toolbarEggAnims = ArrayList<android.animation.ObjectAnimator>()
    /** 按钮栏最左侧那个按钮（彩蛋期间换成彩蛋图标、只逆时针匀速转）。 */
    private var toolbarBrand: View? = null

    /** 品牌按钮内部的两副图标（品牌 / 叉），切换时用面板那套「逆时针旋转 + 渐变」。 */
    private var brandIconNormal: android.widget.ImageView? = null
    private var brandIconAlt: android.widget.ImageView? = null

    /** 深浅切换按钮内部的两副图标（月亮 / 太阳）。 */
    private var modeIconMoon: android.widget.ImageView? = null
    private var modeIconSun: android.widget.ImageView? = null

    /** 品牌按钮右边那个「深色 / 浅色切换」圆（默认收成 0 宽）。 */
    private var toolbarModeToggle: View? = null

    /** 品牌按钮是否处于「已展开」状态（展开时品牌按钮变成叉）。 */
    private var brandExpanded = false

    /** 双击判定：上一次点品牌按钮的时刻（节奏参考大小写锁定那种双击）。 */
    private var brandLastTapAt = 0L

    /** 双击间隔上限（毫秒）。 */
    private val brandDoubleTapMs = 300L

    /** 单击品牌按钮（进设置）的延时任务：等一小会儿确认不是双击再执行。 */
    private val brandSingleTap = Runnable { if (!brandExpanded) openApp("home") }
    private var brandEggAnim: android.animation.ObjectAnimator? = null
    /** 键盘根容器（用来在最上层画点击火花）。 */
    private var eggRoot: EggRootLayout? = null

    /** 给某个按钮挂上「长按 2 秒触发彩蛋」的手势（普通点击行为完全不变）。 */
    private fun attachEggTrigger(view: View) {
        var fired = false
        var hold: Runnable? = null
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    v.isPressed = true
                    val r = Runnable {
                        fired = true
                        v.isPressed = false
                        startEasterEgg()
                    }
                    hold = r
                    eggHandler.postDelayed(r, EGG_HOLD_MS)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    hold?.let { eggHandler.removeCallbacks(it) }
                    // 没到 2 秒就是普通点击 → 照常执行原来的动作（打开设置）
                    if (!fired) v.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    hold?.let { eggHandler.removeCallbacks(it) }
                    true
                }

                else -> false
            }
        }
    }

    private fun startEasterEgg() {
        // 再长按 2 秒就是**关闭**（开关式）
        if (eggRunning) {
            stopEasterEgg()
            return
        }
        eggRunning = true
        AppLog.i(AppLog.Category.UI, "彩蛋触发：所有按键 / 表情 / SVG 持续快速旋转 + 点击火花特效（再长按 2 秒关闭）")
        startToolbarEgg()

        // ② 键盘上**所有**按键一起旋转（字母、数字、逗号等符号、功能键都转）
        keyboardView?.startEgg()

        // ③ 面板：表情全部跟着转；剪贴板 / 常用语的卡片本身不转，但里面的 SVG 都转
        panelView?.startEgg()

        // ④ 没有震动（按用户要求取消随机大/小震动），也没有自动结束时间：
        //    一直转到用户再长按 2 秒关闭为止。
    }

    /**
     * 品牌按钮的点击逻辑：
     *
     *  · **单击** → 进入设置（等一小会儿确认不是双击，再执行）；
     *  · **双击**（间隔 ≤ 300ms，节奏和大小写锁定那种双击一致）→ 弹出深浅切换圆，
     *    同时自己变成**叉**；
     *  · 已展开时点它（就是那个叉）→ 把深浅切换圆收起来。
     *
     * 长按触发彩蛋的逻辑完全没动。
     */
    private fun onBrandClick() {
        if (brandExpanded) {
            // 此时按钮是「叉」：单击就关掉切换圆
            eggHandler.removeCallbacks(brandSingleTap)
            setBrandExpanded(false)
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - brandLastTapAt <= brandDoubleTapMs) {
            // 双击 → 弹出切换圆
            brandLastTapAt = 0L
            eggHandler.removeCallbacks(brandSingleTap)
            setBrandExpanded(true)
            return
        }
        brandLastTapAt = now
        eggHandler.removeCallbacks(brandSingleTap)
        eggHandler.postDelayed(brandSingleTap, brandDoubleTapMs + 20L)
    }

    /**
     * 展开 / 收起品牌按钮右边的深色浅色切换圆。
     *
     * 展开时宽度 0 → 36dp（带动画），看起来就是「从品牌按钮右边推出来一个同样大小的圆」。
     */
    private fun setBrandExpanded(on: Boolean) {
        brandExpanded = on
        val brand = toolbarBrand
        val mode = toolbarModeToggle
        if (!eggRunning) {
            // 彩蛋期间图标归彩蛋管，别去抢
            val normal = brandIconNormal
            val alt = brandIconAlt
            if (normal != null && alt != null) {
                if (on && normal.visibility == View.VISIBLE) {
                    // 品牌 → 叉：逆时针旋转 + 渐变
                    switchIconViews(normal, alt, bouncy = true)
                } else if (!on && alt.visibility == View.VISIBLE) {
                    // 叉 → 品牌：同样逆时针旋转 + 渐变
                    switchIconViews(alt, normal, bouncy = true)
                }
            }
        }
        if (mode == null) return
        val lp = mode.layoutParams as? LinearLayout.LayoutParams ?: return
        val targetW = if (on) dp(36) else 0
        val targetMargin = if (on) dp(6) else 0
        val anim = android.animation.ValueAnimator.ofFloat(lp.width.toFloat(), targetW.toFloat())
        anim.duration = 220L
        anim.interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        anim.addUpdateListener { va ->
            val v = (va.animatedValue as Float).toInt()
            val p = if (targetW > 0) v.toFloat() / targetW else 0f
            val lp2 = mode.layoutParams as? LinearLayout.LayoutParams ?: return@addUpdateListener
            lp2.width = v
            lp2.marginEnd = (targetMargin * p).toInt()
            mode.layoutParams = lp2
        }
        anim.start()
        // 彩蛋期间把切换圆推出来：立刻让它一起转（否则要等下一次心跳，看起来「不跟着转」）
        if (eggRunning && on) spinToolbarChild(mode)
    }

    /** 当前是不是深色（跟随系统时看系统）。 */
    private fun isDarkNow(): Boolean {
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (prefs.themeMode) {
            IcePrefs.THEME_DARK -> true
            IcePrefs.THEME_LIGHT -> false
            else -> night
        }
    }

    /**
     * 刷新切换圆里的图标：显示的是**将要切到**的模式 ——
     * 当前深色 → 显示浅色（太阳）；当前浅色 → 显示深色（月亮）。
     */
    private fun updateModeIcon() {
        val moon = modeIconMoon ?: return
        val sun = modeIconSun ?: return
        // 显示的是「将要切到」的模式：当前深色 → 太阳；当前浅色 → 月亮
        val dark = isDarkNow()
        sun.visibility = if (dark) View.VISIBLE else View.GONE
        moon.visibility = if (dark) View.GONE else View.VISIBLE
        sun.rotation = 0f
        moon.rotation = 0f
        sun.alpha = 1f
        moon.alpha = 1f
        toolbarModeToggle?.contentDescription = if (dark) "切换到浅色" else "切换到深色"
    }

    /**
     * 点切换圆：深 ↔ 浅。
     *
     * 先像按钮栏其它按钮那样**逆时针转半圈**，转完再真正切主题、并把图标换成另一个
     * （看起来就是「转着变成另一个图标」）。转完 `applyTheme()` 会重建工具栏，
     * 新按钮的角度自然是 0、图标也已是最新的。
     */
    private fun toggleDarkLight() {
        val moon = modeIconMoon ?: return
        val sun = modeIconSun ?: return
        val dark = isDarkNow()
        val outgoing = if (dark) sun else moon
        val incoming = if (dark) moon else sun
        // ① 图标：和面板按钮同一套「逆时针旋转 + 渐变」（带 Q 弹回弹，240ms 才走完）
        switchIconViews(outgoing, incoming, bouncy = true)
        // ② 配色：**等回弹走完**再换主题 —— 换主题会重建工具栏，提早换会把回弹掐掉
        //    （之前就是没吃到回弹）。换主题时整块键盘做一次「淡出 → 换色 → 淡入」，
        //    所以颜色是渐变着过去的，不是硬切。
        root?.animate()?.alpha(0.70f)?.setDuration(150L)?.start()
        eggHandler.postDelayed({
            IcePrefs.setThemeMode(if (dark) IcePrefs.THEME_LIGHT else IcePrefs.THEME_DARK)
            AppLog.i(AppLog.Category.UI, if (dark) "切换为浅色主题" else "切换为深色主题")
            applyTheme()
            root?.animate()?.alpha(1f)?.setDuration(220L)?.start()
        }, 380L)
    }

    /**
     * 「外框固定、内部两副图标」的圆角按钮（默认收起：[normalRes] 显示，[altRes] 隐藏）。
     *
     * 外观与 [toolButton] 完全一致（36dp、实色底、圆角跟随设置、涟漪）；
     * 差别只在内部放了两副图标，方便用 [switchIconViews] 做逆时针旋转 + 渐变切换。
     */
    private fun iconSwitchButton(
        normalRes: Int,
        altRes: Int,
        altTint: Int,
        descriptionRes: Int,
        onClick: () -> Unit,
    ): android.widget.FrameLayout {
        val palette = KeyboardTheme.palette(this)
        val density = resources.displayMetrics.density
        val half = 18f * density
        val radius = (prefs.barCornerDp * density).coerceIn(0f, half)
        val shape = android.graphics.drawable.GradientDrawable().apply {

            setColor(palette.funcKeyBg)

            cornerRadius = radius

        }
        val root = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(KeyboardTheme.withAlpha(palette.onSurfaceVariant, 26)),
                shape,
                shape,
            )
            isClickable = true
            isFocusable = true
            // 只用我们自己的按键音：关掉系统触摸提示音，否则点一下会响两声
            isSoundEffectsEnabled = false
            contentDescription = getString(descriptionRes)
            setOnClickListener {
                playToolbarSound()
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) }
        }
        val normal = android.widget.ImageView(this).apply {
            setImageResource(normalRes)
            setColorFilter(palette.onSurfaceVariant)
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val alt = android.widget.ImageView(this).apply {
            setImageResource(altRes)
            setColorFilter(altTint)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            visibility = View.GONE
        }
        val size = dp(22)
        root.addView(normal, android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER
        })
        root.addView(alt, android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER
        })
        return root
    }

    /** 按钮栏每个按钮（含中英切换）持续随机方向/速度旋转。 */
    private fun startToolbarEgg() {
        // 先清掉可能残留的动画引用（重建按钮后旧的动画对象已经失效）
        toolbarEggAnims.clear()
        toolbarEggLastRot.clear()
        toolbar?.let { bar ->
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                if (child.layoutParams?.width == 0) continue // 中间的弹性空白
                // 最左侧那个按钮特殊处理：换成彩蛋图标，只逆时针、固定平缓速度转
                if (child === toolbarBrand) {
                    startBrandEggSpin()
                    continue
                }
                spinToolbarChild(child)
            }
        }
        // 心跳：每隔 0.8 秒看一次，谁停了就重新挂上（重建、被别的动画打断都不会漏）
        eggHandler.removeCallbacks(eggHeartbeat)
        eggHandler.postDelayed(eggHeartbeat, 800L)
        // 候选栏右侧的 ✕ 也是画出来的图标（不是控件），单独让它转起来
        candidateBar?.startEgg()
    }

    /** 给按钮栏的某个按钮挂上"每圈 360°、无限重复"的持续旋转。 */
    private fun spinToolbarChild(child: View) {
        if (child.rotation == 0f) {
            // 新按钮 / 之前停下来的按钮：给一个随机起始角，转起来看不出接缝
            child.rotation = eggRandom.nextInt(360).toFloat()
        }
        val dir = if (eggRandom.nextBoolean()) 1 else -1
        child.animate().cancel()
        val duration = 350L + eggRandom.nextInt(560) // 350~910ms 一圈，速度随机且快
        val anim = android.animation.ObjectAnimator.ofFloat(
            child,
            "rotation",
            child.rotation,
            child.rotation + dir * 360f,
        )
        anim.duration = duration
        anim.interpolator = android.view.animation.LinearInterpolator()
        anim.repeatCount = android.animation.ValueAnimator.INFINITE
        anim.repeatMode = android.animation.ValueAnimator.RESTART
        anim.start()
        toolbarEggAnims.add(anim)
    }

    /**
     * 最左侧按钮的彩蛋动画：**只逆时针**、速度固定平缓。
     *
     *  · 图标换成彩蛋专用 SVG（[R.drawable.ic_egg_bloom]）；
     *  · 只做这一件事，其它按钮的随机速度/方向完全不受影响。
     */
    private fun startBrandEggSpin() {
        val root = toolbarBrand ?: return
        // 图标换成彩蛋专用 SVG：两副图标（品牌 / 叉）都换，当前显示的是哪副就转哪副
        brandIconNormal?.setImageResource(R.drawable.ic_egg_bloom)
        brandIconAlt?.setImageResource(R.drawable.ic_egg_bloom)
        brandEggAnim?.let { runCatching { it.cancel() } }
        // 转的是**整个按钮**（外框 + 实色底 + 里面的图标一起），
        // 以前只转里面那枚 22dp 图标，看着就是「外框和底不跟着转」。
        val anim = android.animation.ObjectAnimator.ofFloat(
            root,
            "rotation",
            root.rotation + 360f,
            root.rotation,
        )
        anim.duration = BRAND_EGG_SPIN_MS
        anim.interpolator = android.view.animation.LinearInterpolator()
        anim.repeatCount = android.animation.ValueAnimator.INFINITE
        anim.repeatMode = android.animation.ValueAnimator.RESTART
        anim.start()
        brandEggAnim = anim
        toolbarEggAnims.add(anim)
    }

    /** 彩蛋心跳：按钮栏有谁不在转就补上（防止被其它动画/重建打断后停住）。 */
    private val eggHeartbeat = object : Runnable {
        override fun run() {
            if (!eggRunning) return
            toolbar?.let { bar ->
                for (i in 0 until bar.childCount) {
                    val child = bar.getChildAt(i)
                    if (child.layoutParams?.width == 0) continue
                    // 最左侧按钮：只认它自己那套「逆时针匀速」，停了就按原速重挂
                    if (child === toolbarBrand) {
                        if (brandEggAnim?.isRunning != true) startBrandEggSpin()
                        toolbarEggLastRot[child] = child.rotation
                        continue
                    }
                    val prev = toolbarEggLastRot[child]
                    val now = child.rotation
                    if (prev == null || kotlin.math.abs(now - prev) < 0.01f) {
                        spinToolbarChild(child)
                    }
                    toolbarEggLastRot[child] = now
                }
            }
            // 候选栏的 ✕ 停了就重新转起来
            candidateBar?.let { if (!it.isEggSpinning()) it.startEgg() }
            // 键盘 / 面板也要自愈：横竖屏切换会重建输入视图，新实例默认不在彩蛋状态里。
            // 以前这里只重挂工具栏 → 于是出现「只有按钮栏在转、键盘不转」。
            keyboardView?.let { if (!it.isEggActive()) it.startEgg() }
            panelView?.let { if (!it.isEggActive()) it.startEgg() }
            eggHandler.postDelayed(this, 800L)
        }
    }

    /** 按钮栏按钮上一跳的角度（判断它是不是停了）。 */
    private val toolbarEggLastRot = HashMap<View, Float>()

    /** 停掉按钮栏按钮的旋转，并平滑停到 360° 的整数倍，最后归零。 */
    private fun stopToolbarEgg() {
        eggHandler.removeCallbacks(eggHeartbeat)
        toolbarEggLastRot.clear()
        toolbarEggAnims.forEach { runCatching { it.cancel() } }
        toolbarEggAnims.clear()
        brandEggAnim = null
        // 左侧按钮恢复原来的图标（彩蛋结束就变回去；两副图标都要还原）
        brandIconNormal?.setImageResource(R.drawable.ic_brand)
        brandIconNormal?.setColorFilter(KeyboardTheme.palette(this).onSurfaceVariant)
        brandIconAlt?.setImageResource(R.drawable.ic_close)
        brandIconAlt?.setColorFilter(PANEL_CROSS_COLOR)
        brandIconNormal?.visibility = if (brandExpanded) View.GONE else View.VISIBLE
        brandIconAlt?.visibility = if (brandExpanded) View.VISIBLE else View.GONE
        candidateBar?.stopEgg()
        toolbar?.let { bar ->
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                val nearest = Math.round(child.rotation / 360f) * 360f
                child.animate().cancel()
                child.animate()
                    .rotation(nearest)
                    .setDuration(240L)
                    .setInterpolator(android.view.animation.LinearInterpolator())
                    .withEndAction { child.rotation = 0f }
                    .start()
            }
        }
    }

    private fun stopEasterEgg() {
        if (!eggRunning) return
        eggRunning = false
        stopEggVibration()
        keyboardView?.stopEgg()
        panelView?.stopEgg()
        stopToolbarEgg()
        AppLog.i(AppLog.Category.UI, "彩蛋结束：一切恢复正常")
        // 里面的图标角度归零（它们平时就停在 0°，这里只是保险）
        panelButtonViews.values.forEach { (normal, close) ->
            normal.rotation = 0f
            close.rotation = 0f
        }
    }

    private fun stopEggVibration() {
        eggVibration?.let { eggHandler.removeCallbacks(it) }
        eggVibration = null
    }
    // ———————————————————————————————————————————————

    /** 点击面板按钮：同一个面板再次点击就收起，回到键盘。 */
    private fun togglePanel(type: PanelType) {
        if (panelVisible && panelType == type) hidePanel() else showPanel(type)
    }

    /** 中英切换按钮：实色圆形底，显示 zh / en 文本。 */
    /**
     * 中英切换按钮。
     *
     * 视觉：当前是**中文**时显示大写的 `EN`（点它就切到英文）；
     *       切到**英文**后显示中文字形图标（点它切回中文）。
     * 两种形态都在同一个圆形按钮里，位置和大小完全不变，不会跳动。
     */
    private fun langToolButton(onClick: () -> Unit): android.widget.FrameLayout {
        val palette = KeyboardTheme.palette(this)
        // 与其它按钮栏按钮一致：底也吃「按钮栏圆角」这个单独设置
        val density = resources.displayMetrics.density
        val half = 18f * density
        val radius = (prefs.barCornerDp * density).coerceIn(0f, half)
        val shape = android.graphics.drawable.GradientDrawable().apply {

            setColor(palette.funcKeyBg)

            cornerRadius = radius

        }
        val wrapper = android.widget.FrameLayout(this).apply {
            // 与三个面板按钮一致：不要安卓默认的点击涟漪，只保留实色圆角底
            isSoundEffectsEnabled = false
            background = shape
            setOnClickListener {
                playToolbarSound()
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(6)
            }
        }
        val label = android.widget.TextView(this).apply {
            text = "EN"
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.onSurfaceVariant)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        wrapper.addView(
            label,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(palette.onSurfaceVariant)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            visibility = android.view.View.GONE
        }
        wrapper.addView(
            icon,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        langLabel = label
        langIcon = icon
        refreshLangButton()
        return wrapper
    }

    /** 按当前布局刷新中英切换按钮的形态（中文=EN 文字，英文=中文字形图标）。
     *
     * 切换时用「旋转切换」动效：旧形态向一侧旋出淡出，新形态从另一侧旋进淡入。
     * 注意**不能**直接让整个按钮旋转 180° —— EN / 拼 是文字，转过去会变成倒的。
     */
    private fun refreshLangButton() {
        // 只有字母布局才更新「当前语言」；数字/符号页沿用上一次字母布局的语言，
        // 这样进数字键盘、符号页时中英切换按钮不会平白旋转切换一次。
        if (layoutType == LayoutType.CHINESE_PINYIN || layoutType == LayoutType.ENGLISH) {
            lastLetterLayout = layoutType
        }
        val chinese = lastLetterLayout == LayoutType.CHINESE_PINYIN
        langLabel?.contentDescription = getString(R.string.kbd_chinese)
        langIcon?.contentDescription = getString(R.string.kbd_chinese)

        val previous = langShownChinese
        if (previous == null || previous == chinese) {
            // 首次布局 / 状态没变：直接摆好形态，不做动画
            applyLangForm(chinese, animated = false)
        } else {
            applyLangForm(chinese, animated = true)
        }
        langShownChinese = chinese
    }

    /** 落定 EN / 拼 的显示形态；animated=true 时走旋转切换。 */
    private fun applyLangForm(chinese: Boolean, animated: Boolean) {
        val incoming = if (chinese) langLabel else langIcon
        val outgoing = if (chinese) langIcon else langLabel
        if (!animated) {
            langLabel?.visibility = if (chinese) android.view.View.VISIBLE else android.view.View.GONE
            langIcon?.visibility = if (chinese) android.view.View.GONE else android.view.View.VISIBLE
            listOfNotNull(langLabel, langIcon).forEach {
                it.rotation = 0f
                it.alpha = 1f
            }
            return
        }
        // 与三个面板按钮共用同一套「逆时针旋转切换 + Q 弹」动效
        if (outgoing != null && incoming != null) {
            switchIconViews(outgoing, incoming, bouncy = true)
        }
    }

    /** 工具栏按钮：实色圆形底 + 圆形涟漪，尺寸与间距统一。 */
    private fun toolButton(iconRes: Int, descriptionRes: Int, onClick: () -> Unit): ImageButton {
        val palette = KeyboardTheme.palette(this)
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(palette.onSurfaceVariant)
            // 按钮栏按钮的圆角由「按钮栏圆角」单独控制：
            // 0dp → 方角；12dp（默认）→ 与键盘按键一致；调到 18dp 及以上 → 正好是圆形。
            val density = resources.displayMetrics.density
            val half = 18f * density // 按钮 36dp，圆角最大只能到一半
            val radius = (prefs.barCornerDp * density).coerceIn(0f, half)
            val shape = android.graphics.drawable.GradientDrawable().apply {

                setColor(palette.funcKeyBg)

                cornerRadius = radius

            }
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(KeyboardTheme.withAlpha(palette.onSurfaceVariant, 26)),
                shape,
                shape,
            )
            contentDescription = getString(descriptionRes)
            isSoundEffectsEnabled = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                playToolbarSound()
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(6)
            }
        }
    }

    /**
     * 震动挡位（0–50）→ 振幅（1–255）。
     * 挡位越多越细腻：1 挡最轻，50 挡最强。
     */
    private fun strengthToAmplitude(strength: Int): Int {
        val level = strength.coerceIn(0, 50)
        if (level == 0) return 1
        return (level * 255f / 50f).toInt().coerceIn(1, 255)
    }

    /** 震动挡位（0–50）→ 时长（10–40ms，挡位越高按得越"实"）。 */
    private fun strengthToDuration(strength: Int): Long {
        val level = strength.coerceIn(0, 50)
        return (10L + level * 30L / 50L).coerceIn(10L, 40L)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 键盘左上角 / 右上角圆角。
     *
     * 只给上面两个角倒角（下面两角是方角），并且用 `clipToOutline` 把**整块键盘的内容
     * 一起裁掉**——候选栏、按钮栏、按键都不再画到圆角外面，所以看起来就是真正的圆角，
     * 而不是「底色圆角、上面的东西还是方角」。
     */
    @Suppress("DEPRECATION") // setConvexPath 在 API 35 起标记废弃，但目前仍是唯一支持「只有上面两角倒角」的裁剪方式
    private fun applyKeyboardTopCorners(view: android.view.View, color: Int) {
        val r = (prefs.keyboardTopCornerDp.coerceIn(0, 20) * resources.displayMetrics.density)
        fun radii(): FloatArray = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadii = radii()
        }
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                if (r <= 0f) {
                    outline.setRect(0, 0, v.width, v.height)
                    return
                }
                val path = android.graphics.Path()
                path.addRoundRect(
                    0f,
                    0f,
                    v.width.toFloat(),
                    v.height.toFloat(),
                    radii(),
                    android.graphics.Path.Direction.CW,
                )
                runCatching { outline.setConvexPath(path) }.onFailure {
                    outline.setRect(0, 0, v.width, v.height)
                }
            }
        }
        view.clipToOutline = r > 0f
        view.invalidateOutline()
    }

    /**
     * 系统导航栏高度（像素），同步从系统资源读取。
     * 用于键盘首次弹出时就预留出底部空间，避免第一帧被导航条遮住一截。
     */
    private fun systemNavigationBarHeight(): Int {
        val res = resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    /** 系统状态栏高度（悬浮键盘向上移动时用它当「上界护栏」）。 */
    private fun systemStatusBarHeight(): Int {
        // 优先拿窗口真实 insets（横屏时状态栏比资源里的 status_bar_height 更薄）
        val real = runCatching {
            val dv = window?.window?.decorView
            when {
                dv == null -> 0
                android.os.Build.VERSION.SDK_INT >= 30 ->
                    dv.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
                else -> legacyInsetTop(dv)
            }
        }.getOrDefault(0)
        if (real > 0) return real
        val res = resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else dp(24)
    }

    /** API 29 及以下的旧式 insets（systemWindowInsetTop 在 30 起废弃）。 */
    @Suppress("DEPRECATION")
    private fun legacyInsetTop(v: android.view.View): Int =
        v.rootWindowInsets?.systemWindowInsetTop ?: 0

    /**
     * 重新应用主题（全局生效）。
     *
     * 键盘、候选栏、面板、工具栏按钮的配色全部来自调色板，所以改主题后必须
     * **重建工具栏按钮**、刷新候选栏样式，否则出现「键盘变了、按钮栏还是旧颜色」。
     * 本方法在每次键盘弹出时也会调用，因此改完主题下一次弹出即生效。
     */
    private fun applyTheme() {
        val palette = KeyboardTheme.palette(this)
        keyboardView?.style = KeyboardTheme.keyboardStyle(this)
        keyboardView?.invalidate()
        candidateBar?.style = KeyboardTheme.candidateStyle(this)
        candidateBar?.invalidate()
        syncCandidateLeftPad()
        panelView?.applyTheme(palette)
        toolbar?.let { bar ->
            bar.removeAllViews()
            buildToolbar(bar)
        }
        // 悬浮卡片底色用的就是这个值：必须跟着主题刷新，否则深色模式下卡片还是浅色
        keyboardBgColor = palette.keyboardBg
        root?.let { applyKeyboardTopCorners(it, palette.keyboardBg) }
        // 顶部把手也重新上色
        dragHandle?.let { styleDragHandle(it) }
        // 正在悬浮的话，把卡片底色立刻换掉
        if (gameFloatActive) applyFloatBackgrounds(dragHandle != null)
        // 设置里的「键盘大小」：每次弹出都按最新值套用
        applyKeyboardSize()
    }

    // —— 布局 ——

    /**
     * 让候选词首个背景的左边缘**严格等于**键盘第一键（字母键盘即 Q 键）的左边缘。
     *
     * 以前这里用的是「按键间距 × 1.5」，那对应的是"没有 leadingPad 的第一排"，
     * 而字母行其实带 0.5 个键宽的 leadingPad，于是候选词会明显偏左。
     * 现在直接量键盘真实算出的首键左边缘，写进候选栏的左留白 —— 两者必然一致。
     */
    /** 上一次记录过的首键左边缘（同值不重复打日志）。 */
    private var lastLoggedLeftPad = -1f

    private fun syncCandidateLeftPad() {
        val bar = candidateBar ?: return
        val kv = keyboardView ?: return
        kv.post {
            val left = kv.firstKeyLeftEdgePx()
            if (left <= 0f) return@post
            bar.updateSidePad(left)
            // 同一次刷新会被调用好几次（主题、布局、弹出各一次），同值只记一次，且降为调试级别
            if (left != lastLoggedLeftPad) {
                lastLoggedLeftPad = left
                AppLog.d(
                    AppLog.Category.UI,
                    "候选词左对齐：键盘首键左边缘=%.1fpx，候选栏左留白=%.1fpx".format(left, bar.style.sidePadPx),
                )
            }
        }
    }

    /** 动作键的文案：跟随输入框动作（搜索 / 发送 / 前往 / 完成…），无动作时为「换行」。 */
    private fun currentActionLabel(): String {
        val info = currentInputEditorInfo ?: return "换行"
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "换行"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一步"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一步"
            else -> "换行"
        }
    }

    private fun applyLayout() {
        val kv = keyboardView ?: return
        // 换键盘（中英切换 / 布局切换）时清掉"已关闭候选"的标记
        suggestionsDismissed = false
        val label = currentActionLabel()
        val mode = currentShiftMode()
        // 记下这次用的是哪种大写键形态（供 syncShiftKeyLayout 比较）
        shiftKeySegment = mode == KeyboardLayouts.ShiftMode.SEGMENT
        val rows = when (layoutType) {
            LayoutType.CHINESE_PINYIN -> KeyboardLayouts.chinesePinyin(label, mode)
            LayoutType.ENGLISH -> KeyboardLayouts.english(shiftActive, label)
            LayoutType.SYMBOLS -> KeyboardLayouts.symbols(symbolCategory, SymbolHistory.recent(), label)
            LayoutType.NUMBERS -> KeyboardLayouts.numbers(label)
        }
        kv.setRows(rows)
        // 换布局后同步「符号入口 / ✕」的形态（与面板按钮同一套旋转 + Q 弹动画）
        updateSymbolButtonState()
        kv.style = KeyboardTheme.keyboardStyle(this)
        // 盲打模式：隐藏 26 个字母键上的字母
        kv.hideLetterLabels = prefs.blindTyping

        // 高度统一：键盘区高度按「最大行数」算一次，字母/数字/符号键盘与所有面板共用同一高度；
        // 行数较少的布局由 KeyboardView 自行放大按键高度来填满。
        val density = resources.displayMetrics.density
        val settings = prefs
        val keyHeight = (settings.keyHeightDp * density).toInt()
        val gap = (settings.keyGapDp * density).toInt()
        val unifiedHeight = KEYBOARD_MAX_ROWS * keyHeight + (KEYBOARD_MAX_ROWS + 1) * gap
        kv.fixedHeightPx = unifiedHeight

        // 面板高度 = 键盘区高度。
        // 按钮栏现在始终显示，所以面板只占据「原本键盘」那块区域，内容紧接在按钮栏下方。
        panelView?.let { panel ->
            // 面板高度 = 键盘区高度（面板展开时看起来和键盘一样大，不会跳动）。
            val panelHeight = unifiedHeight
            val lp = panel.layoutParams
            if (lp != null && lp.height != panelHeight) {
                lp.height = panelHeight
                panel.layoutParams = lp
            }
        }
        // 注意：这里**不能**把根容器高度写死。系统导航条的内边距是加在根容器上的，
        // 一旦写死总高度，内边距就会挤掉内容空间，导致键盘最后一行被裁掉。
        // 键盘与面板各自高度固定，因此总高度本来就一致。

        candidateBar?.style = KeyboardTheme.candidateStyle(this)
        syncCandidateLeftPad()
        // 中英切换按钮随模式实时变化
        // 中英切换按钮的形态随布局切换（中文 → EN 文字；英文 → 中文字形图标）
        refreshLangButton()
        refreshActionRow()
    }

    /**
     * 决定那一行显示什么：
     *   正在组合（有 preedit 或候选） → 候选栏
     *   刚选完词且能联想           → 联想词
     *   其它                        → 工具栏
     */
    private fun refreshActionRow() {
        val row = actionRow ?: return
        // 按钮栏**始终显示**：面板展开时内容显示在按钮栏下方的区域，
        // 所以这里不再隐藏整行。
        row.visibility = View.VISIBLE
        val bar = candidateBar ?: return
        if (panelVisible) {
            // 面板展开时按钮栏保持工具栏形态
            bar.visibility = View.GONE
            toolbar?.visibility = View.VISIBLE
            return
        }

        val composing = !lastPreedit.isEmpty() || lastCandidates.isNotEmpty()
        val showCandidate = composing || predictionWords.isNotEmpty() || clipboardSuggestionText != null
        // 数字键盘下不需要候选栏（且工具栏必须可用）；
        // 唯一的例外是「输入 @ 后的邮箱后缀候选」——它必须**立刻**出现，不用切回字母键盘。
        val wantBar = showCandidate && prefs.candidateBarVisible &&
            (layoutType != LayoutType.NUMBERS || emailSuggestions.isNotEmpty())

        if (wantBar) {
            bar.visibility = View.VISIBLE
            toolbar?.visibility = View.GONE
        } else {
            bar.visibility = View.GONE
            toolbar?.visibility = View.VISIBLE
        }
    }

    private fun updateCandidates() {
        val bar = candidateBar ?: return
        // 正常输入优先：一旦 Rime 产生了组合串（用户开始打拼音），邮箱候选模式立刻退出，
        // 候选栏回到拼音组合 + 普通候选词，不会被邮箱列表占据。
        if (emailMode && lastPreedit.isNotEmpty()) exitEmailMode()
        // 邮箱后缀候选优先：输入 @ 之后，候选栏给主流邮箱后缀（而不是拼音候选）
        // 正在输入拼音时不抢候选（lastPreedit 非空说明有组合串）
        // 用户点过 ✕ 关掉候选时，这里也不再自动算出来
        // 邮箱候选只在「显式处于邮箱模式 + 没被关掉」时才展示；
        // 光靠输入框里有 @ 是不会进来的（这是之前反复抢占候选栏的根因）。
        if (emailMode && !emailBlocked && !suggestionsDismissed &&
            lastPreedit.isEmpty() && refreshEmailSuggestions()
        ) {
            clearClipboardSuggestion()
            lastPreedit = ""
            lastCandidates = emailSuggestions.map { RimeEngine.Candidate(it.first, "") }
            predictionWords = emptyList()
            bar.update(
                preedit = "",
                candidates = lastCandidates,
                highlighted = 0,
                pageNo = 0,
                hasPrevPage = false,
                hasNextPage = false,
            )
            syncShiftKeyLayout()
            refreshActionRow()
            return
        }
        // 英文候选：英文键盘下按已打字母做词表前缀补全
        if (lastPreedit.isEmpty() && showEnglishSuggestions()) {
            clearClipboardSuggestion()
            lastPreedit = ""
            lastCandidates = englishSuggestions.map { RimeEngine.Candidate(it.first, "") }
            predictionWords = emptyList()
            bar.update(
                preedit = "",
                candidates = lastCandidates,
                highlighted = 0,
                pageNo = 0,
                hasPrevPage = false,
                hasNextPage = false,
            )
            syncShiftKeyLayout()
            refreshActionRow()
            return
        }
        if (session == 0L) {
            lastPreedit = ""
            lastCandidates = emptyList()
            predictionWords = emptyList()
            bar.update("", emptyList(), 0, 0, false, false)
            syncShiftKeyLayout()
            refreshActionRow()
            return
        }
        val ctx = RimeEngine.context(session)
        if (ctx == null) {
            // 自愈：会话存在但取不到上下文（会话可能已失效），重建一次
            AppLog.w(AppLog.Category.ENGINE, "会话取不到上下文，重建会话")
            RimeEngine.destroySession(session)
            session = 0L
            createSessionIfNeeded()
            val retry = if (session != 0L) RimeEngine.context(session) else null
            if (retry == null) {
                lastPreedit = ""
                lastCandidates = emptyList()
                bar.update("", emptyList(), 0, 0, false, false)
                refreshActionRow()
                return
            }
            return updateCandidatesWith(retry)
        }
        updateCandidatesWith(ctx)
    }

    private fun updateCandidatesWith(ctx: RimeEngine.RimeContext) {
        val bar = candidateBar ?: return
        if (ctx.preedit.isNotEmpty() || ctx.candidates.isNotEmpty()) {
            // 用户开始打字让剪贴板候选消失：记住这条内容，之后不再自动弹出（剪贴板面板仍保留记录）
            if (!clipboardSuggestionText.isNullOrEmpty()) {
                clipboardSuggestionDismissed = clipboardSuggestionText
            }
            clearClipboardSuggestion()
        }
        // 每次刷新先清空重排映射：只有真正重排过的列表才会重新赋值
        candidateOrder = null
        // 一旦有拼音组合串，邮箱候选模式让位给正常拼音输入（候选栏显示拼音候选）
        if (emailMode && ctx.preedit.isNotEmpty()) exitEmailMode()
        lastPreedit = ctx.preedit
        lastCandidates = ctx.candidates
        // 拼音直接写进目标输入框（组合文本），候选栏不再显示拼音
        syncComposingText(ctx.preedit)
        // 进入/退出拼音输入时，大写键要在「Shift 图标」和「词」之间切换
        syncShiftKeyLayout()

        if (ctx.preedit.isEmpty() && ctx.candidates.isEmpty()) {
            // 没有正在输入的内容：如果刚选完词就展示联想，否则回到工具栏
            if (predictionWords.isNotEmpty()) {
                bar.update(
                    preedit = "",
                    candidates = predictionWords.map { RimeEngine.Candidate(it, "") },
                    highlighted = -1,
                    pageNo = 0,
                    hasPrevPage = false,
                    hasNextPage = false,
                    prediction = true,
                )
            } else {
                bar.update("", emptyList(), 0, 0, false, false)
            }
        } else {
            // 一旦重新开始输入，联想词立即让位给候选
            predictionWords = emptyList()
            val ordered = PinyinOrder.reorder(this, ctx.preedit, ctx.candidates)
            candidateOrder = ordered.order
            // 重排后把引擎高亮同步到「显示第一位」，这样空格默认上屏的就是用户看到的第一个候选
            var displayHighlight = toDisplayIndex(ctx.highlighted)
            val firstEngine = ordered.order?.firstOrNull()
            if (ordered.order != null && firstEngine != null && firstEngine != ctx.highlighted && session != 0L) {
                if (RimeEngine.highlightOnPage(session, firstEngine)) {
                    displayHighlight = 0
                }
                AppLog.d(
                    AppLog.Category.INPUT,
                    "候选重排：${ctx.preedit} → ${ordered.list.take(6).joinToString("/") { it.text }}",
                )
            }
            bar.update(
                preedit = ctx.preedit,
                candidates = ordered.list,
                highlighted = displayHighlight,
                pageNo = ctx.pageNo,
                hasPrevPage = ctx.pageNo > 0,
                hasNextPage = !ctx.isLastPage,
                selectKeys = ctx.selectKeys,
            )
        }
        refreshActionRow()
    }

    /** 候选栏被点击：候选模式下选字，联想模式下上屏联想词。 */
    private fun onCandidatePicked(index: Int) {
        // 点击候选词也要有震动反馈 + 打字音（与 26 字母一致）
        vibrateFeedback()
        playTypeSound()
        // 邮箱后缀候选：只把「还没打的那一段」补全上屏
        if (emailSuggestions.isNotEmpty()) {
            val pick = emailSuggestions.getOrNull(index) ?: return
            // 补全完成 = 这个邮箱已经写完了，退模式并封住这个 @，不会再弹候选
            exitEmailMode(block = true)
            commitToEditor(pick.second)
            lastPreedit = ""
            lastCandidates = emptyList()
            candidateBar?.update("", emptyList(), 0, 0, false, false)
            refreshActionRow()
            AppLog.i(AppLog.Category.INPUT, "补全邮箱后缀：${pick.first}")
            return
        }
        // 英文候选：只把「还没打的那一段」补全上屏
        if (englishSuggestions.isNotEmpty()) {
            val pick = englishSuggestions.getOrNull(index) ?: return
            englishSuggestions = emptyList()
            commitToEditor(pick.second)
            lastCandidates = emptyList()
            candidateBar?.update("", emptyList(), 0, 0, false, false)
            refreshActionRow()
            AppLog.i(AppLog.Category.INPUT, "英文候选补全：${pick.first}")
            return
        }
        if (predictionWords.isNotEmpty() && lastCandidates.isEmpty()) {
            val word = predictionWords.getOrNull(index) ?: return
            // 联想词上屏后按用户要求恢复工具栏（单层联想）
            predictionWords = emptyList()
            commitToEditor(word)
            lastPreedit = ""
            lastCandidates = emptyList()
            candidateBar?.update("", emptyList(), 0, 0, false, false)
            refreshActionRow()
            AppLog.i(AppLog.Category.INPUT, "上屏联想词（长度 ${word.length}）")
            return
        }
        selectCandidate(toEngineIndex(index))
    }

    /** 选词/整句上屏后，用其末字去词表里取下一词作为联想。 */
    private fun offerPrediction(committedText: String) {
        // 只在中文模式下联想：英文键盘逐字母上屏时不应弹出中文联想词
        if (layoutType != LayoutType.CHINESE_PINYIN) {
            predictionWords = emptyList()
            return
        }
        if (committedText.isEmpty() || isPasswordField) {
            predictionWords = emptyList()
            return
        }
        // 只有中文（非 ASCII）上屏才联想
        if (committedText.all { it.code < 128 }) {
            predictionWords = emptyList()
            return
        }
        if (!prefs.candidateBarVisible) {
            predictionWords = emptyList()
            return
        }
        PredictLexicon.ensureLoaded(this)
        val words = PredictLexicon.wordsFor(committedText)
        predictionWords = if (words.size >= 2) words else emptyList()
        if (predictionWords.isNotEmpty()) {
            AppLog.i(AppLog.Category.INPUT, "给出 ${predictionWords.size} 个联想词")
        }
    }

    // —— Rime 引擎准备 ——

    private fun ensureEngine() {
        // 已就绪时直接补建会话即可（会话在 onFinishInput 中会被销毁）
        if (RimeEngine.isReady) {
            createSessionIfNeeded()
            updateCandidates()
            return
        }
        if (engineRequested) return
        engineRequested = true
        scope.launch {
            val ok = RimeEngine.ensureReady(applicationContext)
            if (ok) {
                createSessionIfNeeded()
                AppLog.i(AppLog.Category.ENGINE, "引擎就绪，中文输入可用")
            } else {
                // 允许后续重新尝试，避免一次失败后永远不再初始化
                engineRequested = false
                AppLog.w(AppLog.Category.DEPLOY, "引擎未就绪，中文候选暂不可用，稍后可重试")
            }
            updateCandidates()
        }
    }

    private fun createSessionIfNeeded() {
        if (session != 0L) return
        // 部署进行中不建会话：此时词典/方案正在被重写，建了也只能拿到半成品候选。
        // 部署结束后由 guardDeploying() 负责重建。
        if (RimeEngine.blockingInput) {
            AppLog.w(AppLog.Category.ENGINE, "部署进行中，暂不创建会话")
            return
        }
        // 先清理其它连接遗留的会话，再建新会话，避免误伤刚创建的会话
        RimeEngine.cleanupStaleSessions()
        session = RimeEngine.createSession()
        if (session == 0L) {
            AppLog.w(AppLog.Category.ENGINE, "创建 Rime 会话失败，稍后重试")
            return
        }
        // 依据当前中英文状态同步 Rime 的 ascii_mode
        val ascii = layoutType != LayoutType.CHINESE_PINYIN
        RimeEngine.setOption(session, "ascii_mode", ascii)
        AppLog.i(AppLog.Category.ENGINE, "已创建 Rime 会话（ascii_mode=$ascii）")
    }

    // —— 生命周期 ——

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isPasswordField = isPasswordInput(attribute)
        AppLog.i(
            AppLog.Category.INPUT,
            "开始输入：类型=${describeInputType(attribute)} 密码框=$isPasswordField",
        )
        // 密码框不学习、不保留任何内容：强制英文布局并关闭学习
        if (isPasswordField) {
            layoutType = LayoutType.ENGLISH
            shiftActive = false
        } else {
            layoutType = if (prefs.chineseDefault) {
                LayoutType.CHINESE_PINYIN
            } else {
                LayoutType.ENGLISH
            }
        }
        symbolCategory = 0
        // 新输入连接开始：清掉上一个输入框残留的候选与联想
        lastPreedit = ""
        lastCandidates = emptyList()
        predictionWords = emptyList()
        emailSuggestions = emptyList()
        englishSuggestions = emptyList()
        // 换了输入框：邮箱候选状态完全复位（新场景里可以重新用）
        emailMode = false
        emailBlocked = false
        emailLocalPart = ""
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 系统把本输入法显示出来了 —— 记录「此刻在屏」，供状态卡片判定「已选择」
        IcePrefs.setImeShown(true)
        // 每次弹出都重新套用主题与尺寸：用户在设置里改完外观后，第一次弹出就是新外观
        applyTheme()
        applyLayout()
        // 游戏键盘：横屏悬浮（每次弹出都按当前方向重新判定一次）
        root?.post { applyGameFloat() }
        // 键盘弹出时顺手采集一次剪贴板（尊重历史开关与密码框保护），
        // 这样用户复制过的内容会自然积累到列表里
        captureClipboard()
        refreshClipboardSuggestion()
        // 英文句首自动大写功能已按用户要求移除（不再自动点亮 Shift）。
        applyLayout()
        ensureEngine()
        if (session != 0L) {
            RimeEngine.setOption(session, "ascii_mode", layoutType != LayoutType.CHINESE_PINYIN)
        }
        updateCandidates()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 键盘离开屏幕：切换输入法 / 收起键盘都会走到这里
        IcePrefs.setImeShown(false)
        // 结束输入前把未上屏的组合串提交，避免用户已输入的内容丢失
        if (session != 0L) {
            RimeEngine.commitComposition(session)
            RimeEngine.clearComposition(session)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        if (session != 0L) {
            RimeEngine.destroySession(session)
            session = 0L
        }
        hidePanel()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        // 用户手动挪了光标（不是我们自己写字引起的）→ 邮箱候选状态作废；
        // 否则状态会残留在那里，继续打字时又被旧的 @ 抢走候选栏。
        if ((oldSelStart != newSelStart || oldSelEnd != newSelEnd) &&
            SystemClock.uptimeMillis() - lastTextCommitAt > 300L
        ) {
            if (emailMode || emailSuggestions.isNotEmpty()) exitEmailMode()
        }
        // 光标移动到组合区之外时才清掉组合，防止候选残留
        if (session != 0L &&
            candidatesStart >= 0 &&
            (newSelStart < candidatesStart || newSelEnd > candidatesEnd)
        ) {
            RimeEngine.clearComposition(session)
            updateCandidates()
        }
        // 记录光标在「正在输入的拼音」内部的位置：
        // 用户点拼音中间时，分词按钮就把音节分隔符插在他点的位置。
        composingCursor =
            if (candidatesStart >= 0 && candidatesEnd > candidatesStart &&
                newSelStart == newSelEnd && newSelStart in candidatesStart..candidatesEnd
            ) {
                newSelStart - candidatesStart
            } else {
                -1
            }
    }

    override fun onDestroy() {
        IcePrefs.setImeShown(false)
        unregisterClipboardWatcher()
        if (session != 0L) {
            RimeEngine.syncUserData()
            RimeEngine.destroySession(session)
            session = 0L
        }
        scope.cancel()
        AppLog.i(AppLog.Category.UI, "输入法服务已销毁")
        super.onDestroy()
    }

    // —— 面板 ——

    /** 面板在容器里的位置调整成「按钮栏下面、键盘上面」（常规位置）。 */
    private fun restorePanelPosition() {
        // 面板的父容器现在是卡片（kbBox），必须在同一个父容器里挪动，
        // 否则会「视图已有父容器」崩溃（切输入法/收起键盘时必现）
        val r = kbBox ?: root ?: return
        val pv = panelView ?: return
        val kv = keyboardView ?: return
        if (r.indexOfChild(pv) == r.indexOfChild(kv) - 1) return
        r.removeView(pv)
        r.addView(pv, r.indexOfChild(kv))
    }

    private fun showPanel(type: PanelType) {
        if (type == PanelType.CLIPBOARD && !prefs.clipboardPanelEnabled) return
        // 打开面板前先退出符号键盘页：否则符号入口按钮会一直挂着 ✕，
        // 看着像「点别的按钮它也还在叉」，也容易误点到 ✕
        if (layoutType == LayoutType.SYMBOLS) {
            layoutType = lettersBeforeSymbols
            shiftActive = false
            applyLayout()
            AppLog.i(AppLog.Category.UI, "打开面板前退出符号键盘")
        }
        if (type == PanelType.PHRASE && !prefs.phraseEnabled) return
        if (type == PanelType.EMOJI && !prefs.emojiEnabled) return
        // 打开剪贴板面板时，先记录当前系统剪贴板内容（仅在用户主动操作且开关打开时）
        if (type == PanelType.CLIPBOARD) {
            captureClipboard()
        }
        panelVisible = true
        panelType = type
        panelView?.apply {
            visibility = View.VISIBLE
            applyTheme(KeyboardTheme.palette(this@IceInputMethodService))
            show(type)
        }
        keyboardView?.visibility = View.GONE
        refreshActionRow()
        updatePanelButtonStates()
        AppLog.i(AppLog.Category.UI, "打开面板：${type.name}")
    }

    private fun hidePanel() {
        panelVisible = false
        panelType = null
        panelView?.visibility = View.GONE
        restorePanelPosition()
        keyboardView?.visibility = View.VISIBLE
        applyLayout()
        refreshActionRow()
        updatePanelButtonStates()
        AppLog.i(AppLog.Category.UI, "关闭面板，返回键盘")
    }

    private fun captureClipboard() {
        // 密码框也读取（按用户要求去掉密码框保护）
        ClipboardStore.captureFromSystem(this, fromPasswordField = false)
    }

    /** 刷新候选栏的剪贴板建议：开关关闭 / 数字键盘页 / 内容为空 / 已关闭过 → 都不显示。 */
    private fun refreshClipboardSuggestion() {
        if (!prefs.clipboardCandidateEnabled) { clearClipboardSuggestion(); return }
        if (layoutType == LayoutType.NUMBERS) { clearClipboardSuggestion(); return }
        val text = ClipboardStore.current()
        if (text.isNullOrBlank()) { clearClipboardSuggestion(); return }
        if (text == clipboardSuggestionDismissed) { clearClipboardSuggestion(); return }
        clipboardSuggestionText = text
        candidateBar?.setClipboardSuggestion(text)
        refreshActionRow()
    }

    private fun clearClipboardSuggestion() {
        clipboardSuggestionText = null
        candidateBar?.setClipboardSuggestion(null)
        refreshActionRow()
    }

    /** 点剪贴板建议的文字区域：直接把内容上屏到光标处。 */
    private fun commitClipboardSuggestion() {
        val text = clipboardSuggestionText ?: return
        playTypeSound()
        commitToEditor(text)
        clearClipboardSuggestion()
        AppLog.i(AppLog.Category.CLIPBOARD, "剪贴板候选上屏（长度 ${text.length}）")
    }

    /** 点剪贴板建议的 ✕：关闭这条，内容变化前不再显示。 */
    private fun dismissClipboardSuggestion() {
        val text = clipboardSuggestionText ?: return
        playToolbarSound()
        clipboardSuggestionDismissed = text
        clearClipboardSuggestion()
        AppLog.i(AppLog.Category.CLIPBOARD, "用户关闭剪贴板候选")
    }

/**
     * 监听系统剪贴板：用户每复制一次，就立刻记录一次。
     *
     * 以前只在「打开剪贴板面板」时读一次系统剪贴板，所以连续复制多条之后
     * 面板里只会剩下最后那一条 —— 这就是「复制了多少条都只剩最后一条」的根因。
     */
    private fun registerClipboardWatcher() {
        if (clipboardListener != null) return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                captureClipboard()
                // 有新的复制内容：清掉上一次「点 ✕ 关闭」的记忆，并刷新候选栏建议
                clipboardSuggestionDismissed = null
                refreshClipboardSuggestion()
            }
            cm.addPrimaryClipChangedListener(listener)
            clipboardListener = listener
            AppLog.i(AppLog.Category.CLIPBOARD, "已监听系统剪贴板：每复制一次立即记录一次")
        }.onFailure {
            AppLog.throwable(AppLog.Category.ERROR, "注册剪贴板监听失败", it)
        }
    }

    private fun unregisterClipboardWatcher() {
        val listener = clipboardListener ?: return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.removePrimaryClipChangedListener(listener)
        }
        clipboardListener = null
    }

    /**
     * 英文候选：按已经上屏的字母做词表前缀补全。
     *
     * 英文键盘走 Rime 的 ascii 模式（字母直接上屏、不产生候选），所以这里用随包的英文词表
     * （assets/rime/en_dicts/en.dict.yaml）做前缀匹配；点选时**只补全还没打的那一段**。
     *
     * @return 是否处于英文候选状态
     */
    private fun showEnglishSuggestions(): Boolean {
        englishSuggestions = emptyList()
        if (layoutType != LayoutType.ENGLISH || !prefs.englishCandidates) return false
        // 用户点过候选栏右侧的 ✕：这次不要自动再冒出来，等他再敲字才恢复
        if (suggestionsDismissed) return false
        val ic = currentInputConnection ?: return false
        val before = runCatching { ic.getTextBeforeCursor(24, 0)?.toString() }.getOrNull()
            ?: return false
        val prefix = ENGLISH_TAIL_REGEX.find(before)?.value ?: return false
        if (prefix.length < 2) return false
        if (!EnglishLexicon.ensureLoaded(this)) return false
        val words = EnglishLexicon.suggest(prefix, 8)
        if (words.isEmpty()) return false
        englishSuggestions = words.map { word -> word to word.substring(prefix.length) }
        return true
    }

    // —— 邮箱后缀候选 ——

    /** 主流邮箱后缀（按用户指定：qq / gmail / 163 / 126 / outlook / yahoo / icloud）。 */
    private val emailDomains = listOf(
        "qq.com", "gmail.com", "163.com", "126.com", "outlook.com", "yahoo.com", "icloud.com",
    )

    /** 匹配「光标前文本」末尾的 局部@域名 片段。 */
    private val emailTailRegex = Regex("([A-Za-z0-9._%+\\-]*)@([A-Za-z0-9.\\-]*)$")

    /** 匹配光标前正在输入的英文单词（用于英文候选的前缀补全）。 */
    private val ENGLISH_TAIL_REGEX = Regex("[A-Za-z']{2,}$")

    /**
     * 退出邮箱候选模式。
     *
     * @param block true = “这次的 @ 已经处理完了”：即使输入框里还留着 @，
     * 也不会再自动弹出候选，直到用户重新打一个新的 @。
     */
    private fun exitEmailMode(block: Boolean = false) {
        val wasActive = emailMode || emailSuggestions.isNotEmpty()
        emailMode = false
        emailSuggestions = emptyList()
        emailLocalPart = ""
        if (block) emailBlocked = true
        if (wasActive) {
            AppLog.i(AppLog.Category.INPUT, "退出邮箱候选模式（block=$block）")
            refreshActionRow()
        }
    }

    /**
     * 刷新邮箱后缀候选。
     *
     * **只服务处于邮箱模式的那一个 @**：
     *  · 必须 [emailMode] 为真（只有刚打出一个 @ 才可能）；
     *  · 光标前的片段必须还是「同一个 @」（与 [emailLocalPart] 一致），
     *    所以输入框其它位置的 @ 永远不会抢候选；
     *  · 光标前一个字符必须是邮箱里合法的字符（空格 / 换行 / 汉字 / 标点 → 直接退出模式）；
     *  · 域名前缀过滤后没有结果 → 也退出模式（不会拿个空列表占着候选栏）。
     *
     * @return 是否进入了邮箱候选状态
     */
    private fun refreshEmailSuggestions(): Boolean {
        if (!emailMode || emailBlocked) return false
        val ic = currentInputConnection ?: return false
        val before = runCatching { ic.getTextBeforeCursor(64, 0)?.toString() }.getOrNull()
            ?: return false
        val lastChar = before.lastOrNull() ?: return false
        val emailChar = lastChar == '@' || lastChar == '.' || lastChar == '-' ||
            lastChar == '_' || lastChar == '+' || lastChar.isLetterOrDigit()
        if (!emailChar) {
            // 开始打中文 / 空格 / 标点了：邮箱上下文已经结束
            exitEmailMode()
            return false
        }
        val match = emailTailRegex.find(before)
        if (match == null) {
            // 光标前已经不是一个 xxx@yyy 了（比如 @ 被删掉、或光标移到了别处）
            exitEmailMode()
            return false
        }
        val localPart = match.groupValues[1] + "@"
        if (emailLocalPart.isNotEmpty() && localPart != emailLocalPart) {
            // 换到另一个 @ 上了（不是我们记录的那一个）：作废重来
            exitEmailMode()
            return false
        }
        val prefix = match.groupValues[2].lowercase()
        if (prefix.length > 40) {
            exitEmailMode()
            return false
        }
        val list = emailDomains
            .filter { it.startsWith(prefix) }
            .map { domain -> domain to domain.removePrefix(prefix) }
            .filter { it.second.isNotEmpty() }
        if (list.isEmpty()) {
            exitEmailMode()
            return false
        }
        emailSuggestions = list
        return true
    }

    // —— 按键处理 ——

    private fun handleKey(key: KeyDef) {
        // 面板内编辑时的按键**照常走完整条链路**（Rime 组合 → 候选栏 → 上屏），
        // 只是最后的上屏会由 commitToEditor() 落进面板的编辑框，而不是应用。
        // 这样编辑常用语时中文候选、联想、符号都跟平时打字完全一样。
        // 任何真实按键都会结束联想态
        if (predictionWords.isNotEmpty() && key.action !is KeyAction.OpenPanel) {
            predictionWords = emptyList()
        }
        // 再敲字即视为"重新开始"，英文候选可以再出现
        if (key.action is KeyAction.Rime || key.action is KeyAction.Commit) {
            suggestionsDismissed = false
        }
        when (val action = key.action) {
            is KeyAction.Rime -> {
                // 正常输入优先：中文键盘下继续敲字母 = 开始打拼音，
                // 邮箱候选模式当场退出，候选栏马上换成拼音候选（不用先点 ✕）。
                if (emailMode && layoutType == LayoutType.CHINESE_PINYIN) exitEmailMode()
                sendToRime(action.keycode, action.modifier)
            }
            KeyAction.SegmentSyllable -> handleSegment()
            is KeyAction.Commit -> {
                insertPlainText(action.text)
                // 数字键盘左侧那几个符号（@ % -）：点一下上屏后**回到 26 字母键盘**。
                // 其余键（数字、小数点、逗号、以及其它所有符号）都**留在数字键盘**，
                // 不再一点就把键盘切回字母页。邮箱候选（点 @ 触发的那些）不受影响。
                if (layoutType == LayoutType.NUMBERS && action.text in NUMBER_LEFT_SYMBOLS) {
                    layoutType = lettersBeforeNumbers
                    shiftActive = false
                    applyLayout()
                    updateCandidates()
                    AppLog.i(
                        AppLog.Category.INPUT,
                        "数字键盘符号已输入，返回字母键盘（${if (layoutType == LayoutType.CHINESE_PINYIN) "中文" else "英文"}）",
                    )
                }
            }
            KeyAction.Space -> handleSpace()
            KeyAction.Backspace -> handleBackspace()
            KeyAction.Enter -> handleEnter()
            KeyAction.Shift -> if (layoutType == LayoutType.CHINESE_PINYIN) handleShiftKey() else toggleShift()
            KeyAction.ClearComposition -> {
                session.takeIf { it != 0L }?.let { RimeEngine.clearComposition(it) }
                updateCandidates()
            }

            is KeyAction.SwitchLayout -> {
                // 已经在目标键盘上时不再重复切一次（重复切会造成一次多余的闪动）
                if (action.target == layoutType) {
                    AppLog.i(AppLog.Category.UI, "已在目标键盘，跳过重复切换")
                    return
                }
                // 切键盘布局 = 换输入场景：邮箱候选状态作废（不再残留）
                exitEmailMode(block = true)
                // 进数字键盘前记下原来是中/英，点完符号好回到那个键盘
                if (action.target == LayoutType.NUMBERS && layoutType != LayoutType.NUMBERS) {
                    lettersBeforeNumbers = layoutType
                }
                layoutType = action.target
                shiftActive = false
                applyLayout()
            }

            is KeyAction.OpenPanel -> {
                exitEmailMode(block = true)
                showPanel(action.panel)
            }
            KeyAction.BackToKeyboard -> {
                if (panelVisible) hidePanel() else {
                    exitEmailMode(block = true)
                    layoutType = if (prefs.chineseDefault) LayoutType.CHINESE_PINYIN else LayoutType.ENGLISH
                    applyLayout()
                }
            }

            KeyAction.HideKeyboard -> {
                exitEmailMode(block = true)
                // 收起键盘前若面板（表情/剪贴板/常用语）开着，先关掉，否则下次弹出还是打开状态
                if (panelVisible) hidePanel()
                requestHideSelf(0)
            }
            KeyAction.OpenSettings -> openApp("home")
            KeyAction.ToggleChineseEnglish -> toggleChineseEnglish()
            is KeyAction.PageCandidates -> handlePage(action.next)
            is KeyAction.SymbolCategory -> {
                if (action.index < 0) {
                    layoutType = if (prefs.chineseDefault) LayoutType.CHINESE_PINYIN else LayoutType.ENGLISH
                } else {
                    symbolCategory = action.index
                }
                applyLayout()
            }

            KeyAction.None -> Unit
        }
    }

    /** 记录大写键当前是否处于「词」（分词）形态，用来判断是否需要重建键盘。 */
    private var shiftKeySegment = false

    private fun syncShiftKeyLayout() {
        val segment = currentShiftMode() == KeyboardLayouts.ShiftMode.SEGMENT
        if (segment != shiftKeySegment) {
            AppLog.d(AppLog.Category.UI, "大写键形态切换：${if (segment) "词" else "Shift"}")
            applyLayout()
        }
    }

    /**
     * 分词：在当前拼音里插入音节分隔符 `'`。
     * 例如已经输入 `ni`，点一下「词」变成 `ni'`，再继续输入 `hao` → 候选直接给出「你好」。
     * 如果用户事先点过输入框里拼音的某个位置，分隔符就插在那个位置（点哪插哪）。
     */
    private fun handleSegment() {
        if (guardDeploying()) return
        if (session == 0L) return
        // 注意：Rime 的 preedit 里音节边界是**空格**（如 `ni hao`），
        // 而真实输入串里的分隔符是 `'`。之前直接把 preedit 拿去重建，
        // 重建时把空格也当成按键发给引擎 —— 而空格在 Rime 里是「选字上屏」，
        // 结果就是拼音被切得乱七八糟（用户看到 `ni'hani'hao'o` 这种）。
        // 所以这里先用 `'` 归一化，再逐字符（只发字母和 `'`）重发。
        val input = lastPreedit.replace(" ", "'")
        val at = composingCursor
        if (at in 1 until input.length) {
            val rebuilt = input.substring(0, at) + "'" + input.substring(at)
            RimeEngine.clearComposition(session)
            for (ch in rebuilt) {
                if (ch == ' ') continue
                RimeEngine.processKey(session, ch.code, 0)
            }
            AppLog.i(AppLog.Category.INPUT, "分词：在第 $at 个字符后插入分隔符 → $rebuilt")
            composingCursor = -1
            afterRimeInput()
            return
        }
        val consumed = RimeEngine.processKey(session, '\''.code, 0)
        AppLog.i(AppLog.Category.INPUT, "分词：插入音节分隔符（consumed=$consumed）")
        afterRimeInput()
    }

    /**
     * 大写键的四种状态（中文键盘）：
     * 0 普通 → 按一下：下一个字母大写；快速双击：大写锁定；锁定状态下再按一下：解锁。
     * 正在输入拼音时，按钮会显示「词」并改为分词（见 [currentShiftMode]）。
     */
    private fun currentShiftMode(): KeyboardLayouts.ShiftMode = when {
        lastPreedit.isNotEmpty() -> KeyboardLayouts.ShiftMode.SEGMENT
        capsLocked -> KeyboardLayouts.ShiftMode.LOCK
        shiftActive -> KeyboardLayouts.ShiftMode.ONCE
        else -> KeyboardLayouts.ShiftMode.OFF
    }

    private fun handleShiftKey() {
        val now = System.currentTimeMillis()
        val doubleTap = now - lastShiftTapAt < SHIFT_DOUBLE_TAP_MS
        lastShiftTapAt = now
        if (doubleTap) {
            // 快速双击 → 大写锁定（再双击解除）
            capsLocked = !capsLocked
            shiftActive = false
            AppLog.i(AppLog.Category.INPUT, "大写锁定：$capsLocked")
        } else if (capsLocked) {
            capsLocked = false
            AppLog.i(AppLog.Category.INPUT, "大写锁定：false")
        } else {
            shiftActive = !shiftActive
            AppLog.i(AppLog.Category.INPUT, "大写（单次）：$shiftActive")
        }
        applyLayout()
    }

    // ———— 部署守卫 ————
    /**
     * 词库/方案正在重编译（例如刚切换模糊音开关）时为 true。
     *
     * 这期间词典与方案正在被重写，旧会话取到的候选与新方案对不上，
     * 用户看到的就是「打出来的字完全不对」。所以：
     *   · 进入部署：立刻销毁当前会话、吞掉按键（避免垃圾上屏），并提示一次；
     *   · 部署结束：重建会话、刷新候选，恢复正常输入。
     */
    private var rimeDeployBlocked = false
    private var deployHintShown = false

    private fun guardDeploying(): Boolean {
        if (!RimeEngine.blockingInput) {
            if (rimeDeployBlocked) {
                rimeDeployBlocked = false
                deployHintShown = false
                session.takeIf { it != 0L }?.let { RimeEngine.destroySession(it) }
                session = 0L
                createSessionIfNeeded()
                updateCandidates()
                AppLog.i(AppLog.Category.DEPLOY, "词库部署完成，已重建输入会话并恢复输入")
            }
            return false
        }
        if (!rimeDeployBlocked) {
            rimeDeployBlocked = true
            session.takeIf { it != 0L }?.let { RimeEngine.destroySession(it) }
            session = 0L
            lastPreedit = ""
            lastCandidates = emptyList()
            predictionWords = emptyList()
            candidateOrder = null
            // 会话被销毁后引擎不再持有组合串，输入框里那截下划线拼音要一并清掉，
            // 否则会以「组合中」的状态挂在那里。
            syncComposingText("")
            candidateBar?.update("", emptyList(), 0, 0, false, false)
            AppLog.i(AppLog.Category.DEPLOY, "检测到词库重编译：销毁会话、暂停送键")
            if (!deployHintShown) {
                deployHintShown = true
                runCatching {
                    android.widget.Toast.makeText(
                        this,
                        "正在应用设置（重编译词库，约 20 秒）…",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            watchDeployEnd()
        }
        return true
    }

    /** 轮询等待部署结束（不阻塞主线程），结束后由 [guardDeploying] 恢复。 */
    private fun watchDeployEnd() {
        val tick = object : Runnable {
            override fun run() {
                if (!RimeEngine.isDeploying) {
                    guardDeploying()
                    return
                }
                eggHandler.postDelayed(this, 500L)
            }
        }
        eggHandler.postDelayed(tick, 500L)
    }

    private fun sendToRime(keycode: Int, modifier: Int) {
        if (guardDeploying()) return
        // 大写锁定 / 一次性大写：直接输出大写字母，**不进拼音**（不产生任何候选）
        val wantUpper = capsLocked || shiftActive
        if (wantUpper && keycode in 'a'.code..'z'.code) {
            insertPlainText(keycode.toChar().uppercaseChar().toString())
            if (shiftActive && !capsLocked) {
                shiftActive = false
                applyLayout()
            }
            return
        }
        // 英文键盘下若用户关闭“英文候选”，则直接上屏字母，不进入 Rime
        if (layoutType == LayoutType.ENGLISH && !prefs.englishCandidates && keycode in 0x20..0x7E) {
            val ch = keycode.toChar()
            insertPlainText(if (shiftActive) ch.uppercaseChar().toString() else ch.toString())
            return
        }
        // 自愈：会话缺失但引擎已就绪时，立刻补建，避免「能上屏但没有候选」
        if (session == 0L && RimeEngine.isReady) {
            AppLog.w(AppLog.Category.ENGINE, "会话缺失，按键时自动重建")
            createSessionIfNeeded()
        }
        if (session == 0L) {
            // 引擎未就绪：字母/数字按键退化为直接上屏，保证键盘始终可用
            if (keycode in 0x20..0x7E) {
                val ch = keycode.toChar()
                insertPlainText(if (shiftActive) ch.uppercaseChar().toString() else ch.toString())
            }
            return
        }
        val consumed = RimeEngine.processKey(session, keycode, modifier)
        if (consumed) {
            afterRimeInput()
        } else if (keycode in 0x20..0x7E) {
            val ch = keycode.toChar()
            insertPlainText(if (shiftActive) ch.uppercaseChar().toString() else ch.toString())
        }
    }

    private fun afterRimeInput() {
        // 先取提交内容，再刷新候选，避免上屏与候选错位
        val committed = RimeEngine.commit(session)
        if (!committed.isNullOrEmpty()) {
            commitToEditor(committed)
            // 上空完毕后给出联想词（选字 → 联想 → 再选 → 恢复工具栏）
            offerPrediction(committed)
        }
        updateCandidates()
        // Shift 只对下一个字符生效
        if (shiftActive && !shiftLocked) {
            shiftActive = false
            applyLayout()
        }
    }

    // ———— 候选重排（模糊音下让本音词与派生词交替出现）————
    /**
     * 显示顺序 → 引擎索引 的映射表；null 表示没重排（显示顺序即引擎顺序）。
     * 点击候选、长按候选都必须先映射回引擎索引，否则会选错词。
     */
    private var candidateOrder: IntArray? = null

    private fun toEngineIndex(display: Int): Int =
        candidateOrder?.getOrNull(display) ?: display

    private fun toDisplayIndex(engine: Int): Int =
        candidateOrder?.indexOf(engine)?.takeIf { it >= 0 } ?: engine

    // ———— 冷词菜单（长按候选）————
    private var coldWordMenu: android.widget.PopupWindow? = null

    private fun hideColdWordMenu() {
        coldWordMenu?.let { runCatching { it.dismiss() } }
        coldWordMenu = null
    }

    /**
     * 长按候选弹出的小菜单：降频 / 隐藏 / 取消。
     * 关键点：菜单自身重画一次（measure → showAsDropDown），不做延迟动画，
     * 保证手指还按着时菜单就已经完整可见。
     */
    private fun showColdWordMenu(index: Int, centerX: Float) {
        val bar = candidateBar ?: return
        hideColdWordMenu()
        val st = KeyboardTheme.candidateStyle(this)
        val textColor = st.text
        val density = resources.displayMetrics.density
        val dpf = { v: Number -> v.toFloat() * density }
        val lineColor = (textColor and 0x00FFFFFF) or 0x22000000

        fun row(label: String, kind: Int): android.widget.TextView =
            android.widget.TextView(this).apply {
                text = label
                setTextColor(textColor)
                textSize = 15f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpf(18).toInt(), dpf(12).toInt(), dpf(30).toInt(), dpf(12).toInt())
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        (textColor and 0x00FFFFFF) or 0x33000000,
                    ),
                    null,
                    null,
                )
                setOnClickListener {
                    hideColdWordMenu()
                    coldWordAction(index, kind)
                }
            }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpf(14)
                setColor(st.barBg)
                setStroke(dpf(1).toInt().coerceAtLeast(1), lineColor)
            }
            setPadding(0, dpf(6).toInt(), 0, dpf(6).toInt())
            addView(row("降频（排到后面）", COLD_REDUCE))
            addView(row("隐藏（不再出现）", COLD_HIDE))
            addView(row("取消", COLD_CANCEL))
        }

        val pw = android.widget.PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = dpf(10)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(bar.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val mw = container.measuredWidth
        val mh = container.measuredHeight
        var x = (centerX - mw / 2f).toInt()
        x = x.coerceIn(0, (bar.width - mw).coerceAtLeast(0))
        coldWordMenu = pw
        runCatching { pw.showAsDropDown(bar, x, -(mh + dp(6))) }
        AppLog.i(AppLog.Category.UI, "长按候选 ${index + 1} → 冷词菜单（x=$x, 高=${mh}px）")
    }

    /** kind：0 降频（Control+j）、1 隐藏（Control+x）、2 取消。 */
    private fun coldWordAction(index: Int, kind: Int) {
        if (kind == COLD_CANCEL) return
        val s = session
        if (s == 0L) return
        // 先让引擎高亮该候选，再发送组合键（processor 依据当前候选工作）
        // 注意：菜单拿到的是**显示序号**，必须先映射回引擎序号
        RimeEngine.highlightOnPage(s, toEngineIndex(index))
        val key = if (kind == COLD_REDUCE) 'j'.code else 'x'.code
        val consumed = RimeEngine.processKey(s, key, RimeEngine.Modifier.CONTROL)
        val what = if (kind == COLD_REDUCE) "降频" else "隐藏"
        AppLog.i(
            AppLog.Category.INPUT,
            "冷词操作：$what 第 ${index + 1} 个候选（consumed=$consumed）",
        )
        afterRimeInput()
    }

    private fun handleSpace() {
        if (guardDeploying()) return
        if (session != 0L) {
            val consumed = RimeEngine.processKey(session, RimeEngine.Key.SPACE, 0)
            if (consumed) {
                afterRimeInput()
                return
            }
        }
        commitToEditor(" ")
    }

    private fun handleBackspace() {
        // 部署期间不碰引擎，但退格仍要能删掉已经上屏的字
        if (RimeEngine.blockingInput) {
            val ic = currentInputConnection
            if (ic != null) {
                if (!ic.getSelectedText(0).isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            return
        }
        if (session != 0L) {
            val ctx = RimeEngine.context(session)
            if (ctx != null && (ctx.preedit.isNotEmpty() || ctx.candidates.isNotEmpty())) {
                RimeEngine.processKey(session, RimeEngine.Key.BACKSPACE, 0)
                afterRimeInput()
                return
            }
        }
        val ic: InputConnection = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            // 光标前取一小段，按「字形簇」整体删一个 ——
            // 表情占 2 个及以上 UTF-16 单元（😀=2、👨‍👩‍👧‍👦=十几个），
            // 以前写死删 1 个单元，所以删表情要点好几次。
            val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
            if (before.isEmpty()) {
                ic.deleteSurroundingText(1, 0)
            } else {
                val start = TextSegmenter.backspaceUnitStart(before)
                ic.deleteSurroundingText((before.length - start).coerceAtLeast(1), 0)
            }
        }
        // 删完必须刷新候选：英文候选是「按光标前的字母做前缀补全」，
        // 不刷新的话删掉字母后候选栏还停在上一个词（点删除候选词不动的根因）。
        updateCandidates()
    }

    private fun handleEnter() {
        if (guardDeploying()) return
        // 有正在输入的组合串时，回车把**原始拼音**上屏（nihao），而不是候选词里的字
        if (session != 0L) {
            val ctx = RimeEngine.context(session)
            val raw = ctx?.preedit?.replace(" ", "")?.replace("'", "").orEmpty()
            if (raw.isNotEmpty()) {
                // 让 Rime 丢弃组合状态，再把干净的拼音字母写进输入框
                RimeEngine.clearComposition(session)
                commitToEditor(raw)
                lastPreedit = ""
                lastCandidates = emptyList()
                candidateBar?.update("", emptyList(), 0, 0, false, false)
                updateCandidates()
                return
            }
            // 没有组合串时才走正常的提交逻辑
            RimeEngine.commitComposition(session)
            RimeEngine.commit(session)?.let { if (it.isNotEmpty()) commitToEditor(it) }
            updateCandidates()
        }
        // 回车键：优先执行输入框要求的动作（搜索 / 发送 / 前往 / 完成…），
        // 没有动作或明确要求换行时才插入换行。
        // （以前一律 commitText("\n")，在单行搜索框里会被应用当成一个空格 —— 就是「回车变空格」的来源）
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        val noEnterAction = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val actionId = if (info == null) EditorInfo.IME_ACTION_NONE
        else info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (info != null && !noEnterAction && actionId != EditorInfo.IME_ACTION_NONE) {
            if (!ic.performEditorAction(actionId)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            AppLog.i(AppLog.Category.INPUT, "回车执行输入框动作：action=$actionId")
        } else {
            ic.commitText("\n", 1)
            AppLog.i(AppLog.Category.INPUT, "回车插入换行")
        }
    }

    private fun toggleShift() {
        if (shiftActive && !shiftLocked) {
            shiftLocked = true
        } else if (shiftLocked) {
            shiftLocked = false
            shiftActive = false
        } else {
            shiftActive = true
        }
        applyLayout()
    }

    private fun toggleChineseEnglish() {
        // 中英切换同样是换输入场景：邮箱候选状态作废
        exitEmailMode(block = true)
        layoutType = if (layoutType == LayoutType.CHINESE_PINYIN) {
            LayoutType.ENGLISH
        } else {
            LayoutType.CHINESE_PINYIN
        }
        if (session != 0L) {
            RimeEngine.commitComposition(session)
            RimeEngine.commit(session)?.let { if (it.isNotEmpty()) commitToEditor(it) }
            RimeEngine.setOption(session, "ascii_mode", layoutType == LayoutType.ENGLISH)
        }
        applyLayout()
        updateCandidates()
        AppLog.i(AppLog.Category.INPUT, "切换到 ${if (layoutType == LayoutType.CHINESE_PINYIN) "中文" else "英文"}")
    }

    private fun selectCandidate(index: Int) {
        if (session == 0L) return
        val ok = RimeEngine.selectCandidate(session, index)
        if (ok) {
            afterRimeInput()
        } else {
            AppLog.w(AppLog.Category.ENGINE, "选择候选失败：index=$index")
        }
    }

    private fun handlePage(next: Boolean) {
        if (session == 0L) return
        val ctx = RimeEngine.context(session)
        if (ctx == null) return
        if (next && !ctx.isLastPage) {
            RimeEngine.changePage(session, false)
        } else if (!next && ctx.pageNo > 0) {
            RimeEngine.changePage(session, true)
        } else if (next && ctx.candidates.isNotEmpty()) {
            // 已在最后一页：改为移动高亮，提供“查看更多候选”的实际效果
            RimeEngine.highlightOnPage(session, (ctx.highlighted + 1) % ctx.candidates.size)
        }
        updateCandidates()
    }

    // —— 上屏 ——

    private fun insertPlainText(text: String) {
        if (text.isEmpty()) return
        if (session != 0L) {
            // 上屏符号前先把未完成的组合提交，避免内容丢失
            RimeEngine.commitComposition(session)
            RimeEngine.commit(session)?.let { if (it.isNotEmpty()) commitToEditor(it) }
        }
        commitToEditor(text)
        lastTextCommitAt = SystemClock.uptimeMillis()
        if (text.length == 1) SymbolHistory.markUsed(text)
        // 用户刚用键盘打出一个 @：从这一刻起才进入邮箱候选模式（并解除上一次的关闭屏蔽）
        if (text.endsWith("@")) enterEmailMode()
        // 放在上屏之后刷新：邮箱候选项要看「光标前已经上屏」的文本（刚打的 @ 也要算进去）
        updateCandidates()
    }

    /**
     * 进入邮箱候选模式：**唯一入口**。
     *
     * 只有用户真的打出一个 @ 才会走这里；进入时记下是哪一个 @，
     * 并清掉之前的关闭标记，接着就可以用候选栏补全域名了。
     */
    private fun enterEmailMode() {
        val ic = currentInputConnection ?: return
        val before = runCatching { ic.getTextBeforeCursor(64, 0)?.toString() }.getOrNull() ?: return
        val match = emailTailRegex.find(before) ?: return
        emailMode = true
        emailBlocked = false
        emailLocalPart = match.groupValues[1] + "@"
        suggestionsDismissed = false
        AppLog.i(AppLog.Category.INPUT, "进入邮箱候选模式：$emailLocalPart")
    }

    private fun commitToEditor(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        lastTextCommitAt = SystemClock.uptimeMillis()
        // 组合状态下的学习：由 Rime 自身完成；这里不再记录任何输入文本
    }

    /**
     * 把 Rime 的拼音组合串同步到目标输入框。
     *
     * 主流输入法都是这么做的：拼音显示在**用户正在输入的输入框**里（系统会给它加下划线），
     * 而不是画在输入法自己的候选栏里。候选栏因此可以把整条高度用于候选词。
     */
    private fun syncComposingText(preedit: String) {
        val ic = currentInputConnection ?: return
        runCatching {
            if (preedit.isBlank()) {
                // 先清掉输入框里残留的组合文本，再结束组合。
                // 只调用 finishComposingText() 的话，组合文本会**变成普通文本留在输入框里**，
                // 于是删完拼音还要再删一次 —— 这正是「点了三下才删掉」的根因。
                ic.setComposingText("", 1)
                ic.finishComposingText()
            } else {
                // 输入框里的拼音按**音节分隔**显示（ni'hao），和主流输入法一致；
                // 分隔符只影响显示：回车 / 上屏用的是去掉分隔符的原始字母（见 handleEnter）。
                // 先归一化，避免引擎已经带 ' 时出现 ni''hao 这种双分隔符。
                val shown = preedit.replace(" ", "'").replace(Regex("'+"), "'")
                ic.setComposingText(shown, 1)
            }
        }
    }

    // —— 反馈 ——

    /**
     * 按键音分类：
     *  · 删除键（退格，含长按连删）→ DELETE 音；
     *  · 功能键（大小写 / 空格 / 回车 / 数字键盘页所有按键 / 数字页的表情与符号键 /
     *    按钮栏按钮 / 切页与面板类动作）→ ACTION 音；
     *  · 打字（26 个字母、符号页符号、表情上屏）→ CLICK 音。
     */
    private fun keySoundKind(key: KeyDef): KeySoundPlayer.Kind {
        val onNumbers = layoutType == LayoutType.NUMBERS
        return when (key.action) {
            is KeyAction.Backspace -> KeySoundPlayer.Kind.DELETE
            is KeyAction.Space, is KeyAction.Enter, is KeyAction.Shift ->
                KeySoundPlayer.Kind.ACTION
            is KeyAction.SwitchLayout, is KeyAction.OpenPanel, is KeyAction.BackToKeyboard,
            is KeyAction.HideKeyboard, is KeyAction.OpenSettings,
            is KeyAction.ToggleChineseEnglish, is KeyAction.SymbolCategory,
            is KeyAction.PageCandidates, is KeyAction.None ->
                KeySoundPlayer.Kind.ACTION
            // 数字键（0-9）永远用与 26 字母一致的「打字音」；
            // 数字键盘页上的其它键（符号 / 表情等）仍算功能音。
            else -> {
                val code = (key.action as? KeyAction.Rime)?.keycode
                if (code != null && code in '0'.code..'9'.code) {
                    KeySoundPlayer.Kind.CLICK
                } else if (onNumbers) {
                    KeySoundPlayer.Kind.ACTION
                } else {
                    KeySoundPlayer.Kind.CLICK
                }
            }
        }
    }

    /** 候选栏点击（选词 / 剪贴板候选上屏）统一播「打字音」，与 26 字母一致。 */
    private fun playTypeSound() {
        val settings = prefs
        if (!settings.soundEnabled) return
        val volume = (settings.keySoundVolume / 100f).coerceIn(0f, 1f)
        if (volume <= 0f) return
        if (KeySoundPlayer.isReady) KeySoundPlayer.play(KeySoundPlayer.Kind.CLICK, volume)
    }
    /** 按钮栏按钮（品牌 / 深浅切换 / 中英 / 面板 / 收起键盘）统一播「功能键」音效。 */
    private fun playToolbarSound() {
        val settings = prefs
        if (!settings.soundEnabled) return
        val volume = (settings.keySoundVolume / 100f).coerceIn(0f, 1f)
        if (volume <= 0f) return
        if (KeySoundPlayer.isReady) KeySoundPlayer.play(KeySoundPlayer.Kind.ACTION, volume)
    }

    private fun playFeedback(key: KeyDef) {
        val settings = prefs
        if (settings.soundEnabled) {
            // 三种内置音效，音量由「按键音音量」设置统一控制（0 为静音）
            val volume = (settings.keySoundVolume / 100f).coerceIn(0f, 1f)
            if (KeySoundPlayer.isReady) {
                KeySoundPlayer.play(keySoundKind(key), volume)
            } else if (volume > 0f) {
                // 声音还没加载好时退回系统音效，避免完全没声音
                runCatching {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.playSoundEffect(AudioManager.FX_KEY_CLICK, volume)
                }
            }
        }
        if (settings.vibrateEnabled) {
            vibrateFeedback()
        }
    }

    /**
     * 震动反馈。
     * 按键、点击候选词、点击联想词都会走这里（用户要求候选词点击也要有震动）。
     */
    private fun vibrateFeedback() {
        val settings = prefs
        if (!settings.vibrateEnabled) return
        runCatching {
            val vibe = vibrator() ?: return@runCatching
            if (!vibe.hasVibrator()) return@runCatching
            val amplitude = strengthToAmplitude(settings.vibrateStrength)
            val duration = strengthToDuration(settings.vibrateStrength)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibe.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibe.vibrate(15L)
            }
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    companion object {
        /**
         * 按钮栏（工具栏 / 候选栏）高度。
         * 候选栏需要容纳两行（上方小字拼音 + 下方加粗候选词），因此比单行工具栏高。
         */
        private const val BAR_HEIGHT_DP = 50

        /** 快速双击大写键的判定窗口（毫秒）。 */
        private const val SHIFT_DOUBLE_TAP_MS = 320L
        /** 长按候选弹出的冷词菜单：三种动作。 */
        private const val COLD_REDUCE = 0
        private const val COLD_HIDE = 1
        private const val COLD_CANCEL = 2

        /** 彩蛋：长按最左侧「设置」图标多久算触发。 */
        private const val EGG_HOLD_MS = 2000L
        /** 彩蛋期间左侧按钮那圈「只逆时针、固定平缓」的旋转时长（0.6 秒一圈）。 */
        private const val BRAND_EGG_SPIN_MS = 600L

        /**
         * 键盘区的最大行数：用它算出统一的键盘区高度，行数较少的布局会自动把按键加高填满。
         * 恢复为 5 行（也就是之前的高度，字母键更高更好按）。
         */
        private const val KEYBOARD_MAX_ROWS = 5
    }

    // —— 工具 ——

    /**
     * 从键盘打开 App 自己（左按钮 / 面板里的「打开设置」都走这里）。
     *
     * 关键点：**统一打开主入口 MainActivity**，并带上
     * `NEW_TASK | SINGLE_TOP | CLEAR_TOP`。
     *
     * 以前这里开的是 settings.SettingsActivity（另一个 Activity），
     * 于是「已经在 App 主页时再点左按钮」会**又叠出一个新的主页面**（返回键还要多按一次）。
     * 现在：已经在那一页 → 新 Intent 只是交给现有实例（页面没变 = 没反应）；
     * 不在那一页 → 把现有任务提到前面并切到指定页。
     */
    private fun openApp(page: String) {
        runCatching {
            startActivity(
                Intent().apply {
                    setClassName(packageName, "com.xfgken.icetype.MainActivity")
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                    putExtra("page", page)
                },
            )
        }.onFailure { AppLog.throwable(AppLog.Category.ERROR, "从键盘打开设置失败", it) }
    }

    private fun isPasswordInput(info: EditorInfo?): Boolean {
        if (info == null) return false
        val type = info.inputType and InputType.TYPE_MASK_CLASS
        if (type == InputType.TYPE_CLASS_TEXT) {
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        if (type == InputType.TYPE_CLASS_NUMBER) {
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return false
    }

    private fun describeInputType(info: EditorInfo?): String {
        if (info == null) return "未知"
        val type = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (type) {
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> "文本-密码"

                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> "文本-邮箱"

                InputType.TYPE_TEXT_VARIATION_URI -> "文本-网址"
                InputType.TYPE_TEXT_VARIATION_PHONETIC -> "文本-拼音"
                else -> "文本"
            }

            InputType.TYPE_CLASS_NUMBER -> if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                "数字-密码"
            } else {
                "数字"
            }

            InputType.TYPE_CLASS_PHONE -> "电话"
            InputType.TYPE_CLASS_DATETIME -> "日期时间"
            else -> "其他"
        }
    }
}