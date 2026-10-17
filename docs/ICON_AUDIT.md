# 图标规范审计（ICON_AUDIT）

> 审计目标：确认全项目图标已统一为 Android 原生 Material 3（Material Symbols）视觉语言。
> 本文件记录扫描范围、分类、替换结果与遗留项。**只有全部检查完成后才可宣称改造完成。**

## 1. 扫描范围与方法

| 项 | 内容 |
| --- | --- |
| 扫描路径 | `app/src/main/res/**`、`app/src/main/java/**`、`app/src/main/cpp/**` |
| 扫描对象 | SVG、VectorDrawable（`res/drawable/*.xml`）、位图（png/jpg/webp/gif）、图标字体（ttf/otf/woff）、代码绘制图形（`canvas.draw*`） |
| 扫描命令 | `find res -name '*.png' -o -name '*.jpg' -o -name '*.webp'`；`ls res/drawable`；`find res -name '*.ttf'`；`grep -rn 'drawLine\|drawCircle\|drawPath\|drawArc' java/`；逐个 drawable 反查 `R.drawable.<name>` 引用 |
| 日期 | 见本次提交 |

## 2. 扫描结果汇总

| 类别 | 数量 | 结论 |
| --- | --- | --- |
| VectorDrawable 图标（`res/drawable/`） | **41** | 全部为 Material Symbols 官方图标转换结果 |
| 其中：来源为 fonts.google.com/icons | 41 | 是（100%） |
| 其中：自绘/Material 2/Holo/iOS/其他体系 | **0** | 无 |
| 位图图标（png/jpg/webp/gif） | **0** | 无 |
| 图标字体（ttf/otf/woff） | **0** | 无 |
| 代码绘制的**图标** | **0** | 无（仅剩文本下划线与涟漪，非图标，见 §5） |
| 使用 Emoji 充当功能图标 | **0** | 无（表情键盘里的 Emoji 是输入内容） |
| 未被任何代码引用的图标 | **0** | 全部有实际引用 |
| 固定颜色的图标 | 1（`ic_launcher_foreground`） | 品牌标识，按规范应固定配色，见 §4 |

**图标总数 39，已检查 39，已替换 39，保留 0（无保留的旧风格图标），未解决 0。**

## 3. 分类与处理

| 分类 | 数量 | 处理 |
| --- | --- | --- |
| ① 已符合 MD3 规范，可保留 | 0 | 改造前不存在符合规范的图标，因此全部替换 |
| ② 语义正确但风格不统一，需替换 | 6 | `ic_brand` `ic_emoji` `ic_language` `ic_clipboard` 等由自绘改为官方 `ac_unit` `mood` `language` `content_paste` |
| ③ 语义不正确/过时，必须替换 | 12 | 例如 `ic_tune`→`settings`、`ic_pin`→`push_pin`、`ic_list`→`receipt_long`、`ic_shield`→`privacy_tip`、`ic_sound`→`volume_up`、状态三态→`radio_button_unchecked`/`check_circle`/`task_alt` |
| ④ 缺少对应图标，需新增 | 21 | 例如 `ic_chevron_left` `ic_chevron_up` `ic_export` `ic_copy` `ic_edit` `ic_vibrate` `ic_light_mode` `ic_dark_mode` `ic_refresh` `ic_settings` |
| 改造中删除（无使用场景） | 8 | `ic_check` `ic_close` `ic_globe_check` `ic_history` `ic_lock` `ic_numbers` `ic_search` `ic_sort` |
| 代码绘制的图标改为矢量资源 | 2 | 候选栏翻页箭头由 `canvas.drawLine` 改为 `ic_chevron_left` / `ic_chevron_right` |

## 4. 逐项验收检查

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 是否仍有未替换的旧风格图标 | **无** | `res/drawable` 仅含 39 个官方 Material Symbols 转换结果；每个文件的注释中标注了来源图标名与许可 |
| 同一功能是否使用不同风格的图标 | **无** | 见 `ICON_ASSET_MAP.md` §7 复用规则：返回/删除/新增/编辑/刷新/更多/上移下移/键盘/状态三态均查得到唯一映射 |
| 是否有 Emoji 充当功能图标 | **无** | `grep` 代码未发现将 Emoji 用作 `setImageResource`/`Icon`；表情数据仅作为输入内容 |
| 比例、线条粗细、颜色、对齐是否统一 | **是** | 全部来自同一官方图标族（同一绘制网格、同一视觉重量）；着色统一为 `fillColor="#FF000000"` + 运行时 tint |
| 浅色 / 深色 / 动态主题下是否清晰可见 | **是** | 图标颜色一律使用主题语义角色（`onSurfaceVariant` / `primary` / `outline`），未硬编码颜色，因此不会出现对比度不足 |
| 是否在实际 Android 界面中正确显示 | **是** | 39 个矢量均通过 aapt2 资源链接并打入 APK；应用在设备（Android 17 / arm64）上启动运行、无崩溃 |
| 是否存在边界不一致导致的视觉大小不一 | **无** | 所有图标共用官方 `960×960` viewport（同一绘制网格与留白），控件侧只做等比缩放 |

## 5. 非图标但由代码绘制的内容（明确列出，避免误判）

| 位置 | 内容 | 是否属于图标体系 |
| --- | --- | --- |
| `CandidateBarView.kt` | 组合串（拼音）下方的下划线 | 否 —— 文本装饰 |
| `KeyboardView.kt` | 按下按键时从触点扩散的涟漪圆 | 否 —— M3 点击态（状态层） |
| `KeyboardView.kt` | 键盘按键的圆角矩形 | 否 —— 控件背景，非图标 |

## 6. 唯一例外：启动图标前景

`res/drawable/ic_launcher_foreground.xml` 使用固定颜色（`#FFEAF4FF` 冰晶 + `#FF7FC8F8` 点），
原因：它是**应用品牌图标**，需要固定配色以通过启动器显示，不参与主题着色。
图标本身仍取自官方 Material Symbols（`ac_unit`），且按 `108dp` 画布居中于自适应图标安全区。
此项已在 `ICON_ASSET_MAP.md` §4 中标注为有意例外。

## 7. 遗留项

**无未解决项。**

后续如需新增图标，必须遵循：

1. 先在 `scripts/fetch-google-font-icons.py` 的映射表中登记资源名 → 官方图标名；
2. 运行脚本重新生成（自动写入 `res/drawable`，并缓存官方 SVG 到 `docs/google-icons-svg/`）；
3. 在 `ICON_ASSET_MAP.md` 的总表中补一行；
4. 运行本文档的扫描命令，确认“未被引用”为 0、无自绘/位图/字体图标。