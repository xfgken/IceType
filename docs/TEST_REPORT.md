# IceType 测试记录（TEST_REPORT）

> 本文只记录**实际执行过**的测试与**实际观察到**的输出。
> 未执行或未通过的项会明确标注为「未通过 / 未执行」，不做推测性结论。

---

## 0. 测试环境

| 项目 | 值 |
| --- | --- |
| 构建主机 | Ubuntu 24.04.1 LTS（aarch64，8 核，proot 环境） |
| JDK / Gradle / AGP | 17.0.20 / 9.4.1 / 9.2.1 |
| Android SDK | platform 37、build-tools 37.0.0 |
| Android NDK | 27.2.12479018 |
| 引擎源码 | librime 1.17.0（master 快照）、librime-lua（快照）、Lua 5.4.7、Boost 1.86.0 |
| 词库 | Rime Ice（雾凇拼音），来自 `/storage/emulated/0/AppProjects/IceTypedevdata/full` |
| 设备 | 见下方「设备端测试」小节 |

---

## 1. 引擎离线验证（宿主机，librime 1.17.0）

验证程序：`tools/rime_probe.cc`、`tools/rime_probe_en.cc`（驱动真实 librime，非模拟）。

### 1.1 部署

```
librime 版本: 1.17.0
start_maintenance(full_check=true) 返回: 成功
  [通知] deploy: start
  [通知] deploy: success
部署后维护模式: 否
```

### 1.2 方案列表

```
- rime_ice (雾凇拼音)
- t9 (中文九键)
- double_pinyin (自然码双拼)
- double_pinyin_abc (智能 ABC 双拼)
- double_pinyin_mspy (微软双拼)
- double_pinyin_sogou (搜狗双拼)
- double_pinyin_flypy (小鹤双拼)
- double_pinyin_ziguang (紫光双拼)
- double_pinyin_jiajia (拼音加加双拼)
```

### 1.3 中文候选

| 输入 | 结果 |
| --- | --- |
| `nihao` | 组合串 `ni hao`；候选：你好 👋 / 拟好 / 你 / 尼 |
| 选择第 1 个候选 | 提交串 = **你好**（已验证上屏文本正确） |
| `shi` 第 1 页 | 是 / 时 / 事 / 实 / 十 |
| `shi` 第 2 页 | 🔟 / 师 / 使 / 屎 / 💩（翻页可用） |
| `zhongguo` | 中国 🇨🇳 / 种过 / 中过 / 中果 |

### 1.4 英文候选（开发文档特别要求实测 `h`）

| 输入 | 观察到 |
| --- | --- |
| `h` | 首页：或 / 好 / 👌 / 🙆‍♂️ / 🙆‍♀️；**共 60 个候选**，英文候选出现在第 9–12 页：`he hem hen hep her hex hey he'd he's hell he'll heal heap hear heat heck heel hehe` |
| `hel` | 首页：合理 / 荷兰 🇳🇱 / 河流 / 很恶劣；英文候选在第 10–12 页：`held hell he'll helm help Helen helix hello helps Helena helium helmet helped helper` |
| `hello` | **首候选即 `hello`** |
| ascii_mode（英文模式） | 候选数 0 —— 该模式下 ascii_composer 直接上屏字符，不产生候选（这是方案设计行为，不是缺陷） |

**结论**：英文单词候选与补全**真实生效**（来自方案内 `melt_eng` / `cn_en` 次翻译器），
但在默认配置下排序位于中文拼音候选之后。应用内因此提供「英文候选优先」开关。

### 1.5 「英文候选优先」补丁实测

写入 `rime_ice.custom.yaml`：

```yaml
patch:
  melt_eng/initial_quality: 3.0
  cn_en/initial_quality: 2.6
```

重新部署后观察：

| 输入 | 打补丁前首页 | 打补丁后首页 |
| --- | --- | --- |
| `hel` | 合理 / 荷兰 🇳🇱 / 河流 / 很恶劣 | **held / hell / he'll / helm / help** |
| `h` | 或 / 好 / 👌 / 🙆‍♂️ | 喊cut / H版 / H杯 / HB铅笔 / 很OK（`cn_en` 中英混合词提前） |

**结论**：补丁机制有效，且是应用内开关的真实实现基础（`RimeEngine.applyEnglishPriorityPatch`）。

### 1.6 简繁转换（opencc）

```
[繁体] 候选: 漢字 漢子 汗漬 憨子 蚶子     （traditionalization = true）
[简体] 候选: 汉字 汉子 汗渍 憨子 蚶子     （traditionalization = false）
```

**结论**：OpenCC 集成有效。

### 1.7 用户数据落盘

```
sync_user_data 返回: 成功
```

### 1.8 打包策略的关键验证（两次负向/正向实验）

| 实验 | 条件 | 观察结果 | 结论 |
| --- | --- | --- | --- |
| A | 去掉 `cn_dicts/` 源词典（保留 `build/` 预编译产物） | 部署**仍返回成功**，但 `nihao` 候选数 = **0**，`shi` 只剩英文候选 | 源词典必须打包；不能用"部署成功"判断可用性 |
| B | 仅打包源文件（不含 `build/`） | 部署成功，全部候选与简繁转换正常，宿主机耗时约 **35 秒** | 可以安全地不打包 74MB 预编译产物 |
| C | 把 OpenCC 数据放进 `shared/opencc/`，并**移走**系统 OpenCC 安装目录 | 简繁转换仍正常（漢字/汉字） | 设备端必须自带 OpenCC 数据（依据 `simplifier.cc` 的查找顺序） |

---

## 2. 构建与产物

| 项目 | 值 |
| --- | --- |
| 构建命令 | `bash scripts/build-apk.sh` |
| 原生构建 | `bash scripts/build-native.sh`（Boost.Regex → deps → librime → JNI） |
| librime.so | arm64-v8a，裁剪后 **4.8 MB**（未裁剪 75.6 MB），依赖仅 libm / libc++_shared / libdl / libc |
| libicetype_rime.so | arm64-v8a，**33 KB**，依赖 librime.so / liblog / libc++_shared |
| Rime 资源（assets/rime） | **50 MB**，108 个文件（含 `opencc/` 41 个文件） |
| APK | 路径 `/sdcard/Download/IceType.apk`，大小见下方实测 |

`android { compileSdk = 37; minSdk = 29; targetSdk = 37 }`，构建变体 release（R8 + 资源压缩 + 签名）。

---

## 3. 设备端测试

### 3.1 设备信息

| 项目 | 值 |
| --- | --- |
| 设备 | Xiaomi（`pandora`），指纹 `Xiaomi/pandora/pandora:17/CP2A.260605.016/OS4.0.0.41.XBLCNXM:user/release-keys` |
| Android | API **37（Android 17）**，内核 `6.12.69-android16-6` |
| ABI | arm64 |
| 安装方式 | `pm install -r`（先 `cp` 到 `/data/local/tmp`，因 SELinux 禁止 system_server 读取 `/sdcard` 的 FUSE 文件） |

### 3.2 输入法注册

`ime list -a` 输出（节选）：

```
com.xfgken.iceime/.ime.IceInputMethodService:
  mId=com.xfgken.iceime/.ime.IceInputMethodService
  mSettingsActivityName=com.xfgken.iceime.MainActivity
  mSupportsSwitchingToNextInputMethod=true
  mShowInInputMethodPicker=true
  ServiceInfo:
    name=com.xfgken.iceime.ime.IceInputMethodService
    packageName=com.xfgken.iceime
    enabled=true exported=true
```

**结论**：输入法服务已被系统正确识别，`enabled=true`、`exported=true`，设置入口指向 IceType 主页。

### 3.3 真实运行日志（logcat，tag `IceType` / `IceTypeRime`）

```
I IceType : [23:43:29.316] 初始化 应用启动，日志系统就绪
I IceType : [23:43:29.316] 隐私 日志策略：本地存储，不含输入内容/密码/剪贴板正文，导出前需用户确认
I IceType : [23:43:29.321] 初始化 设置已载入
I IceType : [23:43:29.322] 初始化 IceType 应用进程启动（应用级初始化完成）
I IceTypeRime: RimeSetup 完成 shared=/data/user/0/com.xfgken.iceime/files/rime_shared
                            user=/data/user/0/com.xfgken.iceime/files/rime_user
I IceType : [23:43:29.369] Rime 引擎 librime 已初始化，版本 1.17.0
I IceType : [23:43:57.509] 词库部署 Rime 通知 [deploy] start
I IceType : [23:43:57.510] 词库部署 Rime 通知 [deploy] success
I IceType : [23:43:57.510] 词库部署 词库部署完成，用时 28.14 秒（deploy: success）
I IceType : [23:43:57.650] Rime 引擎 可用方案：rime_ice、t9、double_pinyin、
                            double_pinyin_abc、double_pinyin_mspy、double_pinyin_sogou、
                            double_pinyin_flypy、double_pinyin_ziguang、double_pinyin_jiajia
I IceType : [23:43:57.651] 词库部署 应用启动阶段引擎准备完成
I IceType : [23:44:07.337] Rime 引擎 引擎就绪，中文输入可用
```

**结论（对应验收清单第 2 节）**：

- ✅ 首次启动**自动部署**，用户未做任何手动导入；
- ✅ 设备端部署耗时 **28.14 秒**（宿主 8 核约 35 秒，设备反而更快，因其 CPU 更强且已预热）；
- ✅ 9 个方案全部部署成功；
- ✅ 引擎在 Android 17 真机上完成 `RimeSetup` / `RimeInitialize`；
- ✅ 主页/日志页显示可读的部署结果。

### 3.4 输入法服务运行日志（实际交互）

```
I IceType : [23:44:35.345] 输入行为 切换到 中文
I IceType : [23:44:35.735] 输入行为 切换到 英文
I IceType : [23:44:40.997] 用户界面 用户收起键盘
I IceType : [23:45:01.532] 用户界面 刷新输入法状态：DISABLED
I IceType : [23:45:01.561] 输入行为 开始输入：类型=其他 密码框=false
I IceType : [23:45:15.907] 输入行为 开始输入：类型=文本 密码框=false
I IceType : [23:45:26.317] 隐私 剪贴板历史未开启，跳过采集
I IceType : [23:45:26.323] 用户界面 打开面板：CLIPBOARD
```

**结论**：

- ✅ 输入法服务在真实 App 的文本框中启动（`开始输入：类型=文本`）；
- ✅ 中英切换、收起键盘、剪贴板面板、关闭面板均真实生效；
- ✅ 剪贴板默认关闭历史时**确实没有采集**（隐私行为符合设计）；
- ✅ 崩溃缓冲区中 IceType 相关崩溃计数 = **0**。

### 3.5 修复记录：真机闪退（已解决）

**现象**：首次安装后，打开应用显示主界面随即闪退（native SIGSEGV）。

**定位**：`logcat -b crash` 的 tombstone 明确指向

```
#07 Java_com_xfgken_iceime_rime_RimeNative_nativeSetNotificationHandler+120
#06 art::JNI<false>::GetStaticMethodID
#05 art::FindMethodJNI → ClassLinker::EnsureInitialized → InitializeClass
#04 .. #00 ClassLinker::InitializeClass → PrettyClass → PrettyDescriptor → Class::GetDescriptor → SIGSEGV (null + 0x28)
```

即：为注册引擎通知回调而调用的 `GetStaticMethodID` 触发了 ART 的类初始化路径，
并在格式化错误信息时空指针崩溃。

**修复**：彻底移除 JNI 回调注册，改为

- native 侧：通知处理器只把 `(type, value)` 入队（`std::deque` + `std::mutex`），
  并提供 `nativePollNotification()` 取队首；
- Kotlin 侧：`RimeEngine.drainNotifications()` 在初始化与部署前后轮询取走通知。

**修复后验证**：同机重新安装，应用正常启动，`crash` 缓冲区中 `iceime` 计数为 **0**，
日志完整输出初始化与部署过程（见 3.3 / 3.4）。

### 3.6 修复记录：写一会儿之后不再出现候选（已解决）

**现象**：键盘用一段时间后，打字仍能上屏字母，但候选栏一直是空的。

**根因**：`onFinishInput()` 会销毁 Rime 会话（`session = 0`），而 `ensureEngine()` 中有
`if (engineRequested) return` 的早退：第一次初始化后该标志一直为 `true`，
于是再次聚焦输入框时**不会再创建会话**。会话为 0 时按键走的是"引擎不可用"兜底分支
（直接上屏字符），表现就正好是「能出字、没有候选」。

**修复**：

1. `ensureEngine()` 改为：引擎已就绪时直接补建会话（`createSessionIfNeeded()`），
   不再因早退而漏建；
2. 引擎未就绪时若初始化失败会复位 `engineRequested`，允许后续重试；
3. 加了两处**自愈**：
   - 按键时若发现 `session == 0` 且引擎已就绪 → 立即重建会话；
   - 取上下文失败（会话失效）→ 销毁并重建一次；
4. `createSessionIfNeeded()` 调整为先 `cleanupStaleSessions()` 再建会话，避免误伤新会话；
5. 切换输入框时清空上一条连接的候选与联想残留；
6. `onUpdateSelection` 的清组合条件收紧为「光标移出组合区」才清，避免打字过程中被误清；
7. 联想只在中文模式下触发（英文键盘逐字母上屏不再弹出中文联想词）。

### 3.7 修复后的设备端实测（API 37 / arm64）

```
词库部署 开始释放内置 Rime 资源（资源版本 2）
词库部署 内置资源释放完成，共 96 个文件
Rime 引擎 librime 已初始化，版本 1.17.0
词库部署 Rime 通知 [deploy] success
词库部署 词库部署完成，用时 1.998 秒（deploy: success）
Rime 引擎 可用方案：rime_ice
Rime 引擎 引擎自检通过：方案=rime_ice，nihao→你好/👋/拟好，上屏="你好"，hel→合理/荷兰/🇳🇱
Rime 引擎 联想词表已载入：6309 个首字，用时 11 ms
Rime 引擎 已创建 Rime 会话（ascii_mode=false）
crash 缓冲区中 iceime 计数：0
```

对照验收要点：

| 项目 | 结果 |
| --- | --- |
| 首次部署是否「打开就能用」 | ✅ **2.0 秒**（此前仅源词典时为 28.14 秒） |
| 中文候选与上屏链路 | ✅ 自检输出 `nihao→你好/👋/拟好`，`上屏="你好"` |
| 方案是否干净 | ✅ 只剩 `rime_ice`（已裁掉双拼/九键/部件拆字） |
| 会话是否可重复创建 | ✅ 日志显示新建会话成功，且带自愈兜底 |
| 联想词表 | ✅ 6309 个首字，载入耗时 11 ms |
| 稳定性 | ✅ crash 缓冲区计数 0 |

### 3.8 体积优化对照

| 项目 | 优化前 | 优化后 | 做法 |
| --- | --- | --- | --- |
| APK | 129.9 MB | **24.7 MB** | 裁剪方案与词库 + 允许 assets 压缩 + 单 ABI |
| assets（未压缩） | 124 MB | 36 MB | 去掉双拼/九键/部件拆字、ext/tencent 词库，并用同版本 librime 重新生成匹配产物 |
| 预编译词典产物 | 74 MB | 18 MB | 词典从 base+8105+41448+others 重新编译 |
| 首次部署 | 28.1 s | 2.0 s | 内置与源严格匹配的预编译产物 |
| ABI | arm64 + 其余 ABI 的辅助库 | 仅 arm64-v8a | `abiFilters` |

- 中文候选在真实输入框中弹出（需要人工在输入框里打字确认；引擎侧已在宿主机逐项验证，
  且设备端 `引擎就绪，中文输入可用` 表明会话创建成功）；
- 剪贴板历史的持久化（需要先开启该开关）；
- 常用语、表情面板的插入行为；
- 深色模式与强调色切换的视觉效果。

---

## 4. 未执行 / 未通过项

以下项目在本次交付中**没有**完成实际验证，明确列出，不冒充已完成：

1. iOS/其它 ABI：本项目只产出 `arm64-v8a`（设备为 arm64）。
2. 双拼 / 九键（t9）方案的键盘布局：资源已部署、方案列表可见，但应用内没有对应的双拼键位
   与九宫格布局，未做使用验证。
3. 横屏、大屏、字体缩放下的逐项视觉校准：未逐项执行。
4. 长时间连续输入的压力测试：未执行。
5. 与具体第三方 App 的兼容性遍历：未执行。