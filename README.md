# tellev

tellev 是一个面向 Android 的 SillyTavern 兼容客户端。项目目标是在手机上提供更贴近原生应用体验的角色管理、聊天、世界书、扩展和模型服务配置能力，同时尽量保持与 SillyTavern 数据格式和使用习惯兼容。

> 公开版本以 GitHub Releases 中发布的 APK 为准。

## 主要功能

- 原生 Android / Jetpack Compose 界面
- 角色卡导入、管理与本地存储
- 聊天会话、消息渲染和前端 HTML 片段展示
- 世界书管理与启用状态控制
- OpenAI 兼容接口配置
- 预设、密钥、主题和备份管理
- SillyTavern 风格扩展运行环境的兼容实现

## 兼容性范围说明

tellev 的扩展运行环境是 SillyTavern / 酒馆助手（JS-Slash-Runner）兼容层的一个**脚本风格子集**实现，并非完整复刻。当前兼容范围：

- **支持**：角色卡内嵌的 TavernHelper 脚本（`data.extensions.tavern_helper.scripts`）、EJS 模板语法（`<%= ... %>` / `<% ... %>`）、`eventSource` 事件总线、`TavernHelper.*` API、slash 命令、虚拟 `/api/` 路由。
- **不支持**：直接安装酒馆助手（JS-Slash-Runner）本体或提示词模板（ST-Prompt-Template）本体的 ESM 产物——这两个扩展以 ES Module 形式打包并依赖大量 SillyTavern 内部模块，tellev 的经典 `<script>` 注入模型无法加载它们。tellev 已内置 `TavernHelper` / `EjsTemplate` 兼容 shim 提供等价的脚本能力。
- **不支持**：需要 Tailwind / Vue / jQuery UI 渲染的消息 iframe 扩展、Node 服务端插件、直接文件系统访问。

## 下载安装

可以在 GitHub Releases 下载最新 APK：

[tellev Releases](https://github.com/braze-taffo/tellev/releases)

目前发布的是正式版 APK，适合日常使用。安装前请确认设备允许安装来自浏览器或文件管理器的应用。

## 项目信息

- 应用名：`tellev`
- 包名：`app.tellev`
- 最低系统：Android 12 / API 31
- UI：Kotlin + Jetpack Compose + Material 3
- 网络：OkHttp
- 序列化：Kotlin Serialization
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

Release APK 输出路径：

```text
app/build/outputs/apk/release/app-release.apk
```

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
