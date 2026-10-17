package com.xfgken.icetype.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.core.IcePrefs

/**
 * 键盘主题。
 *
 * 颜色角色**直接采用 Material 3 官方 ColorScheme**（`lightColorScheme()` / `darkColorScheme()`
 * 的默认值即官方基线令牌：primary / surfaceContainerHigh / onSurface / outlineVariant 等），
 * 再叠加用户自定义覆盖项；不使用自行猜测的色值。
 */
object KeyboardTheme {

    /** 配色诊断日志是否已输出（只在每个进程里打一次，避免刷屏）。 */
    @Volatile
    private var paletteLogged = false

    data class Palette(
        val isDark: Boolean,
        val keyboardBg: Int,
        val keyBg: Int,
        val funcKeyBg: Int,
        val primaryBg: Int,
        val primaryContent: Int,
        val keyText: Int,
        val accent: Int,
        val outline: Int,
        val candidateBg: Int,
        val panelBg: Int,
        val panelCard: Int,
        // —— 以下按 M3 ColorScheme 语义角色补充，供按钮栏与点击态使用 ——
        /** 按钮栏容器色：对应 M3 `surfaceContainer`（FloatingToolbar 的 ContainerColor）。 */
        val barContainer: Int,
        /** 按键栏描边色：对应 M3 `outlineVariant`（OutlinedIconButton 的 OutlineColor）。 */
        val barOutline: Int,
        /** 图标/次要内容色：对应 M3 `onSurfaceVariant`（IconButtonTokens.Color）。 */
        val onSurfaceVariant: Int,
        /** 主内容色：对应 M3 `onSurface`，用于状态层。 */
        val onSurface: Int,
        /** 选中态色：对应 M3 `primary`（IconButtonTokens.SelectedColor）。 */
        val selected: Int,
        /** 面板卡片描边色：对应 M3 `outlineVariant`。 */
        val cardOutline: Int,
        /** 对话框容器色：对应 M3 `surfaceContainerHigh`。 */
        val dialogContainer: Int,
    )

    // —— 配色方案 ——
    /**
     * 不受「已选配色方案 / 自定义覆盖」影响的基础强调色（= 动态颜色的主色）。
     * 供「默认配色（跟随动态颜色）」这一条显示色带用：选了别的方案时它不该跟着变。
     */
    /**
     * 这几套配色的键盘背景要再调淡一点（接近白色）——按强调色识别：
     * 雾凇蓝 / 暖阳橙 / 樱花粉 / 浅雾蓝。
     */
    private val NEAR_WHITE_BG_ACCENTS = setOf(
        0xFF1976D2.toInt(), // 雾凇蓝
        0xFFEF6C00.toInt(), // 暖阳橙
        0xFFC2185B.toInt(), // 樱花粉
        0xFF9DC0DE.toInt(), // 浅雾蓝
    )

    fun baseAccent(context: Context): Int {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val settings = IcePrefs.current()
        val dark = when (settings.themeMode) {
            IcePrefs.THEME_DARK -> true
            IcePrefs.THEME_LIGHT -> false
            else -> nightMode == Configuration.UI_MODE_NIGHT_YES
        }
        val baseline = if (dark) darkColorScheme() else lightColorScheme()
        return (dynamicScheme(context, dark) ?: baseline).primary.toArgb()
    }

    fun palette(context: Context): Palette {
        val settings = IcePrefs.current()
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val dark = when (settings.themeMode) {
            IcePrefs.THEME_DARK -> true
            IcePrefs.THEME_LIGHT -> false
            else -> nightMode == Configuration.UI_MODE_NIGHT_YES
        }
        // 默认就跟随系统动态颜色（Material You）：Android 12+ 直接读系统壁纸调色板；
        // 不开启或系统不支持时，回退到 M3 官方基线 ColorScheme。
        // 若选用了某套配色方案（accentArgb 非空），则由该色相推导**整套**配色，
        // 深浅两套自动分别生成 —— 按键、按钮栏、图标、候选词全部跟着走。
        val baseline = if (dark) darkColorScheme() else lightColorScheme()
        val scheme = when {
            settings.accentArgb != null -> schemeFromSeed(settings.accentArgb, dark)
            settings.useDynamicColor -> dynamicScheme(context, dark) ?: baseline
            else -> baseline
        }

        val accentDefault = scheme.primary.toArgb()
        val keyboardBgBase = scheme.surfaceContainerHigh.toArgb()
        // 指定配色：键盘背景再向白色靠一点（浅色下才处理，深色保持深底）
        val keyboardBgDefault = if (!dark && settings.accentArgb in NEAR_WHITE_BG_ACCENTS) {
            blend(keyboardBgBase, 0xFFFFFFFF.toInt(), 0.62f)
        } else {
            keyboardBgBase
        }
        val keyBgDefault = scheme.surfaceContainerLowest.toArgb()
        val funcKeyDefault = scheme.surfaceContainerHighest.toArgb()
        val textDefault = scheme.onSurface.toArgb()

        val accent = settings.accentArgb ?: accentDefault
        val keyBg = settings.keyBgArgb ?: keyBgDefault
        // 按钮色：保证与键盘底色至少有可感知的色差（动态颜色 / 各类预设都适用）
        val funcKey = settings.funcKeyBgArgb
            ?: ensureSeparation(funcKeyDefault, keyboardBgDefault, 0.055)
        val keyText = settings.keyTextArgb ?: textDefault
        val keyboardBg = settings.keyboardBgArgb ?: keyboardBgDefault

        // 诊断：把实际用到的配色打出来（每个进程只打一次，确认按钮色与键盘底色是否过于接近）
        if (!paletteLogged) {
            paletteLogged = true
            AppLog.i(
                AppLog.Category.UI,
                "键盘配色 深色=$dark 键盘底=#%08X 按键=#%08X 按钮=#%08X 工具栏底=#%08X 候选栏底=#%08X 强调=#%08X"
                    .format(keyboardBg, keyBg, funcKey, scheme.surfaceContainer.toArgb(), scheme.surface.toArgb(), accent),
            )
        }

        return Palette(
            isDark = dark,
            keyboardBg = keyboardBg,
            keyBg = keyBg,
            funcKeyBg = funcKey,
            primaryBg = accent,
            primaryContent = scheme.onPrimary.toArgb(),
            keyText = keyText,
            accent = accent,
            outline = scheme.outlineVariant.toArgb(),
            candidateBg = scheme.surface.toArgb(),
            panelBg = scheme.surfaceContainerLow.toArgb(),
            panelCard = scheme.surfaceContainerLowest.toArgb(),
            barContainer = scheme.surfaceContainer.toArgb(),
            barOutline = scheme.outlineVariant.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            selected = scheme.primary.toArgb(),
            cardOutline = scheme.outlineVariant.toArgb(),
            dialogContainer = scheme.surfaceContainerHigh.toArgb(),
        )
    }

    /**
     * 系统动态颜色（Material You）配色。
     *
     * Android 12（API 31）及以上，系统会依据壁纸生成一套调色板（`system_accent1_*` /
     * `system_neutral1_*` / `system_neutral2_*`），这里直接读取这些资源组成 M3 ColorScheme，
     * 因此键盘配色与系统「动态颜色」完全一致；系统不支持时返回 null，由调用方回退到基线配色。
     */
/**
     * 由选中的配色方案（种子色）推导**整套** M3 配色。
     *
     * 以前只把强调色换成种子色，键盘背景 / 按键 / 按钮栏 / 图标仍取自系统动态色，
     * 所以「换配色」时只看得见回车键和候选词高亮在变。现在把 primary、容器色、
     * 中性色（surface 系列）与描边**全部按种子色的色相推导**，并且浅色与深色
     * 各自生成一套（浅色往白偏、深色往黑偏），因此所有按键与图标（SVG）都会跟着变，
     * 深浅模式自动适配。
     */
    private fun schemeFromSeed(
        seed: Int,
        dark: Boolean,
    ): androidx.compose.material3.ColorScheme {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        fun c(v: Int) = androidx.compose.ui.graphics.Color(v)
        /** 与白色/黑色按比例混合（f=0 取原色，f=1 取目标色）。 */
        fun mix(target: Int, f: Float) = blend(seed, target, f)
        /** 粗略亮度，用来决定文字用深色还是浅色。 */
        fun luma(c0: Int): Double {
            val r = (c0 shr 16) and 0xFF
            val g = (c0 shr 8) and 0xFF
            val b = c0 and 0xFF
            return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }

        return if (dark) {
            val primary = mix(white, 0.45f)
            darkColorScheme(
                // —— 强调色 ——
                primary = c(primary),
                onPrimary = c(if (luma(primary) > 0.45) mix(black, 0.8f) else mix(white, 0.95f)),
                primaryContainer = c(mix(black, 0.45f)),
                onPrimaryContainer = c(mix(white, 0.88f)),
                // —— 中性色（带一点色相，整块键盘都跟着配色走）——
                surface = c(mix(black, 0.90f)),
                onSurface = c(mix(white, 0.92f)),
                onSurfaceVariant = c(mix(white, 0.70f)),
                surfaceContainerLowest = c(mix(black, 0.94f)),
                surfaceContainerLow = c(mix(black, 0.90f)),
                surfaceContainer = c(mix(black, 0.86f)),
                surfaceContainerHigh = c(mix(black, 0.82f)),
                // 同上：深色模式也保证按钮色与键盘底有明显色差
                surfaceContainerHighest = c(mix(black, 0.62f)),
                outlineVariant = c(mix(white, 0.42f)),
            )
        } else {
            val primary = mix(black, 0.05f)
            lightColorScheme(
                primary = c(primary),
                onPrimary = c(if (luma(primary) > 0.55) mix(black, 0.92f) else mix(white, 0.95f)),
                primaryContainer = c(mix(white, 0.82f)),
                onPrimaryContainer = c(mix(black, 0.60f)),
                surface = c(mix(white, 0.97f)),
                onSurface = c(mix(black, 0.85f)),
                onSurfaceVariant = c(mix(black, 0.55f)),
                surfaceContainerLowest = c(mix(white, 0.99f)),
                surfaceContainerLow = c(mix(white, 0.95f)),
                surfaceContainer = c(mix(white, 0.92f)),
                surfaceContainerHigh = c(mix(white, 0.88f)),
                // 按钮色（表面容器最高档）：比键盘底色**明显深一档**，
                // 否则浅色系配色下「按钮」和键盘底几乎同色，看起来像没画出来
                surfaceContainerHighest = c(mix(white, 0.70f)),
                outlineVariant = c(mix(white, 0.62f)),
            )
        }
    }

    private fun luma(c: Int): Double {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    /**
     * 保证 [base] 与 [against] 之间至少相差 [minDelta] 的亮度，否则把 base 朝
     * 黑/白方向逐步推开。用于「按钮色 vs 键盘底色」——某些配色（尤其是很浅的
     * 浅色系）相邻两档表面色几乎一样，按钮会看起来根本没画出来。
     */
    private fun ensureSeparation(base: Int, against: Int, minDelta: Double): Int {
        val target = if (luma(against) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        var f = 0.0f
        while (f <= 0.5f) {
            val c = blend(base, target, f)
            if (kotlin.math.abs(luma(c) - luma(against)) >= minDelta) return c
            f += 0.06f
        }
        return base
    }

    /** 两个颜色按比例混合（f=0 取 a，f=1 取 b）。 */
    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + (bv - av) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun dynamicScheme(
        context: Context,
        dark: Boolean,
    ): androidx.compose.material3.ColorScheme? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        fun c(id: Int) = androidx.compose.ui.graphics.Color(context.getColor(id))
        return runCatching {
            if (dark) {
                darkColorScheme(
                    primary = c(android.R.color.system_accent1_200),
                    onPrimary = c(android.R.color.system_accent1_900),
                    primaryContainer = c(android.R.color.system_accent1_700),
                    onPrimaryContainer = c(android.R.color.system_accent1_100),
                    surface = c(android.R.color.system_neutral1_900),
                    onSurface = c(android.R.color.system_neutral1_100),
                    surfaceContainerLowest = c(android.R.color.system_neutral1_900),
                    surfaceContainerLow = c(android.R.color.system_neutral1_900),
                    surfaceContainer = c(android.R.color.system_neutral1_800),
                    surfaceContainerHigh = c(android.R.color.system_neutral1_800),
                    surfaceContainerHighest = c(android.R.color.system_neutral1_700),
                    onSurfaceVariant = c(android.R.color.system_neutral2_200),
                    outlineVariant = c(android.R.color.system_neutral2_700),
                )
            } else {
                lightColorScheme(
                    primary = c(android.R.color.system_accent1_600),
                    onPrimary = c(android.R.color.system_accent1_100),
                    primaryContainer = c(android.R.color.system_accent1_100),
                    onPrimaryContainer = c(android.R.color.system_accent1_900),
                    surface = c(android.R.color.system_neutral1_50),
                    onSurface = c(android.R.color.system_neutral1_900),
                    surfaceContainerLowest = c(android.R.color.system_neutral1_50),
                    surfaceContainerLow = c(android.R.color.system_neutral1_50),
                    surfaceContainer = c(android.R.color.system_neutral1_100),
                    surfaceContainerHigh = c(android.R.color.system_neutral1_200),
                    surfaceContainerHighest = c(android.R.color.system_neutral1_300),
                    onSurfaceVariant = c(android.R.color.system_neutral2_700),
                    outlineVariant = c(android.R.color.system_neutral2_200),
                )
            }
        }.getOrNull()
    }

    fun keyboardStyle(context: Context): KeyboardView.Style {
        val p = palette(context)
        val settings = IcePrefs.current()
        val density = context.resources.displayMetrics.density
        return KeyboardView.Style(
            keyHeightPx = (settings.keyHeightDp * density).toInt(),
            gapPx = (settings.keyGapDp * density).toInt(),
            cornerPx = settings.keyCornerDp * density,
            keyboardBg = p.keyboardBg,
            keyBg = p.keyBg,
            funcKeyBg = p.funcKeyBg,
            primaryBg = p.primaryBg,
            primaryContent = p.primaryContent,
            keyText = p.keyText,
            accent = p.accent,
            outline = p.outline,
            onSurface = p.onSurface,
        )
    }

    fun candidateStyle(context: Context): CandidateBarView.Style {
        val p = palette(context)
        val settings = IcePrefs.current()
        val density = context.resources.displayMetrics.density
        return CandidateBarView.Style(
            // 必须与 IceInputMethodService.BAR_HEIGHT_DP 一致：
            // 若比那一行矮，视图会在行内顶部对齐，候选词就会被「抬高」而离第一行字母很远。
            heightPx = (50 * density).toInt(),
            // 候选栏底色与键盘底色一致 → 视觉上是整体一块，没有割裂感
            barBg = p.keyboardBg,
            text = p.keyText,
            accent = p.accent,
            outline = p.outline,
            buttonBg = p.funcKeyBg,
            // 候选词高亮背景的圆角跟随「按键圆角」设置（0dp 就变成方角）
            cornerPx = settings.keyCornerDp * density,
            // 按钮栏按钮（含候选栏右侧 ✕）的圆角跟随「按钮栏圆角」设置
            barCornerPx = settings.barCornerDp * density,
            // 候选词字号跟随「候选词大小」设置
            textSizePx = settings.candidateTextSp * density,
            // 候选栏左右留白：键盘的按键是「行内再内缩半个按键间隔」后绘制的，
            // 所以首键可见的左边缘 = 间距 × 1.5。取同一个值，候选词背景左边缘
            // 才会与字母 Q 的左边缘落在**同一根垂直线**上。
            sidePadPx = settings.keyGapDp * density * 1.5f,
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}