package com.xfgken.icetype.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfgken.icetype.BuildConfig
import com.xfgken.icetype.R
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.core.IcePrefs
import com.xfgken.icetype.data.EmojiData
import com.xfgken.icetype.ime.KeySoundPlayer
import com.xfgken.icetype.ime.KeyboardTheme
import com.xfgken.icetype.rime.RimeAssetInstaller
import com.xfgken.icetype.rime.RimeEngine
import kotlinx.coroutines.launch

// ——————————————————————————— 通用块 ———————————————————————————

@Composable
private fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (action != null) {
                    Spacer(Modifier.weight(1f))
                    action()
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * 卡片内的一条内容。
 * 直接透传内容，因此可继承外层 Column 的布局作用域（可继续使用 Modifier.weight 等）。
 */
@Composable
private fun Item(content: @Composable () -> Unit) = content()

@Composable
private fun SwitchRow(
    title: String,
    desc: String? = null,
    checked: Boolean,
    @DrawableRes iconRes: Int? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    valueLabel: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            onValueChange = { onChange(it.toInt()) },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Confirm(
    title: String,
    text: String,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ——————————————————————————— 键盘与输入 ———————————————————————————

@Composable
fun KeyboardSettingsPage() {
    val context = LocalContext.current
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()
    // 测试输入框已移到主页面的「输入法测试」里

    SectionCard("输入与候选") {
        Item {
            SwitchRow(
                title = "默认中文输入",
                desc = "进入文本框时默认使用中文拼音键盘",
                checked = settings.chineseDefault,
            ) { IcePrefs.edit { e -> e.putBoolean("chineseDefault", it) } }
        }
        Item {
            SwitchRow(
                title = "显示候选栏",
                checked = settings.candidateBarVisible,
            ) { IcePrefs.edit { e -> e.putBoolean("candidateBarVisible", it) } }
        }
        Item {
            SwitchRow(
                title = "英文候选",
                desc = "关闭后英文键盘直接上屏字母",
                checked = settings.englishCandidates,
            ) { IcePrefs.edit { e -> e.putBoolean("englishCandidates", it) } }
        }
        Item {
            SwitchRow(
                title = "剪贴板候选",
                desc = "复制内容后，候选栏自动显示最新一条，点击可直接粘贴",
                checked = settings.clipboardCandidateEnabled,
            ) { IcePrefs.setClipboardCandidate(it) }
        }
        Item {
            SwitchRow(
                title = "词频学习",
                desc = "关闭后不再把上屏词条写入用户词典",
                checked = settings.learningEnabled,
            ) { enabled ->
                IcePrefs.edit { e -> e.putBoolean("learningEnabled", enabled) }
                scope.launch { RimeEngine.setLearningEnabled(context, enabled) }
                AppLog.i(AppLog.Category.PRIVACY, "词频学习开关：$enabled")
            }
        }
        Item {
            SwitchRow(
                title = "盲打模式",
                desc = "隐藏 26 个字母键上的字母",
                checked = settings.blindTyping,
            ) { IcePrefs.setBlindTyping(it) }
        }
    }

    // 游戏键盘：开关 + 悬浮键盘大小调节，放在同一张卡片里
    SectionCard(
        title = "横屏悬浮键盘",
        action = {
            TextButton(
                onClick = {
                    IcePrefs.setKeyboardScale(95)
                    AppLog.i(AppLog.Category.UI, "游戏键盘大小已恢复默认：95%")
                },
                modifier = Modifier.offset(x = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("恢复默认", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Item {
            SwitchRow(
                title = "横屏悬浮键盘",
                desc = "横屏时整个键盘悬浮，按住顶部把手可任意拖动",
                checked = settings.gameKeyboard,
            ) { IcePrefs.setGameKeyboard(it) }
        }
        Item {
            SliderRow(
                title = "横屏悬浮键盘大小",
                value = settings.keyboardScalePercent,
                range = 70..110,
                valueLabel = { "$it%" },
                onChange = { IcePrefs.setKeyboardScale(it) },
            )
        }
    }

}

// ——————————————————————————— 外观主题 ———————————————————————————

@Composable
fun ThemeSettingsPage() {
    val context = LocalContext.current
    val settings = rememberSettings()
    // 主题外观 = 主题模式 + 配色方案（配色方案放在「浅色/深色/跟随系统」下方，深浅自动跟随）
    SectionCard(
        title = "主题外观",
        action = {
            // 恢复默认配色（稍微往右靠一点）
            TextButton(
                onClick = {
                    IcePrefs.setAccent(null)
                    IcePrefs.setKeyboardBg(null)
                    IcePrefs.setKeyBg(null)
                    IcePrefs.setFuncKeyBg(null)
                    IcePrefs.setKeyText(null)
                    IcePrefs.setKeyCorner(12)
                    IcePrefs.setDynamicColor(true)
                    IcePrefs.setGlassEnabled(false)
                    AppLog.i(AppLog.Category.UI, "键盘配色已恢复默认")
                },
                modifier = Modifier.offset(x = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("恢复默认配色", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeModeChip(
                    "浅色",
                    settings.themeMode == IcePrefs.THEME_LIGHT,
                    R.drawable.ic_light_mode,
                    Modifier.weight(1f),
                ) {
                    IcePrefs.setThemeMode(IcePrefs.THEME_LIGHT)
                }
                ThemeModeChip(
                    "深色",
                    settings.themeMode == IcePrefs.THEME_DARK,
                    R.drawable.ic_dark_mode,
                    Modifier.weight(1f),
                ) {
                    IcePrefs.setThemeMode(IcePrefs.THEME_DARK)
                }
                ThemeModeChip(
                    "跟随系统",
                    settings.themeMode == IcePrefs.THEME_SYSTEM,
                    R.drawable.ic_theme,
                    Modifier.weight(1f),
                ) {
                    IcePrefs.setThemeMode(IcePrefs.THEME_SYSTEM)
                }
            }
        }
        // 动态颜色（Material You）默认**强制开启**，不再提供开关
        Spacer(Modifier.height(16.dp))
        Item {
            Text(
                "配色方案",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "一键换整套配色，深浅色自动跟随上面的主题模式；默认方案取系统动态色，“透明效果”为独立的中性玻璃配色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 默认方案的色带取自**系统动态颜色本身**（系统取什么色，色块就是什么色）
        val dynamicAccent = remember(settings.themeMode) {
            KeyboardTheme.baseAccent(context)
        }
        // 第二个色块就是「透明效果」：它是一套独立的中性玻璃配色，不跟随其它方案
        val glassTones = listOf(
            0xFF2A2D33.toInt(), 0xFF555963.toInt(), 0xFF8A8F99.toInt(),
            0xFFC0C5CC.toInt(), 0xFFF1F3F7.toInt(),
        )
        val cells = buildList {
            add(ColorSchemePreset("默认（动态颜色）", tonesFrom(dynamicAccent)))
            add(ColorSchemePreset("透明效果", glassTones))
            addAll(PresetSchemes)
        }
        // 一行两个色块，依次排下去
        cells.chunked(2).forEach { pair ->
            Item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { preset ->
                        val isDefault = preset.name.startsWith("默认")
                        val isGlass = preset.name == "透明效果"
                        // 单选：透明效果开启时不看 accent，其它方案选中时 transparent 自动落选
                        val selected = when {
                            isGlass -> settings.glassEnabled
                            settings.glassEnabled -> false
                            isDefault -> settings.accentArgb == null
                            else -> settings.accentArgb == preset.accent
                        }
                        SchemeCell(
                            preset = preset,
                            selected = selected,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (isGlass) {
                                // 透明效果：独立的中性玻璃配色（不跟随强调色）
                                IcePrefs.setGlassEnabled(true)
                            } else {
                                // 选其它方案时自动关掉透明效果
                                IcePrefs.setGlassEnabled(false)
                                if (isDefault) {
                                    IcePrefs.setAccent(null)
                                    IcePrefs.setKeyboardBg(null)
                                    IcePrefs.setKeyBg(null)
                                    IcePrefs.setFuncKeyBg(null)
                                    IcePrefs.setKeyText(null)
                                    IcePrefs.setDynamicColor(true)
                                } else {
                                    IcePrefs.setAccent(preset.accent)
                                    IcePrefs.setKeyboardBg(null)
                                    IcePrefs.setKeyBg(null)
                                    IcePrefs.setFuncKeyBg(null)
                                    IcePrefs.setKeyText(null)
                                }
                            }
                            AppLog.i(AppLog.Category.UI, "应用配色方案：${preset.name}")
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    SectionCard(
        title = "尺寸与形状",
        action = {
            TextButton(
                onClick = {
                    IcePrefs.setKeyHeight(42)
                    IcePrefs.setCandidateTextSize(21)
                    IcePrefs.setKeyCorner(12)
                    IcePrefs.setBarCorner(18)
                    AppLog.i(
                        AppLog.Category.UI,
                        "尺寸与形状已恢复默认：键盘高度 42dp、候选词 21sp、按键圆角 12dp、按钮栏圆角 18dp",
                    )
                },
                modifier = Modifier.offset(x = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("恢复默认", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Item {
            SliderRow(
                title = "键盘高度",
                value = settings.keyHeightDp,
                range = 34..64,
                valueLabel = { "$it dp" },
            ) { IcePrefs.setKeyHeight(it) }
        }
        Item {
            SliderRow(
                title = "候选词字号",
                value = settings.candidateTextSp,
                range = 14..30,
                valueLabel = { "$it sp" },
            ) { IcePrefs.setCandidateTextSize(it) }
        }
        Item {
            SliderRow(
                title = "按键 / 候选词圆角",
                value = settings.keyCornerDp,
                range = 0..28,
                valueLabel = { "$it dp" },
                onChange = { IcePrefs.setKeyCorner(it) },
            )
        }
        Item {
            SliderRow(
                title = "按钮栏圆角",
                value = settings.barCornerDp,
                range = 0..18,
                valueLabel = { "$it dp" },
                onChange = { IcePrefs.setBarCorner(it) },
            )
        }
        Item {
            SliderRow(
                title = "键盘上角圆角",
                value = settings.keyboardTopCornerDp,
                range = 0..20,
                valueLabel = { "$it dp" },
                onChange = { IcePrefs.setKeyboardTopCorner(it) },
            )
        }
        Item {
            Text(
                "Material 3 官方形状令牌参考：Small 8dp / Medium 12dp（默认）/ Large 16dp / ExtraLarge 28dp；" +
                    "按钮栏按钮为 36dp，圆角取 18dp 时正好是正圆。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 页面最下面：整块键盘预览（按钮栏 / 候选栏 + 键盘，和主页完全一样）
    KeyboardLivePreviewCard()
}

/** 主题模式按钮：扁扁的横向胶囊，图标在前、文字在后。 */
@Composable
private fun ThemeModeChip(
    label: String,
    selected: Boolean,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 配色方案：同一色相由浅到深的色调序列（参照 Android 原生的色调板做法）。
 * tones 从最浅到最深，应用时取 accent（通常是 700 档）作为强调色。
 */
private data class ColorSchemePreset(
    val name: String,
    val tones: List<Int>,
) {
    val accent: Int get() = tones[3]
}

private fun scheme(name: String, vararg tones: Long) =
    ColorSchemePreset(name, tones.map { it.toInt() })

/** 两个颜色按比例混合（f=0 取 a，f=1 取 b）。 */
private fun blendColor(a: Int, b: Int, f: Float): Int {
    fun ch(shift: Int): Int {
        val av = (a shr shift) and 0xFF
        val bv = (b shr shift) and 0xFF
        return (av + (bv - av) * f).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * 由主色推导 5 档色调（由浅到深），与内置预设一致 ——
 * 供「默认配色（跟随动态颜色）」显示那条浅→深的色带。
 */
private fun tonesFrom(accent: Int): List<Int> = listOf(
    blendColor(accent, 0xFFFFFFFF.toInt(), 0.82f),
    blendColor(accent, 0xFFFFFFFF.toInt(), 0.58f),
    blendColor(accent, 0xFFFFFFFF.toInt(), 0.28f),
    accent,
    blendColor(accent, 0xFF000000.toInt(), 0.35f),
)

private val PresetSchemes: List<ColorSchemePreset> = listOf(
    scheme("雾凇蓝", 0xFFF1F8FE, 0xFFBBDDF8, 0xFF42A5F5, 0xFF1976D2, 0xFF0D47A1),
    scheme("樱花粉", 0xFFFEF3F7, 0xFFF8BBD0, 0xFFEC407A, 0xFFC2185B, 0xFF880E4F),
    scheme("靛青", 0xFFE8EAF6, 0xFF9FA8DA, 0xFF5C6BC0, 0xFF303F9F, 0xFF1A237E),
    scheme("石墨灰", 0xFFECEFF1, 0xFFB0BEC5, 0xFF78909C, 0xFF455A64, 0xFF263238),
    scheme("雪松灰", 0xFFF8FAFB, 0xFFE6EBEF, 0xFFCCD5DD, 0xFFA8B4BE, 0xFF8894A0),
    scheme("浅雾蓝", 0xFFFBFDFF, 0xFFE9F3FC, 0xFFC6DEF2, 0xFF9DC0DE, 0xFF78A2C2),
    scheme("藕荷粉", 0xFFFDF9FB, 0xFFF6E6ED, 0xFFEAC6D6, 0xFFD2A2B8, 0xFFB18399),
    scheme("薄荷青", 0xFFF7FCFA, 0xFFE2F4EE, 0xFFC2E8DC, 0xFF96CEBD, 0xFF6FA795),
)

/**
 * 实时预览卡片：标题 + App 内键盘。
 *
 * 宽度用 `requiredWidth(屏幕宽度)` 跳出页面 16dp 的内边距，
 * 这样键盘的按键宽度、整体比例和输入法里弹出的真实键盘**完全一致**。
 */
@Composable
private fun KeyboardLivePreviewCard() {
    val context = LocalContext.current
    // 键盘整块（顶行 + 键盘区）统一用键盘底色铺底，避免出现白色缝隙/空白
    val keyboardBg = Color(KeyboardTheme.palette(context).keyboardBg)
    // 预览里键盘自己的「键盘上角圆角」，必须跟随设置实时变化
    val previewTopCorner = rememberSettings().keyboardTopCornerDp.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        // 这一块的标题：实时预览
        Text(
            text = "实时预览",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            // 顶部两角保持方角：卡片原来的 20dp 圆角会把预览顶部裁成固定圆角，
            // 那样「键盘上角圆角」这个设置就看不出效果了。
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomEnd = 20.dp,
                bottomStart = 20.dp,
            ),
            // 容器色必须与页面背景一致：键盘顶部圆角裁掉后露出的两个小角就是这层颜色，
            // 用 surfaceContainerLow 会露出比页面偏浅的一块，看着像“多出来的小角”。
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
            // 预览区上下不留任何内边距：键盘底色直接顶到卡片边缘，
            // 否则按钮栏上方会出现一条卡片底色的空白（与真实键盘不一致）。
            // 完整键盘（按钮栏/候选栏 + 5 行按键）都在卡片内按可用宽度铺满：
            // 之前为了和真实键盘「同宽」用了负偏移 + 整屏宽度，导致左侧被父级裁掉一半，
            // 现在改为卡片内满宽 —— 按键是权重布局，比例与真实键盘完全一致。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 按「键盘上角圆角」设置裁剪：预览顶部两角跟着滑块实时变化
                        .clip(
                            androidx.compose.foundation.shape.RoundedCornerShape(
                                topStart = previewTopCorner,
                                topEnd = previewTopCorner,
                            ),
                        )
                        .background(keyboardBg),
                ) {
                    InAppKeyboardTest()
                }
            }
        }
    }
}

/**
 * 一个配色色块（一行两个，依次排下去）：
 *
 *   ┌────────────────────────┐
 *   │ 雾凇蓝            ✓     │  ← 名字用很小的字放在左上角
 *   │ [浅 → 深  五档色调色带]      │
 *   └────────────────────────┘
 *
 * 整块点一下即应用；已选中的加边框 + 右上角对勾。
 */
@Composable
private fun SchemeCell(
    preset: ColorSchemePreset,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 名字压在最浅那一档色带上，所以字色要按它的明度反着来，保证看得清
    val nameColor = if (isLightArgb(preset.tones.first())) Color(0xFF1D1B20) else Color.White
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = Color(preset.tones.first()),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            // 由浅到深五条竖条铺满整块
            Row(Modifier.fillMaxSize()) {
                preset.tones.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(argb)),
                    )
                }
            }
            // 名字：很小很小的字，放在色块左上角
            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 7.dp, top = 4.dp, end = 18.dp),
            )
            // 选中的标记只靠外框加粗（不再画对勾）
        }
    }
}

/** 颜色是否偏亮（决定压在上面的文字用深色还是白色）。 */
private fun isLightArgb(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.62
}


private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )

// ——————————————————————————— 声音与震动 ———————————————————————————

@Composable
fun SoundSettingsPage() {
    val settings = rememberSettings()
    val context = LocalContext.current
    // 按键音素材：优先用 /storage/emulated/0/music/key_press_click.wav。
    // 需要读取权限（Android 13+ 是「音频」权限），这里在进入本页时申请一次，
    // 授权后立刻重新加载素材（不需要重装应用）。
    LaunchedEffect(Unit) {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val activity = (context as? android.app.Activity)
        if (activity == null) return@LaunchedEffect
        val granted = perms.all {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            activity.requestPermissions(perms, 1001)
        }
        // 无论授权结果如何都重载一次：能读到外部文件就用外部文件，读不到用内置素材
        KeySoundPlayer.reload(context)
    }
    SectionCard("按键反馈") {
        Item {
            SwitchRow(
                title = "按键音",
                checked = settings.soundEnabled,
                iconRes = R.drawable.ic_sound,
            ) {
                IcePrefs.edit { e -> e.putBoolean("soundEnabled", it) }
            }
        }
        Item {
            SliderRow(
                title = "按键音音量",
                value = settings.keySoundVolume,
                range = 0..100,
                valueLabel = { if (it == 0) "静音" else "$it%" },
            ) { v ->
                IcePrefs.edit { e -> e.putInt("keySoundVolume", v) }
            }
        }
        Item {
            Text(
                "按键音由 IceType 自己合成并播放，音量由上面这个滑块控制（0 = 静音）；" +
                    "不再受系统「触摸提示音」开关影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Item {
            SwitchRow(
                title = "按键震动",
                checked = settings.vibrateEnabled,
                iconRes = R.drawable.ic_vibrate,
            ) {
                IcePrefs.edit { e -> e.putBoolean("vibrateEnabled", it) }
            }
        }
        Item {
            SliderRow(
                title = "震动强度",
                value = settings.vibrateStrength,
                range = 0..50,
                valueLabel = { "$it / 50 挡" },
            ) { IcePrefs.edit { e -> e.putInt("vibrateStrength", it) } }
        }
        Item {
            Text(
                "共 50 个挡位：1 挡最轻、50 挡最强（0 挡几乎无感）。最终效果还受系统触觉反馈开关与设备马达能力限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ——————————————————————————— 剪贴板 ———————————————————————————

@Composable
fun EmojiSettingsPage() {
    var recent by remember { mutableStateOf(EmojiData.recent()) }

    SectionCard("表情管理") {
        Item {
            Text(
                "最近使用（最多保留 36 个）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (recent.isEmpty()) "暂无记录" else recent.joinToString(" "),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Item {
            OutlinedButton(onClick = {
                EmojiData.clearRecent()
                // 立即刷新页面上的「最近使用」（之前用 remember 缓存，清空后界面没更新）
                recent = EmojiData.recent()
                AppLog.i(AppLog.Category.UI, "清空表情最近使用")
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("清空最近使用")
            }
        }
        Item {
            Text(
                "表情是输入内容，与系统输入法一致，不提供增删改；" +
                    "共 ${EmojiData.totalCount()} 个，按分类展示在下方。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 按分类完整展示（只读）
    SectionCard("全部分类（${EmojiData.categories.size - 1} 类）") {
        EmojiData.categories.drop(1).forEach { (name, list) ->
            Item {
                Text(
                    "$name（${list.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    list.chunked(10).joinToString("\n") { it.joinToString(" ") },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        Item {
            Text(
                "表情是输入内容，不提供编辑（与系统输入法一致）；" +
                    "「最近使用」记录在本地，可随时清空。" +
                    "表情字形由系统字体提供，不同 Android 版本显示会有差异。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ——————————————————————————— 词库与语言 ———————————————————————————

@Composable
fun RimeSettingsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = rememberSettings()
    val deploy = RimeEngine.deploy.collectAsState().value
    var schemas by remember { mutableStateOf(RimeEngine.schemas()) }
    var sharedSize by remember { mutableStateOf(RimeAssetInstaller.sizeOfShared(context)) }
    var confirmResetUser by remember { mutableStateOf(false) }
    var confirmRedeploy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sharedSize = RimeAssetInstaller.sizeOfShared(context)
        schemas = RimeEngine.schemas()
    }

    SectionCard("引擎状态") {
        Item { InfoRow("librime", RimeEngine.engineVersion.ifEmpty { "(未加载)" }) }
        Item {
            InfoRow(
                "部署状态",
                when {
                    deploy.isBusy -> "进行中"
                    deploy.state == RimeEngine.State.FAILED -> "失败"
                    settings.lastDeployOk -> "已就绪"
                    else -> "未部署"
                },
            )
        }
        Item { InfoRow("资源版本", RimeAssetInstaller.ASSET_VERSION.toString()) }
        Item { InfoRow("资源体积", "%.1f MB".format(sharedSize / 1024.0 / 1024.0)) }
        if (deploy.message.isNotEmpty()) {
            Item {
                Text(
                    deploy.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            AppLog.i(AppLog.Category.UI, "用户手动触发词库重新部署")
                            RimeEngine.retryDeploy(context)
                            schemas = RimeEngine.schemas()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("重新部署") }
                OutlinedButton(onClick = { confirmRedeploy = true }, modifier = Modifier.weight(1f)) {
                    Text("重置初始化")
                }
            }
        }
    }

    SectionCard("用户数据") {
        Item {
            Text(
                "用户词频保存在应用私有目录 rime_user 下，资源升级不会覆盖它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Item {
            OutlinedButton(onClick = { confirmResetUser = true }) {
                Text("重置用户词频与自定义配置")
            }
        }
    }

    if (confirmResetUser) {
        Confirm(
            title = "重置用户词频",
            text = "将删除用户词典（userdb）、user.yaml 与自定义补丁，并重新部署一次。此操作不可撤销。",
            confirmLabel = "确认重置",
            onConfirm = {
                confirmResetUser = false
                scope.launch {
                    AppLog.w(AppLog.Category.UI, "用户确认重置用户词频与自定义配置")
                    RimeEngine.resetAndRedeploy(context, alsoWipeUser = true)
                    schemas = RimeEngine.schemas()
                }
            },
            onDismiss = { confirmResetUser = false },
        )
    }
    if (confirmRedeploy) {
        Confirm(
            title = "重置初始化",
            text = "将删除已释放的词库资源并重新释放、重新部署。用户词频会保留。",
            confirmLabel = "确认",
            onConfirm = {
                confirmRedeploy = false
                scope.launch {
                    AppLog.w(AppLog.Category.UI, "用户确认重置初始化")
                    RimeEngine.resetAndRedeploy(context, alsoWipeUser = false)
                    sharedSize = RimeAssetInstaller.sizeOfShared(context)
                    schemas = RimeEngine.schemas()
                }
            },
            onDismiss = { confirmRedeploy = false },
        )
    }
}

// ——————————————————————————— 隐私与学习 ———————————————————————————

@Composable
fun PrivacySettingsPage() {
    val context = LocalContext.current
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    SectionCard("学习") {
        Item {
            OutlinedButton(onClick = { confirmClear = true }) { Text("重置用户词频") }
        }
    }

    SectionCard("隐私说明") {
        Item {
            Text(
                """
                · 完全离线：IceType 不申请网络权限，不包含任何统计/分析 SDK，不会上传任何数据。
                · 不记录输入内容：日志只包含初始化、词库部署、引擎错误与界面操作类别。
                · 剪贴板：复制内容会被记录在本地，可在「剪贴板」页逐条删除或整体清空。
                · 常用语与用户词频均保存在应用私有目录，可随时在“词库与语言 / 隐私”中清除。
                · 日志可查看、复制、清理，全部内容只留在本机。
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmClear) {
        Confirm(
            title = "重置用户词频",
            text = "这会删除用户词典与自定义配置，并重新部署。操作不可撤销。",
            confirmLabel = "确认重置",
            onConfirm = {
                confirmClear = false
                scope.launch {
                    RimeEngine.resetAndRedeploy(context, alsoWipeUser = true)
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

// ——————————————————————————— 关于 ———————————————————————————

/**
 * 项目链接按钮：界面上不出现裸链接，只用图标 + 短语表示；
 * 点击后用系统浏览器打开（离线应用中唯一会离开应用的入口）。
 */
@Composable
private fun LinkIconButton(
    @DrawableRes iconRes: Int,
    label: String,
    url: String,
) {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = {
            runCatching {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLog.i(AppLog.Category.UI, "打开项目链接：$url")
            }.onFailure {
                Toast.makeText(context, "没有可用的浏览器打开链接", Toast.LENGTH_SHORT).show()
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AboutPage() {
    // 应用信息：不加标题（按用户要求去掉“应用信息”这几个字），卡片本身仍在
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_brand),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "冰霜输入法",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "IceType · 中州韵引擎（librime）+ 雾凇拼音方案",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InfoRow("作者", "by xfgken")
            InfoRow("版本号", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            InfoRow("librime", RimeEngine.engineVersion.ifEmpty { "(未加载)" })
            InfoRow("运行环境", "完全离线 · minSdk 29 / targetSdk 37")
        }
    }

    SectionCard("特别鸣谢") {
        Item {
            Text(
                "本项目的输入内核、词库与图标来自以下开源项目，在此一并致谢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Item { InfoRow("librime", "中州韵输入法引擎（Rime）") }
        Item { InfoRow("rime-ice", "雾凇拼音：汉语拼音方案与词库") }
        Item { InfoRow("Melt_eng", "Rime 英文输入方案（Rime 生态）") }
        Item { InfoRow("Material Symbols / Icons", "界面图标") }
        Item { InfoRow("Jetpack Compose & AndroidX", "界面与基础库") }
        Item { InfoRow("Kotlin & kotlinx.coroutines", "语言与协程") }
        Item { InfoRow("DataStore Preferences", "设置持久化") }
    }

    SectionCard("项目") {
        Item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 两个按钮的图标按用户要求互换
                LinkIconButton(
                    iconRes = R.drawable.ic_repo,
                    label = "项目仓库",
                    url = "https://github.com/xfgken/IceType",
                )
                LinkIconButton(
                    iconRes = R.drawable.ic_github,
                    label = "个人主页",
                    url = "https://github.com/xfgken/",
                )
            }
        }
    }
}

// ——————————————————————————— 日志 ———————————————————————————

@Composable
fun LogPage() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(AppLog.snapshot()) }

    SectionCard("诊断日志") {
        Item {
            Text(
                "共 ${entries.size} 条。日志只包含初始化、词库部署、引擎错误与界面操作，" +
                    "不含输入内容、密码与剪贴板正文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { entries = AppLog.snapshot() }, modifier = Modifier.weight(1f)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
                OutlinedButton(
                    onClick = {
                        // 一键复制全部日志（脱敏后），不再弹预览/分享面板
                        val text = AppLog.sanitize(
                            AppLog.exportText(),
                            context.filesDir?.absolutePath,
                            context.cacheDir?.absolutePath,
                        )
                        sendTextToClipboard(context, text)
                        AppLog.i(AppLog.Category.UI, "已复制全部日志（${text.length} 字）")
                        Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("复制")
                }
                OutlinedButton(
                    onClick = {
                        AppLog.clear()
                        entries = AppLog.snapshot()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("清空")
                }
            }
        }
    }

    if (entries.isEmpty()) {
        SectionCard("内容") { Item { Text("暂无日志", style = MaterialTheme.typography.bodySmall) } }
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        items(entries.takeLast(300)) { entry ->
            Text(
                text = entry.format(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

private fun sendTextToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("IceType", text))
}