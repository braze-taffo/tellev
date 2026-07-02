# tellev

tellev 是一个面向 Android 的 SillyTavern 兼容客户端。项目目标是在手机上提供更贴近原生应用体验的角色管理、聊天、世界书、扩展和模型服务配置能力，同时尽量保持与 SillyTavern 数据格式和使用习惯兼容。

> 公开版本以 GitHub Releases 中发布的 APK 为准。当前版本 **v1.1.1**。

## 主要功能

### 原生 UI

- 纯原生 Android / Jetpack Compose + Material 3 界面，非 WebView 套壳
- 聊天：流式生成、停止生成、重试、swipes、编辑、删除、收藏、附件（图像下采样为 base64，不落盘）、推理块（reasoning）渲染、TavernHelper 风格的 HTML 片段渲染、Markdown 渲染（commonmark + GFM 表格，AI 消息按需走 WebView）
- 角色：列表、标签、导入、编辑、复制、导出、删除（含关联变体与内嵌世界书清理）
- 世界书：条目管理、搜索、激活预览、常驻/排他激活，以及 ST 对齐的高级特性——8 种注入位置、4 种选择性逻辑（AND/NOT）、概率触发、递归扫描、正则/全词/大小写匹配、@depth 注入
- 人格（Persona）：列表、创建/编辑/删除、运行时切换
- 扩展：已安装扩展列表、加载/卸载、权限授予状态
- 设置：provider 配置、预设、密钥、主题、备份、扩展管理
- 平板与大屏：双栏聊天、三栏管理布局（基于 AndroidX Window）

### 模型与服务接入

内置 16+ 个 provider 适配器，统一实现 `ProviderAdapter`：

- **OpenAI 兼容**：通用 OpenAI 兼容接口、DeepSeek、火山引擎 Coding Plan
- **聊天补全**：Anthropic、Gemini、OpenRouter、NovelAI、Azure OpenAI
- **本地/自托管**：Ollama、Kobold、KoboldCpp、TextGen (ooba)、llama.cpp server、Horde
- **图像生成**：Stable Diffusion 兼容 API、OpenAI 兼容图像 API
- **语音**：OpenAI 兼容 Speech
- **翻译**：Google 翻译

适配器规范化处理：状态检查、模型列表、流式分块、取消、可重试错误、provider 特定预设字段。

### 提示词引擎

`DefaultPromptEngine` 已实现 SillyTavern 风格的提示词组装：

- 宏展开：`{{char}}`、`{{user}}`、日期/时间、`{{random}}`/`{{roll}}`、`{{newline}}`/`{{space}}`/`{{reverse}}`、`{{greeting::N}}`、`{{model}}`/`{{persona}}`/`{{input}}`、变量宏（`{{getvar::}}` / `{{setvar::name::value}}` / `{{incvar::}}` 等，local/global 分流）与变量简写（`{{.name}}` / `{{$name}}` 及 `++`/`--`/`=`/`+=`/`==`/`!=`/`>`/`<`/`||`/`??`/`||=`/`??=` 等操作符）、扩展提供的宏
- Instruct 模式与上下文模板（Context Template）
- 世界书激活引擎：关键词匹配、selectiveLogic 四逻辑、概率触发、递归扫描、8 种注入位置（before/after/ANTop/ANBottom/atDepth/EMTop/EMBottom/outlet）、@depth 消息注入、优先级与插入顺序排序
- Per-scope 变量：local（`chat_metadata.variables`，随对话持久化）与 global（全局持久化），斜杠命令/宏/EJS 三处分流一致
- 群聊发言人排序
- Token 预算感知的上下文裁剪
- ST-Prompt-Template 兼容的 EJS 模板处理（`<%= ... %>` / `<% ... %>`）
- 扩展注入提示（`injectPrompts`，支持 BEFORE_PROMPT / IN_PROMPT / IN_CHAT depth）

### 数据兼容

- 角色卡导入/导出：JSON、PNG、WebP、CHARX、BYAF 元数据解析与写入
- SillyTavern chat JSONL 解析为类型化 `ChatMessage`，保留未知字段
- 世界书解析：条目标志、优先级、depth、order、选择性关键字
- ZIP 备份导入/导出，含路径遍历防护
- 数据根目录镜像 SillyTavern 的 `USER_DIRECTORY_TEMPLATE`：`context.filesDir/st-data/`

### 扩展运行环境

`WebViewJsExtensionHost` 是 SillyTavern / 酒馆助手（JS-Slash-Runner）兼容层的脚本风格子集实现：

- 每个扩展运行在独立沙盒 WebView，提供 `tellevNative` 桥接
- 兼容 shim 暴露：`SillyTavern`、`getContext`、`eventSource`、`event_types`、`TavernHelper`、`executeSlashCommandsWithOptions`、`executeSlashCommands`、`fetch` 覆写
- 事件总线：108 个 ST 事件常量 + tellev 扩展事件
- `TavernHelper` API：约 140 个方法（generate、角色/世界书/预设/人格 CRUD、注入提示、regex、变量、slash 命令等）
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

- **支持**：角色卡内嵌的 TavernHelper 脚本（`data.extensions.tavern_helper.scripts`）、EJS 模板语法（`<%= ... %>` / `<% ... %>`）、`eventSource` 事件总线、`TavernHelper.*` API、slash 命令、虚拟 `/api/` 路由。
- **不支持**：直接安装酒馆助手（JS-Slash-Runner）本体或提示词模板（ST-Prompt-Template）本体的 ESM 产物——这两个扩展以 ES Module 形式打包并依赖大量 SillyTavern 内部模块，tellev 的经典 `<script>` 注入模型无法加载它们。tellev 已内置 `TavernHelper` / `EjsTemplate` 兼容 shim 提供等价的脚本能力。
- **不支持**：需要 Tailwind / Vue / jQuery UI 渲染的消息 iframe 扩展、Node 服务端插件、直接文件系统访问、非 `/api` 的网络请求（被 CSP 阻断）。

## 下载安装

可以在 GitHub Releases 下载最新 APK：

[tellev Releases](https://github.com/braze-taffo/tellev/releases)

目前发布的是正式版 APK，适合日常使用。安装前请确认设备允许安装来自浏览器或文件管理器的应用。

## 项目信息

- 应用名：`tellev`
- 包名：`app.tellev`
- 当前版本：1.1.1（versionCode 6）
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
.\gradlew.bat assembleRelease
```

构建环境：

- Gradle 8.11.1
- Android Gradle Plugin 8.10.1
- Kotlin 2.1.21
- JDK 17

Release 构建启用 R8 代码/资源缩减（APK 约 15–25 MB），需要 `proguard-rules.pro` 中的 kotlinx-serialization keep 规则，否则 R8 会剥离 `serializer()` 导致运行时崩溃。

签名凭据从 `local.properties`（gitignored）读取：

```properties
tellevStoreFile=.keystore/tellev-release.jks
tellevStorePassword=...
tellevKeyAlias=...
tellevKeyPassword=...
```

Release APK 输出路径：

```text
app/build/outputs/apk/release/app-release.apk
```

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
