package com.xfgken.iceime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfgken.iceime.R
import com.xfgken.iceime.core.AppLog
import kotlinx.coroutines.launch
import com.xfgken.iceime.data.PhraseStore

/**
 * 常用语设置页。
 *
 * · 顶部右侧一个 **「＋ 添加」**，点了才弹出输入框；
 * · 每条：正文最多两行 + 置顶标记；
 * · 点右侧箭头 → **向下拉长**展开三个按钮：**置顶 / 编辑 / 删除**；
 *   删除是默认颜色，**点一次变红，再点一次才真的删**。
 */
@Composable
fun PhraseSettingsPage() {
    var phrases by remember { mutableStateOf(PhraseStore.ordered()) }
    var adding by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PhraseStore.Phrase?>(null) }
    var editText by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    // 正在播放「左滑消失」动画的条目
    var removing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    PhraseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 去掉「常用语」标题，直接显示条数；字号与「＋ 添加」按钮一致
            Text(
                "共 ${phrases.size} 条",
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { adding = true }) {
                Text("＋ 添加")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (phrases.isEmpty()) {
            Text(
                "还没有常用语。点右上「＋ 添加」就能加一条，之后可以在键盘面板里直接选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            phrases.forEach { phrase ->
                key(phrase.id) {
                AnimatedVisibility(
                    visible = phrase.id !in removing,
                    exit = slideOutHorizontally(animationSpec = tween(260)) { -it } +
                        shrinkVertically(animationSpec = tween(260)) +
                        fadeOut(animationSpec = tween(200)),
                ) {
                PhraseRow(
                    text = phrase.text,
                    pinned = phrase.pinned,
                    open = openId == phrase.id,
                    onToggleOpen = { openId = if (openId == phrase.id) null else phrase.id },
                    onPin = {
                        PhraseStore.togglePin(phrase.id)
                        phrases = PhraseStore.ordered()
                        openId = null
                    },
                    onEdit = {
                        editing = phrase
                        editText = phrase.text
                        openId = null
                    },
                    onDelete = {
                        // 先左滑消失、下面的内容上移，动画结束再删数据
                        openId = null
                        removing = removing + phrase.id
                        AppLog.i(AppLog.Category.UI, "设置页删除常用语")
                        scope.launch {
                            kotlinx.coroutines.delay(300)
                            PhraseStore.remove(phrase.id)
                            phrases = PhraseStore.ordered()
                            removing = removing - phrase.id
                        }
                    },
                )
                }
                Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false; newText = "" },
            title = { Text("添加常用语") },
            text = {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    label = { Text("内容") },
                    minLines = 3,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        PhraseStore.add(newText.trim())
                        phrases = PhraseStore.ordered()
                    }
                    adding = false
                    newText = ""
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false; newText = "" }) { Text("取消") }
            },
        )
    }

    editing?.let { target ->
        AlertDialog(
            onDismissRequest = { editing = null; editText = "" },
            title = { Text("编辑常用语") },
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
                    if (editText.isNotBlank()) {
                        PhraseStore.update(target.id, editText.trim())
                        phrases = PhraseStore.ordered()
                    }
                    editing = null
                    editText = ""
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null; editText = "" }) { Text("取消") }
            },
        )
    }
}

// ————————————————————— 组件 —————————————————————

@Composable
private fun PhraseCard(content: @Composable () -> Unit) {
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

/** 一条常用语：点箭头向下拉长，展开 置顶 / 编辑 / 删除。 */
@Composable
private fun PhraseRow(
    text: String,
    pinned: Boolean,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    // 删除按钮的「变红」是平滑过渡，不是瞬间跳色
    val dangerColor by animateColorAsState(
        targetValue = if (armed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "phraseDeleteColor",
    )
    val arrow by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "phraseArrow",
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
                    .clickable { onToggleOpen() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pinned) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已置顶",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
            // 向下拉长：置顶 / 编辑 / 删除
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
                    // 靠右排列：删除按钮变长时，会把左边的按钮顺势往左推（同样是动画）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhraseAction(
                            R.drawable.ic_pin,
                            if (pinned) "取消置顶" else "置顶",
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onPin,
                        )
                        Spacer(Modifier.width(6.dp))
                        PhraseAction(R.drawable.ic_edit_square, "编辑", onClick = onEdit)
                        Spacer(Modifier.width(6.dp))
                        PhraseAction(
                            R.drawable.ic_delete,
                            if (armed) "确定删除" else "删除",
                            tint = dangerColor,
                            animateSize = true,
                            onClick = {
                                if (armed) onDelete() else armed = true
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 展开区里的操作按钮：**实色底**（与剪贴板页同款，比卡片背景亮一档）。
 *
 * [animateSize] 打开时，文案变长（删除 → 确定删除）会让按钮**平滑变长**。
 */
@Composable
private fun PhraseAction(
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
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}