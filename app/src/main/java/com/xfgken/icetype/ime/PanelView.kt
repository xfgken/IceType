package com.xfgken.icetype.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xfgken.icetype.R
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.data.ClipboardStore
import com.xfgken.icetype.data.EmojiData
import com.xfgken.icetype.data.PhraseStore

/**
 * 剪贴板 / 常用语 / 表情面板。
 *
 * 结构对齐参考截图 4：顶部左侧圆形返回按钮、中间分段切换、右侧更多菜单，
 * 下方为纵向圆角卡片列表，内容过长时单行截断，每条卡片右侧有独立更多菜单。
 *
 * 全部条目都可真实操作（插入 / 复制 / 固定 / 删除 / 编辑 / 排序 / 清空）。
 */
class PanelView(context: Context) : LinearLayout(context) {

    var onInsertText: ((String) -> Unit)? = null

    /** 选词回滑时删掉刚输入的字（等价于按一次退格）。 */
    var onDeleteText: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val header = LinearLayout(context)
    private val content = LinearLayout(context)
    private val scroll = ScrollView(context)

    /** 表情分类条：只创建一次，避免每次切换分类都往上叠加新的分类条。 */
    private val tabScroll = android.widget.HorizontalScrollView(context)
    private val tabs = LinearLayout(context)
    private var mode = PanelType.CLIPBOARD
    private var emojiCategory = 1

    /** 当前配色（由输入法服务注入），用于卡片与文字颜色 */
    private var palette: KeyboardTheme.Palette? = null

    private val padding = dp(12)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        // 不再有面板标题栏与返回按钮：
        // 由按钮栏上对应按钮旋转变 ✕ 来表示「已展开」，再点 ✕ 收起。
        content.orientation = VERTICAL
        scroll.addView(content)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        tabs.orientation = HORIZONTAL
        tabs.setPadding(padding, dp(4), padding, dp(4))
        tabScroll.addView(tabs)
        // 分类条只保留"能滑动"，不要底部那条滚动条（滑动本身不受影响）
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.isScrollbarFadingEnabled = true
        tabScroll.visibility = GONE
        addView(tabScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 高度由输入法服务通过 LayoutParams 指定（与键盘区一致），这里不做自定义测量，
        // 保持标准 LinearLayout 行为，避免头部/列表被错误裁剪。
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun applyTheme(palette: KeyboardTheme.Palette) {
        this.palette = palette
        // 与按钮栏用同一个底色（输入法容器色 = 键盘底色），
        // 这样点进表情 / 剪贴板 / 常用语时，面板背景和按钮栏是一整块。
        //
        // 玻璃模式下必须给透明：键盘底色本身是半透明的，而容器已经铺过一层，
        // 面板再铺同一层就会叠成两层 → 列表页比按钮栏/键盘区明显更暗。
        setBackgroundColor(if (palette.glass) Color.TRANSPARENT else palette.keyboardBg)
        rebuild()
    }

    fun show(type: PanelType) {
        mode = type
        if (type == PanelType.EMOJI) emojiCategory = defaultEmojiCategory()
        rebuild()
    }


    private fun defaultEmojiCategory(): Int = if (EmojiData.recent().isEmpty()) 1 else 0

    // —— 头部 ——

    private fun rebuild() {
        content.removeAllViews()
        tabs.removeAllViews()
        // 头部已移除（标题与返回按钮都不要了），只重建内容区
        tabScroll.visibility = if (mode == PanelType.EMOJI) View.VISIBLE else View.GONE
        when (mode) {
            PanelType.CLIPBOARD -> buildClipboardList()
            PanelType.PHRASE -> buildPhraseList()
            PanelType.EMOJI -> buildEmojiPanel()
        }
    }

    // 常用语页顶部的小加号按钮已按用户要求移除（面板不再有多余的一行）。




    /** 打开输入法设置里的某一页（[page] 取 MainActivity 认识的页名，如 phrase / clipboard）。 */
    private fun openSettingsPage(page: String) {
        runCatching {
            val intent = android.content.Intent().apply {
                // 与键盘左按钮一致：打开主入口 + 去重标志，
                // 避免「已经在 App 里」时又叠一个新实例出来。
                setClassName(context.packageName, "com.xfgken.icetype.MainActivity")
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                putExtra("page", page)
            }
            context.startActivity(intent)
            AppLog.i(AppLog.Category.UI, "从键盘面板打开设置页：$page")
        }.onFailure { AppLog.throwable(AppLog.Category.ERROR, "打开设置页失败", it) }
    }

    // —— 剪贴板 ——

    private fun buildClipboardList() {
        // 当前剪贴板内容永远排在最前面（历史里的同一条不重复显示）
        val current = ClipboardStore.current()
        val history = ClipboardStore.list()
        val items = buildList {
            if (!current.isNullOrBlank() && history.none { it.text == current }) {
                add(ClipboardStore.Item("current", current, false, System.currentTimeMillis()))
            }
            addAll(history)
        }
        if (items.isEmpty()) {
            addEmptyHint(context.getString(R.string.clipboard_empty))
            return
        }
        items.forEach { item ->
            val isCurrent = item.id == "current"
            addCard(
                text = item.text,
                badge = if (item.pinned) "已固定" else null,
                actions = if (isCurrent) {
                    // 「当前剪贴板内容」不在历史列表里（它直接来自系统剪贴板），
                    // 所以编辑/删除必须真的去改系统剪贴板 —— 否则点了就像没反应。
                    listOf(
                        RowAction("选词", R.drawable.ic_pick) { startPickMode(item.text, "剪贴板选词") },
                        RowAction(
                            "删除",
                            R.drawable.ic_delete,
                            danger = true,
                            confirmLabel = "删除",
                            removeRow = true,
                        ) {
                            // 先记下"这段内容已被删除"，避免一开面板又被系统剪贴板写回来
                            ClipboardStore.suppress(item.text)
                            ClipboardStore.setCurrent(null)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.clearPrimaryClip()
                            AppLog.i(AppLog.Category.UI, "当前剪贴板内容已清空")
                        },
                    )
                } else {
                    listOf(
                        RowAction("选词", R.drawable.ic_pick) { startPickMode(item.text, "剪贴板选词") },
                        RowAction(
                            "删除",
                            R.drawable.ic_delete,
                            danger = true,
                            confirmLabel = "删除",
                            removeRow = true,
                        ) {
                            ClipboardStore.suppress(item.text)
                            // 若历史里这条正好就是"当前剪贴板内容"，当前内容也一起清掉，
                            // 否则它会以「当前剪贴板内容」的形态又出现在最前面
                            if (ClipboardStore.current() == item.text) {
                                ClipboardStore.setCurrent(null)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.clearPrimaryClip()
                            }
                            ClipboardStore.remove(item.id)
                            AppLog.i(AppLog.Category.UI, "剪贴板条目已删除")
                        },
                    )
                },
                onClick = {
                    onInsertText?.invoke(item.text)
                    AppLog.i(AppLog.Category.UI, "剪贴板条目已插入")
                },
            )
        }
    }
/** 编辑「当前剪贴板内容」的动作已在 buildClipboardList 里用面板内编辑器实现。 */


    // —— 常用语 ——

    private fun buildPhraseList() {
        val items = PhraseStore.ordered()
        if (items.isEmpty()) {
            // 点这段提示 → 直接跳到「输入法设置 → 剪贴板与常用语」的常用语页去新增。
            // （以前点它会在键盘上方弹出一块面板内编辑区，已经去掉了。）
            addEmptyHint("还没有常用语，点这里去添加", onClick = { openSettingsPage("phrase") })
            return
        }
        items.forEach { phrase ->
            addCard(
                text = phrase.text,
                badge = if (phrase.pinned) "已置顶" else null,
                actions = listOf(
                    RowAction("选词", R.drawable.ic_pick) { startPickMode(phrase.text, "常用语选词") },
                    // 编辑功能已移到「输入法设置 → 剪贴板与常用语」里，面板不再提供
                    RowAction("置顶", R.drawable.ic_pin) {
                        PhraseStore.togglePin(phrase.id)
                        show(PanelType.PHRASE)
                        AppLog.i(AppLog.Category.UI, "常用语置顶状态已切换")
                    },
                    RowAction(
                        "删除",
                        R.drawable.ic_delete,
                        danger = true,
                        confirmLabel = "删除",
                        removeRow = true,
                    ) {
                        PhraseStore.remove(phrase.id)
                        AppLog.i(AppLog.Category.UI, "常用语已删除")
                    },
                ),
                onClick = { onInsertText?.invoke(phrase.text) },
            )
        }
    }

    // —— 表情 ——

    private fun buildEmojiPanel() {
        val categories = EmojiData.categories
        val list = if (emojiCategory == 0) EmojiData.recent() else categories[emojiCategory].second

        if (list.isEmpty()) {
            addEmptyHint("还没有使用记录，先用几个表情吧")
        } else {
            val grid = LinearLayout(context).apply { orientation = VERTICAL }
            list.chunked(8).forEach { chunk ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                chunk.forEach { emoji ->
                    val tv = TextView(context).apply {
                        text = emoji
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                        setPadding(0, dp(6), 0, dp(6))
                        // 彩蛋时表情也要跟着一起转
                        tag = EGG_TAG
                        setOnClickListener {
                            EmojiData.markUsed(emoji)
                            onInsertText?.invoke(emoji)
                        }
                    }
                    row.addView(tv, LayoutParams(0, dp(44), 1f))
                }
                for (i in chunk.size until 8) {
                    row.addView(View(context), LayoutParams(0, dp(44), 1f))
                }
                grid.addView(row)
            }
            content.addView(grid)
        }

        // 分类条复用成员变量 tabs（只创建一次），避免每次切换分类都往上叠加一层。
        // 一行放不下时**横向滑动**（按用户要求回到单行，可滑动查看全部分类）。
        tabs.orientation = HORIZONTAL
        categories.forEachIndexed { index, pair ->
            val tv = TextView(context).apply {
                text = pair.first
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(
                    if (index == emojiCategory) Color.WHITE
                    else (palette?.onSurfaceVariant ?: Color.DKGRAY),
                )
                background = if (index == emojiCategory) {
                    roundedBackground(
                        palette?.selected ?: Color.parseColor("#3A7CA8"),
                        dp(999).toFloat(),
                    )
                } else {
                    null
                }
                setOnClickListener {
                    emojiCategory = index
                    rebuild()
                }
            }
            tabs.addView(tv, LayoutParams(dp(56), dp(32)))
        }
        // 单行高度
        (tabScroll.layoutParams as? LayoutParams)?.let { lp ->
            lp.height = tabs.paddingTop + tabs.paddingBottom + dp(32)
            tabScroll.layoutParams = lp
        }
    }

    // —— 通用卡片 ——

    private data class RowAction(
        val label: String,
        @param:androidx.annotation.DrawableRes val icon: Int,
        val danger: Boolean = false,
        /** 需要二次确认时，第一次点击把文案换成这个（例如「确定删除」），再点一次才执行。 */
        val confirmLabel: String? = null,
        /** 执行后本条目要「滑掉并收起」（列表剩下的内容顺势往上移）。 */
        val removeRow: Boolean = false,
        val onClick: () -> Unit,
    )

    /**
     * 一条内容行（微信输入法那种样子）：
     *
     *   ┌──────────────────────────────┬─────┬─────┬─────┐
     *   │ 内容 …                    ⋯ │选词 │编辑 │删除 │
     *   └──────────────────────────────┴─────┴─────┴─────┘
     *
     * 平时操作区被前面的卡片挡住；点最右边的「⋯」→ 整条向左平移，
     * 露出后面的操作按钮；再点一次收回。
     */
    private fun addCard(
        text: String,
        badge: String?,
        actions: List<RowAction>,
        onClick: () -> Unit,
    ) {
        val cardColor = palette?.panelCard ?: Color.WHITE
        val outlineColor = palette?.cardOutline ?: Color.argb(60, 128, 128, 128)
        val textColor = palette?.onSurface ?: Color.parseColor("#1D1B20")
        val accent = palette?.selected ?: Color.parseColor("#3A7CA8")
        val dangerColor = Color.parseColor("#C0392B")
        val actionBg = palette?.funcKeyBg ?: Color.parseColor("#E8EAF0")
        val actionCorner = dp(12).toFloat()
        // 删除按钮确认后文字变宽，需要重新对齐平移距离（真正的实现见下面 applyOpen 之后）
        var reapplyOpen: () -> Unit = {}
        // 本条目「滑掉并收起」的执行体（需要用到 row / wrapper，所以后面再赋值）
        var removeThisRow: (() -> Unit)? = null
        // 收起时要复原每个操作按钮（变宽的、变红的全部还原）
        val actionResets = ArrayList<() -> Unit>()

        // ① 后面的操作区：每个操作都是「实色圆角外框 + 只放 SVG」的按钮，不写文字
        val actionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.forEach { action ->
            val button = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply {
                    setColor(actionBg)
                    cornerRadius = actionCorner
                }
            }
            // 删除这一条在确认后才显示文字（图标在前、文字在后），所以初始隐藏
            val labelView = TextView(context).apply {
                this.text = action.confirmLabel ?: ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(dangerColor)
                gravity = Gravity.CENTER
                setPadding(dp(6), 0, 0, 0)
                visibility = View.GONE
            }
val iconView = android.widget.ImageView(context).apply {
                setImageResource(action.icon)
                // 需要二次确认的（删除）：平时用**主题色**，点击确认后才变红
                setColorFilter(
                    if (action.confirmLabel != null) accent else if (action.danger) dangerColor else accent,
                )
                scaleType = android.widget.ImageView.ScaleType.CENTER
                contentDescription = action.label
                // 图标本身不能吃点击（否则父容器收不到点击）
                isClickable = false
                isFocusable = false
            }
            // 需要二次确认的操作（删除）：第一次点击把按钮变成「图标 + 确定删除」的横向单行，
            // 再点一次才真的执行 —— 不跳页面、其他地方保持不变。
            var armed = false
            val iconColor = if (action.danger && action.confirmLabel == null) dangerColor else accent
            // 收起条目时把这个按钮恢复成「纯图标」的原样（宽度/文字/颜色全部还原）
            actionResets.add {
                armed = false
                labelView.animate().cancel()
                labelView.alpha = 1f
                labelView.visibility = View.GONE
                (labelView.layoutParams as? LayoutParams)?.let {
                    it.width = LayoutParams.WRAP_CONTENT
                    labelView.requestLayout()
                }
                iconView.setColorFilter(iconColor)
            }
            val handler = View.OnClickListener {
                val needsConfirm = action.confirmLabel != null && !armed
                if (needsConfirm) {
                    armed = true
                    iconView.setColorFilter(dangerColor)
                    labelView.setTextColor(dangerColor)
                    // 细腻的「先拉长、再出文字」：
                    // ① 按钮从纯图标开始横向长出一条 →② 文字才淡出来
                    labelView.visibility = View.VISIBLE
                    labelView.alpha = 0f
                    val lp = labelView.layoutParams
                    lp.width = 0
                    labelView.requestLayout()
                    labelView.post {
                        val target = labelView.paint.measureText(labelView.text.toString()).toInt() +
                            labelView.paddingLeft + labelView.paddingRight
                        val anim = android.animation.ValueAnimator.ofInt(0, target.coerceAtLeast(1))
                        anim.duration = 220L
                        anim.interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                        anim.addUpdateListener { a ->
                            lp.width = a.animatedValue as Int
                            labelView.requestLayout()
                        }
                        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                // 拉到位后文字才淡出来
                                labelView.animate().alpha(1f).setDuration(140L).start()
                                // 宽度定型为自适应，避免不同字体下被截断
                                lp.width = LayoutParams.WRAP_CONTENT
                                labelView.requestLayout()
                                // 按钮变宽了：重新对齐平移距离，保证整条都露出来
                                reapplyOpen()
                            }
                        })
                        anim.start()
                    }
                    AppLog.i(AppLog.Category.UI, "条目操作：${action.label}（待再次确认）")
                } else {
                    AppLog.i(AppLog.Category.UI, "条目操作：${action.label}")
                    // 任何一个操作出问题都不允许把输入法带崩，只记日志
                    runCatching { action.onClick() }
                        .onFailure {
                            AppLog.throwable(AppLog.Category.ERROR, "条目操作失败：${action.label}", it)
                        }
                    // 删除：本条「往左划掉 + 收起高度」，后面的内容顺势往上移
                    if (action.removeRow) removeThisRow?.invoke()
                }
            }
            button.addView(iconView, LayoutParams(dp(20), dp(20)))
            button.addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            button.setOnClickListener(handler)
            iconView.setOnClickListener(handler)
            actionRow.addView(
                button,
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(6) },
            )
        }

        // ② 前面的卡片
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            background = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), outlineColor)
            }
            setPadding(dp(12), dp(3), dp(2), dp(3))
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            foreground = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.argb(26, 0, 0, 0)),
                null,
                mask,
            )
        }
        val label = TextView(context).apply {
            this.text = text
            // 压扁显示：最多 2 行，超出省略（存储的内容本身不限长度）
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor)
            setOnClickListener { onClick() }
        }
        card.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        if (badge != null) {
            // 用一枚小图钉代替原来的文字徽标：占位更小，条目能显示更多内容
            val pin = android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_pin)
                setColorFilter(accent)
                contentDescription = badge
            }
            card.addView(pin, LayoutParams(dp(16), dp(16)))
            (pin.layoutParams as LayoutParams).marginEnd = dp(4)
        }

        // ③ 平移开关（点 ‹ 展开 / 点 › 收起）
        var open = false
        // 先声明成可变函数：按钮的点击里要用到它，而它里面又要用到按钮（互相引用）
        var applyOpen: (Boolean) -> Unit = {}
        val more = ImageButton(context).apply {
            setImageResource(R.drawable.ic_row_expand)
            setColorFilter(palette?.onSurfaceVariant ?: Color.GRAY)
            contentDescription = "条目菜单"
            // 实色圆角外框（与操作按钮同一套外观）
            background = GradientDrawable().apply {
                setColor(actionBg)
                cornerRadius = actionCorner
            }
            // 按钮本体保持 32dp；箭头再放大 40%（约 9dp → 约 12dp）
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { applyOpen(!open) }
        }
        // 量出操作区实际宽度（删除按钮确认后会变宽，所以每次展开前都重新量一次）
        fun realActionsWidth(): Int {
            actionRow.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dp(34), View.MeasureSpec.EXACTLY),
            )
            return actionRow.measuredWidth
        }
        applyOpen = { target ->
            open = target
            // 收起时把所有操作按钮恢复原样：变红的图标、拉长出来的「删除」文字都还原
            if (!target) actionResets.forEach { it() }
            // 操作按钮与左侧内容条之间留出间距（不然按钮会贴着卡片）
            val gap = dp(8)
            val dx = if (target) -(realActionsWidth() + gap).toFloat() else 0f
            card.animate()
                .translationX(dx)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            // 按钮本身也换向：展开时是 ›，收起时是 ‹
            more.setImageResource(
                if (target) R.drawable.ic_row_collapse else R.drawable.ic_row_expand,
            )
        }
        reapplyOpen = { if (open) applyOpen(true) }
        // 箭头按钮：整体往左让一点（与卡片正文留出间距，也不贴面板右边缘）
        card.addView(
            more,
            LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            },
        )

        val row = android.widget.FrameLayout(context)
        // 左移时卡片不要溢出到行外（否则会盖住上一条的右侧）
        row.clipChildren = true
        row.addView(
            actionRow,
            android.widget.FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        row.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )

        val wrapper = LinearLayout(context).apply {
            orientation = VERTICAL
            // 上下留白压到最小：一屏能看到更多条
            setPadding(padding, dp(2), padding, dp(2))
        }
        wrapper.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(wrapper)
        // 删除动画：本条目往左划掉（像通知划走）→ 高度收到 0（后面的内容顺势往上移）
        removeThisRow = {
            row.animate()
                .translationX(-dp(72).toFloat())
                .alpha(0f)
                .setDuration(190L)
                .setInterpolator(android.view.animation.PathInterpolator(0.3f, 0f, 0.7f, 0.15f))
                .withEndAction {
                    val lpW = wrapper.layoutParams
                    val startH = wrapper.height.coerceAtLeast(1)
                    lpW.height = startH
                    wrapper.requestLayout()
                    val anim = android.animation.ValueAnimator.ofInt(startH, 0)
                    anim.duration = 210L
                    anim.interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                    anim.addUpdateListener { a ->
                        lpW.height = a.animatedValue as Int
                        wrapper.requestLayout()
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            (wrapper.parent as? android.view.ViewGroup)?.removeView(wrapper)
                            // 删空了就重新渲染一次（显示空列表提示）
                            if (content.childCount == 0) show(mode)
                        }
                    })
                    anim.start()
                }
                .start()
        }
    }


    /**
     * 选词页面：先对文本做**智能分词**（词语 / 网址 / 数字 / 英文词各自成块），再按「词块」选择。
     *
     *  - 手指**往右滑** → 滑过的词块依次**插入输入框**，并**保持主题色不再退回**；
     *  - **往回滑** → 滑回来的词块取消选中，输入框里对应的那段文字**跟着减少**；
     *  - **点一下未选**的词块 → 插入；**点一下已选**的词块 → 从输入框删掉那一段、颜色复原；
     *  - 点在空白处不拦截 → 长文本仍可上下滑动浏览。
     */
    private fun startPickMode(text: String, title: String) {
        content.removeAllViews()
        tabScroll.visibility = View.GONE
        if (text.isEmpty()) return
        val view = PickChipView(context, text)
        // 快路径①：往右滑时只补新选中的那一段
        view.onAppend = { s -> onInsertText?.invoke(s) }
        // 快路径②：往回滑时只删掉多出来的那部分
        view.onDeleteChars = { n -> repeat(n) { onDeleteText?.invoke() } }
        // 慢路径③：取消的是「中间」那段 → 先退掉本页插入的全部内容，再整体写入当前选择
        view.onReplace = { del, ins ->
            repeat(del) { onDeleteText?.invoke() }
            if (ins.isNotEmpty()) onInsertText?.invoke(ins)
        }
        content.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        AppLog.i(AppLog.Category.UI, "选词页面（智能分词）：$title ${view.debugSummary()}")
    }

    /**
     * 选词用的自绘视图：
     * 文字按**词块**（智能分词结果）排成一排排圆角块；
     * 未选中的是浅色底 + 普通文字色，选中过的**保持主题色实心底 + 反白字、不消失**。
     */
    private inner class PickChipView(
        context: android.content.Context,
        text: String,
    ) : View(context) {

        /** 往右滑：追加一段文本上屏。 */
        var onAppend: ((String) -> Unit)? = null

        /** 往回滑：删掉末尾 n 个字符。 */
        var onDeleteChars: ((Int) -> Unit)? = null

        /** 取消中间某段：先删 del 个字符，再写入 ins（整体重写，保证删得准）。 */
        var onReplace: ((Int, String) -> Unit)? = null

        /** 智能分词结果（每一块就是一个可选的「词」）。 */
        private val tokens: List<String> = TextSegmenter.segment(context, text)
        private val picked = BooleanArray(tokens.size)

        /** 本页已经写进输入框的内容（用来算增量 / 回退）。 */
        private var insertedText = ""

        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = dp(18).toFloat()
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        private val chipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rects = ArrayList<android.graphics.RectF>()
        private val rows = ArrayList<IntRange>()
        private var totalHeight = 0

        /** 手指当前所在的词块下标 / 本次是不是滑动过。 */
        private var lastTouched = -1
        private var moved = false

        /** 本次手势开始前每一块的选中状态（滑过时按它取反，见 ACTION_MOVE）。 */
        private var pickedAtStart = BooleanArray(0)

        // —— 手势方向判定 ——
        // 选词是「横向滑」，面板滚动是「竖向滑」。
        // 按住词块时不能一上来就抢走事件，否则外层 ScrollView 永远滑不动（之前的 bug）。
        private var downX = 0f
        private var downY = 0f
        private var horizontal = false
        private var vertical = false
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private val chipPadH = dp(6).toFloat()
        private val chipH = dp(34).toFloat()
        private val gapX = dp(4).toFloat()
        private val gapY = dp(6).toFloat()
        private val edge = dp(10).toFloat()
        private val chipCorner = dp(9).toFloat()

        private fun normalTextColor() = palette?.onSurface ?: Color.parseColor("#1D1B20")
        private fun chipColor() = palette?.funcKeyBg ?: Color.parseColor("#E8EAF0")
        private fun selectedColor() = palette?.selected ?: Color.parseColor("#3A7CA8")

        /** 选中块用实心主题色底，文字取反白（浅主题色时用深色，保证看得清）。 */
        private fun pickedTextColor(): Int {
            val c = selectedColor()
            val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
            return if (lum > 0.62) Color.parseColor("#1D1B20") else Color.WHITE
        }

        /** 日志用：分词结果。 */
        fun debugSummary(): String = "（${tokens.size} 个词块：" + tokens.joinToString("｜") + "）"

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            layoutChips(w)
            setMeasuredDimension(w, totalHeight)
        }

        private fun layoutChips(width: Int) {
            rects.clear()
            rows.clear()
            var x = edge
            var y = gapY
            val avail = (width - edge).coerceAtLeast(dp(40).toFloat())
            var rowStart = 0
            for (i in tokens.indices) {
                val word = tokens[i]
                val textW = textPaint.measureText(word)
                val cw = maxOf(textW + chipPadH * 2, chipH * 0.6f)
                if (x + cw > avail && i > rowStart) {
                    rows.add(rowStart..(i - 1))
                    rowStart = i
                    x = edge
                    y += chipH + gapY
                }
                rects.add(android.graphics.RectF(x, y, x + cw, y + chipH))
                x += cw + gapX
            }
            if (tokens.isNotEmpty()) rows.add(rowStart..tokens.size - 1)
            totalHeight = (y + chipH + gapY).toInt()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            for (i in tokens.indices) {
                val r = rects.getOrNull(i) ?: break
                val on = picked[i]
                chipPaint.color = if (on) selectedColor() else chipColor()
                canvas.drawRoundRect(r, chipCorner, chipCorner, chipPaint)
                textPaint.color = if (on) pickedTextColor() else normalTextColor()
                val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(tokens[i], r.centerX(), baseline, textPaint)
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 点在词块之外 → 不拦截，交给外层滚动（长文本仍可上下滑）
                    val idx = indexAt(event.x, event.y) ?: return false
                    lastTouched = idx
                    moved = false
                    // 记下「手势开始前」的选中状态：之后划过的每一块都按它取反
                    pickedAtStart = picked.copyOf()
                    downX = event.x
                    downY = event.y
                    horizontal = false
                    vertical = false
                    // 注意：这里**不**调 requestDisallowInterceptTouchEvent(true)，
                    // 否则一按在词块上外层面板就再也不能滚动了。
                    // 等确定是横向手势（真的要选词）再抢，竖向手势直接让给面板滚动。
                    return true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    // 已经判定是竖向拖动：不选词，交给外层面板滚
                    if (vertical) return true
                    if (!horizontal) {
                        val dx = kotlin.math.abs(event.x - downX)
                        val dy = kotlin.math.abs(event.y - downY)
                        if (dy > touchSlop && dy > dx) {
                            // 竖向 = 浏览长文本
                            vertical = true
                            return true
                        }
                        if (dx > touchSlop && dx >= dy) {
                            // 横向 = 真的要选词，这时才接管事件
                            horizontal = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else {
                            // 还没看出方向：先什么都不做
                            return true
                        }
                    }
                    val idx = indexAt(event.x, event.y) ?: return true
                    moved = true
                    if (idx == lastTouched) return true
                    // ★ 划过的每一块都按「本次手势开始前的状态」取反：
                    //   · 划到**已经选中**的文字 → 取消（往左划、往右划、来回划都行）
                    //   · 划到没选中的文字 → 选中
                    //   所以「往回滑取消」依然成立，同时满足「怎么划都能取消」。
                    val from = minOf(lastTouched, idx).coerceAtLeast(0)
                    val to = maxOf(lastTouched, idx).coerceAtMost(tokens.size - 1)
                    for (i in from..to) {
                        val wasPicked = i < pickedAtStart.size && pickedAtStart[i]
                        picked[i] = !wasPicked
                    }
                    lastTouched = idx
                    applySelection()
                    return true
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved && !vertical && lastTouched >= 0) {
                        // 单击：未选 → 选中并插入；已选 → 取消并把输入框里那一段删掉
                        picked[lastTouched] = !picked[lastTouched]
                        applySelection()
                    }
                    resetDrag()
                    return true
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    resetDrag()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun resetDrag() {
            lastTouched = -1
            moved = false
            horizontal = false
            vertical = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        /** 把「当前选中集合」同步到输入框：能走增量就走增量，删中间那段才整体重写。 */
        private fun applySelection() {
            val newText = StringBuilder().apply {
                for (i in tokens.indices) if (picked[i]) append(tokens[i])
            }.toString()
            val prev = insertedText
            when {
                newText == prev -> Unit
                newText.startsWith(prev) -> onAppend?.invoke(newText.substring(prev.length))
                prev.startsWith(newText) -> onDeleteChars?.invoke(prev.length - newText.length)
                else -> onReplace?.invoke(prev.length, newText)
            }
            insertedText = newText
            val count = picked.count { it }
            AppLog.i(AppLog.Category.UI, "选词：已选 $count / ${tokens.size} 块，共 ${newText.length} 字")
            invalidate()
        }

        /** 命中某个词块；落在同一行的空白处时取该行最近的一块（滑动更顺手）。 */
        private fun indexAt(x: Float, y: Float): Int? {
            for (i in rects.indices) {
                if (rects[i].contains(x, y)) return i
            }
            for (row in rows) {
                val first = rects.getOrNull(row.first) ?: continue
                val last = rects.getOrNull(row.last) ?: continue
                val top = first.top - gapY / 2
                val bottom = first.bottom + gapY / 2
                if (y >= top && y <= bottom) {
                    if (x < first.left) return row.first
                    if (x > last.right) return row.last
                    // 行内空白：取中心最近的那一块
                    var best = row.first
                    var bestD = Float.MAX_VALUE
                    for (i in row.first..row.last) {
                        val r = rects.getOrNull(i) ?: continue
                        val d = kotlin.math.abs(r.centerX() - x)
                        if (d < bestD) {
                            bestD = d
                            best = i
                        }
                    }
                    return best
                }
            }
            return null
        }
    }

    private fun addEmptyHint(text: String, onClick: (() -> Unit)? = null) {
        val accent = palette?.selected ?: Color.parseColor("#3A7CA8")
        val tv = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (onClick != null) accent else Color.GRAY)
            setPadding(padding, dp(28), padding, dp(28))
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        content.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private val EGG_TAG = "icetype_egg"

    // —— 彩蛋：面板里的旋转（表情全转；卡片不转，但里面的 SVG 都转）——
    private var eggRunning = false
    private var eggStart = 0L
    private var eggLastCollect = 0L
    private val eggViews = ArrayList<View>()
    private val eggDirs = ArrayList<Float>()
    private val eggSpeeds = ArrayList<Float>()
    private val eggHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val eggRandom = java.util.Random()

    private val eggTick = object : Runnable {
        override fun run() {
            if (!eggRunning) return
            // 每 300ms 重新收一遍：新打开的表情页 / 刚展开露出的 SVG 也会跟着转
            val now = System.currentTimeMillis()
            if (now - eggLastCollect >= 300L) {
                eggLastCollect = now
                refreshEggTargets()
            }
            val t = (now - eggStart) / 1000f
            for (i in eggViews.indices) {
                eggViews[i].rotation = eggDirs[i] * eggSpeeds[i] * t
            }
            eggHandler.postDelayed(this, 16L)
        }
    }

    /**
     * 开始彩蛋：把面板里该转的东西收集起来**持续**旋转（直到 [stopEgg]）。
     * 收集是动态的：彩蛋运行期间会不断补收"新出现"的元素
     * （例如后来才打开的表情面板、刚展开露出的 SVG），所以不会漏。
     */
    /** 彩蛋是否正在旋转（服务端在输入视图被重建后判断要不要重新挂上）。 */
    fun isEggActive(): Boolean = eggRunning

    fun startEgg() {
        eggViews.clear()
        eggDirs.clear()
        eggSpeeds.clear()
        collectEggViews(this)
        eggRunning = true
        eggStart = System.currentTimeMillis()
        eggLastCollect = System.currentTimeMillis()
        eggHandler.removeCallbacks(eggTick)
        eggHandler.post(eggTick)
        AppLog.i(AppLog.Category.UI, "彩蛋：面板内 ${eggViews.size} 个元素开始持续旋转")
    }

    /** 停止彩蛋：平滑停到 360° 的整数倍再归零（看上去就是 0°，不会跳）。 */
    fun stopEgg() {
        eggRunning = false
        eggHandler.removeCallbacks(eggTick)
        // 先平滑停到最近的 360° 整数倍
        eggViews.forEach { v ->
            val nearest = Math.round(v.rotation / 360f) * 360f
            v.animate().cancel()
            v.animate()
                .rotation(nearest)
                .setDuration(240L)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .withEndAction { v.rotation = 0f }
                .start()
        }
        eggViews.clear()
        eggDirs.clear()
        eggSpeeds.clear()
    }

    /** 彩蛋运行期间：剔除已消失的元素、补收新出现的元素（每 300ms 一次）。 */
    private fun refreshEggTargets() {
        var i = 0
        while (i < eggViews.size) {
            if (eggViews[i].parent == null) {
                eggViews.removeAt(i)
                eggDirs.removeAt(i)
                eggSpeeds.removeAt(i)
            } else {
                i++
            }
        }
        val found = ArrayList<View>()
        collectNewEggViews(this, found)
        found.forEach { v ->
            eggViews.add(v)
            eggDirs.add(if (eggRandom.nextBoolean()) 1f else -1f)
            // 360~960°/s，随机且很快
            eggSpeeds.add((6 + eggRandom.nextInt(11)) * 60f)
        }
    }

    private fun collectNewEggViews(v: View, out: ArrayList<View>) {
        if (v.visibility != View.VISIBLE) return
        if (v !== this) {
            val isSvg = v is android.widget.ImageView
            val marked = v.tag == EGG_TAG
            if (isSvg || marked) {
                if (!eggViews.contains(v) && !out.contains(v)) out.add(v)
                return
            }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectNewEggViews(v.getChildAt(i), out)
        }
    }

    /**
     * 收集要转的视图：
     *  - 打过 [EGG_TAG] 标记的（表情格）；
     *  - 所有 ImageView（也就是面板条目里的各种 SVG：箭头、选词 / 编辑 / 删除图标…）。
     *
     * 条目卡片本身（文字卡片、行容器）不标记也不收集 → 它们保持不动。
     */
    private fun collectEggViews(v: View) {
        if (v.visibility != View.VISIBLE) return
        if (v !== this) {
            val isSvg = v is android.widget.ImageView
            val marked = v.tag == EGG_TAG
            if (isSvg || marked) {
                eggViews.add(v)
                eggDirs.add(if (eggRandom.nextBoolean()) 1f else -1f)
                // 240~600°/s，仍是 60°/s 的整数倍 → 结束时总角度是 360° 的整数倍
                eggSpeeds.add((4 + eggRandom.nextInt(7)) * 60f)
                return
            }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectEggViews(v.getChildAt(i))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}