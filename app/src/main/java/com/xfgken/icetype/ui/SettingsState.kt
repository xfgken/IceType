package com.xfgken.icetype.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.xfgken.icetype.core.IcePrefs

/** 观察本地设置的便捷方法（Compose 侧统一入口）。 */
@Composable
fun rememberSettings(): IcePrefs.Settings = IcePrefs.state.collectAsState().value