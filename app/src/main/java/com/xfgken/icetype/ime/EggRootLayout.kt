package com.xfgken.icetype.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin

/**
 * 输入法的根容器。
 *
 * 它只是为了「彩蛋里的点击火花特效」而存在，做法刻意选成**完全不干预交互**：
 *
 *  - [dispatchTouchEvent]：事件进来时**先偷看一眼坐标**（生成火花），
 *    然后原样 `super.dispatchTouchEvent()` 继续往下发 ——
 *    按键 / 按钮 / 面板的点击行为一点都不会变；
 *  - [dispatchDraw]：先照常画完所有子控件，**再在最上层画火花** ——
 *    火花不是控件，因此**不可能抢走任何触摸**。
 *
 * 关闭状态（没触发彩蛋）时：[effectEnabled] 返回 false，此时既不生成也不绘制，
 * 连一次多余的 invalidate 都不会发生，等于这段代码不存在。
 */
class EggRootLayout(context: Context) : LinearLayout(context) {

    /** 开关（由输入法服务根据「彩蛋是否开启」提供）。 */
    var effectEnabled: () -> Boolean = { false }

    private val sparks = EggSparks(context)
    private var lastTrailAt = 0L

    init {
        // 粒子是外部（彩蛋）主动加进来的，加进来后要立刻排一帧重绘
        sparks.onSpawned = { postInvalidateOnAnimation() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (effectEnabled()) {
            // 坐标就是容器自身坐标系（和 dispatchDraw 的画布同一坐标系），直接用触点坐标即可
            val x = ev.x
            val y = ev.y
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> sparks.burst(x, y)
                MotionEvent.ACTION_MOVE -> {
                    // 拖动时带小火花（节流，避免每帧都生成）
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTrailAt >= 50L) {
                        lastTrailAt = now
                        sparks.trail(x, y)
                    }
                }
            }
        }
        // 关键：照原样把事件交给下面的真实控件
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 把容器高度告诉火花层：小球落到键盘最下方就消失
        sparks.setBounds(w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (sparks.isActive()) {
            sparks.step()
            sparks.draw(canvas)
            // 还有火花在飞 → 继续下一帧
            postInvalidateOnAnimation()
        }
    }
}

/**
 * 点击火花（小烟花）效果：纯数据 + 绘制，不持有任何 View，也不接收触摸。
 *
 * 每颗小球：随机方向飞散 + 重力下坠 + 先放大再缩小的脉冲 + 淡出，
 * 颜色完全随机（与主题色无关）。
 */
private class EggSparks(context: Context) {

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val bornAt: Long,
        val lifeMs: Long,
        val baseRadius: Float,
        val color: Int,
    )

    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = java.util.Random()
    private val particles = ArrayList<Particle>()
    private var lastStepAt = 0L

    /** 有新粒子加进来时的回调（根容器据此排一帧重绘）。 */
    var onSpawned: (() -> Unit)? = null

    /** 容器可视区域（小球落到底部就消失），由根容器在尺寸变化时写进来。 */
    private var boundsH = 0f

    /** 粒子总数上限（防止狂滑时无限堆积）。 */
    private val maxParticles = 900

    // 重力偏小：小球先向上/四周飞散，再慢慢自由落体往下掉
    private val gravity = 520f * density // px/s²

    fun setBounds(width: Int, height: Int) {
        boundsH = height.toFloat()
    }

    fun isActive(): Boolean = particles.isNotEmpty()

    /** 点一下：炸开 16~32 颗小球。 */
    fun burst(x: Float, y: Float) {
        if (particles.size >= maxParticles) return
        val count = 16 + random.nextInt(17)
        repeat(count) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = (150f + random.nextFloat() * 260f) * density
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    bornAt = SystemClock.uptimeMillis(),
                    lifeMs = 1100L + random.nextInt(700),
                    baseRadius = (3f + random.nextFloat() * 4f) * density,
                    color = randomColor(),
                ),
            )
        }
        lastStepAt = SystemClock.uptimeMillis()
    }

    /** 拖动时撒的小火花：数量同样 17~30 随机，一下子冒出很多。 */
    fun trail(x: Float, y: Float) {
        if (particles.size >= maxParticles) return
        val count = 17 + random.nextInt(14)
        repeat(count) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = (60f + random.nextFloat() * 190f) * density
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    bornAt = SystemClock.uptimeMillis(),
                    lifeMs = 900L + random.nextInt(600),
                    baseRadius = (2f + random.nextFloat() * 3f) * density,
                    color = randomColor(),
                ),
            )
        }
    }

    /** 完全随机的鲜艳颜色（和主题色系无关）。 */
    private fun randomColor(): Int = Color.HSVToColor(
        floatArrayOf(random.nextFloat() * 360f, 0.85f, 1f),
    )

    /** 推进一帧（重力、阻尼、清除已消散的 / 已落到底部的）。 */
    fun step() {
        val now = SystemClock.uptimeMillis()
        if (lastStepAt == 0L) lastStepAt = now
        val dt = ((now - lastStepAt) / 1000f).coerceIn(0f, 0.05f)
        lastStepAt = now
        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            val expired = now - p.bornAt >= p.lifeMs
            // 落到底部（键盘最下方）就消失
            val fellOut = boundsH > 0f && p.y - p.baseRadius > boundsH
            if (expired || fellOut) {
                particles.removeAt(i)
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += gravity * dt
            p.vx *= 0.99f
            p.vy *= 0.998f
            i++
        }
        if (particles.isEmpty()) lastStepAt = 0L
    }

    /** 画在最上层（调用方在 dispatchDraw 里、所有子控件画完之后调用）。 */
    fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (p in particles) {
            val t = ((now - p.bornAt) / p.lifeMs.toFloat()).coerceIn(0f, 1f)
            // 脉冲：很快放大到 1.0，再慢慢缩到 0.35（像烟花绽放）
            val scale = if (t < 0.25f) {
                0.5f + 2f * t
            } else {
                1.0f - 0.65f * ((t - 0.25f) / 0.75f)
            }
            val alpha = ((1f - t * t) * 255f).toInt().coerceIn(0, 255)
            paint.color = p.color
            paint.alpha = alpha
            canvas.drawCircle(p.x, p.y, p.baseRadius * scale, paint)
        }
    }
}
