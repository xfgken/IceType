package com.xfgken.icetype.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.xfgken.icetype.R
import com.xfgken.icetype.rime.RimeEngine
import kotlin.math.abs

/**
 * 候选栏（与工具栏共用同一行）。
 *
 * 布局：
 *   ┌────────────────────────────────────────────┐
 *   │  ni hao                                    │ ← 拼音：小字、次要色，放在候选词上方
 *   │      1 你好  2 拟好  3 你  4 尼  5 呢  6 泥  │ ← 候选词：加粗加大、整行垂直居中
 *   └────────────────────────────────────────────┘
 *
 * 交互：
 *  · 点击候选词上屏；
 *  · **左右滑动**切换候选页（不再有翻页按钮，把空间留给候选词）；
 *  · 联想态：右下角一个纯圆 ✕ 用于关闭联想（不再显示「联想」占位文字）。
 */
class CandidateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Style(
        val heightPx: Int,
        val barBg: Int,
        val text: Int,
        val accent: Int,
        val outline: Int,
        /** 圆形按钮底（与工具栏的功能键底色一致，供联想态的 ✕ 使用）。 */
        val buttonBg: Int = Color.LTGRAY,
        /** 候选词高亮背景的圆角（像素），跟随「按键圆角」设置。 */
        val cornerPx: Float = 999f,
        /** 按钮栏按钮（含候选栏 ✕）的圆角（像素），跟随「按钮栏圆角」设置。 */
        val barCornerPx: Float = 999f,
        /** 候选词字号（像素），跟随「候选词大小」设置。 */
        val textSizePx: Float = 0f,
        /**
         * 候选栏左右内边距（像素）= 键盘第一行左右留白，
         * 这样**第一个候选词背景的左边缘正好和字母 Q 的左边缘对齐**。
         */
        val sidePadPx: Float = 0f,
    )

    var style: Style = Style(
        heightPx = dp(56),
        barBg = Color.WHITE,
        text = Color.BLACK,
        accent = Color.BLUE,
        outline = Color.LTGRAY,
    )
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
.apply { textAlign = Paint.Align.LEFT }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 右侧 ✕ 左侧的渐变遮罩画笔（让候选文字柔和淡出，而不是被生硬切断）。 */
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var preedit = ""
    private var candidates: List<RimeEngine.Candidate> = emptyList()
    private var selectKeys = ""
    private var highlighted = 0
    private var pageNo = 0
    private var hasPrevPage = false
    private var hasNextPage = false
    private var prediction = false

    private val candidateRects = ArrayList<RectF>()
    private var dismissRect = RectF()

    var onCandidate: ((Int) -> Unit)? = null
    var onPage: ((Boolean) -> Unit)? = null

    /** 联想态下点击右侧 ✕：结束联想并恢复工具栏。 */
    var onDismiss: (() -> Unit)? = null

    /** 候选态下点击右侧 ✕：清空当前正在输入的拼音。 */
    var onClear: (() -> Unit)? = null

    /** 长按候选：回调（候选序号、该候选在控件内的中心 X）。 */
    var onLongPressCandidate: ((Int, Float) -> Unit)? = null

    /** 滚动回调（仅用于诊断日志：确认横向滑动手势是否真的进来、可滚动范围有多大）。 */
    var onSwipe: ((dx: Float, maxScroll: Float) -> Unit)? = null

    // 滑动手势状态
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private var pressedCandidate = -1
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false

    /**
     * 横向滚动偏移（像素）。
     * 候选词是**一整条连续排列**的，左右滑动就是平滑滚动，不再有「翻一页闪一下」。
     */
private var scrollOffset = 0f
    private var maxScroll = 0f
    private var scrollStart = 0f

    // 惯性滑动：抬手后按当时的划动速度继续滑，再细腻地减速停下
    private val flingScroller = android.widget.OverScroller(context)
    private var velocityTracker: android.view.VelocityTracker? = null

    private val flingStep = object : Runnable {
        override fun run() {
            if (flingScroller.computeScrollOffset()) {
                scrollOffset = flingScroller.currX.toFloat()
                invalidate()
                postOnAnimation(this)
            } else {
                // 到边界时细腻地收进范围内（正常情况不会越界）
                val target = scrollOffset.coerceIn(0f, maxScroll)
                if (target != scrollOffset) {
                    val anim = android.animation.ValueAnimator.ofFloat(scrollOffset, target)
                    anim.duration = 180L
                    anim.interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                    anim.addUpdateListener { a ->
                        scrollOffset = a.animatedValue as Float
                        invalidate()
                    }
                    anim.start()
                }
            }
        }
    }

    private fun stopFling() {
        flingScroller.forceFinished(true)
        removeCallbacks(flingStep)
    }

    /** 抬手：带着速度继续滑（惯性 + 细腻减速）。 */
    private fun startFling() {
        val vt = velocityTracker ?: return
        val vx = vt.getXVelocity()
        stopFling()
        val max = maxScroll.toInt().coerceAtLeast(0)
        if (kotlin.math.abs(vx) < dpF(60)) return
        flingScroller.fling(
            scrollOffset.toInt(),
            0,
            -vx.toInt(),
            0,
            0,
            max,
            0,
            0,
        )
        postOnAnimation(flingStep)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dpF(v: Number) = v.toFloat() * resources.displayMetrics.density

    /** 候选词左留白 = 键盘第一个按键（Q）的左边缘，保证两者严格对齐。 */
    fun updateSidePad(px: Float) {
        if (px <= 0f) return
        if (kotlin.math.abs(style.sidePadPx - px) < 0.5f) return
        style = style.copy(sidePadPx = px)
        invalidate()
    }

    /** 取某个候选在控件内的中心 X（长按菜单定位用）；未命中返回 -1。 */
    fun candidateCenterX(index: Int): Float {
        val r = candidateRects.getOrNull(index) ?: return -1f
        return if (r.width() > 0f) r.centerX() else -1f
    }

    private fun cancelCandidateLongPress() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    fun update(
        preedit: String,
        candidates: List<RimeEngine.Candidate>,
        highlighted: Int,
        pageNo: Int,
        hasPrevPage: Boolean,
        hasNextPage: Boolean,
        selectKeys: String = "",
        prediction: Boolean = false,
    ) {
        this.preedit = preedit
        this.candidates = candidates
        this.selectKeys = selectKeys
        this.highlighted = highlighted
        this.prediction = prediction
        this.pageNo = pageNo
        this.hasPrevPage = hasPrevPage
        this.hasNextPage = hasNextPage
        // 新一轮输入（新的拼音 / 新的候选列表）必须回到最前面，
        // 否则上一轮滑到后面的位置会带到下一轮，看不到首选词。
        stopFling()
        scrollOffset = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), style.heightPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(style.barBg)
        val w = width.toFloat()
        val h = height.toFloat()
        // 与键盘左右留白完全一致（用按键间距那个值）→ 首个候选词的背景左边缘对齐 Q 键左边缘
        val pad = if (style.sidePadPx > 0f) style.sidePadPx else dpF(4)
        candidateRects.clear()
        // 每次重画都先清空 ✕ 的命中区：没有候选时它不该被点到
        dismissRect = RectF()


        // ① 拼音不再画在这里 —— 已改为通过 setComposingText 直接显示在目标输入框里，
        //     候选栏整条都留给候选词，因此候选词可以在栏内垂直居中。

        // ② 候选词 / 联想词：在候选栏内**垂直居中**（整条高度都能用）
        // 右侧动作按钮：候选态（清空拼音）与联想态（关闭联想）都用同一个 ✕，
        // 位置、尺寸与工具栏「收起键盘」按钮一致，所以候选区都要给它让出位置。
        val showActionButton = prediction || candidates.isNotEmpty()
        val rightReserve = if (showActionButton) dpF(52) else 0f
        val limitX = w - rightReserve

        candidatePaint.textSize = if (style.textSizePx > 0f) style.textSizePx else dpF(20)
        candidatePaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val centerY = h / 2f
        val baseline = centerY - (candidatePaint.descent() + candidatePaint.ascent()) / 2f
        val textTop = baseline + candidatePaint.ascent() - dpF(3)
        val textBottom = baseline + candidatePaint.descent() + dpF(3)

        // 先把所有候选项的宽度算出来（用于滚动范围），再按当前偏移绘制
        val items = candidates.mapIndexed { index, candidate ->
            // 只给前若干个候选标注数字选字键；超出 select_keys 或补齐的空键不显示标签
            val rawKey = if (!prediction && index < selectKeys.length) selectKeys[index] else null
            val label = if (rawKey != null && !rawKey.isWhitespace() && rawKey.code != 0) rawKey.toString() else ""
            val display = if (label.isNotEmpty()) "$label ${candidate.text}" else candidate.text
            // 每个候选占的**总宽度**仍是「文字 + 20dp」——与之前完全一致，
            // 所以候选之间的疏密不会变；收紧的只是高亮背景本身（左右各 4dp）。
            display to (candidatePaint.measureText(display) + dpF(20))
        }
        val contentWidth = pad + items.sumOf { it.second.toDouble() }.toFloat()
        maxScroll = (contentWidth - limitX).coerceAtLeast(0f)
        if (scrollOffset > maxScroll) scrollOffset = maxScroll
        if (scrollOffset < 0f) scrollOffset = 0f

        var x = pad - scrollOffset
        items.forEachIndexed { index, (display, itemWidth) ->
            // 点击热区用整个格子（好点），高亮背景只包住文字
            val cell = RectF(x, textTop, x + itemWidth, textBottom)
            if (cell.right > 0f && cell.left < limitX) {
                candidateRects.add(cell)

                if (index == highlighted) {
                    candidatePaint.color = style.accent
                    pillPaint.color = Color.argb(
                        26,
                        Color.red(style.accent),
                        Color.green(style.accent),
                        Color.blue(style.accent),
                    )
                    // 高亮背景：左边缘贴齐候选栏留白（= 字母 Q 的最左侧边缘），
                    // 宽度 = 文字 + 左右各 8dp，圆角 = 按键圆角。
                    val chip = RectF(
                        x,
                        textTop,
                        x + candidatePaint.measureText(display) + dpF(16),
                        textBottom,
                    )
                    canvas.drawRoundRect(chip, style.cornerPx, style.cornerPx, pillPaint)
                } else {
                    candidatePaint.color = style.text
                }
                canvas.drawText(display, x + dpF(8), baseline, candidatePaint)
            } else {
                candidateRects.add(RectF())
            }
            x += itemWidth
        }

        // ③ 右侧动作按钮：候选态 / 联想态都是同一个「✕」
        //    位置与尺寸**严格等于**按钮栏的「收起键盘」按钮：
        //    按钮右边缘 = 屏宽 - 6dp（= 字母 P 的右边缘），按钮 36dp → 圆心在 屏宽 - 24dp
        if (showActionButton) {
            val size = dpF(36)
            val cx = w - dpF(24)

            // 候选文字在按钮周围用**一整圈径向渐变**柔和淡出（透明 → 栏底色），
            // 文字无论从哪一侧靠近 ✕ 都会被化开，并且让按钮本身看起来像「融进」栏里。
            val haloInner = size / 2 + dpF(2)
            val haloOuter = size / 2 + dpF(20)
            if (haloOuter > haloInner) {
                val opaque = style.barBg
                val clear = style.barBg and 0x00FFFFFF
                fadePaint.shader = android.graphics.RadialGradient(
                    cx, centerY, haloOuter,
                    intArrayOf(opaque, clear),
                    floatArrayOf((haloInner / haloOuter).coerceIn(0f, 1f), 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawRect(
                    cx - haloOuter,
                    centerY - haloOuter,
                    cx + haloOuter,
                    centerY + haloOuter,
                    fadePaint,
                )
                fadePaint.shader = null
            }

            dismissRect = RectF(
                cx - size / 2,
                centerY - size / 2,
                cx + size / 2,
                centerY + size / 2,
            )
            // 与按钮栏功能键一致：实色底 + 图标；圆角同样跟随「按键 / 候选词圆角」设置
            pillPaint.color = style.buttonBg
            val r = style.barCornerPx.coerceIn(0f, size / 2)
            canvas.drawRoundRect(dismissRect, r, r, pillPaint)
            drawIcon(canvas, R.drawable.ic_close, dismissRect, style.text, dp(20), eggAngle)
        }

        // ④ 不再画任何翻页指示：候选词是一整条可左右滑动的列表
    }

    /** 用 Material 图标绘制按钮（不使用代码画的图标）。 */
    private fun drawIcon(
        canvas: Canvas,
        res: Int,
        rect: RectF,
        color: Int,
        sizePx: Int,
        rotation: Float = 0f,
    ) {
        val d = ContextCompat.getDrawable(context, res) ?: return
        val cx = rect.centerX().toInt()
        val cy = rect.centerY().toInt()
        val half = sizePx / 2
        d.setBounds(cx - half, cy - half, cx + half, cy + half)
        d.setTint(color)
        if (rotation != 0f) {
            // 彩蛋期间：绕图标中心自转（图标是画在画布上的，不是控件，所以单独处理）
            val save = canvas.save()
            canvas.rotate(rotation, rect.centerX(), rect.centerY())
            d.draw(canvas)
            canvas.restore()
        } else {
            d.draw(canvas)
        }
    }

    // —— 彩蛋：候选栏右侧的 ✕ 持续旋转 ——
    private var eggAnim: android.animation.ValueAnimator? = null

    /** 当前 ✕ 的自转角度（心跳靠它判断有没有在转）。 */
    var eggAngle = 0f
        private set

    fun isEggSpinning(): Boolean = eggAnim?.isRunning == true

    /** 配上「每圈 360°、无限重复、方向与速度随机」的持续旋转（重复调用会自动接上当前角度）。 */
    fun startEgg() {
        eggAnim?.cancel()
        val dir = if (Math.random() < 0.5) 1f else -1f
        val duration = 350L + (Math.random() * 560).toLong()
        val from = eggAngle
        val anim = android.animation.ValueAnimator.ofFloat(from, from + dir * 360f)
        anim.duration = duration
        anim.interpolator = android.view.animation.LinearInterpolator()
        anim.repeatCount = android.animation.ValueAnimator.INFINITE
        anim.repeatMode = android.animation.ValueAnimator.RESTART
        anim.addUpdateListener {
            eggAngle = it.animatedValue as Float
            invalidate()
        }
        anim.start()
        eggAnim = anim
    }

    fun stopEgg() {
        eggAnim?.cancel()
        eggAnim = null
        eggAngle = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiped = false
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
                // 按下时就确定命中哪个候选（手指轻微移动也不丢点击）
                // 右侧 ✕ 优先判定：它压在候选区域之上
                pressedCandidate = if (dismissRect.contains(event.x, event.y)) {
                    PRESS_DISMISS
                } else if (prediction) {
                    -1
                } else {
                    candidateRects.indexOfFirst { it.contains(event.x, event.y) }
                }
                // 长按候选 → 冷词菜单（移动超过阈值即取消）
                longPressFired = false
                cancelCandidateLongPress()
                if (pressedCandidate >= 0 && !prediction) {
                    val idx = pressedCandidate
                    val r = Runnable {
                        longPressFired = true
                        onLongPressCandidate?.invoke(idx, candidateCenterX(idx))
                    }
                    longPressRunnable = r
                    postDelayed(r, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                velocityTracker?.addMovement(event)
                // 超过触摸阈值且以横向为主 → 进入滚动状态（此后不再触发点击）
                if (abs(dx) > dpF(4) || abs(dy) > dpF(4)) cancelCandidateLongPress()
                if (!swiped && abs(dx) > dpF(4) && abs(dx) > abs(dy)) {
                    swiped = true
                    pressedCandidate = -1
                    scrollStart = scrollOffset
                    onSwipe?.invoke(dx, maxScroll)
                }
                if (swiped) {
                    // 连续平滑滚动：所有候选词都在同一条里，不再有翻页闪烁
                    scrollOffset = (scrollStart - dx).coerceIn(0f, maxScroll)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                cancelCandidateLongPress()
                if (longPressFired) {
                    // 长按已弹出菜单：这一下不算点击候选
                    longPressFired = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    pressedCandidate = -1
                    return true
                }
                if (!swiped) {
                    when {
                        // 联想态是「关闭联想」，候选态是「清空当前拼音」
                        pressedCandidate == PRESS_DISMISS ->
                            if (prediction) onDismiss?.invoke() else onClear?.invoke()
                        pressedCandidate >= 0 -> onCandidate?.invoke(pressedCandidate)
                        else -> {
                            // 兜底：按下位置没命中时，用抬起位置再试一次
                            val idx = candidateRects.indexOfFirst {
                                it.contains(event.x, event.y)
                            }
                            if (idx >= 0) onCandidate?.invoke(idx)
                        }
                    }
                } else {
                    // 带着划动速度继续滑：有惯性、细腻减速，不再"松手即停"
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    startFling()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                pressedCandidate = -1
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelCandidateLongPress()
                longPressFired = false
                pressedCandidate = -1
                swiped = false
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val PRESS_DISMISS = -2
        /** 长按触发时长（毫秒）。 */
        private const val LONG_PRESS_MS = 400L
    }
}