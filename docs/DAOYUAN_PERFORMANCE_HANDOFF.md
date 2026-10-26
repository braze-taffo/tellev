# 《道渊》创建页性能修复交接

日期：2026-10-21。基线：正式渠道 master，`a849d7a`，本地版本 1.7.1.7。

## 修改结果

保留原卡和《梦鲸思客 V4-1015》的脚本、正则、MVU 变量与原始数据，优化 Tellev 内部的查询和桥接。没有通过停用小手机、删减预设或裁剪完整上下文来降低开销。

### 上下文按需构建

- `ExtensionContextProvider.snapshot(fields)` 提供内部投影，默认实现兼容只实现原有 snapshot() 的提供者；聊天提供者跳过未请求的消息、世界书正文、角色全文和扩展设置构建。
- 宿主属性代理及内部助手读取所需字段；消息 WebView 的消息/绑定查询也使用字段投影。显式 `getContext()` 继续返回完整、新鲜的数据，保留角色、预设和兼容别名。
- 属性代理保留原有的同一任务内对象身份与脚本临时修改，避免 MVU 初始化设置被后续读取丢弃；显式完整读取及写后的失效机制保持独立。未实现“无限期缓存完整上下文”。
- 原生运行时归属检查、会话写入检查及宏所需字段使用投影，避免这些内部检查顺带构造全部世界书。

涉及：`ExtensionHost.kt`、`ChatTavernAdapter.kt`、`ChatViewModel.kt`、`ChatScreen.kt`、`ChatWebViewPanel.kt`、`ExtensionScriptTemplate.kt`、`compat/host.js`、`compat/message.js`。

### 世界书读取和解析

- 内部 `POST /api/tellev/worldbook {name}` 按名称或 ID 解析目标，仅返回该书。原有公开世界书接口维持原结构；新路由仍要求 Storage 权限。
- 名称/ID 冲突及重名保持原目录顺序中的首次匹配规则。条目 uid、未知字段及读改写所需的原始数据保留。
- 名称索引只保留摘要。每次读取检查目录成员、文件大小、修改时间和文件标识；应用写入会主动失效，原始 ST 文件修改也会重新读取。
- 已解析的完整世界书只用弱引用复用：UI/调用者仍持有且文件未变时，共享不可变模型，避免世界书变更通知反复解析整库；存储层不额外强持有所有正文。写入后重新按磁盘结果读取。

涉及：`VirtualApiRouter.kt`、`WorldBookApiHandler.kt`、`WorldBookRepository.kt`、`ExtensionHostPolicy.kt`。

### 桥接峰值和并发

- 响应转义改为精确预估容量后写入一个输出缓冲，保留原有反斜杠、引号、换行、U+2028/U+2029 的转义语义，消除多次 replace 和字符串拼接的大量中间副本。
- 大响应的准备在后台线程完成，主线程仅注入 WebView；旧能力令牌的回调不会进入新运行时。
- 每个桥接运行时的大数据只读请求最多并发 2 个，覆盖读取、编码及交付过程；请求等待而不被丢弃。写入和模型生成独立处理，不占用这些只读名额。

涉及：`WebViewJsExtensionHost.kt`、`ExtensionScriptTemplate.kt`、`ExtensionHostPolicy.kt`。

## 验证结果

### 同环境的未修改版本 / 修改版本对照

环境：Android 14 / API 34，WebView 113.0.5672.136，2 GiB 模拟器；独立包名 `app.tellev.mvuvalidation`，Java 堆增长上限 192 MiB。

未修改版本由 HEAD 的 Git archive 构建；两组均使用外部测试 Activity 和独立数据目录，避免额外挂载默认聊天页。均实际导入原卡、原预设并启用全部 11 个脚本，在创建页加入额外 12 本同规模世界书，总计 13 本库内世界书。

| 观测 | 未修改版本 | 修改版本 |
| --- | --- | --- |
| 最终结果 | 约 88 秒自然 OOM 崩溃 | 完成 300 个样本；运行器约 659.8 秒，OK |
| Java 堆采样峰值 | 约 192 MiB，触及上限 | 约 155.3 MiB |
| 世界书数量 | 13 | 13 |
| 可见消息 WebView | 1 | 1 |
| 介绍页切换后加载计数 | 2 | 2 |

修改版本最后一个样本在 657.82 秒，Java 堆约 120.9 MiB。采样间隔约 2 秒，表中是采样峰值，不能等同于所有瞬时峰值或整个应用含渲染进程的总内存。

修改版本保留了小手机/配置助手的正常轮询。一个 20.06 秒空闲窗口中：

- `stGetContext` 完整读取 0 次；按字段读取共约 520 万 JS 字符，原生调用累计约 426 毫秒。
- 单本世界书响应约 199 万字符，角色正则约 134 万字符；16 个请求全部返回 200，已完成响应约 0.51–0.78 秒。
- 无关的 12 本世界书正文不进入单本查询响应。

此前原卡加原预设、单本库的 20 秒窗口完整上下文约 1.22 亿字符。两段采样的库规模、定时器相位不同，只用于说明传输量级的变化，不作为固定比例或所有设备的性能承诺。

### 功能与兼容检查

- JVM：1111 项，0 失败、0 错误，其中 2 项跳过。
- JavaScript：48 项通过，包含真实 MVU/Zod、预设、世界书读改写、显示正则、消息变量、投影/完整上下文和旧桥接回退。
- Android 原生：6 项通过，覆盖独立脚本 iframe、跨运行时事件、预设读取/写回、取消加载、两种界面的消息按钮持久化及旧前端写入拒绝。
- `lintDebug` 通过，0 errors；现有报告仍有 117 warnings、4 hints。
- 原卡创建页通过 3 轮输入、境界切换、抽灵根、抽气运和选择气运；ADB 实际弹出输入法、输入 `abc` 并收起，页面读取到更新后的内容，未重建 WebView。
- 已有 13 本库的冷启动/重新进入通过，单次运行器约 105.6 秒。
- 大库中显式 `SillyTavern.getContext()` 成功：返回 14 个上下文世界书投影（包含角色内嵌书）、123 个预设提示词、角色脚本及预设脚本、事件源和 37 个完整上下文字段；该次完整读取约 1.98 秒。完整大对象读取仍有成本，内部高频查询已不再依赖它。

两个原有工坊 bootstrap 兼容错误仍存在，未将其误计为性能回归；程序驱动抽取时的音频用户手势限制也与原卡一致。未调用真实模型、未进行用户手机验收，不能据模拟器结果宣称所有第三方功能或所有设备均已验收。

## 回归与证据

新增/更新的回归覆盖：

- `TavernContextProjectionTest`：逐字段与完整快照一致、未知字段保留、身份读取不遍历世界书正文。
- `VirtualApiRouterTest` / `FileStDataStoreTest`：单书解析、重名顺序、写后立即读、原始文件编辑、缓存失效和未知字段。
- `ScriptSourceEscapingTest`：大文本及特殊字符转义与原语义逐字一致。
- `ExtensionHostPolicyTest`：只读限流不覆盖写入及生成。
- `tools/mvu/test/context-projection.test.mjs` / `message-variable-reads.test.mjs`：按字段读取、对象身份/临时修改、完整读取、旧桥接回退和绑定字段选择。

本地完整证据位于工作区上层 `_buildlogs/daoyuan-20261021/perf/`：

- `complete.jsonl`、`complete-summary.json`、`instrument-complete.log`、`complete-logcat.log`：最终长期测试。
- `baseline-clean.jsonl`、`baseline-clean-logcat.log`、`baseline-clean-instrument.log`：HEAD 未修改版本自然 OOM 对照。
- `bridge-complete.json`、`runtime-hashes-fixed.json`：桥接采样及实际加载的外链脚本摘要。
- `android-interactions.json`、`keyboard.png`、`ime.txt`、`input-after.json`：创建操作和实际输入法记录。
- `android-regressions.log`、`verification.log`、`node-verified.log`、`cold-reopen.log`、`full-context-verified.json`：其余验证。

`complete-logcat.log` 中辅助 UIAutomator 进程 PID 6392 的 `already registered` 异常不是应用进程 PID 6029 的崩溃；最终长期测试中应用无 OOM。调试中主动停止的试验不作为自然崩溃证据。

原卡 SHA-256：`647249EDB773B47B558D27A629EF7C936881C04ADFDDDAF88CEF4FC397E94697`。

原预设 SHA-256：`F027D4111524536E5F2AB7810D0555EA7D41374C2E801A83501634423968084E`。

未修改原文件。已有 AI 创作相关未提交修改保留；本次未提交、推送、改版本号或发布。测试 Activity 和用户卡/预设仅在外部诊断目录注入，不进入产品源代码。

## 正式签名构建与手机安装（2026-10-21）

按用户要求，直接从当前工作区构建本地正式签名版本，保留版本 `1.7.1.7` / `49`，包名 `app.tellev`。`:app:assembleRelease --offline` 成功，包含 `verifyUpdateGuides`、R8 和 release lint 检查；没有注入诊断 Gradle 脚本或测试 Activity。

- APK：`_buildlogs/daoyuan-20261021/perf/tellev-1.7.1.7-daoyuan-performance-release.apk`（相对工作区上层）。
- APK SHA-256：`30188A17C0FE10A22155AC05BDA304CE1180F1C423DF847BCF3A146C1A709915`。
- 签名证书 SHA-256：`aee2a2d53aeba1f89a50aa4ac52d517f4aa73f95335de1cc6672cf10d1d9f93f`，与手机安装前的正式版相同，APK v2 签名验证通过。
- Manifest：正式包名、正常主 Activity、非 debuggable；ZIP 中没有 `libstable_diffusion_core.so`、`assets/ldcvt/` 或诊断素材。
- 手机：`2602BRT18C`，Android API 36，arm64-v8a；ADB `install -r` 返回 `Success`，保留应用数据，未卸载或清除数据。
- 安装后读取手机 base.apk 的 SHA-256 与上述产物一致；版本仍为 `1.7.1.7` / `49`，更新时间 `2026-10-21 16:39:54`，启动入口解析为 `app.tellev/.MainActivity`。

构建、签名、安装及产物回读已验证。手机内的《道渊》实际使用体验留待用户测试；此次未启动用户会话、调用模型或发布新版本。日志：`release-build.log`、`release-signature.txt`、`release-badging.txt`、`release-manifest.txt`、`phone-install.log`、`phone-installed-package.txt`、`phone-installed-sha256.txt`。

## 1.7.1.8 正式发行准备（2026-10-21）

按用户授权，将本交接所述性能优化与工作区已有 AI 创作协作规则调整一并纳入正式版。版本提升为 `1.7.1.8` / `50`，包名保持 `app.tellev`；已更新四语言版本引导、README 和 `docs/RELEASE_1.7.1.8.md`。本次发行的独立构建、测试、签名及模拟器引导验证结果记录在 `docs/RELEASE_1.7.1.8_VALIDATION.md`。上文“未提交、未推送、未发布”描述的是此前诊断及安装阶段。
