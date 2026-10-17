package com.xfgken.iceime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.xfgken.iceime.core.AppLog
import kotlin.math.hypot

/**
 * 键盘绘制与触摸处理（自定义 View）。
 *
 * 选择自定义 View 而非 Compose 的原因（对应文档 1 节对 IME UI 的建议）：
 * 输入法窗口生命周期与 Compose 组合器的重建时机耦合较差，自绘 View 在
 * 按键反馈、连续输入、长按重复与多布局切换上更可控、更省内存。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 键盘配色与尺寸（由主题系统提供）。 */
    data class Style(
        val keyHeightPx: Int,
        val gapPx: Int,
        val cornerPx: Float,
        val keyboardBg: Int,
        val keyBg: Int,
        val funcKeyBg: Int,
        val primaryBg: Int,
        val primaryContent: Int,
        val keyText: Int,
        val accent: Int,
        val outline: Int,
        /** M3 `onSurface`：普通/功能键按下状态层所用的内容色。 */
        val onSurface: Int,
    )

    var style: Style = Style(
        keyHeightPx = dp(52),
        gapPx = dp(6),
        cornerPx = dp(12).toFloat(),
        keyboardBg = Color.TRANSPARENT,
        keyBg = Color.WHITE,
        funcKeyBg = Color.LTGRAY,
        primaryBg = Color.BLUE,
        primaryContent = Color.WHITE,
        keyText = Color.BLACK,
        accent = Color.BLUE,
        outline = Color.TRANSPARENT,
        onSurface = Color.BLACK,
    )
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var rows: List<KeyRow> = emptyList()
        private set

    /**
     * 键盘区固定高度（像素），由输入法服务按「最大行数 × 单键高度」统一算出。
     * 设为 >0 后，各布局的总高度完全一致；行数少的布局，其按键会自动变高。
     */
    var fixedHeightPx: Int = 0
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** 当前实际使用的单键高度（由固定高度与行数推导）。 */
    private var effectiveKeyHeightPx: Int = 0

    /** 按键回调。 */
    var onKey: ((KeyDef) -> Unit)? = null

    /** 盲打模式：隐藏 26 个字母键上的字母标签（按键仍在原位，只是不显示字母）。 */
    var hideLetterLabels: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 触摸反馈（声音/震动）。 */
    var onKeyFeedback: ((KeyDef) -> Unit)? = null

    /**
     * 一次触摸被判定为「滑动 / 误触」时回调，用来把按下瞬间已经输入的那个字符撤掉。
     *
     * 判定条件（都必须满足，尽量保守）：
     *  · 按下即输入的那个键确实是字符类（字母 / 数字 / 符号 / 空格）；
     *  · 手指明确滑到过另一个按键；
     *  · 位移超过 1.5 个按键高度。
     * 三者同时成立基本上就是「顺手划过去」，不是打字。
     */
    var onSwipeCancel: (() -> Unit)? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldTypeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    private val hitRects = ArrayList<RectF>()
    private var pressedIndex = -1

    /** 符号键盘底部类别栏的横向滚动偏移（只有可滚动行用到）。 */
    private var categoryScrollX = 0f

    /** 可滚动类别栏在 hitRects 里的下标范围（用于：不给它震动、知道它是不是分类键）。 */
    private var scrollRowStart = -1
    private var scrollRowEnd = -1

    /** 每个按键的绘制透明度（可滚动行靠近边缘时渐变淡出）。 */
    private val keyAlphas = HashMap<Int, Float>()

    /** 惯性滑动。 */
    private val flingScroller = android.widget.OverScroller(context)
    private val moveSamples = java.util.ArrayDeque<Pair<Long, Float>>()
    private var lastLoggedEdge = -1f
    private val flingRunner = object : Runnable {
        override fun run() {
            if (!flingScroller.computeScrollOffset()) return
            categoryScrollX = flingScroller.currX.toFloat()
            computeHitRects(width, log = false)
            invalidate()
            postDelayed(this, 16L)
        }
    }

    private fun stopFling() {
        flingScroller.abortAnimation()
        removeCallbacks(flingRunner)
    }

    /** 抬手时根据最后 120ms 的移动算出手指速度（px/s）。 */
    private fun dragVelocity(): Float {
        val now = SystemClock.uptimeMillis()
        while (moveSamples.isNotEmpty() && now - moveSamples.first().first > 120L) {
            moveSamples.removeFirst()
        }
        if (moveSamples.size < 2) return 0f
        val first = moveSamples.first()
        val last = moveSamples.last()
        val dt = (last.first - first.first).coerceAtLeast(1L)
        return (last.second - first.second) * 1000f / dt
    }

    /** 按手指速度继续滑一段（惯性滑动）。 */
    private fun startFling(velocityX: Float) {
        val max = scrollMaxX.toInt()
        if (max <= 0) return
        flingScroller.fling(
            categoryScrollX.toInt(),
            0,
            (-velocityX).toInt(),
            0,
            0,
            max,
            0,
            0,
        )
        removeCallbacks(flingRunner)
        post(flingRunner)
    }

    private fun inScrollRow(index: Int): Boolean =
        scrollRowStart >= 0 && index >= scrollRowStart && index < scrollRowEnd

    /** 按下点坐标与「本次触摸是否已经输入过字符」，用于识别滑动误触。 */
    private var downX = 0f
    private var downY = 0f
    // 触摸涟漪（M3 ripple）：从按下点扩散的状态层
    private var rippleRunning = false
    private var rippleStart = 0L
    private var rippleCx = 0f
    private var rippleCy = 0f

    /**
     * 布局是否需要重算。
     *
     * 关键：命中/绘制矩形以前只在 onSizeChanged 里算，而**切换布局时 view 尺寸并没有变化**
     * （高度是统一的固定值），于是新布局的按键会被塞进上一个布局遗留的格子里
     * （典型现象：数字键盘画出来是字母键盘那种 10 / 9 个一行的结构）。
     * 现在改成按需重算，行数或宽度一变就刷新。
     */
    private var layoutDirty = true

    /** 确保命中矩形与当前 rows / 尺寸一致。 */
    private fun ensureLayout() {
        if (layoutDirty && width > 0) {
            computeHitRects(width)
            layoutDirty = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // —— 彩蛋：按键疯狂旋转 ——
    private var eggActive = false
    private var eggStartAt = 0L
    private var eggDir: FloatArray = FloatArray(0)
    private var eggSpeed: FloatArray = FloatArray(0)
    private val eggRandom = java.util.Random()

    // 收尾（平滑停到 360° 整数倍）用的状态
    private var eggStopping = false
    private var eggStopStartAt = 0L
    private var eggStopFrom: FloatArray = FloatArray(0)
    private var eggStopTo: FloatArray = FloatArray(0)
    private val EGG_SETTLE_MS = 260L

    /** 彩蛋：键盘上**所有**按键都参与旋转（字母、数字、逗号等符号、功能键一起转）。 */
    private fun isEggKey(key: KeyDef): Boolean = true

    /**
     * 开始彩蛋：**持续旋转**，直到调用 [stopEgg]（服务端由"再长按 2 秒"触发关闭）。
     * 每键随机方向 + 随机速度（360~960°/s，很快）。
     */
    /** 彩蛋是否正在旋转（服务端在输入视图被重建后判断要不要重新挂上）。 */
    fun isEggActive(): Boolean = eggActive

    fun startEgg() {
        val keys = flatKeys()
        eggDir = FloatArray(keys.size) { if (eggRandom.nextBoolean()) 1f else -1f }
        eggSpeed = FloatArray(keys.size) { (6 + eggRandom.nextInt(11)) * 60f }
        eggStartAt = System.currentTimeMillis()
        eggStopping = false
        eggActive = true
        invalidate()
    }

    /**
     * 停止彩蛋：不瞬间归零（会"啪"一下跳），而是继续转 260ms 平滑停到 **360° 的整数倍**，
     * 因为整数倍角度和 0° 看起来完全一样，所以最后归零看不出任何跳变。
     */
    fun stopEgg() {
        if (!eggActive || eggStopping) return
        val keys = flatKeys()
        val from = FloatArray(keys.size) { currentEggAngle(it) }
        eggStopFrom = from
        eggStopTo = FloatArray(keys.size) { i -> Math.round(from[i] / 360f) * 360f }
        eggStopStartAt = System.currentTimeMillis()
        eggStopping = true
        invalidate()
    }

    /** 某个键此刻的角度（持续旋转模式）。 */
    private fun currentEggAngle(index: Int): Float {
        if (index >= eggDir.size) return 0f
        val t = (System.currentTimeMillis() - eggStartAt) / 1000f
        return eggDir[index] * eggSpeed[index] * t
    }

    fun setRows(newRows: List<KeyRow>) {
        rows = newRows
        pressedIndex = -1
        // 换布局（中英切换、切符号页等）时硬清掉长按状态，
        // 拉长动画立即归零，避免残留的「拉长」画在别的键上
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        longPressFired = false
        stretchAnimating = false
        stretchProgress = 0f
        longPressIndex = -1
        handler.removeCallbacks(stretchRippleStep)
        stretchRippleRunning = false
        layoutDirty = true
        ensureLayout()
        requestLayout()
        invalidate()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 按 M3 状态层的方式给颜色加透明度（只用 RGB，忽略原 alpha）。 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = rows.size.coerceAtLeast(1)
        // 行数 / 尺寸变化都要重算格位
        layoutDirty = true
        if (fixedHeightPx > 0) {
            // 统一高度：按键高度按行数自适应，保证 4 行 / 5 行布局的总高一致。
            // 首行只留 2dp 的上间距（候选词与第一行按键之间留一点点呼吸感）。
            val gaps = rowCount * style.gapPx + dp(3)
            effectiveKeyHeightPx = ((fixedHeightPx - gaps) / rowCount).coerceAtLeast(dp(28))
            setMeasuredDimension(width, fixedHeightPx)
        } else {
            effectiveKeyHeightPx = style.keyHeightPx
            val content = rowCount * style.keyHeightPx + (rowCount + 1) * style.gapPx
            setMeasuredDimension(width, content + paddingTop + paddingBottom)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeHitRects(w)
        layoutDirty = false
    }

    private fun computeHitRects(width: Int, log: Boolean = true) {
        hitRects.clear()
        keyAlphas.clear()
        scrollRowStart = -1
        scrollRowEnd = -1
        val keyHeight = if (effectiveKeyHeightPx > 0) effectiveKeyHeightPx else style.keyHeightPx
        val left0 = paddingLeft + style.gapPx
        val right0 = width - paddingRight - style.gapPx
        val available = (right0 - left0).toFloat()
        // 首行只留 3dp 上间距（与 onMeasure 的计算一致）
        var y = paddingTop + dp(3).toFloat()
        for (row in rows) {
            if (row.scrollable) {
                layoutScrollableRow(row, left0.toFloat(), y, keyHeight, width)
                y += keyHeight + style.gapPx
                continue
            }
            val totalWeight = row.leadingPad + row.trailingPad +
                row.keys.sumOf { it.weight.toDouble() }.toFloat()
            if (totalWeight <= 0f) continue
            val unit = available / totalWeight
            var x = left0 + unit * row.leadingPad
            for (key in row.keys) {
                val w = unit * key.weight
                hitRects.add(
                    RectF(
                        x + style.gapPx / 2f,
                        y,
                        x + w - style.gapPx / 2f,
                        y + keyHeight,
                    ),
                )
                x += w
            }
            y += keyHeight + style.gapPx
        }
        // 诊断：把「第一个按键（字母键盘就是 Q）的左边缘」打出来，
        // 用于和候选栏的左留白对比（两者应当完全相等）。
        hitRects.firstOrNull()?.let {
            // 只在真正变化时才记日志（滑动 / 惯性每帧都会重算，不能刷屏）
            if (log && kotlin.math.abs(it.left - lastLoggedEdge) > 0.5f) {
                lastLoggedEdge = it.left
                AppLog.i(
                    AppLog.Category.UI,
                    "键盘首键左边缘=%.1fpx（宽 %dpx，gap=%dpx，leadingPad=%.1f）".format(
                        it.left,
                        width,
                        style.gapPx,
                        rows.firstOrNull()?.leadingPad ?: 0f,
                    ),
                )
            }
        }
    }

    /**
     * 布局「可滚动行」（符号键盘底部类别栏）。
     *
     *  · 前 [KeyRow.fixedCount] 个按键固定不动（返回键），宽度按 weight 算；
     *  · 其余按键按内容自适应宽度排成一条长条，整体受 [categoryScrollX] 偏移控制；
     *  · 屏幕外的按键会得到**空矩形**：既画不出来也点不到。
     */
    private fun layoutScrollableRow(
        row: KeyRow,
        left0: Float,
        y: Float,
        keyHeight: Int,
        width: Int,
    ) {
        val gap = style.gapPx.toFloat()
        var x = left0
        val fixed = row.keys.take(row.fixedCount)
        val strip = row.keys.drop(row.fixedCount)
        for (key in fixed) {
            val w = dp(56).toFloat() * key.weight
            hitRects.add(RectF(x + gap / 2f, y, x + w - gap / 2f, y + keyHeight))
            x += w
        }
        val stripLeft = x
        val stripRight = (width - paddingRight - style.gapPx).toFloat()
        val visible = (stripRight - stripLeft).coerceAtLeast(1f)
        // 每颗按键宽度按标签长度自适应（「括号/成对符号」这类长名字能完整显示）
        val widths = strip.map { key ->
            dp(56).toFloat() + dp(12).toFloat() * (key.label.length - 2).coerceAtLeast(0)
        }
        val content = widths.sum() + gap * (strip.size - 1).coerceAtLeast(0)
        scrollStripLeft = stripLeft
        scrollStripRight = stripRight
        scrollBandTop = y
        scrollBandBottom = y + keyHeight
        scrollMaxX = (content - visible).coerceAtLeast(0f)
        categoryScrollX = categoryScrollX.coerceIn(0f, scrollMaxX)
        // 类别栏按用户要求：**始终完整显示，不做任何渐变淡出**
        // （屏幕外的分类靠拖动滑出来，可见范围内的按键一律全不透明）
        scrollRowStart = hitRects.size
        var sx = stripLeft - categoryScrollX
        for ((i, key) in strip.withIndex()) {
            val w = widths[i]
            val l = sx
            val r = sx + w
            val cl = maxOf(l + gap / 2f, stripLeft)
            val cr = minOf(r - gap / 2f, stripRight)
            keyAlphas[hitRects.size] = 1f
            hitRects.add(if (cr > cl) RectF(cl, y, cr, y + keyHeight) else RectF(l, y, l, y))
            sx += w + gap
        }
        scrollRowEnd = hitRects.size
    }

    /**
     * 第一个按键的左边缘（字母键盘即 Q 键左边缘）。
     * 候选栏用它作为左留白，保证候选词的背景左边缘与 Q 键严格对齐。
     */
    fun firstKeyLeftEdgePx(): Float {
        ensureLayout()
        return hitRects.firstOrNull()?.left ?: 0f
    }

    private fun flatKeys(): List<KeyDef> = rows.flatMap { it.keys }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureLayout()
        canvas.drawColor(style.keyboardBg)
        // 彩蛋角度：持续旋转；关闭时平滑停到 360° 的整数倍（整数倍 = 看上去就是 0°）
        var eggT = 0f
        var settleP = -1f
        if (eggActive) {
            if (eggStopping) {
                settleP = ((System.currentTimeMillis() - eggStopStartAt) / EGG_SETTLE_MS.toFloat())
                    .coerceIn(0f, 1f)
                if (settleP >= 1f) {
                    eggActive = false
                    eggStopping = false
                    settleP = -1f
                }
            } else {
                eggT = (System.currentTimeMillis() - eggStartAt) / 1000f
            }
        }
        val keys = flatKeys()
        for (i in keys.indices) {
            if (i >= hitRects.size) break
            // 可滚动行里滑出屏幕的按键：矩形是空的，既不绘制也点不到
            if (hitRects[i].width() <= 0f) continue
            val keyAlpha = keyAlphas[i] ?: 1f
            if (keyAlpha <= 0.02f) continue
            val spin = when {
                !eggActive || i >= eggDir.size || !isEggKey(keys[i]) -> 0f
                settleP >= 0f -> {
                    val from = eggStopFrom.getOrElse(i) { 0f }
                    val to = eggStopTo.getOrElse(i) { 0f }
                    from + (to - from) * settleP
                }

                else -> eggDir[i] * eggSpeed[i] * eggT
            }
            // 类别栏边缘：按钮框保持完整，只让**文字**提前、平缓地淡出
            drawKey(canvas, hitRects[i], keys[i], i == pressedIndex, spin, keyAlpha)
        }
        // 长按逗号/句点：整个按钮**向上拉长**（带动画），拉长出来的上部显示备选标点
        val lip = longPressIndex
        if (lip >= 0 && lip < hitRects.size && lip < keys.size && stretchProgress > 0.01f &&
            hitRects[lip].width() > 0f && keys[lip].longPress.isNotEmpty()
        ) {
            drawStretchedKey(
                canvas,
                hitRects[lip],
                keys[lip],
                hitRects[lip].height() * 0.92f * stretchProgress,
                // 备选字符跟着拉长一起淡入，看着像"长出来"的
                (stretchProgress * 1.6f).coerceIn(0f, 1f),
            )
        }
        if (eggActive) postInvalidateOnAnimation()
    }

    /**
     * 长按弹出的「向上拉长」按键：
     * 整个按键矩形向上变高，原字符仍在原位，拉长出来的上部显示备选字符。
     */
    private fun drawStretchedKey(canvas: Canvas, rect: RectF, key: KeyDef, stretch: Float, altAlpha: Float) {
        val box = RectF(rect.left, rect.top - stretch, rect.right, rect.bottom)
        val bg = when {
            key.isPrimary -> style.primaryBg
            key.isFunction -> style.funcKeyBg
            else -> style.keyBg
        }
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = bg
        canvas.drawRoundRect(box, style.cornerPx, style.cornerPx, keyPaint)
        // 长按不用「整块压暗」的按下状态层，改成从触点扩散的涟漪（与普通按键手感一致）
        if (stretchRippleRunning) {
            val progress = ((SystemClock.uptimeMillis() - stretchRippleStart) / STRETCH_RIPPLE_MS)
                .coerceIn(0f, 1f)
            val maxRadius = maxOf(
                hypot(rippleCx - box.left, rippleCy - box.top),
                hypot(rippleCx - box.right, rippleCy - box.top),
                hypot(rippleCx - box.left, rippleCy - box.bottom),
                hypot(rippleCx - box.right, rippleCy - box.bottom),
            )
            val radius = maxRadius * progress
            val alpha = ((1f - progress) * 32).toInt().coerceAtLeast(0)
            if (alpha > 0 && radius > 0f) {
                val save = canvas.save()
                val clip = android.graphics.Path().apply {
                    addRoundRect(box, style.cornerPx, style.cornerPx, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clip)
                keyPaint.color = withAlpha(
                    if (key.isPrimary) style.primaryContent else style.onSurface,
                    alpha,
                )
                canvas.drawCircle(rippleCx, rippleCy, radius, keyPaint)
                canvas.restoreToCount(save)
            }
        }
        // 原字符保持原位
        drawKeyLabel(canvas, key.label, rect.centerX(), rect.centerY(), rect, 1f)
        // 拉长出来的上部：备选字符（跟着动画淡入）
        if (altAlpha > 0.02f) {
            drawKeyLabel(
                canvas,
                key.longPress,
                rect.centerX(),
                box.top + stretch * 0.5f,
                rect,
                altAlpha,
            )
        }
    }

    /** 在指定位置画一个按键文字（与 drawKey 里的字号规则保持一致）。 */
    private fun drawKeyLabel(canvas: Canvas, label: String, cx: Float, cy: Float, rect: RectF, alpha: Float) {
        if (label.isEmpty() || alpha <= 0.02f) return
        val byWidth = rect.width() / (label.length.coerceAtLeast(1) * 1.05f + 0.6f)
        val size = minOf(rect.height() * 0.44f, byWidth).coerceIn(dp(10).toFloat(), dp(22).toFloat())
        textPaint.color = style.keyText
        textPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        textPaint.typeface = normalTypeface
        textPaint.textSize = size
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    private fun drawKey(
        canvas: Canvas,
        rect: RectF,
        key: KeyDef,
        pressed: Boolean,
        spin: Float = 0f,
        textAlpha: Float = 1f,
    ) {
        val bg = when {
            key.isPrimary -> style.primaryBg
            key.isFunction -> style.funcKeyBg
            else -> style.keyBg
        }
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = bg
        canvas.drawRoundRect(rect, style.cornerPx, style.cornerPx, keyPaint)

        val content = when {
            key.isPrimary -> style.primaryContent
            key.active -> style.accent
            else -> style.keyText
        }

        // M3 状态层：按下时叠加「内容色」的 10%（StateTokens.PressedStateLayerOpacity）
        if (pressed) {
            keyPaint.color = withAlpha(if (key.isPrimary) style.primaryContent else style.onSurface, 26)
            canvas.drawRoundRect(rect, style.cornerPx, style.cornerPx, keyPaint)
        }
        if (key.active) {
            keyPaint.color = withAlpha(style.accent, 48)
            canvas.drawRoundRect(rect, style.cornerPx, style.cornerPx, keyPaint)
        }

        // 触摸涟漪：从按下点向外扩散的状态层（与 M3 ripple 的表现一致）
        if (pressed && rippleRunning) {
            val progress = ((System.currentTimeMillis() - rippleStart).toFloat() / RIPPLE_DURATION_MS)
                .coerceIn(0f, 1f)
            val maxRadius = maxOf(
                hypot(rippleCx - rect.left, rippleCy - rect.top),
                hypot(rippleCx - rect.right, rippleCy - rect.top),
                hypot(rippleCx - rect.left, rippleCy - rect.bottom),
                hypot(rippleCx - rect.right, rippleCy - rect.bottom),
            )
            val radius = maxRadius * progress
            val alpha = ((1f - progress) * 26).toInt().coerceAtLeast(0)
            if (alpha > 0 && radius > 0f) {
                val save = canvas.save()
                val clip = android.graphics.Path().apply {
                    addRoundRect(rect, style.cornerPx, style.cornerPx, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clip)
                keyPaint.color = withAlpha(if (key.isPrimary) style.primaryContent else style.onSurface, alpha)
                canvas.drawCircle(rippleCx, rippleCy, radius, keyPaint)
                canvas.restoreToCount(save)
            }
            postInvalidateOnAnimation()
        }

        // —— 内容（图标 / 文字）：彩蛋时绕按键中心旋转 ——
        val spinSave = if (spin != 0f) {
            canvas.save().also { canvas.rotate(spin, rect.centerX(), rect.centerY()) }
        } else {
            -1
        }

        if (key.icon != null) {
            val d = ContextCompat.getDrawable(context, key.icon)
            val size = (minOf(rect.height() * 0.52f, rect.width() * 0.62f)).toInt()
                .coerceAtMost(dp(26))
            val cx = rect.centerX().toInt()
            val cy = rect.centerY().toInt()
            d?.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            d?.setTint(content)
            if (textAlpha < 0.99f) d?.alpha = (textAlpha * 255f).toInt().coerceIn(0, 255)
            d?.draw(canvas)
            if (textAlpha < 0.99f && d != null) d.alpha = 255
            if (spinSave >= 0) canvas.restoreToCount(spinSave)
            return
        }

        val label = key.label
        if (label.isEmpty()) {
            textPaint.alpha = 255
            if (spinSave >= 0) canvas.restoreToCount(spinSave)
            return
        }
        // 盲打模式：26 个字母键不画字母（数字 / 标点 / 功能键仍然显示）
        if (hideLetterLabels && label.length == 1 && (label[0] in 'a'..'z' || label[0] in 'A'..'Z')) {
            textPaint.alpha = 255
            if (spinSave >= 0) canvas.restoreToCount(spinSave)
            return
        }
        // 文字尺寸随键宽自适应，保证“空格 / 返回 / 中文标点”等长标签不溢出
        val byWidth = rect.width() / (label.length.coerceAtLeast(1) * 1.05f + 0.6f)
        val size = minOf(rect.height() * 0.44f, byWidth).coerceIn(dp(10).toFloat(), dp(22).toFloat())
        textPaint.color = content
        textPaint.alpha = (textAlpha * 255f).toInt().coerceIn(0, 255)
        textPaint.typeface = if (key.bold) boldTypeface else normalTypeface
        textPaint.textSize = size
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, rect.centerX(), baseline, textPaint)
        if (spinSave >= 0) canvas.restoreToCount(spinSave)
    }

    /**
     * 按键触发时机：**统一为「抬手触发」**。
     *
     * 按下只做视觉 / 触感反馈（高亮 + 涟漪 + 震动），抬手时手指还在这个键上才上屏。
     * 这样打快了不会因为指尖漂移而错键，也不会因为“按下即上屏”而多字；
     * 抬手时如果只是轻微滑到缝隙（没滑到别的键），仍然算按下那个键，避免漏打。
     *
     * 多指：每个手指单独记录它按下的键，互不影响 —— 两根手指交替快速打字不会漏键。
     */
    private val downKeys = HashMap<Int, Int>()
    private val pointerEnteredOther = HashSet<Int>()
    private var repeatFired = false
    // —— 长按「向上拉长」的按键（中文「，」/ 英文「,」）：拉长后松手上屏备选标点 ——
    /** 正在向上拉长的按键下标，-1 表示没有。 */
    private var longPressIndex = -1
    private var longPressRunnable: Runnable? = null
    /** 是否已经真的触发过长按（决定松手时上屏哪个字符）。 */
    private var longPressFired = false
    // 拉长动画：0 = 收起，1 = 完全拉长（用缓动逐帧推进，不是瞬间弹出）
    private var stretchProgress = 0f
    private var stretchFrom = 0f
    private var stretchTo = 0f
    private var stretchAnimStart = 0L
    private var stretchAnimDur = 1f
    private var stretchAnimating = false
    // 长按拉长时从触点扩散的涟漪（替代"整块压暗"的按下状态层）
    private var stretchRippleRunning = false
    private var stretchRippleStart = 0L
    private val stretchRippleStep = object : Runnable {
        override fun run() {
            val p = ((SystemClock.uptimeMillis() - stretchRippleStart) / STRETCH_RIPPLE_MS)
                .coerceIn(0f, 1f)
            if (p >= 1f) {
                stretchRippleRunning = false
                invalidate()
                return
            }
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    // —— 符号键盘底部类别栏的拖动状态 ——
    private var scrollPointerId = -1
    private var scrollStartX = 0f
    private var scrollStartOffset = 0f
    private var scrollDragging = false
    private var scrollBandTop = 0f
    private var scrollBandBottom = 0f
    private var scrollStripLeft = 0f
    private var scrollStripRight = 0f
    private var scrollMaxX = 0f

    /** 手指是否落在可滚动类别栏的纵向范围内。 */
    private fun inScrollBand(y: Float): Boolean =
        scrollMaxX > 0f && y >= scrollBandTop && y <= scrollBandBottom

    override fun onTouchEvent(event: MotionEvent): Boolean {
        ensureLayout()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val id = event.getPointerId(0)
                val index = indexOf(event.x, event.y)
                if (index < 0) {
                    // 可能是按在类别栏的缝隙上（要当拖动起点），否则不拦截
                    if (!inScrollBand(event.y)) return false
                    stopFling()
                    downKeys[id] = -1
                    beginScrollGesture(id, event.x)
                    return true
                }
                val key = flatKeys().getOrNull(index) ?: return false
                stopFling()
                downKeys[id] = index
                pointerEnteredOther.clear()
                if (inScrollBand(event.y)) beginScrollGesture(id, event.x)
                pressedIndex = index
                rippleCx = event.x
                rippleCy = event.y
                rippleStart = System.currentTimeMillis()
                rippleRunning = true
                invalidate()
                // 触感反馈放在按下时给（手感更跟手），真正上屏等抬手；
                // 但底部类别栏例外：滑动时不能震，只有真的点中某个分类才震
                if (!inScrollRow(index)) onKeyFeedback?.invoke(key)
                if (key.repeatOnHold) {
                    repeatFired = false
                    startRepeat(key)
                }
                // 长按「，」/「,」：稍等片刻后整个按钮向上拉长，松手上屏句号 / 点号
                if (key.longPress.isNotEmpty()) startKeyLongPress(index, key)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val pi = event.actionIndex
                val id = event.getPointerId(pi)
                val index = indexOf(event.getX(pi), event.getY(pi))
                val key = if (index >= 0) flatKeys().getOrNull(index) else null
                if (key != null) {
                    downKeys[id] = index
                    onKeyFeedback?.invoke(key)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // ① 正在拖类别栏：只横向滚动，不触发任何按键
                val sid = scrollPointerId
                if (sid >= 0) {
                    val pi = event.findPointerIndex(sid)
                    if (pi >= 0) {
                        val dx = event.getX(pi) - scrollStartX
                        if (!scrollDragging && kotlin.math.abs(dx) > dp(6)) {
                            scrollDragging = true
                            pressedIndex = -1
                            stopRepeat()
                        }
                        if (scrollDragging) {
                            val now = SystemClock.uptimeMillis()
                            moveSamples.addLast(now to event.getX(pi))
                            while (moveSamples.size > 12) moveSamples.removeFirst()
                            categoryScrollX = (scrollStartOffset - dx).coerceIn(0f, scrollMaxX)
                            computeHitRects(width, log = false)
                            invalidate()
                            return true
                        }
                    }
                }
                // ② 普通滑动：只跟随高亮，不再连续上屏
                val index = indexOf(event.x, event.y)
                if (index >= 0 && index != pressedIndex) {
                    pointerEnteredOther.add(event.getPointerId(0))
                    pressedIndex = index
                    rippleRunning = false
                    stopRepeat()
                    // 滑到别的键：取消「向上拉长」，避免松手时误上屏句号
                    cancelKeyLongPress()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pi = event.actionIndex
                releasePointer(event.getPointerId(pi), event.getX(pi), event.getY(pi), false)
                return true
            }

            MotionEvent.ACTION_UP -> {
                releasePointer(event.getPointerId(0), event.x, event.y, true)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                cancelKeyLongPress()
                moveSamples.clear()
                downKeys.clear()
                pointerEnteredOther.clear()
                scrollPointerId = -1
                scrollDragging = false
                pressedIndex = -1
                rippleRunning = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 开始一次「按在类别栏上」的手势（可能是点击分类，也可能是横向拖动）。 */
    private fun beginScrollGesture(id: Int, x: Float) {
        scrollPointerId = id
        scrollStartX = x
        scrollStartOffset = categoryScrollX
        scrollDragging = false
        moveSamples.clear()
        moveSamples.addLast(SystemClock.uptimeMillis() to x)
    }

    /**
     * 某一个手指抬起：决定要不要上屏。
     *
     *  · 抬手仍停在按下的那个键上 → 上屏；
     *  · 滑到过别的键、最后停在别的键上 → 不上屏（滑动不当作打字）；
     *  · 只是滑到缝隙里且没滑到别的键 → 仍算按下那个键（不漏打）；
     *  · 刚刚在拖类别栏 → 不上屏。
     */
    private fun releasePointer(id: Int, x: Float, y: Float, primary: Boolean) {
        val downIndex = downKeys.remove(id)
        val entered = pointerEnteredOther.remove(id) ?: false
        val scrolled = primary && scrollDragging
        val velocity = if (primary) dragVelocity() else 0f
        // 长按「向上拉长」中：松手就直接上屏备选标点（句号 / 点号），
        // 不再走「抬手仍停在原键才上屏」的判定
        val longCommit = if (primary && longPressFired && longPressIndex >= 0) {
            flatKeys().getOrNull(longPressIndex)?.takeIf { it.longPress.isNotEmpty() }
        } else {
            null
        }
        if (longCommit != null) {
            stopRepeat()
            cancelKeyLongPress()
            pressedIndex = -1
            rippleRunning = false
            scrollPointerId = -1
            scrollDragging = false
            invalidate()
            onKey?.invoke(longCommit.copy(action = KeyAction.Commit(longCommit.longPress)))
            AppLog.i(AppLog.Category.UI, "长按「${longCommit.label}」上屏「${longCommit.longPress}」")
            return
        }
        cancelKeyLongPress()
        if (primary) {
            stopRepeat()
            pressedIndex = -1
            rippleRunning = false
            scrollPointerId = -1
            scrollDragging = false
            invalidate()
        }
        if (scrolled) {
            // 按手指速度继续滑一段（惯性）
            startFling(velocity)
            return
        }
        val keys = flatKeys()
        val upIndex = indexOf(x, y)
        val upKey = if (upIndex >= 0) keys.getOrNull(upIndex) else null
        val targetIndex = when {
            upKey != null && downIndex != null && upIndex == downIndex -> upIndex
            upKey != null && entered -> -1
            upKey == null && !entered -> downIndex ?: -1
            else -> -1
        }
        if (targetIndex >= 0) {
            val target = keys.getOrNull(targetIndex)
            if (target != null) {
                // 类别栏：只有真的点中某个分类才震动（手指一划而过不震）
                if (inScrollRow(targetIndex)) onKeyFeedback?.invoke(target)
                // 长按重复已经发过就不再补一次（避免多删一个字）
                if (!(repeatFired && target.repeatOnHold)) onKey?.invoke(target)
            }
        }
        repeatFired = false
    }

    private fun startRepeat(key: KeyDef) {
        stopRepeat()
        val runnable = object : Runnable {
            override fun run() {
                repeatFired = true
                onKey?.invoke(key)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, REPEAT_INITIAL_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    /** 手指按下「，」/「,」后启动长按计时：到点就让整个按钮向上拉长（带动画）。 */
    private fun startKeyLongPress(index: Int, key: KeyDef) {
        cancelKeyLongPress()
        val runnable = object : Runnable {
            override fun run() {
                longPressFired = true
                longPressIndex = index
                // 从触点重新扩散一圈涟漪（不复用按下时那圈已经扩散完的）
                stretchRippleStart = SystemClock.uptimeMillis()
                stretchRippleRunning = true
                handler.removeCallbacks(stretchRippleStep)
                handler.postDelayed(stretchRippleStep, 16L)
                // 拉长出来的时候补一次触感，提示已经进入「备选标点」状态
                onKeyFeedback?.invoke(key)
                animateStretch(1f)
            }
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    /**
     * 取消长按：按钮**带动画缩回**（松手 / 滑到别的键 / 被系统打断时都会走这里）。
     *
     * 注意 [longPressFired] 会立刻复位，所以松手那条路径要先取好要上屏的键再调用本方法。
     */
    private fun cancelKeyLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        longPressFired = false
        // 收回时把拉长态那圈涟漪一起停掉
        handler.removeCallbacks(stretchRippleStep)
        stretchRippleRunning = false
        // 缩回动画跑完再把下标清掉；要是没有任何拉长就直接清
        if (stretchProgress <= 0.01f) {
            stretchProgress = 0f
            longPressIndex = -1
            invalidate()
        } else {
            animateStretch(0f)
        }
    }

    /** 拉长动画：to = 1 长出来（减速），to = 0 收回（加速）。 */
    private fun animateStretch(to: Float) {
        stretchFrom = stretchProgress
        stretchTo = to
        stretchAnimStart = SystemClock.uptimeMillis()
        stretchAnimDur = (if (to > stretchFrom) STRETCH_OUT_MS else STRETCH_BACK_MS).toFloat()
        if (!stretchAnimating) {
            stretchAnimating = true
            handler.post(stretchStep)
        }
    }

    private val stretchStep = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - stretchAnimStart) / stretchAnimDur).coerceIn(0f, 1f)
            val eased = if (stretchTo > stretchFrom) {
                // EmphasizedDecelerate：先快后慢（长出来）
                1f - (1f - t) * (1f - t) * (1f - t)
            } else {
                // EmphasizedAccelerate：先慢后快（收回去，干脆利落）
                t * t
            }
            stretchProgress = stretchFrom + (stretchTo - stretchFrom) * eased
            if (t >= 1f) {
                stretchProgress = stretchTo
                stretchAnimating = false
                if (stretchTo <= 0.01f) {
                    stretchProgress = 0f
                    longPressIndex = -1
                }
                invalidate()
                return
            }
            invalidate()
            // 用 16ms 逐帧推进（比 postOnAnimation 更稳，避免个别环境解析不到该 API）
            handler.postDelayed(this, 16L)
        }
    }

    private fun indexOf(x: Float, y: Float): Int {
        // 只承认「按在按键矩形内」的触摸。
        // 落在按键之间的缝隙（4dp）时返回 -1，也就是**不触发任何按键**——
        // 之前那句「取离触点最近的键」会让打快时误触发旁边的键（用户反馈不准），已去掉。
        for (i in hitRects.indices) {
            if (hitRects[i].contains(x, y)) return i
        }
        return -1
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        stopFling()
        super.onDetachedFromWindow()
    }


    companion object {
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
        /** 长按「，」/「,」多久后按钮向上拉长（手感接近系统键盘的长按）。 */
        private const val LONG_PRESS_MS = 300L
        /** 按钮向上拉长的动画时长（M3 EmphasizedDecelerate）。 */
        private const val STRETCH_OUT_MS = 180L
        /** 按钮收回去的动画时长（M3 EmphasizedAccelerate）。 */
        private const val STRETCH_BACK_MS = 140L
        /** 长按拉长时那圈涟漪的扩散时长。 */
        private const val STRETCH_RIPPLE_MS = 420f

        /** 涟漪扩散时长（M3 ripple 的展开约 300ms 量级）。 */
        private const val RIPPLE_DURATION_MS = 320f

    }
}