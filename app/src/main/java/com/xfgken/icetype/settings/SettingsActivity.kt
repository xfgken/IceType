package com.xfgken.icetype.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xfgken.icetype.ui.IceTypeApp
import com.xfgken.icetype.ui.SettingsPage

/**
 * 设置宿主 Activity。
 *
 * 从键盘工具栏、剪贴板面板等处通过 Intent extra `page` 直接跳到指定子页面，
 * 避免用户再从主页逐层点进去。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = intent?.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name.equals(name, true) } }
        setContent {
            IceTypeApp(initialPage = initial)
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }
}