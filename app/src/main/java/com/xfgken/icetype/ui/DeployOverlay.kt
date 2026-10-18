package com.xfgken.icetype.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xfgken.icetype.R
import com.xfgken.icetype.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** 完成态的绿色（与主页状态卡一致）。 */
private val SuccessGreenLight = Color(0xFF2E7D32)
private val SuccessGreenDark = Color(0xFF81C784)

/** 首次部署的编译阶段大约要多久（秒），仅用于把「未知」折算成百分比。 */
private const val ESTIMATED_COMPILE_SECONDS = 30f

/**
 * 词库部署面板（强制等待 + 百分比进度 + 原始日志 + 完成确认）。
 *
 * 进度怎么来的（必须说清楚，别假装）：
 *   · 资源释放阶段：`RimeAssetInstaller` 按已复制文件数给出真实百分比，映射到 0–40%；
 *   · 编译阶段：librime 不提供任何进度回调（`deploy` 通知只有 start/success/failure），
 *     所以按「已用时间 / 预计 30 秒」折算到 40–99%，封顶 99% 等真正完成才跳到 100%；
 *   · 完成：100%。
 *
 * 日志是**原始输出**：直接读 librime 自己写进 `cacheDir/rime-log/` 的 glog 文件末尾若干行
 * （形如 `I20261017 21:09:12.123456 ...`），不做任何改写；读不到文件时才退回应用自身日志原文。
 */
@Composable
fun DeployOverlay(
    message: String,
    percent: Int,
    done: Boolean,
    failed: Boolean = false,
    onConfirm: () -> Unit,
) {
    BackHandler(enabled = true) { /* 未确认不放行 */ }

    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val success = if (isSystemInDarkTheme()) SuccessGreenDark else SuccessGreenLight
    val stateColor = when {
        failed -> MaterialTheme.colorScheme.error
        done -> success
        else -> accent
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    // 已用秒数（编译阶段估算百分比要用）
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(done) {
        if (done) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsed += 1
        }
    }

    // 百分比：0–40 来自资源释放真实进度，40–99 来自时间折算
    val shownPercent: Int = when {
        done || failed -> 100
        percent in 1..100 -> (percent * 0.4f).toInt().coerceIn(0, 40)
        else -> (40 + (elapsed / ESTIMATED_COMPILE_SECONDS * 59f)).toInt().coerceIn(40, 99)
    }

    // 日志：优先 librime 自己写的原始 glog 尾部；没有（本项目 librime 关了日志）时，
    // 只取「词库部署」类别 + 与编译/初始化相关的引擎行，**不混入界面、剪贴板等其它日志**。
    var logLines by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            logLines = deploymentLogLines(10)
                .ifEmpty {
                    withContext(Dispatchers.IO) { readRawLogTail(context.cacheDir, 10) }
                        .reversed()
                }
            delay(600)
        }
    }

    Dialog(
        onDismissRequest = { /* 不放行 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                    // —— 标题行 ——
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(
                                when {
                                    failed -> R.drawable.ic_error
                                    done -> R.drawable.ic_status_enabled
                                    else -> R.drawable.ic_book
                                },
                            ),
                            contentDescription = null,
                            tint = stateColor,
                            modifier = Modifier.size(if (done || failed) 26.dp else 20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                failed -> "部署失败"
                                done -> "部署完成"
                                else -> "正在准备词库"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (done || failed) stateColor else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$shownPercent%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = stateColor,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    // 自绘进度条（不依赖 M3 进度组件的版本签名）
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    ) {
                        val h = size.height
                        val radius = CornerRadius(h / 2f, h / 2f)
                        drawRoundRect(
                            color = trackColor,
                            size = Size(size.width, h),
                            cornerRadius = radius,
                        )
                        val w = size.width * (shownPercent / 100f)
                        if (w > 0f) {
                            drawRoundRect(
                                color = stateColor,
                                size = Size(w.coerceAtMost(size.width), h),
                                cornerRadius = radius,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            failed -> "词库编译失败。可以到「词库与语言」里重新部署，错误详情见日志。"
                            done -> "词库已就绪，现在可以开始使用了。"
                            else -> message.ifEmpty { "首次安装需要编译词库，请稍候…" }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // —— 原始日志区 ——
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (logLines.isEmpty()) {
                            Text(
                                text = "等待日志输出…",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            logLines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (done || failed) {
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                        ) {
                            Text(if (failed) "知道了" else "确定", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 部署/编译相关的应用日志（带原始时间戳，不改写内容）。
 *
 * 之所以要过滤：面板是给用户看「词库正在编译什么」的，混进「输入法状态判定」「剪贴板已监听」
 * 这类行会把真正的编译进度挤掉。
 */
private fun deploymentLogLines(maxLines: Int): List<String> {
    val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return AppLog.snapshot()
        .filter { entry ->
            entry.category == AppLog.Category.DEPLOY ||
                (entry.category == AppLog.Category.ENGINE && (
                    entry.message.contains("编译") ||
                        entry.message.contains("部署") ||
                        entry.message.contains("deploy", ignoreCase = true) ||
                        entry.message.contains("librime")
                    ))
        }
        .takeLast(maxLines)
        .map { entry -> "${clock.format(java.util.Date(entry.timestamp))}  ${entry.message}" }
        // 最新的一条排第一行：新日志进来就顶到最上面，旧的一行行往下挤
        .reversed()
}

/**
 * 读 librime（glog）日志目录里**最新修改的文件**的末尾若干行 —— 原始内容，不改写。
 * glog 文件名形如 `librime.INFO.20261017-210912.12345`，也有 `.WARNING` / `.ERROR`。
 */
private fun readRawLogTail(cacheDir: File, maxLines: Int): List<String> {
    return runCatching {
        val dir = File(cacheDir, "rime-log")
        if (!dir.isDirectory) return emptyList()
        val newest = dir.listFiles { f -> f.isFile && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
            ?: return emptyList()

        RandomAccessFile(newest, "r").use { raf ->
            val length = raf.length()
            val window = 16 * 1024L
            val start = (length - window).coerceAtLeast(0L)
            val buf = ByteArray((length - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            text.split('\n')
                .filter { it.isNotBlank() }
                .takeLast(maxLines)
                .map { it.trimEnd('\r') }
        }
    }.getOrDefault(emptyList())
}