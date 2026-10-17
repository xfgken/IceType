# 第三方组件与许可证（THIRD_PARTY_NOTICES）

IceType 随 APK 分发以下第三方组件。所有许可证原文已收集在仓库 `LICENSES/` 目录中。

## 1. 输入引擎与词库

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| librime（RIME） | 1.17.0 | BSD 3-Clause | 由本项目从源码编译为 librime.so |
| librime-lua | 构建时快照 | BSD 3-Clause | Lua 翻译器/过滤器插件，已合并进 librime.so |
| Rime Ice（雾凇拼音） | 与 IceTypedevdata 快照一致 | GPL-3.0 | 方案、词典、Lua 脚本、opencc 配置随 APK 分发 |
| Lua | 5.4.7 | MIT | 编译进 librime 以支持方案中的 Lua 脚本 |

> GPL-3.0 注意：雾凇拼音（Rime Ice）的方案与词库采用 GPL-3.0。本项目将其作为资源随 APK
> 一并分发，并保留其完整许可证文本。若对资源部分做修改，相应修改也应按 GPL-3.0 提供。

## 2. 引擎依赖（静态链接进 librime.so）

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| OpenCC | 1.4.2 | Apache-2.0 |
| marisa-trie | 随 librime/deps 子模块 | BSD-2-Clause / LGPL-2.1 |
| leveldb | 随 librime/deps 子模块 | BSD-3-Clause |
| yaml-cpp | 随 librime/deps 子模块 | MIT |
| Boost（仅 Regex 被编译，其余为头文件） | 1.86.0 | Boost Software License 1.0 |

## 3. AndroidX / Jetpack

| 组件 | 许可证 |
| --- | --- |
| androidx.core:core-ktx | Apache-2.0 |
| androidx.activity:activity-compose | Apache-2.0 |
| androidx.compose.*（BOM 2026.06.00、material3、ui、foundation） | Apache-2.0 |
| androidx.lifecycle:* | Apache-2.0 |
| androidx.datastore:datastore-preferences | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | Apache-2.0 |

## 4. 设计与参考资料

| 资料 | 来源 | 使用方式 |
| --- | --- | --- |
| androidx-material3 目录 | AndroidX Compose Material 3 源码（Apache-2.0） | 作为 M3 设计令牌与实现规范参考。本项目未从中复制任何素材（该目录内不含任何 SVG）；颜色、形状在运行时通过官方 API（MaterialTheme.colorScheme、lightColorScheme / darkColorScheme / dynamicColorScheme）取得 |
| Material Symbols 风格 | Google Material Design | 仅作为图标风格参考；本项目所有图标均为自制 VectorDrawable，见 docs/ICON_ASSET_MAP.md |

## 5. 未包含的组件（有意为之）

- 无任何网络、统计或崩溃上报 SDK；
- 无广告 SDK；
- 无账号或云同步组件。

## 6. 许可证文本位置

```
LICENSES/
├── librime-BSD-3-Clause.txt
├── librime-lua-BSD-3-Clause.txt
├── RimeIce-GPL-3.0.txt
├── OpenCC-Apache-2.0.txt
├── marisa-trie-BSD-2-Clause.txt
├── leveldb-BSD-3-Clause.txt
├── yaml-cpp-MIT.txt
├── Boost-Software-License-1.0.txt
├── Lua-MIT.txt
└── AndroidX-Apache-2.0.txt
```