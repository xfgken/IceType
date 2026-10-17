# IceType 架构说明

## 1. 分层

```
┌───────────────────────────────────────────────────────────────┐
│ 应用界面层（Compose + Material 3）                            │
│  MainActivity / SettingsActivity → ui/IceTypeApp.kt            │
│  主页（状态卡片、设置卡片、快捷入口、版本与初始化状态）        │
│  子页：键盘与输入 / 外观主题 / 声音与震动 / 剪贴板 / 常用语 /  │
│        表情键盘 / 词库与语言 / 隐私与学习 / 关于 / 诊断日志     │
└───────────────┬───────────────────────────────────────────────┘
                │ 读写 IcePrefs（StateFlow）、调用引擎管理
┌───────────────▼───────────────────────────────────────────────┐
│ 输入法层（自定义 View，不用 Compose）                          │
│  IceInputMethodService                                         │
│   ├── 工具栏（设置 / 表情 / 中英切换 / 剪贴板 / 收起）         │
│   ├── CandidateBarView（候选栏、翻页、高亮）                   │
│   ├── KeyboardView（自绘键盘、触摸、长按重复、按下状态层）      │
│   ├── PanelView（剪贴板 / 常用语 / 表情）                      │
│   └── KeyboardLayouts（中文拼音 / 英文 / 符号 / 数字）          │
└───────────────┬───────────────────────────────────────────────┘
                │ RimeEngine（线程内同步调用）
┌───────────────▼───────────────────────────────────────────────┐
│ 引擎层                                                         │
│  RimeEngine      初始化 / 部署 / 会话 / 候选 / 选项 / 补丁      │
│  RimeAssetInstaller  资源释放（共享目录版本化管理）            │
│  RimeNative（Kotlin external） ──JNI──► icetype_rime.cpp       │
│                                         └─► librime.so         │
└───────────────┬───────────────────────────────────────────────┘
                │ 本地文件
┌───────────────▼───────────────────────────────────────────────┐
│ 数据层（全部在应用私有目录，无网络）                            │
│  rime_shared/   只读词库资源（可被资源升级整体替换）            │
│  rime_user/     用户数据（user.yaml、userdb、build），永不覆盖  │
│  data/*.json    剪贴板历史、常用语、表情最近使用                │
│  logs/          诊断日志（2MB 上限，自动轮换）                  │
└───────────────────────────────────────────────────────────────┘
```

## 2. 为什么输入法 UI 用自定义 View 而不是 Compose

开发文档第 1 节明确允许「根据工程与依赖实际兼容性选用自定义 View 或经过验证的 Compose 容器」。
本项目选择自定义 View，理由是：

1. **生命周期匹配**：输入法视图由系统在 `onCreateInputView` 中一次性获取并长期复用；
   Compose 组合器的重组时机与输入法窗口时序耦合较差，历史上有焦点抖动问题。
2. **连续输入性能**：自绘键盘在按下态、连续按键、长按重复上只需要 `invalidate()`，
   不需要经过组合与布局阶段。
3. **候选栏的即时刷新**：每次按键后都要刷新候选，自绘 View 更新成本最低。
4. **可预测性**：键盘尺寸完全由 `onMeasure` 决定，不受主题/字体缩放影响。

应用设置页则全部使用 Compose + 官方 Material 3 组件（`Card`、`ListItem`、`Switch`、`Slider`、
`TopAppBar`、`Scaffold`、`AlertDialog` 等），不做自造组件。

## 3. 原生层设计

### 3.1 librime 的获取方式

设备上不存在任何可直接复用的 librime 原生库：

- Trime 的 `librime_jni.so` 是 GPLv3 且 JNI 与它自己的类名硬绑定；
- fcitx5-android 的 `librime.so` 额外依赖 `libFcitx5Core.so` 等，不是独立引擎。

因此本项目**从源码编译 librime 1.17.0**：

| 依赖 | 处理方式 |
| --- | --- |
| Boost | 只用到 `boost::regex` 需要编译，其余是头文件；用 NDK clang 手工编译 Boost.Regex 静态库 |
| leveldb / marisa-trie / yaml-cpp / opencc | 用 librime 自带子模块，NDK 工具链交叉编译为静态库 |
| Lua | librime-lua 插件要求 Lua；使用官方 5.4.7 源码，去掉 `lua.c` / `luac.c` 后编入库中 |
| librime-lua | 放入 `librime/plugins/`，由 librime 的 CMake 自动发现并合并进同一个 `librime.so` |

### 3.2 自研 JNI 层

`app/src/main/cpp/icetype_rime.cpp` 不依赖任何第三方 JNI 框架，直接：

- 通过 `rime_get_api()` 获取**版本化的 API 结构体**，并用 `RIME_API_AVAILABLE` 做能力探测
  （比直接链接导出符号更稳，缺失的函数不会导致加载失败）；
- 把上下文（组合串 + 当前页候选）与状态整体序列化为 JSON 字符串回传，
  避免高频跨边界调用；
- 通过 `RimeSetNotificationHandler` 把部署进度/失败原因回调给 Kotlin，
  不依赖 glog（Android 侧 `ENABLE_LOGGING=OFF`）。

### 3.3 资源部署策略

| 目录 | 内容 | 升级行为 |
| --- | --- | --- |
| `filesDir/rime_shared` | 方案 yaml、词库源文件、Lua 脚本、opencc 数据、`default.yaml` | 资源版本号变化时**整体替换** |
| `filesDir/rime_user` | `user.yaml`、`installation.yaml`、用户词典 `*.userdb`、编译产物 | **永不删除**，只有用户明确点击"重置"才会清理 |

打包策略基于实测：**必须包含 `cn_dicts/` 等源词典**。实测去掉源词典后部署仍返回成功，
但中文候选会全部失效（`nihao` → 0 个候选）；不打包 `build/` 预编译产物（约 74MB）以把
APK 体积减半，改由首次启动编译一次。

## 4. 编辑器适配

`onStartInput` 中解析 `EditorInfo`：

| 输入框类型 | 处理 |
| --- | --- |
| 文本 / 多行 | 中文拼音键盘（或按设置默认英文） |
| 密码（文本/数字） | 强制英文布局，不做学习，不写剪贴板历史 |
| 邮箱 / 网址 / 电话 / 数字 | 依据 `inputType` 选择布局；数字类使用数字键盘更合适（当前实现保留英文/中文键盘，见已知限制） |
| 动作键 | 依据 `imeOptions` 显示 前往 / 搜索 / 发送 / 下一步 / 完成 / 换行 / 回车 |

`onStartInputView` 中对 `TYPE_TEXT_FLAG_CAP_SENTENCES` / `CAP_WORDS` 的英文输入框自动点亮 Shift。

## 5. 隐私设计

- 未在 `AndroidManifest.xml` 中申请任何权限（包括网络权限）；
- 日志只有 8 个类别（初始化 / 词库部署 / Rime 引擎 / 用户界面 / 输入行为 / 剪贴板 / 隐私 / 错误），
  **不写入**输入文本、密码、剪贴板正文与完整候选；
- 剪贴板历史默认关闭，仅在用户主动打开面板时读取当前内容；
- 日志导出前先展示脱敏预览并要求确认；
- 破坏性操作（清空历史、重置词频、重置初始化）均二次确认。

## 7. 严格遵循的 Material 3 令牌（布局 / 点击态）

界面的外形与交互状态全部取自参考目录的官方 Token，不凭印象取值：

| 位置 | 使用的官方令牌 | 取值 |
| --- | --- | --- |
| 按钮栏容器底色 | `FloatingToolbarTokens.ContainerColor` | `surfaceContainer` |
| 按钮栏外框 | `OutlinedIconButtonTokens.OutlineColor` | `outlineVariant`，1dp 描边 |
| 按钮栏圆角 | `FloatingToolbarTokens.ContainerShape` | `CornerFull`（全圆角） |
| 按钮栏高度 | `FloatingToolbarTokens.ContainerHeight` | 官方 64dp → 输入法内适配为 44dp |
| 按钮栏外边距 | `FloatingToolbarTokens.ContainerExternalPadding` | 官方 16dp → 适配为 8dp（避免挤压候选词） |
| 工具栏图标色 | `IconButtonTokens.Color` | `onSurfaceVariant` |
| 工具栏选中色 | `IconButtonTokens.SelectedColor` | `primary` |
| 工具栏按钮尺寸 | `IconButtonTokens` 容器/图标 | 40dp 容器 / 24dp 图标 |
| **按下状态层** | `StateTokens.PressedStateLayerOpacity` | **0.10**（按键与图标按钮通用） |
| 涟漪展开时长 | 参考 `MotionTokens` 中时长量级 | 320ms（自绘 ripple 的扩散+淡出） |
| 按键圆角 | `ShapeTokens.CornerMedium` | 12dp（设置页可改，范围含 8/12/16/28） |
| 面板卡片 | `OutlinedCard` 造型 | `surfaceContainerLowest` + `outlineVariant` 边框 + `CornerLarge`(16dp) |
| 面板卡片点击 | M3 ripple（`RippleDrawable`，状态层 0.10） | — |

实现方式：

- **按键（自定义 View）**：`KeyboardView` 在按下时先叠加「内容色 10%」的状态层，
  再从触点绘制一个扩散并淡出的圆形涟漪（`postInvalidateOnAnimation` 驱动）。
  状态层颜色取内容色：普通/功能键用 `onSurface`，主操作键（primary 容器）用 `onPrimary`。
- **按钮栏图标按钮（平台 View）**：使用平台 `RippleDrawable`
  （遮罩为 `CornerFull` 圆形，状态层 = `onSurfaceVariant` 10%）。
- **面板卡片**：`foreground` 设为 `RippleDrawable`，遮罩为 `CornerLarge` 圆角矩形。

> 适配说明：M3 的 FloatingToolbar 高 64dp、外边距 16dp，用于输入法会挤占键盘高度，
> 因此按比例压缩为 44dp / 8dp；其余（颜色角色、描边、圆角、状态层、动效时长）
> 均严格使用官方令牌值。

## 8. 键盘候选行（候选栏 / 联想 / 高度统一）

| 项 | 实现 |
| --- | --- |
| 那一行的两种形态 | 空闲时是工具栏（设置 / 表情 / **zh·en** / 剪贴板 / 收起）；输入时整行变成候选栏 |
| 候选栏布局 | 上方一行**小字拼音**（12sp、次要色、细下划线）；下方**加粗加大候选词**（19sp、sans-serif-medium BOLD），整行垂直居中 |
| 每页候选数 | **6 个**（通过用户补丁 `menu/page_size: 6` 写入 `rime_ice.custom.yaml` 并重新部署） |
| 翻页 | **左右滑动**候选栏翻页；右上角一条细进度条提示“还有更多”，不再有翻页按钮 |
| 中英切换 | 工具栏按钮直接显示 **zh / en** 文本，随当前模式实时变化（底部切换键同理） |
| 联想（选词后） | **前缀续写**：以已上屏内容为前缀的词，例如「你好」→你好吗/你好啊/你好坏；单字上屏则给以该字开头的常用词。右下角一个**纯圆 ✕** 关闭联想，不显示「联想」占位文字 |
| 联想数据 | `assets/predict/lexicon.txt`（构建期生成，约 1.6MB，静态只读、不含用户输入） |
| 高度统一 | 键盘区高度 = 最大行数(5) × 单键高度 + 间距；**行数少的布局自动加高按键**；面板（剪贴板/常用语/表情）与键盘**完全同高**，容器总高固定为「按钮栏 + 键盘区」，因此切换任何页面高度都不变 |
| 键盘高度默认 | 单键 **38dp**（可在「键盘与输入」中调 30–56dp） |

## 9. 关键取舍记录

| 取舍 | 原因 |
| --- | --- |
| 不引入 Room / Navigation / 序列化库 | 数据量小、层级浅，手写 JSON 与状态切换即可；减少依赖与体积 |
| 设置使用 SharedPreferences 而非 DataStore | 输入法需要在 `onStartInputView` 同步读取键盘参数 |
| 候选栏的"更多候选" | 末页时改为移动高亮（librime `highlight_candidate_on_current_page`），不虚构一个无效按钮 |
| 按键音不提供音量滑块 | Android 没有公开接口可按输入法调整该音效音量，做了也是无效控件 |
| 符号键盘 | 10 个分类全部有真实符号表，点击直接上屏并写入"最近使用" |
