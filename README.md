# IceType · 冰霜输入法

一个**完全离线**的 Android 中文拼音输入法。输入引擎是 [librime](https://github.com/rime/librime)，词库方案是 [雾凇拼音（Rime Ice）](https://github.com/iDvel/rime-ice)，界面用 Kotlin 手写：设置页是 Jetpack Compose，键盘本身是自绘 View。

- 包名：`com.xfgken.iceime`
- `minSdk = 29`（Android 10）／`targetSdk = 37`（Android 17）
- 仅 `arm64-v8a`
- **不申请网络权限**，没有统计 SDK、没有账号、没有云端，输入数据只留在本机

---

## 下载

| 方式 | 说明 |
|---|---|
| Releases 页 | 直接下载 `IceType-v1.1.0.apk` 安装（推荐） |
| 自行构建 | 见下面「从源码构建」 |

安装后在系统的「语言和输入法」里启用「冰霜输入法」并切换为默认输入法即可。

- 首次打开会自动释放约 45MB 的词典资源到应用私有目录并部署引擎（约 20 秒，之后秒开）
- 重装 APK 后系统有时会取消启用输入法，重新启用一次就行

---

## 功能

### 词库规模（v1.1.0 起）
| 词典 | 词条数 |
|---|---|
| `base` 基础词库 | 543,118 |
| `81448` 大字表 | 46,019 |
| `8105` 字表 | 8,757 |
| `ext` 扩展词库（专业词、术语、人名地名） | 339,186 |
| `tencent` 腾讯词向量词库（网络热词） | 981,545 |
| `others` 杂项 | 633 |
| **合计** | **1,919,258 条**（去重前） |

编译产物 `rime_ice.table.bin` 约 59MB，首次部署约 20 秒，常驻内存增加约 60–70MB。

### 输入
- 中文拼音：全拼、整句、简拼、混拼，候选分页与用户词频学习
- **模糊音**（设置 → 键盘与布局 → 模糊音）：总开关 + 6 组分组开关
  - 平翘舌 `zh=z ch=c sh=s`（默认开）、鼻边音 `n=l`（默认开）
  - 唇齿音 `f=h`、卷舌音 `l=r`、前鼻音 `an=ang en=eng`、后鼻音 `in=ing`
  - 只作用于全拼；开关变化即时生成 `rime_ice.custom.yaml` 的 `derive` 规则并重新部署
- **冷词隐藏**（长按候选）：降频（排到后面）/ 隐藏（不再出现）/ 取消，写入用户目录，升级不丢
- 英文：`melt_eng` 词表 + 内置英文词库前缀补全
- 数字、符号键盘；符号页底部是**可横向拖动**的分类条（带惯性）
- 长按「，」按钮向上拉长，松手得到「。」（英文为「.」）
- 候选栏两行：上行小字显示拼音音节，下行是候选词；右侧 ✕ 可关闭联想
- 拼音组合串写进目标输入框（系统下划线），候选栏只放候选
- 邮箱场景自动给出 `@qq.com` 一类域名候选
- 回车键文案跟随输入框（搜索 / 发送 / 前往 / 完成 / 换行）

### 键盘内面板
- **剪贴板**：历史记录、置顶、删除、纯文本粘贴
- **常用语**：一键上屏、置顶、删除；为空时点击提示直接跳到设置页
- **表情**：分类 + 常用 + 最近使用
- **选词**：文本智能分词成词块，右滑依次插入、回滑取消、点选切换

### 外观与反馈
- 按键音（可读取外部 `key_press_click.wav`，读不到用内置素材）
- 震动强度 0–50 档
- 深色 / 浅色 / 跟随系统；强调色由单一色相推导**整套**配色
- 动画：图标逆时针旋转切换（带回弹）、深浅切换渐变、按下涟漪、面板展开收起
- 有一个不太容易发现的彩蛋（长按最左侧品牌键 2 秒）

### 设置
主页（状态卡片 + 分组入口 + 版本与初始化状态）＋ 9 个设置页：键盘与布局、主题与外观、按键音与震动、剪贴板、常用语、表情、引擎与自检、隐私与学习、关于、日志。

---

## 从源码构建

需要：JDK 17、Gradle、Android SDK（platform 37）、NDK r27（重新编译 librime 时用）。

```bash
# 打包（产物复制到 Download 目录）
bash scripts/build-apk.sh
```

只想改 Kotlin / 资源时不用动原生库，`app/src/main/jniLibs/arm64-v8a/` 里已经放好编译好的 `librime.so`、`libiceime_rime.so` 与 `libc++_shared.so`。

本仓库直接包含：
- `app/src/main/assets/rime/`：雾凇拼音完整配置与词典（6 个中文词典，合计约 1.9M 词条，压缩前 45MB）
- `app/src/main/cpp/icetype_rime.cpp`：自研 JNI 层
- `keystore/icetype.jks`：release 签名（口令见 `app/build.gradle.kts`）

> 注意：仓库里的 keystore 是公开的，只适合自用分发，不要用它给应用商店包签名。

---

## 工程结构

```
app/src/main/java/com/xfgken/iceime/
├── MainActivity.kt            # 唯一入口，支持 --es page <枚举名> 直达设置页
├── core/                      # 配置（IcePrefs）、日志（AppLog）
├── rime/                      # 引擎：RimeNative(JNI 声明) / RimeEngine / 资源释放 / 自检
├── ime/                       # 键盘本体：输入法服务、自绘键盘、候选栏、面板、主题
├── data/                      # 剪贴板、常用语、表情、英文词库、联想词表、符号历史
└── ui/                        # Compose 设置页（主页 / 各分区 / 剪贴板页 / 常用语页）
```

更详细的说明（模块职责、JNI 与 R8 的坑、构建环境、已知问题、开发约定）见 [项目.md](项目.md)。

---

## 词库与上游同步

上游 [rime-ice](https://github.com/iDvel/rime-ice) 更新时，用仓库里的同步脚本一条命令完成：

```bash
# 只看计划（不动文件）
python3 scripts/sync-rime-assets.py --from /path/to/rime-ice/full --dry-run

# 执行：自动快照 → 拷文件 → 重新应用定制补丁 → ASSET_VERSION +1 → 校验
python3 scripts/sync-rime-assets.py --from /path/to/rime-ice/full --apply

# 只做校验（build-apk.sh 会自动调用它，不通过则拒绝构建）
python3 scripts/sync-rime-assets.py --check
```

脚本内置四类规则（清单就是脚本顶部的常量）：

| 类别 | 处理方式 |
|---|---|
| `PLAIN_COPY` | 纯替换（我们没改过的词典、opencc、lua） |
| `PATCHED` | 覆盖后重打补丁（`default.yaml` / `rime_ice.schema.yaml` / `rime_ice.dict.yaml` / `en_ext.dict.yaml`） |
| `NEVER_COPY` | 永不进包（t9、双拼、部件拆字、桌面皮肤、build 缓存、冷词词表） |
| 未知新文件 | 默认不拷，只在报告里列出，等人工判断 |

10 项闸门校验：`page_size=20`、方案只剩 `rime_ice`、`import_tables` 六项齐全、无 radical 残留、冷词组件已挂载、引用完整性、英文专名齐全、冷词词表为空、大词库存在、**资源变了但 `ASSET_VERSION` 没变**（这是最容易漏的坑）。

---

## 技术要点

- **自绘键盘**：`KeyboardView` 自己算命中、绘制与多点触控；滑动不当作打字，抬手仍停在原键才上屏。
- **JNI 用轮询而非回调**：早期用 `GetStaticMethodID` 注册回调，在真机上触发过 ART `ClassLinker::InitializeClass → PrettyDescriptor` 空指针崩溃（tombstone 确认），现在改成 native 侧入队、Kotlin 侧 `nativePollNotification()` 轮询。
- **R8 保留规则**：`RimeNative` 与 `IceInputMethodService` 必须 `-keep`，否则混淆会破坏 JNI 绑定与系统对输入法服务的实例化。
- **主题推导**：给定一个色相种子，`KeyboardTheme.schemeFromSeed()` 生成整套配色，保证深/浅两套都有足够对比度。
- **冷词隐藏走 rime 的 lua 组件**：`lua_processor@*cold_word_drop.processor` + `lua_filter@*cold_word_drop.filter`，长按候选时发送 `Control+j`（降频）/ `Control+x`（隐藏）；三张词表在首次安装时复制到 `rime_user/lua/cold_word_drop/`，保证可写、升级不丢。
- **librime 全局串行锁**：rime 的 Service 不是线程安全的，并发进入会破坏 `lua_State`（`lua_load` 空指针 / longjmp 导致 SIGILL）。所以所有 native 调用都收在 `RimeNative.locked.*` 里，由一把锁串起来；内置压力自测（`--ei stress 400`）连续 1.3 万次引擎调用 0 崩溃。

---

## 许可与致谢

| 组件 | 许可 |
|---|---|
| librime / librime-lua | BSD-3-Clause |
| 雾凇拼音 Rime Ice | GPL-3.0 |
| OpenCC | Apache-2.0 |
| boost | Boost 1.0 |
| leveldb | BSD-3-Clause |
| marisa-trie | BSD-2-Clause |
| yaml-cpp | MIT |
| Lua | MIT |
| AndroidX / Compose | Apache-2.0 |

许可证原文见 `LICENSES/`；图标取自 Google Material Symbols（Apache-2.0），来源映射见 `docs/ICON_ASSET_MAP.md`。

本工程本质是**输入法前端 + 引擎集成**，真正的中文输入能力来自 librime 与雾凇拼音方案，感谢上游作者。