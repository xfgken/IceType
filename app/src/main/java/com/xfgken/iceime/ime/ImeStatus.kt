package com.xfgken.iceime.ime

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

import com.xfgken.iceime.core.IcePrefs

/**
 * 输入法系统状态检测（按主流输入法的做法实现）。
 *
 * 判定「是否已启用」用**官方 API**：`InputMethodManager.getEnabledInputMethodList()`
 * 直接返回系统当前启用的输入法列表，比自己解析字符串可靠得多。
 *
 * 判定「是否已选择」读取 `Settings.Secure.DEFAULT_INPUT_METHOD`，
 * 并用 `ComponentName.unflattenFromString()` 解析——它能同时处理系统可能写入的两种写法：
 *   com.xfgken.iceime/.ime.IceInputMethodService               （短写）
 *   com.xfgken.iceime/com.xfgken.iceime.ime.IceInputMethodService（全写）
 *
 * 三者语义与界面文案一一对应：
 *   DISABLED             → 未启用
 *   ENABLED_NOT_SELECTED → 已启用，未选择
 *   ACTIVE               → 已启用，已选择（正在使用）
 */
object ImeStatus {

    enum class State { DISABLED, ENABLED_NOT_SELECTED, ACTIVE }


    fun imeId(): String = "com.xfgken.iceime/.ime.IceInputMethodService"

    private fun imm(context: Context): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun readSecure(context: Context, key: String): String =
        runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull().orEmpty()

    /** 该组件名是否属于本应用（用 unflattenFromString，兼容短写/全写）。 */
    private fun belongsToUs(context: Context, flattened: String): Boolean {
        val cn = ComponentName.unflattenFromString(flattened.trim()) ?: return false
        if (cn.packageName != context.packageName) return false
        return cn.className.endsWith("IceInputMethodService")
    }

    /** 是否已启用：优先用官方列表，其次回退到 Secure 设置项。 */
    private fun isEnabledNow(context: Context): Boolean {
        val fromImm = imm(context)?.enabledInputMethodList
            ?.any { it.packageName == context.packageName }
        if (fromImm == true) return true
        val secure = readSecure(context, Settings.Secure.ENABLED_INPUT_METHODS)
        return secure.split(':').any { belongsToUs(context, it) }
    }

    /**
     * 是否当前选中。
     * ① 主流做法：读 `Settings.Secure.DEFAULT_INPUT_METHOD`，指向本输入法即为选中。
     * ② 补充：本键盘**此刻正在屏幕上**时，必然就是被选中的（系统切走会通知我们隐藏），
     *    用于兜住 MIUI 在输入法进程未运行时把该值写回自带输入法的情况。
     * 注意：不能用「最近显示过」这种宽泛条件，否则切到别的输入法后仍会误判为已选中。
     */
    private fun isSelectedNow(context: Context): Boolean {
        val current = readSecure(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (current.isNotEmpty() && belongsToUs(context, current)) return true
        return IcePrefs.imeShown()
    }

    fun currentState(context: Context): State = when {
        !isEnabledNow(context) -> State.DISABLED
        isSelectedNow(context) -> State.ACTIVE
        else -> State.ENABLED_NOT_SELECTED
    }

    /** 打开系统输入法选择器。 */
    fun showPicker(context: Context) {
        imm(context)?.showInputMethodPicker()
    }


    fun isActive(context: Context): Boolean = currentState(context) == State.ACTIVE

    /** 诊断用：显示判定过程用到的原始值。 */
    fun debugText(context: Context): String = buildString {
        append("判定：").append(currentState(context)).append('\n')
        val list = imm(context)?.enabledInputMethodList
            ?.joinToString("、") { it.packageName } ?: "(读取失败)"
        append("已启用列表：").append(list).append('\n')
        append("默认输入法：")
            .append(readSecure(context, Settings.Secure.DEFAULT_INPUT_METHOD).ifEmpty { "(空)" })
        val shown = IcePrefs.imeShown()
        append("\n本键盘在屏：").append(if (shown) "是" else "否")
    }

    fun raw(context: Context): String = debugText(context)
}