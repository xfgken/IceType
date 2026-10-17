package com.xfgken.iceime.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.xfgken.iceime.core.IcePrefs

/**
 * 应用主题。
 *
 * 严格使用 Material 3 官方实现：
 *  - 浅色/深色使用 `lightColorScheme()` / `darkColorScheme()` 的官方基线令牌；
 *  - 设备支持时优先使用系统动态颜色（Android 12+ 的 `dynamicLightColorScheme`）。
 * 没有自定义颜色体系，也没有自造组件外观。
 */
@Composable
fun IceTypeTheme(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = rememberSettings()
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        IcePrefs.THEME_DARK -> true
        IcePrefs.THEME_LIGHT -> false
        else -> systemDark
    }
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        settings.useDynamicColor && supportsDynamic ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}