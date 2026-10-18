package com.xfgken.icetype.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xfgken.icetype.R
import com.xfgken.icetype.core.AppLog
import com.xfgken.icetype.ime.ImeStatus

/** 设置子页面标识。 */
enum class SettingsPage {
    KEYBOARD, THEME, SOUND, CLIPBOARD, PHRASE, EMOJI, RIME, PRIVACY, ABOUT, LOGS
}

/**
 * 状态色。
 * M3 的 ColorScheme 没有“成功”语义角色，因此绿色按项目约定固定取值（红叉使用 M3 的 error 角色）。
 */
private val SuccessGreenLight = Color(0xFF2E7D32)
private val SuccessGreenDark = Color(0xFF81C784)

/** 警告黄（M3 ColorScheme 没有“警告”语义角色，按项目约定固定取值）。 */
private val WarningAmberLight = Color(0xFFF9A825)
private val WarningAmberDark = Color(0xFFFFD54F)

/**
 * 主页面（文档第 4、9 节）。
 * 顺序：标题 → 输入法状态卡片 → 分组设置卡片 → 快捷入口 → 版本与初始化状态。
 */
@Composable
fun HomeScreen(
    onNavigate: (SettingsPage) -> Unit,
    onTestKeyboard: () -> Unit,
    /** 滚动状态由外层保管，保证从子页返回时主页停在原来的位置。 */
    scrollState: ScrollState = rememberScrollState(),
) {
    val context = LocalContext.current
    val settings = rememberSettings()
    // 部署遮罩由 MainActivity 统一叠加（覆盖全部页面），这里不再观察部署状态
    var imeState by remember { mutableStateOf(ImeStatus.currentState(context)) }
    var showManualHint by remember { mutableStateOf(false) }

    // 从系统设置返回应用时重新检测状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeState = ImeStatus.currentState(context)
                AppLog.i(AppLog.Category.UI, "刷新输入法状态：$imeState")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统「输入法选择器」是覆盖在应用之上的浮层，应用可能不会收到 ON_RESUME，
    // 因此在前台每秒主动检测一次，保证用户选完输入法后状态卡片会立刻更新。
    LaunchedEffect(Unit) {
        while (true) {
            val now = ImeStatus.currentState(context)
            if (now != imeState) {
                imeState = now
                AppLog.i(AppLog.Category.UI, "输入法状态变化：$now")
            }
            delay(1000)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            HeaderSection()

            Spacer(Modifier.height(16.dp))

            StatusCard(
                state = imeState,
                onEnable = {
                    val ok = openImeSettings(context)
                    if (!ok) showManualHint = true
                },
                onSelect = { ImeStatus.showPicker(context) },
            )

            if (showManualHint) {
                Spacer(Modifier.height(8.dp))
                ManualPathCard(onDismiss = { showManualHint = false })
            }

            Spacer(Modifier.height(20.dp))

            GroupTitle("键盘")
            SettingsEntry(R.drawable.ic_keyboard, R.string.card_keyboard, R.string.card_keyboard_desc) {
                onNavigate(SettingsPage.KEYBOARD)
            }
            SettingsEntry(R.drawable.ic_theme, R.string.card_theme, R.string.card_theme_desc) {
                onNavigate(SettingsPage.THEME)
            }
            SettingsEntry(R.drawable.ic_sound, R.string.card_sound, R.string.card_sound_desc) {
                onNavigate(SettingsPage.SOUND)
            }
            SettingsEntry(R.drawable.ic_book, R.string.card_rime, R.string.card_rime_desc) {
                onNavigate(SettingsPage.RIME)
            }

            Spacer(Modifier.height(16.dp))
            GroupTitle("内容")
            SettingsEntry(R.drawable.ic_clipboard, R.string.card_clipboard, R.string.card_clipboard_desc) {
                onNavigate(SettingsPage.CLIPBOARD)
            }
            SettingsEntry(R.drawable.ic_phrase, R.string.card_phrase, R.string.card_phrase_desc) {
                onNavigate(SettingsPage.PHRASE)
            }
            SettingsEntry(R.drawable.ic_emoji, R.string.card_emoji, R.string.card_emoji_desc) {
                onNavigate(SettingsPage.EMOJI)
            }

            Spacer(Modifier.height(16.dp))
            GroupTitle("其它")
            SettingsEntry(R.drawable.ic_info, R.string.card_about, R.string.card_about_desc) {
                onNavigate(SettingsPage.ABOUT)
            }
            SettingsEntry(R.drawable.ic_list, R.string.quick_log, R.string.card_log_desc) {
                onNavigate(SettingsPage.LOGS)
            }

            Spacer(Modifier.height(20.dp))

            Spacer(Modifier.height(24.dp))
        }
    }
}

// —— 主页统一圆角（比 M3 默认更圆，视觉更柔和）——
private val HomeCardShape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
private val HomeFieldShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun HeaderSection() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_brand),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 输入法状态卡片。
 * 未启用 → 红色叉（Material cancel，M3 error 角色）
 * 已启用未选中 → 中性圆（Material radio_button_unchecked，tertiary 角色）
 * 已启用且当前 → 绿色对勾（Material check_circle，项目约定的 success 绿）
 */
@Composable
private fun StatusCard(
    state: ImeStatus.State,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val success = if (dark) SuccessGreenDark else SuccessGreenLight
    val warning = if (dark) WarningAmberDark else WarningAmberLight
    // 输入法测试框内容（仅本地，不保存、不上传）
    var testText by remember { mutableStateOf("") }

    val iconRes: Int
    val tint: Color
    val titleRes: Int
    val descRes: Int
    when (state) {
        ImeStatus.State.DISABLED -> {
            iconRes = R.drawable.ic_cancel
            tint = MaterialTheme.colorScheme.error
            titleRes = R.string.status_disabled
            descRes = R.string.status_disabled_desc
        }

        ImeStatus.State.ENABLED_NOT_SELECTED -> {
            // 已启用但未选择：黄色感叹号（用户指定图标：Material Symbols / error）
            iconRes = R.drawable.ic_error
            tint = warning
            titleRes = R.string.status_enabled
            descRes = R.string.status_enabled_desc
        }

        ImeStatus.State.ACTIVE -> {
            iconRes = R.drawable.ic_status_enabled
            tint = success
            titleRes = R.string.status_active
            descRes = R.string.status_active_desc
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HomeCardShape,
        // 状态卡与其它设置卡片用同一个容器色，避免主页第一张卡看起来"更亮/更灰"
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // 一行搞定：小状态图标 + 状态文字 + 右侧两个紧凑操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onEnable,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(stringResource(R.string.action_enable), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onSelect,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(stringResource(R.string.action_select), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                text = stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            // 输入法测试框：点它唤起系统输入法（IceType）实测
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                placeholder = { Text("输入法测试：点这里唤起 IceType 键盘") },
                shape = HomeFieldShape,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ManualPathCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HomeCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "无法直接打开系统键盘设置页",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.status_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun SettingsEntry(
    iconRes: Int,
    titleRes: Int,
    descRes: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = HomeCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 箭头：垂直居中 + 放大
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * 打开系统“键盘/输入法”设置页。
 * 使用系统公开 Intent，按可用优先级回退；全部失败时由调用方显示手动路径。
 */
private fun openImeSettings(context: Context): Boolean {
    val candidates = buildList {
        add(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        add(Intent(Settings.ACTION_SETTINGS))
        add(
            Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS).apply {
                putExtra(Settings.EXTRA_INPUT_METHOD_ID, ImeStatus.imeId())
            },
        )
    }
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) continue
        if (runCatching { context.startActivity(intent) }.isSuccess) {
            AppLog.i(AppLog.Category.UI, "已打开系统设置页：${intent.action}")
            return true
        }
    }
    AppLog.w(AppLog.Category.UI, "系统未提供可用的键盘设置入口，转为显示手动路径")
    Toast.makeText(context, "请手动前往系统设置启用输入法", Toast.LENGTH_LONG).show()
    return false
}