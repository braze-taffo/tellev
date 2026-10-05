# tellev

tellev 是一个面向 Android 的 SillyTavern 兼容客户端。项目目标是在手机上提供更贴近原生应用体验的角色管理、聊天、世界书、扩展和模型服务配置能力，同时尽量保持与 SillyTavern 数据格式和使用习惯兼容。

> 公开版本以 GitHub Releases 中发布的 APK 为准。当前源码版本 **v1.7.1.3**。

## v1.7.1.3 更新

- 改善长聊天历史上翻，减少消息前端重复加载、尺寸变化和变量读取开销。
- 修复部分角色卡前端正文被裁切、只显示背景的问题。
- 移除聊天、角色、世界书、扩展、设置主页的介绍横幅。
- 系统返回与侧滑返回按页面层级处理，仅在聊天首页确认退出应用。
- 详见 [v1.7.1.3 发布说明](docs/RELEASE_1.7.1.3.md)。

## v1.7.1.2 更新

- 修复角色变量保存触发脚本反复重载，导致聊天标题闪烁和“卡片界面”按钮时隐时现的问题。
- 改善变量更新期间的聊天生成稳定性，修复角色卡保存期间可能读到不完整数据的问题。
- 详见 [v1.7.1.2 发布说明](docs/RELEASE_1.7.1.2.md)。

## v1.7.1.1 更新

- 所有页面的系统返回和侧滑返回统一显示退出确认，修复聊天页直接退回桌面的问题。
- 选择“否”保留当前页面和对话；左上角的页面返回按钮继续按原有导航返回。
- 详见 [v1.7.1.1 发布说明](docs/RELEASE_1.7.1.1.md)。

## v1.7.1 更新

- 角色页突出头像、封面和标签，聊天、世界书、设置与扩展页统一布局和明暗主题。
- 修复角色和世界书列表底部导航消失，以及聊天软键盘遮挡；角色列表侧滑返回时先确认是否退出。
- 修复混合 Markdown / HTML 消息泄露源码、卡片样式分离和浅色主题对比度问题。
- 修复酒馆预设、生成事件与参数、角色与预设变量、世界书绑定和取消处理的兼容性问题。
- 详见 [v1.7.1 发布说明](docs/RELEASE_1.7.1.md)。

## v1.7.0.3 更新

- 聊天消息有多个回复版本时，增加左右切换按钮，可直接切换版本。
- AI 创建角色卡时，支持将草稿中的内嵌世界书单独导出为 JSON。

## v1.7.0.2 更新

- **修复推理模型做记忆整理必然失败**：记忆整理的输出预算不再固定 1800 token，
  可按模型在「扩展 → 长期记忆」设置（默认 16384，推理思考与正文共用额度）；
  额度耗尽或正文被截断时明确报错并保留待处理消息，不再把半截结果写入记忆，
  也压不过聊天连接里的输出上限设置。
- **聊天界面**：角色消息里孤立成行的 MVU 状态占位符不再原样显示（代码块与
  行内示例不受影响）；记忆状态行移到消息列表上方，失败摘要更完整，点击即可
  打开记忆管理器查看详情。

## v1.7.0.1 更新

从 v1.6.x 一步跨到 v1.7.x，新增内容较多。更新后首次打开会弹出应用内更新指引，
全新安装的用户看到的则是四步新手引导；两者都能从「设置 → 关于」随时重看。

- 新增 **AI 协作创作**：与独立的创作 agent 对话生成角色卡、前端与世界书，支持引导创作、长文提炼、
  封面与导出。入口：角色页/世界书页顶栏「AI 创建」，角色卡菜单「AI 编辑」「基于此卡写世界书」。
- 新增 **长期记忆**：自动提取对话要点并在后续对话注入，两种记忆模式与可选向量检索。
  入口：扩展页「长期记忆」，聊天页「更多选项 → 长期记忆…」。
- 界面新增 **四种语言**：简体中文 / English / 日本語 / 한국어，可跟随系统。入口：设置 → 语言。
- **角色卡脚本兼容面扩大**：世界书读写、聊天记录写入、正则脚本与模板函数真实现，脚本报错不再拖垮整个应用。
- **修复带选项的消息点击无反应**：点选项会把答案填进输入框草稿（只填草稿，不代替你发送）。
- **性能与稳定性**：世界书库大、创作草稿多时不再卡顿闪退；服务商空回复显式报错，不再出现空气泡；
  流式回复取消后立即断开连接。
- 本版本同时包含 v1.6.6 / v1.6.6.1 的修复内容（这两个版本当时未单独写发布说明）。

## v1.6.5.1 更新

- 修复删除角色卡后对话数据未清除的问题：删卡时级联清理其全部会话、聊天图片、画廊与会话背景。
- 新增会话删除能力，并对写前日志（.tellev-writes）容量回收、聊天图片落盘，遏制长期使用后的数据膨胀。
- 优化角色与聊天列表读取路径，减少重复解析。
- 详见 [v1.6.5.1 发布说明](docs/RELEASE_1.6.5.1.md)。

## v1.6.5 更新

- 优化聊天入口的历史会话读取与消息渲染，减少数据增多时的加载开销。
- 改善 HTML 卡片滚动、折叠和流式回复跟随，修复异常导入消息的编辑问题。
- 修复世界书连续编辑、保存反馈和页面切换问题。
- 详见 [v1.6.5 发布说明](docs/RELEASE_1.6.5.md)。

## v1.6.4 更新

- 自动更新按标签后缀与 APK 文件名双重隔离正式版和 MNN 渠道。
- 新增启动自动检查更新开关，默认开启；关闭后仍可手动检查。
- 详见 [v1.6.4 发布说明](docs/RELEASE_1.6.4.md)。

## v1.6.3 更新

- 拆分聊天、提示词、扩展接口、数据存储和设置模块，保留现有功能与数据格式。
- 补充生图会话隔离、变量写入失败提示和消息编辑回归测试。
- 正式版使用 app.tellev 和正式签名，仅支持 ComfyUI、NovelAI 远程生图。
- 详见 [v1.6.3 发布说明](docs/RELEASE_1.6.3.md)。

## 主要功能

### 原生 UI

- 纯原生 Android / Jetpack Compose + Material 3 界面，非 WebView 套壳
- 聊天：流式生成、停止生成、重试、swipes、编辑、删除、收藏、附件（图像下采样为 base64，不落盘）、推理块（reasoning）渲染、TavernHelper 风格的 HTML 片段渲染、Markdown 渲染（commonmark + GFM 表格，AI 消息按需走 WebView）；流式回复同样实时套用正则与富文本渲染
- 角色：列表、标签、导入、编辑、复制、导出、删除（含关联变体与内嵌世界书清理）
- 世界书：条目管理、搜索、激活预览、常驻/排他激活，以及 ST 对齐的高级特性——8 种注入位置、4 种选择性逻辑（AND/NOT）、概率触发、递归扫描、正则/全词/大小写匹配、@depth 注入
- 人格（Persona）：列表、创建/编辑/删除、运行时切换
- 扩展：已安装扩展列表、加载/卸载、权限授予状态
- 设置：provider 配置、预设、密钥、主题、备份、扩展管理
- 平板与大屏：双栏聊天、三栏管理布局（基于 AndroidX Window）

### 模型与服务接入

内置 16+ 个 provider 适配器，统一实现 `ProviderAdapter`：

- **OpenAI 兼容**：通用 OpenAI 兼容接口、DeepSeek、tellevclick、火山引擎 Coding Plan
- **聊天补全**：Anthropic、Gemini、OpenRouter、NovelAI、Azure OpenAI
- **本地/自托管**：Ollama、Kobold、KoboldCpp、TextGen (ooba)、llama.cpp server、Horde
- **图像生成**：Stable Diffusion 兼容 API、OpenAI 兼容图像 API
- **语音**：OpenAI 兼容 Speech
- **翻译**：Google 翻译

适配器规范化处理：状态检查、模型列表、流式分块、取消、可重试错误、provider 特定预设字段。

SillyTavern JSON 预设可按 OpenAI / TextGen / Kobold / NovelAI 类别导入，导入后自动成为当前预设。采样参数、提示词顺序、预设正则等受支持字段会进入运行链路；服务商、接口、密钥和模型字段仅原样保留，避免预设意外切换连接配置。设置页会区分已应用、仅保留和暂未支持的字段。

### 提示词引擎

`DefaultPromptEngine` 已实现 SillyTavern 风格的提示词组装：

- 宏展开：`{{char}}`、`{{user}}`、日期/时间、`{{random}}`/`{{roll}}`、`{{newline}}`/`{{space}}`/`{{reverse}}`、`{{greeting::N}}`、`{{model}}`/`{{persona}}`/`{{input}}`、变量宏（`{{getvar::}}` / `{{setvar::name::value}}` / `{{incvar::}}` 等，local/global 分流）与变量简写（`{{.name}}` / `{{$name}}` 及 `++`/`--`/`=`/`+=`/`==`/`!=`/`>`/`<`/`||`/`??`/`||=`/`??=` 等操作符）、扩展提供的宏
- Instruct 模式与上下文模板（Context Template）
- 世界书激活引擎：关键词匹配、selectiveLogic 四逻辑、概率触发、递归扫描、8 种注入位置（before/after/ANTop/ANBottom/atDepth/EMTop/EMBottom/outlet）、@depth 消息注入、优先级与插入顺序排序
- Per-scope 变量：local（`chat_metadata.variables`，随对话持久化）与 global（全局持久化），character（`tavern_helper.variables`，角色卡默认值只读），message（`chat[i].variables[swipe_id]`），斜杠命令/宏/EJS 三处分流一致
- 角色卡深度提示（`data.extensions.depth_prompt`）：按指定 depth 和 role 注入聊天历史
- 角色卡系统提示词（`data.system_prompt`）与越狱提示（`data.post_history_instructions`）
- 群聊发言人排序
- Token 预算感知的上下文裁剪
- ST-Prompt-Template 兼容的 EJS 模板处理（`<%= ... %>` / `<% ... %>`）
- 扩展注入提示（`injectPrompts`，支持 BEFORE_PROMPT / IN_PROMPT / IN_CHAT depth）
- OpenAI 预设行为：`names_behavior`、`squash_system_messages` 与 `assistant_prefill`
- 角色卡与预设正则按 Normal / Display / Prompt 阶段执行，支持 depth、`runOnEdit` 与宏替换，并避免同一 swipe 的 Normal 规则重复运行

### 数据兼容

- 角色卡导入/导出：JSON、PNG、WebP、CHARX、BYAF 元数据解析与写入
- SillyTavern chat JSONL 解析为类型化 `ChatMessage`，保留未知字段
- 世界书解析：条目标志、优先级、depth、order、选择性关键字
- SillyTavern 预设导入/导出：保留未知字段和连接路由字段，运行时只应用明确支持的生成字段
- ZIP 备份导入/导出，含路径遍历防护
- 数据根目录镜像 SillyTavern 的 `USER_DIRECTORY_TEMPLATE`：`context.filesDir/st-data/`

### 扩展运行环境

`WebViewJsExtensionHost` 是 SillyTavern / 酒馆助手（JS-Slash-Runner）兼容层的脚本风格子集实现：

- 每个扩展运行在独立沙盒 WebView，提供 `tellevNative` 桥接
- 兼容 shim 暴露：`SillyTavern`、`getContext`、`eventSource`、`event_types`、`TavernHelper`、`executeSlashCommandsWithOptions`、`executeSlashCommands`、`fetch` 覆写
- 事件总线：108 个 ST 事件常量 + tellev 扩展事件
- `TavernHelper` API：约 140 个方法（generate、角色/世界书/预设/人格 CRUD、注入提示、regex、变量、消息读取/更新、slash 命令等）
- Slash 命令引擎：200+ ST 内建命令，支持管道 `|`、命名参数、引号字符串
- 虚拟 `/api/` 路由：`/api/characters/edit`、`/api/chats/get`、`/api/settings/get`、`/api/secrets/write` 等兼容端点
- 每扩展设置、能力令牌、权限门控与异步请求/授予流程

### 安全

- API 密钥通过 `AndroidKeystoreSecretStore` 存入 Android Keystore，不写入明文导出
- `SensitiveFieldScanner` 与 `PrivacyGuard` 在备份/日志中脱敏
- WebView 扩展宿主强化：origin 校验、能力令牌、每扩展权限
- `network_security_config.xml` 限制明文流量

## 兼容性范围说明

tellev 的扩展运行环境是 SillyTavern / 酒馆助手兼容层的一个**脚本风格子集**实现，并非完整复刻。当前兼容范围：

- **支持**：角色卡内嵌的 TavernHelper 脚本（`data.extensions.tavern_helper.scripts`），包括 ES Module 语法（`import`/`export`）的脚本（如 MVU/ZOD 框架），通过 `<script type="module">` 加载并允许 HTTPS CDN 导入；EJS 模板语法（`<%= ... %>` / `<% ... %>`）、`eventSource` 事件总线、`TavernHelper.*` API、slash 命令、虚拟 `/api/` 路由。
- **不支持**：直接安装酒馆助手（JS-Slash-Runner）本体或提示词模板（ST-Prompt-Template）本体——这两个扩展依赖大量 SillyTavern 内部模块，tellev 的兼容 shim 无法提供完整运行时。tellev 已内置 `TavernHelper` / `EjsTemplate` 兼容 shim 提供等价的脚本能力。
- **不支持**：需要 Tailwind / Vue / jQuery UI 渲染的消息 iframe 扩展、Node 服务端插件、直接文件系统访问、

## 下载安装

可以在 GitHub Releases 下载最新 APK：

[tellev Releases](https://github.com/braze-taffo/tellev/releases)

目前发布的是正式版 APK，适合日常使用。安装前请确认设备允许安装来自浏览器或文件管理器的应用。

应用内置更新检查：默认每次启动静默检查一次 GitHub 上本渠道的最新版本，有新版时在「设置 → 关于」提示并支持应用内下载安装；可在「设置 → 关于」关闭启动时的自动检查，关闭后仍可手动检查。正式版与 MNN 生图版各只检查本渠道的发行，不会互相提示；国内网络下自动回退到镜像站。

## 项目信息

- 应用名：`tellev`
- 包名：`app.tellev`
- 当前源码版本：1.7.0.3（versionCode 40）
- 最低系统：Android 12 / API 31
- 目标/编译 SDK：API 36
- UI：Kotlin + Jetpack Compose + Material 3
- 网络：OkHttp 4.12.0
- 图像加载：Coil 2.7.0
- 序列化：Kotlinx Serialization JSON 1.8.1
- 协程：Kotlinx Coroutines 1.10.1
- 大屏布局：AndroidX Window 1.3.0
- 导航：AndroidX Navigation Compose 2.8.7
- 开源协议：AGPL-3.0
- SillyTavern 兼容基线：`release`，版本 `1.18.0`，提交 `51ad27fb86d39a3daca3adaa970375c9670c12df`

## 本地构建

项目已包含 Gradle Wrapper。打开本目录后，可以使用 Android Studio 同步工程，也可以直接运行：

```powershell
.\gradlew.bat test
.\gradlew.bat assembleMini
.\gradlew.bat assembleRelease   # 需要 release 签名凭据，缺失时非零失败，见下文
```

`assembleRelease` 是唯一会产生正式签名的命令；没有 signing 凭据时它会失败（不会产出未签名包），本地验收请改用 `.\gradlew.bat assembleMini` 或 `assembleDebug`。

构建环境：

- Gradle 8.11.1
- Android Gradle Plugin 8.10.1
- Kotlin 2.1.21
- JDK 17

Release 构建启用 R8 代码/资源缩减（APK 约 15–25 MB），需要 `proguard-rules.pro` 中的 kotlinx-serialization keep 规则，否则 R8 会剥离 `serializer()` 导致运行时崩溃。

### Signed release 与 unsigned / debug / mini 的区别

| 变体 | 签名 | applicationId | 用途 |
| --- | --- | --- | --- |
| `release`：`assembleRelease` / `packageRelease` / `packageReleaseBundle` / `packageReleaseUniversalApk` | release keystore，四项签名属性缺一不可 | `app.tellev` | 唯一可以发布到 GitHub Releases 的正式包 |
| `mini`：`assembleMini` / `packageMini` | debug keystore | `app.tellev.mini` | 本地体积/功能验收：与 release 相同的 R8 与资源压缩，可与正式版并存安装，不是发布渠道 |
| `debug` / `mvuValidation` | debug keystore | `app.tellev.debug` / `app.tellev.mvuvalidation` | 开发调试（`mvuValidation` 同时是 `testBuildType`，`.\gradlew.bat test` 跑的就是它） |

正式 release 打包在下列情况下会**明确以非零退出码失败**，不会静默产出未签名包，也不会回退到 debug 签名：

- 四项签名属性（`tellevStoreFile`、`tellevStorePassword`、`tellevKeyAlias`、`tellevKeyPassword`）有任一缺失或为空；
- `tellevStoreFile` 指向的 keystore 不存在或不可读；
- 因此 `release` 构建类型拿不到可用的 signingConfig。

失败信息只列出缺失的属性名，不打印任何凭据值。构建脚本只读取这四个属性，不创建、导出或写入密钥；keystore 文件放在 `.keystore/`（gitignored）。没有签名凭据时的本地验收请用 `assembleMini` 或 `assembleDebug`，不要为了让 `assembleRelease` 跑过去而把 debug keystore 填进签名属性。

签名凭据按优先级从 Gradle 项目属性（`-P` / `~/.gradle/gradle.properties`）→ `local.properties`（gitignored）读取：

```properties
tellevStoreFile=.keystore/tellev-release.jks
tellevStorePassword=...
tellevKeyAlias=...
tellevKeyPassword=...
```

Release 产物输出路径：

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/bundle/release/app-release.aab
```

### 更新检查的渠道与包名配对

应用内更新只接受本渠道、且 tag 与 APK 资产版本一致的发行：

- 正式版：tag `vX.Y.Z` ↔ 资产 `tellev-X.Y.Z.apk`；
- 生图版：tag `vX.Y.Z-mnn` ↔ 资产 `tellev-X.Y.Z-mnn.apk`。

`app-release.apk`、版本与 tag 不一致的资产、另一渠道的资产、非 `.apk` 后缀都会被拒绝；单发行接口（`parseReleaseJson`）同样做渠道与资产校验，不会绕过。

生成依赖/许可证报告：

```powershell
.\gradlew.bat dependencyReport
```

输出写入 `DEPENDENCIES.md`。

## 测试

单元测试位于 `app/src/test`，覆盖存储兼容、提示词引擎、provider 适配器、扩展层、regex、聊天渲染等：

```powershell
.\gradlew.bat test
```

## 项目结构

```text
app/src/main/java/app/tellev/
├── MainActivity.kt              # 入口、角色卡导入 intent
├── TellevGraph.kt               # 依赖图：组装 provider、扩展宿主、密钥存储等
├── core/
│   ├── extension/               # 扩展宿主、slash 引擎、虚拟 API、事件目录
│   ├── model/                   # 核心数据模型
│   ├── plugin/                  # NativePluginApi（替代 Node 服务端插件）
│   ├── prompt/                  # PromptEngine、宏、instruct、上下文模板、token 预算
│   ├── provider/                # ProviderAdapter 及 16+ 个具体适配器
│   ├── regex/                   # 角色正则应用
│   ├── security/                # Keystore、脱敏、隐私保护
│   └── storage/                 # StDataStore、文件存储、角色卡解析、导入导出
├── feature/                     # Compose 屏幕：about / characters / chat / extensions / settings / world
└── ui/                          # 根导航、主题
```

架构原则与各层职责详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 开源协议

tellev 以 GNU Affero General Public License v3.0（AGPL-3.0）发布。完整协议文本见 [LICENSE](LICENSE)。

你可以自由使用、复制、修改和分发本程序；如果分发修改版，或将修改版作为网络服务提供给他人使用，应按 AGPL-3.0 提供相应源代码。

`tellev` 名称、启动图标和作者信息用于识别官方版本与项目作者。该说明不对 AGPL 覆盖的源代码增加额外限制，但也不表示允许冒充官方版本，或暗示作者为第三方版本背书。

## 与 SillyTavern 的关系

tellev 不是 SillyTavern 官方应用。它是一个受 SillyTavern 启发、并尽量兼容其数据结构和部分工作流的 Android 客户端实现。

SillyTavern 项目地址：

[https://github.com/SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern)

## 作者

- B站：迷迭香のねこ
- 主页：[https://space.bilibili.com/499259948](https://space.bilibili.com/499259948)

## 开发文档

如果要继续开发，可以先阅读：

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/AI_TASKS.md](docs/AI_TASKS.md)
- [docs/RELEASE_1.5.2.md](docs/RELEASE_1.5.2.md)
- [docs/RELEASE_1.5.3.md](docs/RELEASE_1.5.3.md)
- [docs/RELEASE_1.5.4.md](docs/RELEASE_1.5.4.md)
- [docs/RELEASE_1.5.5.md](docs/RELEASE_1.5.5.md)
- [docs/RELEASE_1.5.5.1.md](docs/RELEASE_1.5.5.1.md)
- [docs/RELEASE_1.6.0.1.md](docs/RELEASE_1.6.0.1.md)
- [docs/RELEASE_1.6.1.md](docs/RELEASE_1.6.1.md)
- [docs/RELEASE_1.6.3.md](docs/RELEASE_1.6.3.md)
