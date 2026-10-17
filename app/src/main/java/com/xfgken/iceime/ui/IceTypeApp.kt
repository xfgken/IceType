package com.xfgken.iceime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xfgken.iceime.R

/**
 * 应用导航宿主：主页 + 各设置子页。
 *
 * 页面切换使用 `AnimatedContent` + 位移/淡入淡出（对应 M3 的层级导航动效）：
 *  - 进入子页：从右侧轻微滑入（EmphasizedDecelerate 曲线）；
 *  - 返回主页：反向滑出。
 *
 * 刻意不引入 Navigation 组件：层级只有一层，用状态切换即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IceTypeApp(
    initialPage: SettingsPage? = null,
    initialScrollBottom: Boolean = false,
    pageRequestToken: Long = 0L,
) {
    var page by rememberSaveable { mutableStateOf(initialPage?.name) }
    // 外部再次要求跳页（例如已经在 App 主页时又点了键盘左按钮）：只有序号变化时才切；
    // 若本来就在那一页 → page 不变 → 界面完全不动，不会闪、也不会叠出新页面。
    LaunchedEffect(pageRequestToken) {
        if (pageRequestToken > 0L) page = initialPage?.name
    }
    val currentPage = page?.let { SettingsPage.valueOf(it) }
    // 主页的滚动状态提到这一层保管：主页在切换动画中会被移出组合树，
    // 状态放在里面的话返回时就会丢，于是「滑到底部 → 进子页 → 返回」会跳回顶部。
    val homeScroll = rememberScrollState()
    val pageScroll = rememberScrollState()
    // 诊断用：从外部直接打开某页并滚到底部（用于截图核对底部预览的真实渲染）
    LaunchedEffect(initialPage, initialScrollBottom) {
        if (initialScrollBottom && initialPage != null) {
            repeat(6) {
                kotlinx.coroutines.delay(300)
                if (pageScroll.value < pageScroll.maxValue) pageScroll.scrollTo(pageScroll.maxValue)
            }
        }
    }

    IceTypeTheme {
        BackHandler(enabled = currentPage != null) { page = null }

        // 整块底色：页面切换时即使两页都没铺满屏幕，露出的也只是主题背景色，
        // 而不是 Activity 窗口的底色（那一下闪黑就是窗口底色露出来的结果）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                // 纯位移，**不加任何淡入淡出**：两页同时半透明的一瞬间会露出窗口底色，
                // 看起来就是「黑屏闪一下」（之前 fadeIn 有 120ms 延迟，问题更明显）。
                val slide = if (targetState != null) {
                    slideInHorizontally(
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                    ) { full -> full }
                } else {
                    slideInHorizontally(
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                    ) { full -> -full / 4 }
                }
                val exit = if (targetState != null) {
                    slideOutHorizontally(
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                    ) { full -> -full / 4 }
                } else {
                    slideOutHorizontally(
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                    ) { full -> full }
                }
                slide togetherWith exit
            },
            label = "page-transition",
        ) { target ->
            if (target == null) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
                    Column(Modifier.padding(inner)) {
                        HomeScreen(
                            onNavigate = { next -> page = next.name },
                            onTestKeyboard = { page = SettingsPage.KEYBOARD.name },
                            scrollState = homeScroll,
                        )
                    }
                }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = { Text(titleOf(target)) },
                            navigationIcon = {
                                IconButton(onClick = { page = null }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = "返回",
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    },
                ) { inner ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner)
                            .verticalScroll(pageScroll)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (target) {
                            SettingsPage.KEYBOARD -> KeyboardSettingsPage()
                            SettingsPage.THEME -> ThemeSettingsPage()
                            SettingsPage.SOUND -> SoundSettingsPage()
                            SettingsPage.CLIPBOARD -> ClipboardSettingsPage()
                            SettingsPage.PHRASE -> PhraseSettingsPage()
                            SettingsPage.EMOJI -> EmojiSettingsPage()
                            SettingsPage.RIME -> RimeSettingsPage()
                            SettingsPage.PRIVACY -> PrivacySettingsPage()
                            SettingsPage.ABOUT -> AboutPage()
                            SettingsPage.LOGS -> LogPage()
                        }
                    }
                }
                }
            }
        }
    }
}

private fun titleOf(page: SettingsPage): String = when (page) {
    SettingsPage.KEYBOARD -> "键盘与输入"
    SettingsPage.THEME -> "外观主题"
    SettingsPage.SOUND -> "声音与震动"
    SettingsPage.CLIPBOARD -> "剪贴板"
    SettingsPage.PHRASE -> "常用语"
    SettingsPage.EMOJI -> "表情键盘"
    SettingsPage.RIME -> "词库与语言"
    SettingsPage.PRIVACY -> "隐私与学习"
    SettingsPage.ABOUT -> "关于 IceType"
    SettingsPage.LOGS -> "诊断日志"
}