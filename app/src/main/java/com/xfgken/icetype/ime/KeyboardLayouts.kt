package com.xfgken.icetype.ime

import com.xfgken.icetype.R
import com.xfgken.icetype.rime.RimeEngine

/**
 * 四种键盘布局的定义。
 *
 * 布局严格按开发文档第 5 节的控件位置约束实现：
 *  - 中文拼音：候选栏 + 三行 QWERTY + 底行（数字切换 / 标点 / 空格 / 中英切换 / 动态动作键）
 *  - 英文 QWERTY：第一行 QWERTYUIOP，第二行 ASDFGHJKL 整体居中，第三行 Shift + ZXCVBNM + 退格
 *  - 符号键盘：三行符号网格 + 底部分类快捷入口（只列出已实现的分类）
 *  - 数字键盘：左侧 @%-+ 竖列、中间 1–9 网格、右侧退格/空格/表情、底行 0 与小数点与动作键
 */
object KeyboardLayouts {

    /**
     * 大写键的四种形态：
     *  - [OFF]     普通状态：显示「Shift」图标
     *  - [ONCE]    点了 1 下：下一个字母大写（图标高亮）
     *  - [LOCK]    快速双击：大写锁定，之后输入的都是纯大写字母、不进拼音（图标换成带下横线的锁大写图标）
     *  - [SEGMENT] 正在输入拼音时：按钮变成「词」，点一下插入音节分隔符（分词）
     */
    enum class ShiftMode { OFF, ONCE, LOCK, SEGMENT }

    /**
     * 字母键。
     * @param uppercase 显示成大写（中文键盘固定大写显示）
     * @param shiftModifier 是否把 Shift 修饰符交给 Rime（英文键盘用；拼音不需要）
     */
    private fun letter(ch: Char, uppercase: Boolean, shiftModifier: Boolean = uppercase): KeyDef {
        val code = ch.lowercaseChar().code
        return KeyDef(
            label = if (uppercase) ch.uppercaseChar().toString() else ch.lowercaseChar().toString(),
            action = KeyAction.Rime(code, if (shiftModifier) RimeEngine.Modifier.SHIFT else 0),
            // 26 个字母（中文键盘固定大写显示）用粗一点的字重，笔画更清楚
            bold = true,
        )
    }

    /** 大写键：随状态切换图标/文字。 */
    private fun shiftKey(mode: ShiftMode): KeyDef = when (mode) {
        ShiftMode.SEGMENT -> KeyDef(
            label = "词",
            action = KeyAction.SegmentSyllable,
            weight = 1.5f,
            isFunction = true,
        )

        ShiftMode.LOCK -> KeyDef(
            icon = R.drawable.ic_caps_lock,
            action = KeyAction.Shift,
            weight = 1.5f,
            isFunction = true,
            active = true,
        )

        ShiftMode.ONCE -> KeyDef(
            icon = R.drawable.ic_shift,
            action = KeyAction.Shift,
            weight = 1.5f,
            isFunction = true,
            active = true,
        )

        ShiftMode.OFF -> KeyDef(
            icon = R.drawable.ic_shift,
            action = KeyAction.Shift,
            weight = 1.5f,
            isFunction = true,
        )
    }


    /**
     * 数字键。
     * 必须显式指定权重：数字键盘每行是「左列 1.1 + 中列 1.15×3 + 右列 1.3」，
     * 若这里用默认权重 1.0，各行总权重不等，底部那一行就永远对不齐。
     */
    private fun digit(d: Char): KeyDef = KeyDef(
        label = d.toString(),
        action = KeyAction.Rime(d.code),
        weight = NUM_COL,
    )

    /** 动态动作键：由 EditorInfo 决定显示“前往/搜索/发送/下一步/完成/换行”。 */
    fun actionKey(label: String, weight: Float = 1.5f, isFunction: Boolean = false): KeyDef = KeyDef(
        label = label,
        icon = R.drawable.ic_enter,
        action = KeyAction.Enter,
        weight = weight,
        isFunction = isFunction,
        isPrimary = !isFunction,
    )

    // —— 中文拼音（全拼） ——
    fun chinesePinyin(actionLabel: String, shiftMode: ShiftMode = ShiftMode.OFF): List<KeyRow> = listOf(
        KeyRow(
            listOf(
                letter('q', true, false), letter('w', true, false), letter('e', true, false), letter('r', true, false),
                letter('t', true, false), letter('y', true, false), letter('u', true, false), letter('i', true, false),
                letter('o', true, false), letter('p', true, false),
            ),
        ),
        KeyRow(
            listOf(
                letter('a', true, false), letter('s', true, false), letter('d', true, false), letter('f', true, false),
                letter('g', true, false), letter('h', true, false), letter('j', true, false), letter('k', true, false),
                letter('l', true, false),
            ),
            leadingPad = 0.5f,
            trailingPad = 0.5f,
        ),
        KeyRow(
            listOf(
                shiftKey(shiftMode),
                letter('z', true, false), letter('x', true, false), letter('c', true, false), letter('v', true, false),
                letter('b', true, false), letter('n', true, false), letter('m', true, false),
                KeyDef(icon = R.drawable.ic_backspace, action = KeyAction.Backspace, weight = 1.5f, isFunction = true, repeatOnHold = true),
            ),
        ),
        KeyRow(
            listOf(
                KeyDef(label = "123", action = KeyAction.SwitchLayout(LayoutType.NUMBERS), weight = 1.9f, isFunction = true, bold = true),
                KeyDef(label = "，", action = KeyAction.Rime(RimeEngine.Key.COMMA), weight = 1.4f, isFunction = true, longPress = "。"),
                // 中英切换已移到按钮栏，这里去掉以腾出空间：空格更宽（自然右移）、回车更大
                KeyDef(icon = R.drawable.ic_space, action = KeyAction.Space, weight = 4.4778f),
                actionKey(actionLabel, weight = 2.2222f),
            ),
        ),
    )

    // —— 英文 QWERTY ——
    fun english(shifted: Boolean, actionLabel: String): List<KeyRow> = listOf(
        KeyRow(
            listOf(
                letter('q', shifted), letter('w', shifted), letter('e', shifted), letter('r', shifted),
                letter('t', shifted), letter('y', shifted), letter('u', shifted), letter('i', shifted),
                letter('o', shifted), letter('p', shifted),
            ),
        ),
        KeyRow(
            listOf(
                letter('a', shifted), letter('s', shifted), letter('d', shifted), letter('f', shifted),
                letter('g', shifted), letter('h', shifted), letter('j', shifted), letter('k', shifted),
                letter('l', shifted),
            ),
            leadingPad = 0.5f,
            trailingPad = 0.5f,
        ),
        KeyRow(
            listOf(
                KeyDef(icon = R.drawable.ic_shift, action = KeyAction.Shift, weight = 1.5f, isFunction = true),
                letter('z', shifted), letter('x', shifted), letter('c', shifted), letter('v', shifted),
                letter('b', shifted), letter('n', shifted), letter('m', shifted),
                KeyDef(icon = R.drawable.ic_backspace, action = KeyAction.Backspace, weight = 1.5f, isFunction = true, repeatOnHold = true),
            ),
        ),
        KeyRow(
            listOf(
                // 底行与中文键盘**完全一致**（权重、按键、位置都相同）：
                // 这样中英切换时底部这一行的按钮宽度、布局不会有任何跳变。
                KeyDef(label = "123", action = KeyAction.SwitchLayout(LayoutType.NUMBERS), weight = 1.9f, isFunction = true, bold = true),
                // 英文键盘的逗号必须是**英文逗号**（直接上屏 ","，不走中文标点映射）
                KeyDef(label = ",", action = KeyAction.Commit(","), weight = 1.4f, isFunction = true, longPress = "."),
                KeyDef(icon = R.drawable.ic_space, action = KeyAction.Space, weight = 4.4778f),
                actionKey(actionLabel, weight = 2.2222f),
            ),
        ),
    )

    /**
     * 参考图里符号页默认显示的那 20 个符号（第一行 10 个 + 第二行 10 个）。
     * 「最近使用」为空时就显示这一套，和参考图完全一致。
     */
    val defaultSymbolGrid: List<String> = listOf(
        "−", "—", ";", "#", "%", "&", "^", "*", "+", "=",
        "-", "/", ":", "~", "(", ")", "…", "@", "“", "”",
    )

    /**
     * 符号分类（底部类别栏的顺序，可左右滑动访问）。
     *
     * 每个分类最多取 20 个符号，正好铺满上方两行（每行 10 个）。
     * 第一个「最近使用」为空时回退到 [defaultSymbolGrid]（参考图那 20 个）。
     */
    val symbolCategories: List<Pair<String, List<String>>> = listOf(
        "最近使用" to emptyList(),
        "中文" to listOf(
            "，", "。", "、", "；", "：", "？", "！", "“", "”", "‘",
            "’", "（", "）", "《", "》", "……", "——", "·", "〈", "〉",
        ),
        "英文" to listOf(
            ",", ".", ";", ":", "?", "!", "\"", "'", "(", ")",
            "[", "]", "{", "}", "<", ">", "-", "_", "/", "\\",
        ),
        "括号/成对符号" to listOf(
            "(", ")", "[", "]", "{", "}", "〈", "〉", "《", "》",
            "「", "」", "『", "』", "【", "】", "〔", "〕", "〖", "〗",
        ),
        "货币符号" to listOf(
            "$", "€", "£", "¥", "₩", "₽", "₹", "¢", "₪", "₫",
            "₱", "₦", "₴", "₺", "₸", "₡", "₭", "₮", "₲", "﹩",
        ),
        "数学符号" to listOf(
            "+", "−", "×", "÷", "=", "≠", "≈", "≤", "≥", "±",
            "∞", "∑", "∏", "∫", "√", "π", "°", "‰", "µ", "∴",
        ),
        "数字/序号" to listOf(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "①", "②", "③", "④", "⑤", "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ",
        ),
        "拉丁变音字母" to listOf(
            "ā", "á", "ǎ", "à", "ē", "é", "ě", "è", "ī", "í",
            "ǐ", "ì", "ō", "ó", "ǒ", "ò", "ū", "ú", "ǔ", "ù",
        ),
        "日文假名" to listOf(
            "あ", "い", "う", "え", "お", "か", "き", "く", "け", "こ",
            "さ", "し", "す", "せ", "そ", "た", "ち", "つ", "て", "と",
        ),
        "其他扩展符号" to listOf(
            "★", "☆", "♥", "♡", "☀", "☁", "☂", "☺", "♪", "♫",
            "♬", "※", "§", "¶", "†", "•", "→", "←", "↑", "↓",
        ),
    )

    /**
     * 符号键盘（严格按参考图）。
     *
     *   第一行（共 10 键）：分类符号 1~10
     *   第二行（共 10 键）：分类符号 11~20
     *   第三行（共 8 键）：123 / ° / ， / \ / ? / ! / . / 退格
     *   类别栏：最左侧「返回」固定不动，右侧分类可左右滑动
     *
     * 固定 4 行，行数不随分类变化，避免切换分类时整个键盘高度跳变。
     */
    fun symbols(categoryIndex: Int, recentSymbols: List<String>, actionLabel: String): List<KeyRow> {
        val isRecent = categoryIndex == 0
        val grid = if (isRecent) {
            recentSymbols.ifEmpty { defaultSymbolGrid }
        } else {
            symbolCategories.getOrNull(categoryIndex)?.second ?: defaultSymbolGrid
        }
        // 分类内容固定两行、每行 10 个（不足时用空键补齐位置，保持行高一致）
        val flat = grid.take(20)
        val row1 = (0 until 10).map { i -> flat.getOrNull(i)?.let { sym(it) } ?: KeyDef(action = KeyAction.None) }
        val row2 = (10 until 20).map { i -> flat.getOrNull(i)?.let { sym(it) } ?: KeyDef(action = KeyAction.None) }
        // 固定控制行（按参考图：123 / ° / ， / \ / ? / ! / . / 退格）
        val row3 = listOf(
            KeyDef(
                label = "123",
                action = KeyAction.SwitchLayout(LayoutType.NUMBERS),
                weight = 1.35f,
                isFunction = true,
                bold = true,
            ),
            sym("°"),
            sym("，"),
            sym("\\"),
            sym("?"),
            sym("!"),
            sym("."),
            KeyDef(
                icon = R.drawable.ic_backspace,
                action = KeyAction.Backspace,
                weight = 1.35f,
                isFunction = true,
                repeatOnHold = true,
            ),
        )
        // 类别栏：最左侧返回键固定，其余分类可左右滑动（固定列宽 + 内容自适应）
        val row4 = buildList {
            add(
                KeyDef(
                    icon = R.drawable.ic_arrow_back,
                    action = KeyAction.BackToKeyboard,
                    weight = 1f,
                    isFunction = true,
                ),
            )
            symbolCategories.forEachIndexed { index, pair ->
                add(
                    KeyDef(
                        label = pair.first,
                        action = KeyAction.SymbolCategory(index),
                        weight = 1f,
                        isFunction = true,
                        active = index == categoryIndex,
                    ),
                )
            }
        }
        return listOf(
            KeyRow(row1),
            KeyRow(row2),
            KeyRow(row3),
            KeyRow(row4, scrollable = true, fixedCount = 1),
        )
    }

    /**
     * 符号键。
     * 必须支持自定义权重：数字键盘左列（@ % -）要与底部的返回键同为 LEFT_COL，
     * 否则上面三行总权重是 5.75、底部是 5.85，两行单位宽度不同，永远对不齐。
     */
    private fun sym(symText: String, weight: Float = 1f) =
        KeyDef(label = symText, action = KeyAction.Commit(symText), weight = weight, isFunction = false)

    // —— 数字键盘：按参考图（九宫格 + 左右功能列）——
    //   @  1  2  3  ⌫
    //   %  4  5  6  空格
    //   -  7  8  9  😀（表情）
    //   ←  符号 0  .  前往
    // 左列 3 个符号 + 返回键，中间九宫格 1-9，右列功能键，共 4 行。
    fun numbers(actionLabel: String): List<KeyRow> = listOf(
        KeyRow(
            listOf(
                sym("@", LEFT_COL),
                digit('1'), digit('2'), digit('3'),
                KeyDef(
                    icon = R.drawable.ic_backspace,
                    action = KeyAction.Backspace,
                    weight = RIGHT_COL,
                    isFunction = true,
                    repeatOnHold = true,
                ),
            ),
        ),
        KeyRow(
            listOf(
                sym("%", LEFT_COL),
                digit('4'), digit('5'), digit('6'),
                KeyDef(icon = R.drawable.ic_space, action = KeyAction.Space, weight = RIGHT_COL, isFunction = true),
            ),
        ),
        KeyRow(
            listOf(
                sym("-", LEFT_COL),
                digit('7'), digit('8'), digit('9'),
                KeyDef(
                    icon = R.drawable.ic_emoji,
                    action = KeyAction.OpenPanel(PanelType.EMOJI),
                    weight = RIGHT_COL,
                    isFunction = true,
                ),
            ),
        ),
        KeyRow(
            listOf(
                KeyDef(
                    icon = R.drawable.ic_arrow_back,
                    action = KeyAction.BackToKeyboard,
                    weight = LEFT_COL,
                    isFunction = true,
                ),
                KeyDef(
                    label = "符号",
                    icon = R.drawable.ic_symbols,
                    action = KeyAction.SwitchLayout(LayoutType.SYMBOLS),
                    weight = NUM_COL,
                    isFunction = true,
                ),
                subActionKey("0"),
                KeyDef(label = ".", action = KeyAction.Commit("."), weight = NUM_COL),
                // 回车键用右列权重，与上面几行的最后一列严格对齐（大小一致）
                actionKey(actionLabel, weight = RIGHT_COL),
            ),
        ),
    )

    /** 数字 0：交给 Rime 处理（与拼音数字行为一致）。 */
    private fun subActionKey(label: String) = KeyDef(
        label = label,
        action = KeyAction.Rime(label[0].code),
        weight = NUM_COL,
    )

    // 数字键盘各列权重：左列 1.1 + 中间 1.15×3 + 右列 1.3 = 5.85，四行完全对齐
    private const val LEFT_COL = 1.1f
    private const val NUM_COL = 1.15f
    private const val RIGHT_COL = 1.3f
}