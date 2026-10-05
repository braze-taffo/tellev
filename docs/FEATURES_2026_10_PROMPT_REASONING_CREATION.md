# 2026-10 功能增量：提示词优化 / 统一思考强度 / 制卡运行时 / 建议 / 图片归一化

状态：已实现并通过单元测试的部分见下；尚未实现与待验收项在文末，不得标记为完成。

## 1. 统一思考/推理强度（ReasoningEffort）

模型：`core/model/ReasoningEffort.kt`（Auto/Off/Low/Medium/High/Max，`GenerationPreset.reasoningEffort` 持久化字段，null = Auto）。
映射：`core/provider/ReasoningEffort.kt`（纯函数，13 项单测）。

- 解析链：请求级 override（`GenerateRequest.metadata["tellev_reasoning_effort"]`）> 会话 override（`GenerateRequest.metadata["tellev_reasoning_effort_session"]`，由协调器从会话元数据注入）> 预设字段 > Auto。
- Auto：保持各适配器既有 raw 透传行为，不新增任何字段——已有酒馆预设的 reasoning 字段不受影响。
- Off：抑制 reasoning 字段；DeepSeek 家族显式发送 `thinking: {"type":"disabled"}`。
- 显式档位按家族映射：OpenAI 形状 → `reasoning_effort`（Max 钳到 high）；DeepSeek → `thinking` 开关；OpenRouter → `reasoning.effort`（Max→high）；Gemini → `thinkingConfig.thinkingBudget`（1024/4096/12288/24576，Off=0）；Anthropic → `thinking{type:enabled,budget}`（2048/8192/16384/32768），预算钳制到 `maxTokens-1` 且 ≥1024，同时强制 `temperature=1`、抑制 `top_p`。
- Anthropic 的 Auto 行为与旧版一致：不主动开启 extended thinking（避免静默计费变化）。
- 已接线适配器：OpenAiCompatibleAdapter（通用 + DeepSeek 专用载荷）、GeminiAdapter、AnthropicAdapter、OpenRouterAdapter。
- **UI 选择器与会话级 override（第二批实现）**：聊天「更多」菜单新增「思考强度」分区（仅当 Provider 家族支持推理控制时出现），六档单选、当前档加粗；选择持久化于会话元数据 `tellev_reasoning_effort`（`ChatViewModel.setSessionReasoningEffort` → `persistSessionMutation`），「跟随预设」= 清除 override（预设字段重新生效）。主线生成（含重掷）经 `ReasoningSupport.sessionOverrideMetadata` 注入请求元数据；辅助流（场景摘要/记忆/扩展触发）不受影响。`ReasoningSupportTest` 增至 18 项。usage 诊断回显仍未实现（见文末）。

## 2. 提示词优化服务

- `core/prompt/PromptOptimizationModels.kt` / `PromptOptimizationParser.kt` / `PromptOptimizer.kt`（12 项单测）。
- 无副作用：不写会话、不触发扩展事件、不进聊天记录；独立 `GenerateRequest`（metadata `tellev_prompt_optimizer=true`）。
- 使用当前聊天 Provider + 当前酒馆预设（采样沿用预设，输出预算 = min(预设, 4096)，钳下限 512）。
- 严格 JSON 输出 `{"optimized","notes"}`；解析失败最多 1 轮修复（temperature 0），永不覆盖原草稿——失败只返回原因。
- reasoning-only 回复、空回复、失败 chunk 均显式报错。
- UI：`ui/PromptOptimizationDialog.kt` 共享对话框；聊天输入框（有草稿文字时出现魔杖按钮）与创作对话输入框均可触发；「替换草稿」只改草稿，发送永远由用户按下。
- 文案：四语言（strings.xml ×4 + S.kt + TSV 记账）。

## 3. AI 制卡运行时

- `CreationEngine` 增加可选 `store: StDataStore`（`CreationViewModel` 传入）：
  - 解析当前聊天选中的 Provider 与其预设类别的当前预设（`in_use` 工作副本 + 命名预设）。
  - 采样（temperature/topP/…）、输出预算（钳 1024..128000）、stop、Provider raw 字段（含 reasoning）全部跟随预设。
  - 制卡系统指令保持权威：预设的 prompt 插槽永不注入创作请求。
  - 修复/压缩轮强制 temperature 0；常规轮跟随预设温度（无预设回退 0.7）。
  - 预设 I/O 失败降级为内置 agent 预设，不中断创作回合。
  - `providerLabel` 追加预设名，UI 现有面板直接可见。
- 无 store（测试路径）保持旧行为：`ai-creation-agent` 固定预设。

## 4. 创作引导建议（点击填入，不发送）

- 协议：模型可在回复末尾附加不可见注释 `<!-- suggestions: ["…", …] -->`（指令追加在系统提示）；`splitReplySuggestions` 用确定性注释扫描剥离并解析（最多 4 条、去重；截断/畸形注释一律剥离，永不外泄到界面）。
- `CreationViewModel.suggestions` 随回复更新、随下次发送清空；UI 以 AssistChip 展示，点击仅填入输入框。

## 5. 图片结果归一化

- `core/provider/ImageResultNormalizer.kt`（10 项单测）：裸 base64 / data URI / https URL 统一处理；magic 嗅探（PNG/JPEG/WebP/GIF）拒绝 HTML/JSON 错误体；20MB 上限；URL 下载器注入（`TellevGraph.imageDownloader`，走共享 OkHttp + CleartextGuard，内容长度+流式双上限）。
- `ChatImageGenerationCoordinator`：结果先归一化再落盘；PNG 原样保存，JPEG/WebP 在设备端经 `decodeImageAsPng` 转 PNG（最长边 1024），失败显式报错（新文案 `chatimgco_invalid_image_data`）。
- 修复的真实缺陷：OpenAI Images 返回 URL 时被当 base64 解码成坏图。

## 6. 批量导入角色卡（第二批）

- `core/storage/CharacterBatchImporter.kt`：逐文件解析（`CharacterImporter` 全格式：V1/V2/V3 JSON、PNG/WebP 内嵌卡、CHARX、BYAF、ZIP）→ 占位 id 修复 → 查重（对调用方传入的现有 id 集 + 本批先导入的 id 去重，冲突加 8 位 hex 后缀）→ `StDataStore.importCharacter` 落盘。**单文件失败不中断其余文件**，逐项结果进 `BatchImportReport`。
- UI：角色列表页导入按钮改用 `OpenMultipleDocuments`（可多选，也可单选）；读取用 `UriUtils.readBounded`（64KB 流式 + 100MB 上限，读不动的文件以失败项进报告而非静默丢弃）。多文件结果以对话框逐行展示（✓/✕ + 文件名 + 角色名/错误），单文件仍走原 snackbar；文案四语言。
- 兼容旧语义：`CharactersViewModel.importCharacter(bytes, fileName)` 保留为批量路径的单文件包装；未命名卡片沿用解析层行为（空名 → `unknown`，纯标点名 → 占位符 → 现按 `char_<uuid>` 生成真 id）。
- 新增测试 `CharacterBatchImporterTest`（7 项）：混合格式、批内同名查重、不覆盖现有角色、坏文件隔离、读取失败报告、占位 id 生成、计数汇总。
- 已知边界：外部「分享/打开」入口（`MainActivity` VIEW/SEND）仍为单文件；`ACTION_SEND_MULTIPLE` 支持留待后续。

## 附带修复

- `CreationRepository` Windows 竞争：草稿列表流式读取与原子改名互斥时 `AccessDeniedException` 导致 checkpoint 误判失败；三处改名统一 `moveReplacing`（短暂重试后仍失败才抛出）。修复了基线中确定性失败的 `failedSecondRoundKeepsDraftAcrossReopenAndContinue`。

## 尚未实现（后续阶段，按已批准计划）

~~标准角色蓝图（CharacterBlueprint + 编译器 + `data.extensions.tellev.standard_profile`）与 AI 生成封面流程。~~ **第四批已实现**（键名按代码库惯例定为 `tellev_standard_profile`，与 `tellev_additional_worldbooks` 等既有平铺键一致；蓝图含 cover_prompt 并在导出时重新生成）。
~~自定义生图端口多配置（ImageProviderProfile）与设置页。~~ **第四批已实现**。
- Prompt preview / capability badge / 预算提示 / 诊断导出脱敏 / 版本历史等质量项。
- 思考强度 usage 诊断回显；外部 SEND_MULTIPLE 多文件分享导入。
- ~~真实 Provider 请求体实测、设备 UI 验收、SillyTavern 双端端到端验收。~~ 请求体形状（单测）与 relay 捕获链路（本地）第四批已验证；**设备 UI 验收、真实上游请求、SillyTavern 双端端到端仍需真机 + 真实 API 环境**。
- 正式 Release 签名包（等 `local.properties` 签名凭据；未签名 R8 包已可构建）。

## 测试基线

- 第一批新增：ReasoningSupportTest(13→18)、PromptOptimizerTest(12)、CreationPresetCompositionTest(5)、CreationSuggestionsTest(7)、ImageResultNormalizerTest(10)。
- 第二批新增：CharacterBatchImporterTest(7)。
- 修复：CreationViewModelCancelTest（Windows 原子改名竞争）。
- 全量 `:app:testDebugUnitTest` 于本文件写作时 1133+ 项、0 失败（历次增量后均复跑通过）。

## i18n 工具链注意（第二批发现 → 第三批已修复）

~~`tools/i18n_consolidate.py` 当前会因约 170 个更早批次手写进 S.kt/strings.xml 但从未回填 TSV 的 key 校验失败、不生成。~~ **第三批已修复**：`tools/i18n_backfill.py` 一次性回填 194 个欠账 key（含 25 个迁移自 `ui_atmosphere.xml` 的 key，该文件已删除避免重复资源），管线恢复一键生成；此后所有新文案直接走 TSV 流程。唯一已知残留：`creng_system_prompt` 因 TSV 约定无法表达字面反斜杠序列，其 S.kt `fallbackZh`（仅 JVM 测试回退用）与运行时 strings.xml 有 3 个字符的转义差异——运行时值与迁移前逐字节一致（1328 个既有 key ×4 语言零差异已验证），且无测试断言该文案。

## 7. 聊天/记忆可观测性 + 脚本权限与诊断（第三批）

### 7.1 上下文查看器（含世界书命中 + 记忆溯源 + 诊断导出）

- 数据层：`PromptDiagnostics` 新增 `worldBookHits`（entryId/标题/书名/命中主次关键词/是否常驻/递归层级/token）与 `rejectedWorldEntries`（disabled/keyword_miss/probability/inclusion_group/budget/delayed_until_recursion 六类原因）。`WorldInfoScanner.scan` 增加可选 `ScanDiagnostics` 收集器——**默认 null 时所有判定路径零改动**，收集器只旁观（命中键镜像 `matchEntry`、预算落选在溢出门闩处记录、末尾对未激活候选做 keyword_miss/delayed 对账）。`emitPromptDiagnostics` 扩展事件负载同步带新字段。
- `ChatGenerationCoordinator` 每次主线生成/重掷构建 prompt 后把快照（消息列表 + token + 警告 + 命中/落选 + 记忆注入明细）写入 `ChatUiState.contextSnapshot`（仅内存、仅最新一轮、跨会话自动失效）。
- UI：聊天「更多」→「上下文查看器」：统计头、警告卡、世界书命中卡（书名/标题/匹配词/递归标记）、未生效条目清单（本地化原因）、记忆注入卡（检索评分 + 来源消息跳转，关闭对话框并滚动到楼层）、Prompt 消息列表（折叠/展开/复制）。「导出 JSON」经 SAF 写出完整快照。
- 记忆溯源：`MemoryRetrieval.searchScored` 暴露评分；`MemoryService.contextDetail` 返回 `MemoryContextDetail(text, injected)`（recordId/kind/text/score/sourceIds），`context()` 保持原签名。

### 7.2 记忆纠正与回滚

- `MemoryRecord.history: List<MemoryCorrection>`（上限 5，序列化向后兼容）；`correct()` 覆盖前把旧文本入栈（内容未变不入栈）；新增 `rollbackCorrection(sessionId, recordId, index)`——恢复历史版本且当前文本同样入栈（回滚本身可撤销）。
- UI：记忆管理对话框记录行新增「历史版本」→ 版本列表 + 「恢复此版本」。

### 7.3 脚本权限与诊断（扩展调试面板）

- **API 统计**：JS shim 在模板最前统一包装 `tellevNative`（静态方法名清单，跳过 emit/log/traceCall/apiCall/extensionReady），经新桥方法 `traceCall(name, ms, failed)` 上报；`apiCall` 的异步处理器耗时在 Kotlin 侧以 `api METHOD path` 原生记录（JS 只测握手不测处理）。`ExtensionStatsCollector`：每扩展每 API 的次数/总耗时/最大/错误数（名称上限 200 防动态路径撑爆）+ 最近 50 条调用流水。
- **失败堆栈**：`reportExtensionError` 把堆栈写入 `ExtensionDiagnostics.recentErrors`（50 条、按扩展+消息+首栈帧去重计数）；`extensionFailed`/控制台 error/渲染进程崩溃均接入。调试面板错误分区：次数/最近时间/可选择复制的完整堆栈。
- **权限清单**：`ExtensionHost.declaredExtensionPermissions()` 暴露各运行时声明集；调试面板逐权限开关（吊销立即生效——bridge `checkApiPermissions` 现成；重授仅限调试面板，同意弹窗仍是主门）。
- **单脚本禁用**：`CharacterTavernHelperScripts.listScriptEntries`（含禁用项 + raw 树路径）+ `withScriptEnabledAt`（按路径无损写回 `enabled`，legacy `{type:'script',value:{…}}` 落在嵌套 ScriptData 上；legacy 文件夹无 enabled 字段不支持整体禁用——已知限制）。聊天「更多」→「脚本管理」逐脚本开关，写回卡片后热重载；启用集合变化改变隔离源指纹 → 按既有安全设计重新征求同意。

### 7.4 测试基线

- 新增：WorldInfoScannerTest 诊断（+7）、MemoryCorrectionHistoryTest（4）、ExtensionStatsCollectorTest（5）、ExtensionDiagnosticsErrorsTest（3）、CharacterTavernHelperScriptsTest 开关（+4）。
- i18n 回填后全量 strings.xml ×4 与迁移前逐 key 零差异验证；`nav_qq_group_name` 维持既有 zh 回退。

## 8. 标准角色蓝图 + AI 封面 + 自定义生图端口（第四批）

### 8.1 标准角色蓝图（CharacterBlueprint + 编译器 + write_blueprint）

- 模型：`feature/creation/CharacterBlueprint.kt`——`CharacterBlueprint`（ST V2 snake_case 协议键：name/description/personality/scenario/first_mes/alternate_greetings/mes_example/system_prompt/post_history_instructions/creator_notes/tags/cover_prompt/lore[]）+ `BlueprintLore`；`fromArguments` 接受 camelCase 别名（first_message/creatorNotes/secondaryKeys 等），未知顶层键拒绝并列出可用键（与 set_card_fields 同等严格，模型可凭报错自纠重试）。
- 工具：`creation_tool` 新增 `write_blueprint`（TOOL_NAMES 与原生 enum 同步）。语义：整卡字段整体覆写 + 世界书按标题 upsert（命中即原位更新、保留 id/sourceQuote/originalEntry 合并基线；新增条目走单调 L 编号；蓝图未提及的既有条目一律保留不删）。世界书会话与空 name 拒绝。
- 系统指令：`BLUEPRINT_DIRECTIVE`（硬编码英文协议文本，同 SUGGESTIONS_DIRECTIVE 先例，不走 i18n）追加在角色会话 system prompt；世界书会话不追加。
- 编译与导出：`CharacterBlueprintCompiler.compile(session)` 从草稿+世界书**在每次导出时重新生成** `data.extensions.tellev_standard_profile`（`toCharacterCard` 注入，草稿是唯一事实源，蓝图永不失效；第三方 extensions 键原样透传）。`fromCharacter` 从既有 extensions 恢复 `cover_prompt` → `CharacterDraft.coverPrompt`（新增字段）。
- UI：`CharacterDraftEditor` 新增「标准蓝图」分区——状态行（条目数/封面提示词）、「让 AI 生成标准蓝图」（发 `creng_blueprint_request` 进对话）与「复制蓝图 JSON」（剪贴板）。
- 计数反馈：write_blueprint 结果报 lore_created/lore_updated/lore_kept（模型视角：蓝图命中=updated，未提及=kept，新标题=created）。

### 8.2 AI 生成封面

- `CreationCoverGenerator`（feature/creation）：一次性生图——引擎解析（内置 ComfyUI/NovelAI 或 `imgprof:` 自定义端口）→ GenerateRequest → `ImageResultNormalizer` 归一化（URL/base64、magic 嗅探）→ 非 PNG 经 `decodeImageAsPng` 转码 → 返回 PNG 字节走既有 `repository.saveCover`（PNG 魔数+10MB 校验）；不进聊天画廊、不耦合会话。
- `CreationViewModel`：`refreshCoverEngines`/`generateCover`/`cancelCoverGeneration`，独立 `isGeneratingCover` 状态与 job（不阻塞对话 busy）；`persistCover` 与 `setCoverPng` 共用落盘路径。`TellevRoot` 为工厂注入 `graph.imageDownloader`。
- UI：封面分区新增「AI 生成封面」按钮（仅当有已配置引擎时出现）→ `CoverGenerationDialog`（引擎单选含自定义端口 + 提示词预填 `cover_prompt`（缺失回退 name+personality）+ 反向提示词）。

### 8.3 自定义生图端口多配置（ImageProviderProfile）

- 模型：`core/provider/ImageProviderProfile.kt`——命名的 OpenAI Images 兼容端点（baseUrl/apiKey/model/path=`/v1/images/generations`/modelsPath/size/quality/style/responseFormat/sendNegativePrompt/extraBody）；空白可选字段不进请求体（严格上游不会收到 DALL·E 专属参数）；`extraBody` 最后合并可覆盖任何字段；`toProviderConfig()`/`requestMetadata()` 供两个调用方共用。
- 持久化：`ProviderConfigPersistence` 新增加密 JSON 列表（secret id `image-provider-profiles`）+ upsert/delete/find；引擎 id 前缀 `imgprof:`（`IMAGE_PROFILE_PREFIX`）。`configuredImageEngines` 纳入已配置 profile；`saveImageEngine` 校验 profile 存在且 baseUrl 非空，删除 profile 时活动引擎自动回落 ComfyUI。
- 适配器：`OpenAiImageAdapter` 元数据化——`images_path` 覆盖生成路径、checkStatus 读 `options["models_path"]`、size/quality/style/response_format/negative_prompt 仅在元数据存在时发送、`extra_body` 末位合并；请求体构建抽为纯函数 `imageGenerationPayload`（7 项单测锁定 wire 形状）。
- 聊天侧：`ChatImageGenerationCoordinator` 引擎解析改为 id 直通（内置枚举/`imgprof:` 三分支），未知 id 保留原「请选择生图引擎」错误；场景总结签名改 `usesEnglishTags: Boolean`；`ImageGenerationDialog` 新增 profile 行（`ChatImageProfileOption`）。`ChatImageEngine` 枚举保持两内置不变（AGENTS.md 渠道约束不受影响——自定义端口是远程 OpenAI 兼容端点，不含本地推理）。
- 设置页：`SettingsImageGenSection` 生图子页新增「自定义生图端口」区块（列表/设为当前引擎/编辑/删除/添加）；`ImageProfileEditDialog` 全字段编辑（密钥遮蔽、extraBody JSON 校验、连接测试走 checkStatus）；`ImageGenSettingsController` 增 save/delete/test/load；入口卡摘要显示自定义端口数。

### 8.4 i18n 与测试基线（第四批）

- i18n：a1（crs_ 11 键）、a2（creng_/crvm_ 9 键）、a3（setimg_ 25 键）、a5（imgctl_ 5 键）共 50 键 ×4 语言，`i18n_consolidate.py` 一键通过（1488 键）。
- 新增测试：`ImageProviderProfileTest`（7：payload 形状/可选字段省略/extraBody 覆盖/config 规范化/extraBody 解析/configured 语义）、`ProviderConfigPersistenceTest` 扩展（+5：round-trip/按配置过滤/profile 引擎选择与失效回落/未知 id 拒绝/id 唯一性）、`CharacterBlueprintTest`（10：别名解析/未知键拒绝/lore upsert 语义/compile 往返/导出注入/导入恢复 cover_prompt/write_blueprint 计数与世界书拒绝）。
- 全量 `:app:testDebugUnitTest` 1188 项、0 失败（历次 1133+ 基线之上累计）。

### 8.5 真实 Provider 请求体实测（本地可完成部分）

- `imageGenerationPayload` 纯函数单测锁定 OpenAI Images 请求体形状（含 profile 元数据映射与 extraBody 覆盖）。
- `tools/openai-relay.mjs` 本地链路复验：桩上游 + relay（/tellev 前缀）→ OpenAI Images 形状请求逐字到达上游、Authorization 转发且捕获脱敏（[REDACTED]）、bodySha256/summary 记录、响应回传——捕获链路完好。
- **仍开放（需真机 + 真实 API 环境）**：设备 UI 验收（设置页编辑端口、聊天/封面生图）、真实上游计费请求、SillyTavern 双端端到端（导出蓝图卡导入 ST 对话 + ST 卡导入回编辑）。

### 8.6 Release 构建状态

- `local.properties` 仅有 `sdk.dir`，无 `tellevStoreFile/tellevStorePassword/tellevKeyAlias/tellevKeyPassword` → `assembleRelease` 产出 **未签名** APK（R8 + 资源压缩生效）。正式签名包需用户补齐凭据后重跑 `assembleRelease` 并用 apksigner 验签；交付前按 AGENTS.md 校验 manifest/签名/ZIP 内容（master 不得含 `libstable_diffusion_core.so` 或 `assets/ldcvt/`）。
