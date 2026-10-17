# 图标资源清单（ICON_ASSET_MAP）

> 本文件记录 IceType 全部图标的：名称、用途、所属页面/组件、原始资源路径、最终资源路径、
> 来源、是否符合 MD3 规范、是否完成替换与验证。图标总数为 **41**，详见下表。

---

## 1. 统一设计标准（强制）

| 项 | 规定 |
| --- | --- |
| 图标体系 | **仅使用 Material Symbols（Outlined）**，即 Android 原生 Material 3 图标语言 |
| 尺寸规范 | 全部为 `24dp` 画布 / viewport `960×960`（官方 Material Symbols 的视图框），再由控件按需缩放 |
| 线条与视觉重量 | 由官方资源统一保证，不允许自制图标混入 |
| 填充方式 | 默认 Outlined（线性）；本项目的选中态/状态态由**颜色角色**表达（`primary` / `onSurfaceVariant`），不混用 Filled 图标 |
| 着色 | 矢量内只写 `fillColor="#FF000000"`，实际颜色由主题系统控制（Compose `Icon(tint=)` / `Drawable.setTint()`） |
| 主题适配 | 浅色 / 深色 / 动态颜色均取自 `MaterialTheme.colorScheme` 语义角色 |
| Emoji | **不使用 Emoji 充当功能图标**（表情键盘中的 Emoji 是输入内容，不是图标） |
| 禁止 | 不混用 Material 2 / Holo / iOS / Font Awesome / 游戏 / 拟物风格 |

## 2. 图标来源与生成方式

官方 SVG 的下载地址（fonts.google.com/icons 页面实际加载的资源）：

```
https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/<名称>/default/24px.svg
```

- 官方 SVG 原文件已缓存进仓库：`docs/google-icons-svg/`（44 个 `.svg`）
- 转换脚本：`scripts/fetch-google-font-icons.py`
  - 读取缓存的 SVG（缺失时才联网下载）
  - 把 `viewBox="0 -960 960 960"` 的路径转成 VectorDrawable：
    `android:viewportWidth/Height="960"` + `<group android:translateY="960">`
  - 输出到 `app/src/main/res/drawable/`
- 许可：Material Symbols 采用 **Apache License 2.0**（Copyright (C) The Android Open Source Project）

## 3. 图标总表

| # | 图标名称 | 用途 | 所属页面 / 组件 | 原始资源 | 最终资源 | 来源 | 符合 MD3 | 已替换并验证 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `ic_add` | 新增常用语、剪贴板条目「插入」 | 常用语页 / 面板条目菜单 | `add` | `res/drawable/ic_add.xml` | Material Symbols | 是 | 是 |
| 2 | `ic_arrow_back` | 返回 | 面板头部 / 设置子页顶栏 | `arrow_back` | `res/drawable/ic_arrow_back.xml` | Material Symbols | 是 | 是 |
| 3 | `ic_backspace` | 退格键 | 键盘（中文/符号/数字） | `backspace` | `res/drawable/ic_backspace.xml` | Material Symbols | 是 | 是 |
| 4 | `ic_book` | 词库与语言卡片 | 主页设置卡片 | `menu_book` | `res/drawable/ic_book.xml` | Material Symbols | 是 | 是 |
| 5 | `ic_brand` | 品牌标识、工具栏最左「设置」入口 | 主页标题 / 键盘工具栏 | `ac_unit` | `res/drawable/ic_brand.xml` | Material Symbols | 是 | 是 |
| 6 | `ic_chevron_down` | 下移、收起 | 常用语页 / 面板条目菜单 | `expand_more` | `res/drawable/ic_chevron_down.xml` | Material Symbols | 是 | 是 |
| 7 | `ic_chevron_left` | 候选栏上一页 | 候选栏 | `chevron_left` | `res/drawable/ic_chevron_left.xml` | Material Symbols | 是 | 是 |
| 8 | `ic_chevron_right` | 候选栏下一页、卡片可点击指示 | 候选栏 / 主页 / 设置页 | `chevron_right` | `res/drawable/ic_chevron_right.xml` | Material Symbols | 是 | 是 |
| 9 | `ic_chevron_up` | 上移 | 常用语页 / 面板条目菜单 | `expand_less` | `res/drawable/ic_chevron_up.xml` | Material Symbols | 是 | 是 |
| 10 | `ic_clipboard` | 剪贴板入口 | 键盘工具栏 / 主页卡片 | `content_paste` | `res/drawable/ic_clipboard.xml` | Material Symbols | 是 | 是 |
| 11 | `ic_copy` | 复制 | 剪贴板条目菜单 | `content_copy` | `res/drawable/ic_copy.xml` | Material Symbols | 是 | 是 |
| 12 | `ic_dark_mode` | 深色主题 | 外观主题页 | `dark_mode` | `res/drawable/ic_dark_mode.xml` | Material Symbols | 是 | 是 |
| 13 | `ic_delete` | 删除、清空 | 常用语页 / 剪贴板页 / 日志页 / 各菜单 | `delete` | `res/drawable/ic_delete.xml` | Material Symbols | 是 | 是 |
| 14 | `ic_edit` | 编辑 | 常用语页 / 面板条目菜单 | `edit` | `res/drawable/ic_edit.xml` | Material Symbols | 是 | 是 |
| 15 | `ic_emoji` | 表情入口 | 键盘工具栏 / 主页卡片 | `mood` | `res/drawable/ic_emoji.xml` | Material Symbols | 是 | 是 |
| 16 | `ic_enter` | 回车 / 动态动作键 | 键盘底行 | `keyboard_return` | `res/drawable/ic_enter.xml` | Material Symbols | 是 | 是 |
| 17 | `ic_export` | 导出日志 | 诊断日志页 | `file_upload` | `res/drawable/ic_export.xml` | Material Symbols | 是 | 是 |
| 18 | `ic_info` | 关于与日志卡片、快捷入口 | 主页 | `info` | `res/drawable/ic_info.xml` | Material Symbols | 是 | 是 |
| 19 | `ic_keyboard` | 键盘布局、返回字母键盘、测试键盘 | 主页 / 键盘底行 / 快捷入口 | `keyboard` | `res/drawable/ic_keyboard.xml` | Material Symbols | 是 | 是 |
| 20 | `ic_keyboard_hide` | 收起键盘 | 键盘工具栏最右 | `keyboard_hide` | `res/drawable/ic_keyboard_hide.xml` | Material Symbols | 是 | 是 |
| 21 | `ic_language` | 中英切换 | 键盘工具栏 / 键盘底行 | `language` | `res/drawable/ic_language.xml` | Material Symbols | 是 | 是 |
| 22 | `ic_launcher_foreground` | 启动图标前景 | 自适应图标 | `ac_unit` | `res/drawable/ic_launcher_foreground.xml` | Material Symbols | 是 | 是 |
| 23 | `ic_light_mode` | 浅色主题 | 外观主题页 | `light_mode` | `res/drawable/ic_light_mode.xml` | Material Symbols | 是 | 是 |
| 24 | `ic_list` | 查看日志快捷入口 | 主页快捷入口 | `receipt_long` | `res/drawable/ic_list.xml` | Material Symbols | 是 | 是 |
| 25 | `ic_more_vert` | 更多菜单 | 面板头部 / 面板条目 | `more_vert` | `res/drawable/ic_more_vert.xml` | Material Symbols | 是 | 是 |
| 26 | `ic_phrase` | 常用语卡片 | 主页设置卡片 | `text_snippet` | `res/drawable/ic_phrase.xml` | Material Symbols | 是 | 是 |
| 27 | `ic_pin` | 固定 / 置顶 | 常用语页 / 面板条目菜单 | `push_pin` | `res/drawable/ic_pin.xml` | Material Symbols | 是 | 是 |
| 28 | `ic_refresh` | 刷新 | 剪贴板页 / 诊断日志页 | `refresh` | `res/drawable/ic_refresh.xml` | Material Symbols | 是 | 是 |
| 29 | `ic_settings` | 「打开设置」菜单项 | 面板更多菜单 | `settings` | `res/drawable/ic_settings.xml` | Material Symbols | 是 | 是 |
| 30 | `ic_shield` | 隐私与学习卡片 | 主页设置卡片 | `privacy_tip` | `res/drawable/ic_shield.xml` | Material Symbols | 是 | 是 |
| 31 | `ic_shift` | 上档键 | 键盘（字母行） | `shift` | `res/drawable/ic_shift.xml` | Material Symbols | 是 | 是 |
| 32 | `ic_sound` | 声音与震动卡片、按键音开关 | 主页 / 声音与震动页 | `volume_up` | `res/drawable/ic_sound.xml` | Material Symbols | 是 | 是 |
| 33 | `ic_status_active` | 输入法状态：正在使用 | 主页状态卡片 | `task_alt` | `res/drawable/ic_status_active.xml` | Material Symbols | 是 | 是 |
| 34 | `ic_status_disabled` | 输入法状态：未启用 | 主页状态卡片 | `radio_button_unchecked` | `res/drawable/ic_status_disabled.xml` | Material Symbols | 是 | 是 |
| 35 | `ic_status_enabled` | 输入法状态：已启用但未选中 | 主页状态卡片 | `check_circle` | `res/drawable/ic_status_enabled.xml` | Material Symbols | 是 | 是 |
| 36 | `ic_symbols` | 符号键盘入口 | 键盘（数字页「更多符号」） | `emoji_symbols` | `res/drawable/ic_symbols.xml` | Material Symbols | 是 | 是 |
| 37 | `ic_theme` | 外观主题卡片、跟随系统 | 主页 / 外观主题页 | `contrast` | `res/drawable/ic_theme.xml` | Material Symbols | 是 | 是 |
| 38 | `ic_tune` | 「启用输入法」按钮 | 主页状态卡片 | `settings` | `res/drawable/ic_tune.xml` | Material Symbols | 是 | 是 |
| 39 | `ic_vibrate` | 震动开关 | 声音与震动页 | `vibration` | `res/drawable/ic_vibrate.xml` | Material Symbols | 是 | 是 |
| 40 | `ic_cancel` | 状态：未启用（红色叉） | 主页状态卡片 | `cancel` | `res/drawable/ic_cancel.xml` | Material Symbols | 是 | 是 |
| 41 | `ic_close` | 关闭联想 / 关闭 | 候选栏联想态右侧 ✕ | `close` | `res/drawable/ic_close.xml` | Material Symbols | 是 | 是 |

## 4. 启动图标

| 文件 | 说明 |
| --- | --- |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标：background 纯色 + foreground + monochrome |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | 圆形自适应图标 |
| `res/drawable/ic_launcher_foreground.xml` | 前景：Material `ac_unit`，按 108dp 画布缩放并居中于安全区 |
| `res/values/colors.xml` | `ic_launcher_background = #0E1A2B` |

> 说明：启动图标前景是**唯一**使用固定颜色（浅色冰晶 + 蓝色点）的矢量资源。
> 这是品牌标识而非 UI 图标，必须固定配色，且不参与主题着色；其余 38 个图标全部为
> `fillColor="#FF000000"`，随主题着色。

## 5. 非图标的绘制内容（说明）

以下两处是**绘制而非图标**，不属于图标体系，故保留自绘：

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| `CandidateBarView` | 组合串下方的下划线 | 文本装饰（表示正在组词），非图标 |
| `KeyboardView` | 按下时的涟漪圆 | M3 点击态（状态层扩散），非图标 |

## 6. 主题适配

- **Compose 侧**：`Icon(painterResource(...), tint = MaterialTheme.colorScheme.<角色>)`，
  角色使用 `onSurfaceVariant`（普通）、`primary`（选中/强调）、`outline`（禁用）。
- **键盘/面板（平台 View）侧**：`Drawable.setTint(KeyboardTheme.palette(...).<角色>)`，
  调色板来自 `lightColorScheme()` / `darkColorScheme()` / `dynamic*ColorScheme()`。
- 浅色与深色下均使用语义角色，因此不存在“深色下看不见”的问题。

## 7. 复用规则

同一功能在所有页面复用同一个资源，避免同一语义出现多种画风：

| 功能 | 统一使用的资源 |
| --- | --- |
| 返回 | `ic_arrow_back` |
| 删除 / 清空 | `ic_delete` |
| 新增 / 插入 | `ic_add` |
| 编辑 | `ic_edit` |
| 刷新 | `ic_refresh` |
| 更多 | `ic_more_vert` |
| 上移 / 下移 | `ic_chevron_up` / `ic_chevron_down` |
| 键盘相关 | `ic_keyboard` / `ic_keyboard_hide` |
| 状态三态 | `ic_status_disabled` / `ic_status_enabled` / `ic_status_active` |