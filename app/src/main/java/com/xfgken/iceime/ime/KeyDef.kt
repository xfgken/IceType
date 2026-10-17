package com.xfgken.iceime.ime

import androidx.annotation.DrawableRes

/** 键盘页面类型。 */
enum class LayoutType { CHINESE_PINYIN, ENGLISH, SYMBOLS, NUMBERS }

/** 面板类型（截图 4 的剪贴板 / 常用语，以及表情面板）。 */
enum class PanelType { CLIPBOARD, PHRASE, EMOJI }

/** 按键动作。所有动作都有真实实现，不存在占位按钮。 */
sealed interface KeyAction {
    /** 上屏固定文本（符号、标点兜底）。 */
    data class Commit(val text: String) : KeyAction

    /** 交给 Rime 处理（字母、数字、被方案接管的标点）。 */
    data class Rime(val keycode: Int, val modifier: Int = 0) : KeyAction

    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Space : KeyAction
    data object Enter : KeyAction
    data object ClearComposition : KeyAction

    /** 分词：在拼音里插入音节分隔符 `'`（例如 ni → ni' ，再输 hao 得到「你好」）。 */
    data object SegmentSyllable : KeyAction

    /** 切换键盘布局。 */
    data class SwitchLayout(val target: LayoutType) : KeyAction

    /** 打开面板。 */
    data class OpenPanel(val panel: PanelType) : KeyAction

    /** 返回键盘（面板关闭 / 符号页返回）。 */
    data object BackToKeyboard : KeyAction

    data object HideKeyboard : KeyAction
    data object OpenSettings : KeyAction

    /** 中英文切换（等价于切换 Rime 的 ascii_mode 并切换布局）。 */
    data object ToggleChineseEnglish : KeyAction

    /** 候选翻页。 */
    data class PageCandidates(val next: Boolean) : KeyAction

    /** 符号分类切换。 */
    data class SymbolCategory(val index: Int) : KeyAction

    /** 无操作（占位或状态展示）。 */
    data object None : KeyAction
}

/**
 * 单个按键定义。
 *
 * @param label 显示文字（与 icon 二选一或同时存在）
 * @param icon 图标资源（自制线性矢量图）
 * @param weight 相对宽度权重
 * @param isFunction 是否功能键（使用功能键配色）
 * @param isPrimary 是否主操作键（使用强调色）
 */
data class KeyDef(
    val label: String = "",
    @DrawableRes val icon: Int? = null,
    val action: KeyAction = KeyAction.None,
    val weight: Float = 1f,
    val isFunction: Boolean = false,
    val isPrimary: Boolean = false,
    /** 长按重复（退格） */
    val repeatOnHold: Boolean = false,
    /** 是否处于激活状态（例如 Shift 锁定、符号分类选中） */
    val active: Boolean = false,
    /** 标签是否用粗体绘制（例如底行的「123」）。 */
    val bold: Boolean = false,
    /**
     * 长按该键时「整个按钮向上拉长」弹出的备选字符：
     * 中文键盘「，」→「。」、英文键盘「,」→「.」。为空表示该键没有这个交互。
     */
    val longPress: String = "",
)

/** 一行按键。 */
data class KeyRow(
    val keys: List<KeyDef>,
    /** 左右内边距权重，用来让第二行字母居中（截图 2）。 */
    val leadingPad: Float = 0f,
    val trailingPad: Float = 0f,
    /**
     * 本行是否横向可滚动（符号键盘底部类别栏）。
     * 可滚动行里：前 [fixedCount] 个按键固定不动（宽度按 weight 算），后面的按键
     * 按内容自适应宽度排成一条长条，整条可以左右拖动，屏幕外的分类滑出来才能点到。
     */
    val scrollable: Boolean = false,
    /** 可滚动行里固定不动的按键个数（最左侧的返回键）。 */
    val fixedCount: Int = 0,
)