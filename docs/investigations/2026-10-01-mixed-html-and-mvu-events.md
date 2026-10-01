# 混合 HTML 与 MVU 生成事件修复

基于官方 master 的 `d6213fe`，对应用户提供的五张 Android 截图。

## 根因与改动

1. `TavernRenderParser` 识别到围栏 HTML 卡片后直接返回，其余文本不再解析原始 HTML；原始完整文档周围的片段也会被跳过。修复为逐段处理围栏卡片、完整文档和原始片段。普通代码围栏仍保留原有酒馆前端识别规则，不把仅有 div 的代码示例变成交互卡片。
2. HTML 宿主缺少 `--SmartThemeBodyColor`、`--SmartThemeBlurTintColor` 等酒馆颜色变量。使用当前 Material 配色提供默认变量，原卡自己的 CSS 位于默认样式之后，可继续覆盖它们。WebView 画布仍透明，不改角色卡或预设文件。
3. 生成事件原来只有 chatId/characterId/providerType，而 MVU 的 `filterPrompts({messages})` 直接调用 `messages.filter`。补正事件形状和先后顺序：`CHAT_COMPLETION_PROMPT_READY` 收到 `{chat,dryRun:false}`，随后 `CHAT_COMPLETION_SETTINGS_READY` 收到 `{messages}`。宿主等待异步监听器、回收修改结果，依次传给其他运行环境，并只更新本次发送的提示词投影。

上游对照：

- [MVU filterPrompts](https://github.com/MagicalAstrogy/MagVarUpdate/blob/61010dab47bc3a08a1b626320bf7fc8c9573eca4/src/function/request/filter_prompts.ts)
- [SillyTavern openai.js](https://github.com/SillyTavern/SillyTavern/blob/51ad27fb86d39a3daca3adaa970375c9670c12df/public/scripts/openai.js)，`prepareOpenAIMessages` / `sendOpenAIRequest` 的生成事件。

这是针对提示词消息过滤的兼容修复；不宣称已经支持所有请求参数、工具调用或全部酒馆设置。首图原有的两项兼容性诊断警告保留。

## 后端与数据边界

不改持久化、世界书扫描、记忆服务、模型适配器、API 凭据、包名或发布版本。普通没有监听器的请求保持原提示词对象；修改后的提示词保留未变的角色、名字、channel、stop、maxTokens、模板变量更新和诊断。非法消息形状回退到上一个有效投影；宿主延续已有的错误隔离和取消处理。

只调整前端解析/主题与生成事件兼容边界，未修改用户原始角色卡和预设，也没有删除、重写保存的聊天记录。修复包仍是 `app.tellev`、`1.7.1-beta.1` / 41，使用既有官方签名。

## 验证

- 全量 Debug 单元测试：1005 项，零失败、零错误、零跳过。实际 ViewModel → 生成协调器 → 模型请求测试证明过滤后的文字到达模型，而 JSONL 中仍是原始用户输入。
- JavaScript：34 项通过，运行生产桥接及真实 MVU bundle，覆盖异步监听器、占位符过滤和变量更新。
- Android 14 模拟器：5 项通过，覆盖混合 HTML、浅色和深色对比度、真实《道渊》变量日志的展开与样式、跨子 iframe 和多个宿主的修改回传、原有取消清理与隔离存储。
- Release 构建、lintDebug、签名、manifest、兼容资产哈希及官方渠道内容检查。

测试样本将已有《道渊》4.7 角色卡的完整变量更新模板与已有梦境预设面板组合，填入合成文本，不调用模型；原始输入哈希和测试日志保存在工作区外的 `_buildlogs/render-fix-20261001/`。

本轮没有操作真实手机或调用真实模型服务，没有修改 GitHub 已发布资产。
