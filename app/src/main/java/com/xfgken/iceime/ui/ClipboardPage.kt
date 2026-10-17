package com.xfgken.iceime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xfgken.iceime.R
import com.xfgken.iceime.core.AppLog
import com.xfgken.iceime.core.IcePrefs
import kotlinx.coroutines.launch
import com.xfgken.iceime.data.ClipboardStore
import com.xfgken.iceime.data.PhraseStore

/**
 * 剪贴板设置页。
 *
 * 自上而下三块：**条数设置 → 搜索 → 内容列表**。
 *
 * · 搜索：按钮就在**搜索框内最右边**（纯文字「搜索」，不用图标），**点它才出结果**；
 * · 多选按钮在最右侧，点一下变「完成」，同时**左侧滑出「全选 / 删除」**（带动画）；
 * · 每条：正文最多两行 + 精确到秒的小时间，点内容 = 编辑弹窗；
 * · 点右侧箭头 → **向下拉长**展开：左「添加到常用语」、右「删除」；
 *   两个按钮都是**实色底**（比卡片背景稍亮一档）；
 * · 删除：默认色 → 点一次**丝滑变红**并长成「确定删除」→ 再点一次才真的删。
 */
@Composable
fun ClipboardSettingsPage() {
    val context = LocalContext.current
    var count by remember { mutableStateOf(ClipboardStore.size()) }
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ClipboardStore.Item?>(null) }
    var editText by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(IcePrefs.current().clipboardHistoryLimit) }
    // 正在播放「左滑消失」动画的条目：动画期间先不删数据，播完再删
    var removing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    val all = remember(count) { ClipboardStore.list() }
    val items = remember(all, applied) {
        if (applied.isBlank()) all
        else all.filter { it.text.contains(applied, ignoreCase = true) }
    }
    val pinnedCount = all.count { it.pinned }

    LaunchedEffect(all) {
        if (selected.isNotEmpty()) {
            val alive = all.map { it.id }.toSet()
            val kept = selected.filter { it in alive }.toSet()
            if (kept.size != selected.size) selected = kept
        }
    }

    // ———— ① 条数设置 ————
    ClipCard {
        Text(
            if (limit <= 0) "历史条数：不限" else "历史条数：最多 $limit 条",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = limit.toFloat(),
            onValueChange = { limit = it.toInt() },
            valueRange = 0f..2000f,
            onValueChangeFinished = {
                IcePrefs.edit { e -> e.putInt("clipboardHistoryLimit", limit) }
            },
        )
        Text(
            "拖到 0 = 不限制条数（只受手机存储影响）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("清空未置顶")
        }
    }

    // ———— ② 搜索（按钮在框内最右） ————
    Spacer(Modifier.height(10.dp))

    ClipCard {
        // 搜索条：整条 48dp 高、按钮 40dp → 按钮上 / 下 / 右三侧间隙都是 4dp（完全一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "搜索剪贴板内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { applied = query }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { query = ""; applied = "" }
                        .padding(horizontal = 6.dp),
                )
            }
            // 搜索按钮：高度不变，**往左拉长**（右边缘固定不动）
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .width(88.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { applied = query },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "搜索",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            // 固定 40dp 高：切换多选时整行高度不变 → 上面的内容不会往上跳
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ★ 原地渐变切换：进入多选时「共 x 条 · 置顶 x」淡出，
            //   「全选 / 删除 / 已选 x 条」在同一位置淡入；退出时反过来。
            Crossfade(
                targetState = managing,
                animationSpec = tween(durationMillis = 220),
                label = "clipManageBar",
                modifier = Modifier.weight(1f),
            ) { on ->
                if (on) {
                    Row(
                        modifier = Modifier.height(34.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ClipSolid("全选") {
                            selected =
                                if (selected.size == items.size) emptySet()
                                else items.map { it.id }.toSet()
                        }
                        Spacer(Modifier.width(6.dp))
                        ClipSolid("删除", danger = true) {
                            if (selected.isNotEmpty()) confirmDeleteSelected = true
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已选 ${selected.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    // 与多选分支同高（34dp）：切换时垂直位置一模一样，不会上下跳
                    Box(Modifier.height(34.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (applied.isBlank()) "共 ${all.size} 条 · 置顶 $pinnedCount"
                            else "「$applied」匹配 ${items.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 多选 / 完成：始终在最右
            TextButton(onClick = {
                managing = !managing
                if (!managing) selected = emptySet()
            }) { Text(if (managing) "完成" else "多选") }
        }
    }

    // ———— ③ 内容列表 ————
    Spacer(Modifier.height(10.dp))

    ClipCard {
        Text("内容（${items.size} 条）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                when {
                    all.isEmpty() -> "还没有记录。复制任意内容就会出现在这里。"
                    else -> "没有找到匹配「$applied」的内容。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            items.forEach { item ->
                key(item.id) {
                AnimatedVisibility(
                    visible = item.id !in removing,
                    exit = slideOutHorizontally(animationSpec = tween(260)) { -it } +
                        shrinkVertically(animationSpec = tween(260)) +
                        fadeOut(animationSpec = tween(200)),
                ) {
                ClipRow(
                    item = item,
                    open = openId == item.id,
                    managing = managing,
                    checked = item.id in selected,
                    onToggleCheck = {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    },
                    onEdit = {
                        editing = item
                        editText = item.text
                    },
                    onToggleOpen = { openId = if (openId == item.id) null else item.id },
                    onToPhrase = {
                        PhraseStore.add(item.text)
                        openId = null
                        android.widget.Toast.makeText(
                            context, "已添加到常用语",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onDelete = {
                        // 先让它往左滑走、下面的内容顺势上移，动画结束再删数据
                        openId = null
                        removing = removing + item.id
                        AppLog.i(AppLog.Category.UI, "设置页删除剪贴板条目")
                        scope.launch {
                            kotlinx.coroutines.delay(300)
                            ClipboardStore.remove(item.id)
                            count = ClipboardStore.size()
                            removing = removing - item.id
                        }
                    },
                )
                }
                Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ———— 弹窗 ————
    editing?.let { target ->
        AlertDialog(
            onDismissRequest = { editing = null; editText = "" },
            title = { Text("编辑剪贴板内容") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("内容") },
                    minLines = 3,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ClipboardStore.update(target.id, editText.trim())
                    count = ClipboardStore.size()
                    editing = null
                    editText = ""
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null; editText = "" }) { Text("取消") }
            },
        )
    }

    if (confirmClear) {
        ClipConfirm(
            title = "清空剪贴板历史",
            text = "将删除所有未固定的剪贴板记录，置顶项目会保留。此操作不可撤销。",
            confirmLabel = "清空",
            onConfirm = {
                ClipboardStore.clear(keepPinned = true)
                count = ClipboardStore.size()
                openId = null
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmDeleteSelected) {
        ClipConfirm(
            title = "删除选中条目",
            text = "将删除选中的 ${selected.size} 条记录，此操作不可撤销。",
            confirmLabel = "删除",
            onConfirm = {
                runCatching { selected.forEach { id -> ClipboardStore.remove(id) } }
                selected = emptySet()
                count = ClipboardStore.size()
                openId = null
                confirmDeleteSelected = false
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
}

// ————————————————————— 组件 —————————————————————

@Composable
private fun ClipCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { content() }
    }
}

/** 实色小按钮（和多选栏、展开区里的按钮同一套外观）。 */
@Composable
private fun ClipSolid(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (danger) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 一条剪贴板记录。
 *
 * · 点内容 → 编辑弹窗；
 * · 点右侧箭头 → **向下拉长**展开：左「添加到常用语」、右「删除」（都是实色底）；
 * · 删除：点一次**平滑变红**并长成「确定删除」（宽度带动画），再点一次才真的删。
 */
@Composable
private fun ClipRow(
    item: ClipboardStore.Item,
    open: Boolean,
    managing: Boolean,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onEdit: () -> Unit,
    onToggleOpen: () -> Unit,
    onToPhrase: () -> Unit,
    onDelete: () -> Unit,
) {
    var armed by remember(item.id) { mutableStateOf(false) }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val arrow by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "clipArrow",
    )
    // 删除按钮的「变红」是一个平滑过渡，不是瞬间跳色
    val dangerColor by animateColorAsState(
        targetValue = if (armed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "clipDeleteColor",
    )
    LaunchedEffect(open) { if (!open) armed = false }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (managing) onToggleCheck() else onEdit() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 进入多选：勾选圈横向展开「推」出来，正文被顺势往右推（细腻过渡）
                AnimatedVisibility(
                    visible = managing,
                    enter = expandHorizontally(animationSpec = tween(220)) + fadeIn(tween(180)),
                    exit = shrinkHorizontally(animationSpec = tween(180)) + fadeOut(tween(140)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClipCheck(checked)
                        Spacer(Modifier.width(10.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            clipTime(item.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.pinned) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "已置顶",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                // 圆形点击区（以前是方形背景），图标也放大一点
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onToggleOpen() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_down),
                        contentDescription = if (open) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer { rotationZ = arrow },
                    )
                }
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(160)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SolidAction(R.drawable.ic_phrase, "添加到常用语", onClick = onToPhrase)
                        // 变长（宽度）与变红都是动画
                        SolidAction(
                            R.drawable.ic_delete,
                            if (armed) "确定删除" else "删除",
                            tint = dangerColor,
                            animateSize = true,
                            onClick = { if (armed) onDelete() else armed = true },
                        )
                    }
                }
            }
        }
    }
}

/** 圆形勾选。 */
@Composable
private fun ClipCheck(checked: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) accent else Color.Transparent)
            .border(1.5.dp, if (checked) accent else outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                "✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * 展开区里的操作按钮：**实色底**（比所在卡片背景亮一档）。
 *
 * [animateSize] 打开时，文案变化（删除 → 确定删除）会让按钮**平滑变长**，
 * 同一行里它左边的按钮会被顺势推过去，同样是动画。
 */
@Composable
private fun SolidAction(
    iconRes: Int,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    animateSize: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick() }
            .then(if (animateSize) Modifier.animateContentSize(tween(220)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** 确认弹窗。 */
@Composable
private fun ClipConfirm(
    title: String,
    text: String,
    confirmLabel: String,
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

/** 小时间：今天只给「时:分:秒」，更早的带上日期（精确到秒）。 */
private fun clipTime(timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) "HH:mm:ss" else "MM-dd HH:mm:ss"
    return java.text.SimpleDateFormat(fmt, java.util.Locale.CHINA).format(java.util.Date(timestamp))
}