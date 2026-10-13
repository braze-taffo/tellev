# Tellev rebuild11 缺陷追踪清单

汇总自 `2026-10-07-rebuild11-frontend-backend-audit.md`(第 1-5 节),供修复排期用。
编号规则:F=前端功能缺失(第一节)、B=后端 Bug(第二节)、N=第二轮(第四节)、U=UI 专项(第五节)、P=第三轮 provider 审计、E=第三轮引擎审计。
状态:□ 未修 / ◐ 部分修 / ■ 已修并验证。修复请同时更新本表。

## 修复优先级总览(建议顺序)

1. **安全/数据损坏**:N1 权限闸门绕过、N2 备份吞哈希、N3 重生成清空记忆、B1 TTS 崩溃
2. **核心链路**:F1 图片渲染、F3 脚本授权死锁、F2 用户消息编辑、F4/F5 生图反馈、N4 跨角色会话
3. **高频数据安全**:N10 预设清参、U3 世界书写错书、N11 index 串台、B2 草稿丢失
4. 其余 Medium/Low 按批次。

## A. 安全与数据损坏(修复第一批)

| ID | 问题 | 位置 | 状态 |
|----|------|------|------|
| N1 | 扩展权限闸门被 `//` 绕过,可读写全部密钥(实测) | ExtensionHostPolicy.kt:7-14 + VirtualApiRouter dispatch | ■ |
| N2 | 备份脱敏把 processed 哈希变 [REDACTED],恢复后记忆清空(实测) | SensitiveFieldScanner.kt:~35 + BackupCoordinator.kt:52 | ■ |
| N3 | 重新生成/滑动最新回复清空全部长期记忆(实测) | MemoryService.kt:295-313 reconcile | □ |
| B1 | TTS 测试按钮:非法 URL 崩溃 + 主线程网络 + 绕过全局超时 | TtsSettingsSection.kt:209、OpenAiSpeechAdapter.kt:39-44 | ■ |
| N6 | 文本附件被当 EJS/宏执行 + 仅当轮生效(实测) | ChatGenerationCoordinator.kt:214-224,348 | □ |

## B. 核心功能断裂(前端缺失 HIGH)

| ID | 问题 | 位置 | 状态 |
|----|------|------|------|
| F1 | 消息内图片/附件不渲染,ChatBubbleImage 零调用 | DshChatScreen.kt:992-1111 | □ |
| F2 | 用户消息不可编辑/删除/swipe | DshChatScreen.kt:992-1032 | □ |
| F3 | 角色卡脚本授权弹窗缺失,授权死锁 | ChatViewModel.kt:2049-2078 零 UI 调用 | ■ |
| F4 | 生图结果画廊缺失,无处查看/删除 | ChatScreen 旧 704-756;ui/dsh 零引用 | □ |
| F5 | 生图进度行+取消缺失 | isGeneratingImage/stopImageGeneration 零引用 | □ |
| F6 | 待发附件不可见不可移除 | DshComposer 无 attachments 参数 | □ |
| F7 | Persona 运行时切换缺失 | selectPersona(ChatViewModel.kt:1824)零调用 | □ |
| F8 | 宽屏 NavigationRail 缺失 | DshRoot.kt:253-277 | □ |
| N4 | 抽屉跨角色会话不切角色,问候语被改写落盘 | DshDrawer onOpenSession + ChatViewModel.kt:863-903 | □ |
| N5 | 生图进度/错误/结果在 dsh 全无展示(grep 证实) | ui/dsh 对 imageGen* 零引用 | □ |

## C. 数据与状态安全(MEDIUM)

| ID | 问题 | 位置 | 状态 |
|----|------|------|------|
| N10 | 预设调节保存清掉 maxCompletionTokens;非法输入静默清参 | DshPopups.kt:1725,1782-1786 + ChatViewModel.kt:~1930 | ■ |
| U3 | 世界书 sheet:未绑定改写第一本书;uid 跨书串台;逗号切 key | DshPopups.kt:1341,1571-1578,1635 | □ |
| N11 | 滑动/编辑/菜单 index 串台(删除后旧 index 作用到别的消息) | DshChatScreen.kt:1079-1087,188,511 | □ |
| N12 | 世界书条目编辑器跨书串台(同 U3 第二条,合并处理) | DshPopups.kt:1436,1571-1578 | □ |
| N13 | 创作页 LaunchedEffect 重组即重置会话 | DshRoot.kt:404,419,434 | □ |
| B2 | 输入草稿/附件 remember 丢失(旧版 Saveable) | DshChatScreen.kt:165-166,197 | ■ |
| U6 | 角色卡编辑器一半字段旋转丢失;Comfy 对话框全丢 | CharactersScreen.kt:585-601 + SettingsImageGenSection 636-642 | □ |
| U7 | 世界书条目编辑器默认值静默覆盖;WorldComponents options.first() 空崩 | WorldBookEntryEditScreen.kt:101-115、WorldComponents.kt:36 | ◐ (仅修空列表崩溃,默认值覆盖未处理) |
| U5 | 设置页 state.xxx!! 三处 NPE 窗口 | SettingsProviderSection.kt:417、SettingsImageGenSection.kt:305,441 | ■ |
| N16 | 其余 WebView 无 onRenderProcessGone,崩溃杀全应用 | WebViewTemplateEvaluator.kt:101 等 4 处 | □ |
| B4 | 损坏 TTS 缓存条目永久毒化播放 | TtsCache.get + TtsPlayer.kt:73-75 | □ |
| B7 | 抽屉孙会话不可见(fork-of-fork 消失) | DshDrawer.kt:372-389 treeRows | □ |

## D. 交互与体验(MEDIUM/LOW)

| ID | 问题 | 位置 | 状态 |
|----|------|------|------|
| N7 | 语音识别中点发送,已发内容被灌回输入框;「取消」仍回调 | DshChatScreen.kt:277-285 + VoiceInputController | ■ |
| N8 | 输入框无最大高度,长文挤掉控制行 | DshComposer.kt:124-141 | ■ |
| N9 | 选其它供应商分组的模型不切供应商 | ChatViewModel.kt:1642-1675 selectModel | □ |
| N14 | 双击角色卡双导航;AI 创作入口回不到世界书;presetFocus 不清零 | DshRoot.kt:315-321,356,215/469/559 | □ |
| N15 | 抽屉:搜索收起不清 query;删除会话不刷新;旋转复位 | DshDrawer.kt:179,284 + ChatViewModel deleteSession | □ |
| F9 | 生图失败弹窗与诊断查看缺失(零引用) | imageGenError/imageGenDiagnostic | □ |
| F10 | 首 token 前 loading 占位缺失 | DshChatScreen.kt:529 | □ |
| F11 | 模型名手动输入兜底缺失 | DshPopups.kt 模型菜单 | □ |
| F12 | TTS 页面生命周期绑定缺失(离开页面继续播) | TtsRuntime.bindPage 零调用 | □ |
| F13 | WebView 卡片边界滚动传导三个空 lambda | DshChatScreen.kt:510,567,1105 | □ |
| F14 | frontend 消息 swipe 控件缺失 | DshChatScreen.kt:1112 | □ |
| F15 | 聊天内生图/统计/TTS 入口死参数 | DshChatScreen.kt:146-147 | □ |
| F16 | 触控目标 <48dp 批量 | ActionKey 28dp、抽屉 24dp 等 | □ |
| F17 | 草稿 rememberSaveable(同 B2,合并) | DshChatScreen.kt:165 | ■ |
| F18-F27 | 诊断导出/空正文重试/世界书直入/会话标题/记忆状态条/pinnedOnly/收键盘/初始定位/隐藏 TTS/分组折叠 | 见审计第一节 LOW 表 | □ |
| B3 | TTS 字段每字符写盘 + trim 吃空格 | TtsSettingsSection.kt:157-176 | □ |
| B5 | 静音模式 TTS 照播 | 无 ringerMode 检查 | □ |
| B6 | 模型分组只能增不能删 | ChatModelGroups + DshPopups 393-483 | □ |
| B8 | 流式滚动偏移(横幅 item) | DshChatScreen.kt:307-309 | □ |
| B9 | ttsProviderConfigured 提示与实际约束不一致 | SettingsScreen.kt:90-91 | □ |
| B10-B14 | TTS 缓存键归一/自定义鉴权头/错误码/长按冲突/附件键语义 | 见审计第二节 LOW | □ |
| U1 | Bre 滑杆硬编码亮色系,暗色不可见 | DshPopups.kt:555-1025 | □ |
| U2 | 分段条亮色对比度低 | DshContextPopover | □ |
| U4 | dsh 数字输入无键盘类型、非法输入静默清空 | DshPopups.kt DshNumberField/DshDecimalField | □ |
| U8 | CharactersViewModel 14 处硬编码中文提示;核心层异常文案直通 UI | CharactersViewModel.kt:89-344 等 | □ |
| U9 | 扩展页 7 处 AssistChip 点击无反应 | ExtensionsScreen.kt:386-443 | □ |
| U10 | 世界书圆点 18dp 等无障碍 | DshPopups.kt:1487-1493 | □ |
| LOW-滚动组 | followLatest 不恢复/发送不回底/跳转差一条/流式双显 | DshChatScreen.kt:296-310,737-740 | □ |
| LOW-杂项 | 隐藏消息无标识/删除无确认/硬编码字符串若干/imePadding 仅一处 | 见审计第四、五节 LOW | □ |

## E. 流程备注

- 签名口令曾在会话输出中暴露:会话记录分享前需脱敏,建议换口令(详见审计第四节末)。
- HEAD(acde811)不可单独发布,41 个未提交文件必须先提交。


## F. 第三轮新增(provider 审计 + 宏引擎自查)与修复状态

| ID | 问题 | 位置 | 状态 |
|----|------|------|------|
| P1 | Anthropic 首条消息为 assistant(角色问候语)必 400 | AnthropicAdapter.kt 会话构造 | ■ 首条补 user 占位,有测试 |
| P2 | NovelAI 主链路 stream=true 却走非流式端点,回复恒空(未验证) | NovelAiAdapter.kt:88,111-126 | □ 需真实端点验证 |
| P3 | OpenAI 兼容非流式 content 为数组时抛错被吞,回复/usage 全丢 | OpenAiCompatibleAdapter.kt parseNonStreamResponse | ■ |
| P4 | 非图片附件被当 image block 发送(Anthropic/OpenRouter/Ollama) | 三家适配器 | ■ 加 image/ 过滤,有测试 |
| P5 | instruct 模式 prompt.stop 在 Anthropic/Gemini/Ollama/OpenRouter/NovelAI 丢失 | 五家适配器 | ■ 合并 preset+prompt,有测试 |
| P6 | OpenRouter 按官方文档填 /api/v1 会拼成 /api/v1/api/v1 → 404 | OpenRouterAdapter.kt | ■ apiV1Url 去重 |
| P7 | OpenRouter 流式只解析 delta.content,丢 reasoning/tool_calls/usage/error 帧 | OpenRouterAdapter.kt:188-192 | □ 建议复用 OpenAI 解析 |
| P8 | Gemini thinkingBudget 不随 maxOutputTokens 收缩 | GeminiAdapter.kt:117-126 | □ |
| P9 | OpenAI o 系列正式请求仍发 temperature/top_p/penalty/max_tokens → 400 | OpenAiCompatibleAdapter.kt | ■ OpenAI 兼容适配器;Azure 同类 □ |
| P10 | Horde 忽略 config.baseUrl,硬编码 stablehorde.net | HordeAdapter.kt | □ |
| P11 | OpenAI 兼容端点 supportsVision 默认 false,图片静默丢弃无诊断 | OpenAiCompatibleAdapter.kt:459-463 | □ |
| P12 | Anthropic 兜底 max_tokens=8192 超 Claude 3 的 4096 上限 | AnthropicAdapter.kt:82-85 | □ |
| P13 | Kobold/Horde max_context_length 硬编码 2048 | KoboldAdapter.kt:95、HordeAdapter.kt:304 | □ |
| P14 | 6 家适配器输出预算回退链缺 preset.maxCompletionTokens | Kobold/KoboldCpp/LlamaCpp/TextGen/Azure/Horde | □ |
| P15 | Ollama checkStatus/listModels 不带 config.headers,鉴权反代误报 | OllamaAdapter.kt:41/58 | ■;OpenRouter checkStatus 走无鉴权端点未改 □ |
| E1 | `{{roll:1d0}}`/`d0` 触发空 Random 区间异常,中断整个提示词构建 | MacroEngine.kt resolveRoll | ■ 有回归测试 |
| E2 | 骰子/随机数规格数字溢出 toInt() 抛 NumberFormatException | MacroEngine.kt | ■ |
| E3 | `{{roll:2000000000d6}}` 卡死提示词构建(卡片可触发) | MacroEngine.kt | ■ 上限 1000 个骰子 |
| E4 | `{{newline::2000000000}}`/`{{space::…}}` OOM(Error,直接崩应用) | MacroEngine.kt:695-701 | ■ 上限 1000 |
| E5 | `{{random:0-2147483647}}` high+1 溢出 | MacroEngine.kt resolveRandom | ■ 改用 Long |

## G. 第四轮：dsh UI 重建批次（sess_402cc2bf 续）

本轮全部改动已通过 `:app:compileMvuValidationKotlin` 编译与 1274 项单元测试（0 失败）。

| ID | 任务 | 位置 | 状态 |
|----|------|------|------|
| U10 | 回形针直接唤起系统文件选择器（*/*），附件弹层整体剔除；strip 展示 + × 移除 | DshComposer.kt / DshChatScreen.kt | ■ |
| U11 | 回形针换 Lucide paperclip 图标（40dp 盒 20dp 线稿） | DshIcons.kt Paperclip | ■ |
| U12 | ⋮ 菜单去重：删掉与集合 sheet 重复的 预设/用户设定/世界书/调整预设 四项；生图入口移入 ⋮ 菜单 | DshChatScreen.kt DshMoreMenu | ■ |
| U13 | 弹层统一 dsh 风格：预设完整编辑/usage/模型配置三张新弹层同用 menuSurface+RADIUS_LG+把手形制 | DshPopups.kt | ■ |
| U14 | 背景功能：解码上限 1024→2048；fork 会话复制背景文件（原来只带 metadata key，子会话背景静默丢失） | ImageDownscale.kt / ChatViewModel.forkSession | ■ |
| U15 | 优化提示词常驻键（草稿空置灰） | DshComposer.kt WandSparkles | ■ |
| U16 | 提示词可重复发送：optimizeDraft 的 job 先 cancel 再 launch（既有）；onApply 回填不关闭弹层 | DshChatScreen.kt showOptimize | ■ |
| U17 | 抽屉图标移顶栏最左（PanelLeft 第一键，返回键其后） | DshChatScreen.kt topBar | ■ |
| U18 | 会话列表修复：递归树（孙会话层级展开）、搜索 × 清空、底部重复导出行删除 | DshDrawer.kt | ■ |
| U19 | 用户消息：编辑 / 重发（trimMessagesAfter 裁尾+回填输入框）/ 删除三键 | DshChatScreen.kt + ChatViewModel.trimMessagesAfter | ■ |
| U20 | 预设完整调节：全 13 字段（温度/TopP/TopK/TopA/MinP/重复惩罚/窗口/存在/频率/回复上限/上下文上限/种子/思考强度），留空=保持原值 | DshPopups.kt DshPresetEditorSheet + savePresetAdjustment | ■ |
| U21 | 回复框（streaming 气泡）在角色头像之下：流式行头像左、内容右（结构已是 QQ 式左头像） | DshChatScreen.kt streaming item | ■ |
| U22 | tok 统计：新增 usage 面板（本会话/今日/累计 三区，每区 输入/缓存命中/输出 分行，标题=三行和，口径统一）；右上删除图标→确认→清空明细+日汇总 | DshPopups.kt DshUsagePanel + GenerationMetricsStore.clearAll | ■ |
| U23 | 模型配置弹层（不再直跳设置）：服务商（只读）/地址/密钥/模型/默认思考强度（bre 档位胶囊）/测试连接/保存 | DshPopups.kt DshModelConfigSheet + ChatViewModel.saveModelConfig/testProviderConnection | ■ |
| U24 | dock 统计条左键（速度 pill）改开 usage 面板；⋮ 菜单指标项保留旧 GenerationMetricsDialog | DshChatScreen.kt | ■ |

### 遗留（本轮未动）
- F1 附件渲染进消息气泡（strip 只覆盖待发送态）。
- F4/F5 生图展示、N4 跨角色会话切换细节、U3 世界书写错书 —— 仍需用户确认交互。
- P2/P7/P8/P10-P14 provider 项照旧。

## H. 第五轮：用户反馈批次（rebuild13）

编译通过 + 1274 项测试 0 失败。

| ID | 反馈 | 修复 | 状态 |
|----|------|------|------|
| U25 | 预设只能调参数，不能改 JSON | 预设弹层改双页：参数页（13 字段）+ JSON 页（整份 raw，等宽字体编辑，解析失败报错不落盘，savePresetRaw 写回 + in_use 同步） | ■ |
| U26 | 模型配置要进「抽屉→设置」，dsh 布局 + bre 设置 | SettingsScreen(Models 区) 新增模型配置入口卡；点击弹 DshModelConfigSheet（服务商/地址/密钥/模型/默认思考强度胶囊/测试/保存）；composer 的配置键移除 | ■ |
| U27 | 模型选择 UI 与 dsh/bre 差距大 | DshModelEffortMenu 全量重写为 harness ModelSelect 结构：root = 模型行 + 思考强度行（各带当前值 + ›）；模型行钻入搜索+分组列表，档位行钻入档位单选；选择即提交；旧滑杆机器（drawBreSlider/drawRadiation 等 700+ 行）整体删除 | ■ |
| U28 | 优化提示词弹层迁到设置 | 设置页 Models 区新增优化提示词入口卡（就地弹同一弹层）；composer 键保留（用户澄清：只迁弹层不删键） | ■ |
| U29 | 回复框不再往右，在角色头像下居中 | streaming 块改 Column：头像一行置左，流式气泡 BoxWithConstraints(contentAlignment=Center) 居中 | ■ |
| U30 | 怎么会有 2M 上下文 | 快照 contextTokenLimit 的 1M 内部兜底值被当真实上限展示（dshCompactTokens 把 1_000_000~1_999_999 显示为 ~1M~2M）。修复：预设未配置 maxContextTokens 时 contextRatioOf/contextSegmentsOf 返回 null（环不画、弹层不出、dock 不显示 x/y） | ■ |
| U31 | 点击 o 与点击缓存命中一样 | 之前右 pill 与上下文环都开 context popover。现三入口三去处：环=context popover、右 pill=usage 面板、左 pill=GenerationMetricsDialog | ■ |
| U32 | 数据统计不准/不统一/丢失/重复 | 根因：快照只在生成时写入（发送/切会话后 dock 显示旧值）+ usage 面板累计区与日汇总双口径。修复：① refreshContextEstimate 在 switchSession/trimMessagesAfter 后异步刷新（轻量，不走生成链）；② usage 面板三区（本会话/今日/累计）全部只吃明细 jsonl 单一数据源，标题总量=输入+输出同源相加；③ 面板底部注明「仅保留最近 N 条明细」防误解 | ■ |

### 勘误
- 抽屉图标：用户原意是「整体左移」不是「与返回键调换」——已还原返回键第一位、顶栏行 padding 左移到 0。
- 发送路径的快照预刷新与 SceneImageGenerationTest 的 spy engine 冲突（builds.single() 断言），已移除；快照仍由生成链 + 切会话刷新。

## I. 第六轮：数据统计 dsh token-meter 对齐（复刻）

编译通过 + 1286 项测试 0 失败（新增 12 项账本/解析用例）。

### 改造内容（对照 dsh 语义逐项落地）
| 项 | dsh 语义 | 落地 |
|----|---------|------|
| 四互斥桶 | uncachedInput/cacheRead/cacheWrite/output，reasoning 含在 output 内 | 新建 ChatTokenUsageLedger（会话 metadata.tellev_token_usage + 消息 metadata.tellev_turn_usage），GenerationMetrics 增加 uncachedInputTokens/reasoningTokens 字段 |
| 会话累计无界 | tokenUsage 投影 O(1) 折叠 | 账本随会话文件持久化，dock/面板"本会话"直接读账本——不再受明细 100 条缓冲截断（U32 根因修复） |
| 重试替换 | addReplacing：同消息重刷先减旧再加新 | withRegeneratedSwipe/继续生成路径接入：旧 swipe 桶从账本扣回，新桶入账；消息删除/裁剪同步扣回（subtractFromLedger） |
| DeepSeek 缓存 | （dsh 内部统一 TokenUsage） | parseUsage 增 prompt_cache_hit_tokens（→cacheRead）/prompt_cache_miss_tokens（→uncachedInput）/reasoning_tokens（矛盾样本即弃）；修复 OpenAI cached_tokens 不进 cacheReadTokens 的漏项 |
| 诚实百分比 | formatCacheHitPercent：部分命中不显示 100% | dshCacheHitPercent 移植（round-half-up 0.1% + 99.9…n 降精度），dock 右 pill 与 usage 面板共用 |
| 估算标记 | 宁缺毋滥（校验失败整轮不出数） | 账本只收 provider 上报样本（估算样本零桶不入账）；估算速度带 ≈ 前缀；面板本会话区有估算徽标 |

### 涉及文件
- 新增：ChatTokenUsageLedger.kt、ChatTokenUsageLedgerTest.kt（12 用例）
- 修改：GenerationMetrics.kt（桶字段+DeepSeek 解析+OpenAI fallback）、ChatGenerationCoordinator.kt（Completed→账本）、ChatViewModel.kt（删除/裁剪扣回）、DshPopups.kt（DshUsageLedgerSection+dshCacheHitPercent）、DshChatScreen.kt（pill 口径）

### 遗留（下一步候选）
- 轮级 TurnUsagePanel（每条消息轮尾 pill 读自己的 tellev_turn_usage）
- decode 速度（剔除 TTFT）——当前仍是 completion/totalMs
- contextWindow 来自 adapter 声明（当前是预设值，诚实化已做）

## J. 第七轮：bre 模型思考档案层全量复刻

编译通过 + 1297 项测试 0 失败（新增 11 项档案/知识库/注入用例）。

### 复刻对照（bre → tellev）
| bre 组件 | tellev 落地 | 文件 |
|---|---|---|
| reasoningEfforts 每模型声明 + wire 拼写 | ModelReasoningProfile（efforts: 档位→拼写，null=不发）+ ModelReasoningProfileStore（unset 名单） | core/provider/ModelReasoningProfile.kt（新） |
| knowledge.ts 知识库（1685 行） | ReasoningKnowledgeBase 精编 17 家族条目（DeepSeek V4/V4.1/V3/R1、GPT-5.2/5.1/5/o 系列/4o、Claude、Gemini、Qwen、GLM、Kimi、Step），最长边界命中 + 置信度 + defaultEffort + 容量参考 | core/provider/ReasoningKnowledgeBase.kt（新） |
| effort-memory 五级回退链 | applyRememberedEffort 补第四级：会话 override > 档案默认档 > 记忆 > 厂商默认档（知识库） | ChatViewModel.kt |
| UNSET_MARKER / AUTOFILL_MARKER 纪律 | unset 名单 + source 字段：用户声明与明确 unset 永不被自动适配覆盖 | ModelReasoningProfile.kt withSuggestion |
| EffortEditor 档位网格 UI | DshModelProfileEditor（勾选+拼写输入+默认档下拉+建议只读区+自动适配+unset）挂在模型配置弹层钻入行 | ui/dsh/DshPopups.kt |
| inject 按声明拼写落线 | injectWithProfile：档案拼写 > family 硬编码；null 拼写=抑制字段；无档案=旧路径原样（向后兼容，Auto 永远直通） | core/provider/ReasoningEffort.kt |
| 生成链传递声明 | 请求 metadata.tellev_model_profile（生成协调器注入，OpenAI 兼容两条分支消费） | ChatGenerationCoordinator.kt + OpenAiCompatibleAdapter.kt |

### 质量内建
- 档案读缓存（VM 内 @Volatile + 写穿透刷新）——生成链每次发送不再读盘
- 持久化损坏降级为空存储（不抛出）；DurableFileOps 原子写
- 11 项单测：最长命中/别名优先/R1 无 off/未知模型低置信/非推理家族 null/往返+损坏降级/建议不覆盖用户/unset 不被覆盖/拼写优先/null 拼写抑制/off 缺席抑制/无档案回退/Auto 直通

## K. 第八轮：dsh 统计三项收尾（轮级 pill / decode 速度 / 窗口声明）

编译通过 + 1300 项测试 0 失败（新增 3 项 decode 用例）。

| 项 | 实现 |
|---|---|
| 轮级 TurnUsagePanel | DshTurnUsagePill（轮尾动作行，数据库图标+紧凑总量）读消息 metadata.tellev_turn_usage；点击弹 DshTurnUsageDialog（精确总量+四桶+思考小注+诚实缓存命中%）；无样本（估算轮）整 pill 不渲染（dsh 宁缺毋滥） |
| decode 速度 | GenerationMetrics 增 decodeMs（=totalMs−TTFT）与 decodeTokensPerSecond；dock 左 pill 改用 decode 口径，旧记录（无 decode 计时）回落总时长；估算仍带 ≈ |
| contextWindow 声明 | ProviderAdapter.declaredContextWindow（默认 null，Gemini 适配器声明 1M）；快照上限分层：用户档案 > 知识库 > adapter 声明 > 预设值 > 未知(null)。生成链不再写 1M 内部兜底——快照里的 limit 一律可信，UI 守卫从「预设没配就不显示」放宽为「limit 非空即显示」 |

## L. 第九轮：性能 + 裁切 + 横幅 + 返回（用户反馈批次）

编译通过 + 1300 项测试 0 失败。

| ID | 反馈 | 修复 | 状态 |
|----|------|------|------|
| U33 | 长聊天历史上翻卡顿 | 三处开销源：① LazyColumn 加 contentType（user/assistant/assistant-swipes 三槽位，滚动不再跨类型重建=尺寸稳定）；② tavernMessageVariablesJson 加代数缓存（会话 id+消息数+末消息 id+swipe+storageRevision 为键，代内不再 O(N) 重扫全消息变量槽——WebView 每次重组都拉它）；③ 面板高度计算已有 remember（未动） | ■ |
| U34 | 角色卡正文被裁切只显背景 | 双根因：① 面板上限 480dp（2/3 屏）对正文卡过小——改为 Frontend 正文卡以 0.92 屏高为上限（引用块小面板不变）；② 卡内脚本注入 DOM 后无任何 load/resize/toggle 事件触发，真实高度从未上报——注入脚本补 MutationObserver（childList+subtree+style/class），注入完成即重测 | ■ |
| U35 | 移除各主页介绍横幅 | CharactersList/Creation/Extensions/Settings/WorldBooksList 五页 AtmosphereIntro item 整块删除（含无用 import 清理；settings 误回退后已恢复模型配置/优化入口卡） | ■ |
| U36 | 系统返回按页面层级处理，仅聊天首页确认退出 | 退出确认改为双条件：canPopBackStack=false（导航栈已到栈底）∧ isDshTopLevel（chat 加入集合）。栈里有上级页面时返回键交给 NavController 正常回退；从深层页跳进 chat 时有上级也不弹退出 | ■ |

## M. 第十轮：bre 对齐收尾三项

编译通过 + 1300 项测试 0 失败。

| 项 | 实现 |
|----|------|
| 轮级 reasoning 小注 | 账本 tellev_turn_usage 增加 reasoning 字段（生成链从 metrics.reasoningTokens 写入，读取走 messageReasoning()）；轮尾 pill 与详情弹层改读真实值——此前硬编码 0L 的断线接通 |
| 端点能力信号 | 新建 EndpointSignalDetector（bre detection.ts 字段约定移植：布尔约定×4/capabilities 数组与对象/模态×4/上下文长度×4，tri-state 纪律）；OpenAI 兼容 listModels 把信号写进 ProviderModel.metadata；目录组携带 signals 映射；自动适配升级三源融合：端点明确 false→unset、true+KB 命中→KB 档位+端点容量、true+无 KB→家族通用档（off: none），KB 未命中且无信号→放弃 |
| minimal/xhigh 词汇 | ReasoningEffort 加 Minimal/XHigh；uiLevels/capabilities 全家族对齐（DeepSeek 中继仍只开/关三档诚实展示）；inject 映射：OpenAI 兼容直接小写拼写、OpenRouter Max→high 钳制不变、Gemini 预算 512/…/32768、Anthropic 预算 1024/…/32768；OpenAI 兼容 Max 钳制从 high→xhigh；dshEffortLabel + i18n 四语；既有测试期望同步更新（Max 钳制、预算值、uiLevels 列表、uiIndexOf） |

## N. 第十一轮：可见性修复（用户气泡操作键 + 会话条功能键）

编译通过 + 1300 项测试 0 失败。

| ID | 反馈 | 根因与修复 | 状态 |
|----|------|-----------|------|
| U37 | 提示词发送后气泡没有重发/编辑键 | 键一直在（U19），但被 `if (!state.isGenerating)` 挡住——发送即生成，永远看不到。改常驻显示（编辑/重发/删除的落盘通道自带 isLoading 门禁，无并发风险） | ■ |
| U38 | 会话条没有编辑名称功能和功能键 | 重命名此前只有长按（不可发现）。行尾改单个 ⋮ 功能键：菜单含 重命名（铅笔）/ 置顶切换（图钉，置顶态蓝色）/ 删除会话（红色危险项）；长按重命名保留 | ■ |

## O. 第十二轮：预设机制理解 + 调节预设修复

### 预设机制结论（本轮梳理，写入文档）
- GenerationPreset = 类型化字段（采样/长度/思考档/stop/prompts 栈+prompt_order/extensions）+ raw（ST 原样 JSON 保真）
- 存储：presets/<category>/<id>.json + 每目录 in_use.json 工作副本 + preset-selection.json
- 生成链（GenerationRuntimeResolver）读的是 **in_use.json**（工作副本类型化字段优先，只借 named 身份）
- savePreset = 类型化字段合并回 raw（null 清别名）写 named 文件，不碰 in_use
- selectPreset = 复制 named → in_use + 广播 presetChanges（ChatViewModel observePresetChanges 刷新 UI）

### 缺陷与修复
| ID | 缺陷 | 修复 |
|----|------|------|
| U39 | JSON 页编辑被覆盖：savePresetRaw 只换 raw，savePreset 把内存旧类型化字段（temperature 等）合并到新 raw 之上——JSON 页对采样参数的修改全部静默失效 | 保存前先用 PresetCodec.parsePreset 从新 raw 重新推导类型化字段（身份沿用原预设），类型化与 raw 自洽后落盘 |
| U40 | 「留空保持」基准错位：参数页显示 in_use 形状的 selectedPreset，保存基准却取 named 文件——漂移时保存值与所见不一致 | 基准改为编辑器实际显示的 selectedShape（in_use 值 + named 身份），named 仅作兜底 |
| U41 | JSON 页保存成功不关 sheet，切回参数页看到旧草稿 | 保存成功回调关闭 sheet |

## P. 第十三轮：预设调节补齐——提示词栈 UI（上一轮的深化）

上一轮（O 节）修了字段级三缺陷；本轮完成真正的功能核心缺口：**提示词栈此前在 UI 完全不可编辑**（只能改 JSON 原文，ST 预设的灵魂正是 prompts/prompt_order）。

| 内容 | 落地 |
|------|------|
| 第三编辑页 | 预设弹层从「参数/JSON」双页扩为「参数/**提示词**/JSON」三页 |
| prompt_order 编辑 | 启用列表上移/下移重排（实时改序，保存写回 prompt_order） |
| 启用/停用 | 每条提示词一键停用（移入未使用）、未使用区一键启用（回到启用列表） |
| 逐条编辑 | name / role（system·user·assistant 下拉）/ content 多行 / depth / injection_order / relative（相对位置）/ forbid_overrides（禁用角色卡覆盖）——ST PromptManager 全字段 |
| 保存 | savePresetPrompts：baseline 用编辑器实际显示的那份（in_use 形状），prompts+prompts_unused+prompt_order 三者一次序列化写回，随后 selectPreset 同步 in_use（生成链立即生效） |
| 语义守护 | 未参与编辑的 typo 防御：保存只动三条键，raw 其余键由 savePreset 保留；序列化沿用 PresetCodec（与磁盘解析严格互逆） |

## Q. 第十四轮：预设「全部可调」——raw 键全量编辑器

编译通过 + 1307 项测试 0 失败（新增 7 项拍平/写回用例）。

### 研究结论（以用户真实 ST 预设 3.27【可待】甲戌.json 为样本）
ST 预设 41 个顶层键，tellev 类型化字段只覆盖 14 个采样/惩罚/上限键；其余 27 个
（n、stream_openai、send_if_empty、assistant_impersonation/impersonation_prompt、
continue_prefill/continue_postfix/continue_nudge_prompt/group_nudge_prompt、
new_chat_prompt/new_example_chat_prompt/new_group_chat_prompt、names_behavior、
wrap_in_quotes、personality/scenario/wi_format、claude/use_makersuite_sysprompt、
max_context_unlocked、bias_preset_selected、function_calling、enable_web_search、
image/audio/video_inlining、request_images、inline_image_quality、show_thoughts、
extensions.SPreset.ChatSquash/RegexBinding/MacroNest）此前只能改 JSON 原文。

### 落地
- PresetRawKeys（纯函数）：raw 拍平成点分路径叶子行；按 JSON 类型推断控件
  （布尔→开关/数字→数字框/文本→文本框/对象数组→JSON 编辑）；typed 键与
  prompts 三键不重复出现；setPath 只动该路径（中间对象缺失则创建）、
  removePath 删键并清空壳；parseUserValue 按形状解析（坏 JSON 回落字符串不抛）
- VM savePresetRawKey(presetId, path, value|null)：baseline 用编辑器实际显示的
  那份（in_use 形状）+ selectPreset 同步 in_use，只动该路径其余键逐字保留
- UI：参数页新增「更多预设键」区——开关行即时保存；数字/文本行只读展开 + ⋯ 进
  单键编辑对话框（显示当前类型 + 值输入 + 删除键）；嵌套对象/数组以 JSON 行整键编辑
- 7 项单测：拍平排除/类型推断/深写+兄弟保留/中间创建/删除+空壳清理/用户输入解析/
  真实 ST 预设 17 个非类型化键全部可寻址

## R. 第十五轮：优化提示词按开源案例复刻（linshenkx/prompt-optimizer 36.7k★）

编译通过 + 1315 项测试 0 失败（新增 8 项策略模板用例，更新 2 项旧断言）。

### 研究结论（读源码：template/processor/service 三层）
上游核心资产是「策略模板库」：每条策略是一份完整 LangGPT 结构系统提示词
（Role/Profile/Skills/Goals/Constrains/Workflow/OutputFormat），两大家族：
系统提示词（general-optimize 通用/analytical-optimize 分析式/output-format）
与用户提示词（basic 基础/professional 专业/planning 步骤化），迭代是独立
模板（lastOptimizedPrompt + iterateInput）。四条纪律：① 只改提示词不执行
② JSON 包裹证据防注入（helpers.toJson 转义）③ 变量占位符逐字保留+输出前
自检，缺一个即失败 ④ 直接输出不带代码块。

### tellev 复刻（问题诊断：旧版只有 5 个 mode 标签 + 一段英文指令，无策略体系）
- PromptOptimizationStrategies（新）：6 策略（通用/分析式/基础/专业/精简/迭代），
  中文结构模板逐段复刻；Evidence（证据纪律）+ Variable preservation（变量铁律）
  + Output contract（JSON 契约）在唯一出口 messages() 统一追加——单一来源，
  测试钉死
- 证据包裹：kotlinx JsonPrimitive 序列化（与上游 helpers.toJson 同语义），
  草稿里的引号/换行/假协议层全部转义成字符串
- 迭代语义：iterateInput 非空或 strategyId=iterate 即走双字段证据链；
  basePrompt=上一版结果（版本链）
- PromptOptimizer 重接策略模板；旧 PromptOptimizationMode 保留为兼容层，
  mode→strategy 映射表（空 strategyId 的旧调用方零改动）；JSON 解析/格式修复轮/
  流式预览原样复用
- UI：五项 mode chips → 六策略单选卡（名称+一句说明+选中描边）+ 迭代需求输入框
  + 版本链提示；聊天弹层记住「上次应用的结果」作下一轮 basePrompt
- 8 项新用例：六策略都带三条纪律/证据转义（引号与换行）/迭代双字段/策略 id 触发/
  普通路径无迭代字段/语言与附加指令透传/策略 id 稳定/mode 映射全覆盖

## S. 第十六轮：AI 制卡选择器/统计 + 思考档位滑杆（用户反馈批次）

编译通过 + 全量单测 0 失败（新增制卡用量/引擎/VM/滑杆几何用例）。

| ID | 反馈 | 根因与修复 | 状态 |
|----|------|-----------|------|
| U42 | AI 创建角色卡无法选择模型和思考档位 | 聊天式创建页把档位入口写死：`effortLevels = emptyList()`、`onSelectEffort = { }`、`modelsOnly = true`（只出模型列表，档位面板不可达）；`controlsBusy` 还漏了 `modelState.isGenerating`，隐藏聊天生成期间点选模型会被静默拒绝。修复：档位按当前模型档案收窄后真实下发（`uiLevelsForModel`），`onSelectEffort` 写入草稿并随草稿持久化（`CreationSession.reasoningEffort`），请求经 `ReasoningSupport.REQUEST_OVERRIDE_KEY` + 档案 metadata 落到线上；`controlsBusy` 补 `isGenerating` | ■ |
| U43 | 制卡没有数据统计 | 引擎把 `GenerateChunk.Completed.usage` 直接丢弃，VM 只记流式计时；composer 统计位传 null。修复：`converse`/`extractChunk` 增 `onUsage` 回调（每次 accepted Completed 在解析/工具执行前记账，修复与压缩重试轮同样计入）；`CreationUsage` 聚合（完成请求数/上报 token/缓存桶/思考子集/估算数）随草稿持久化；明细写入与聊天共用同一个 `GenerationMetricsStore`（`metricsStoreForCreation`），composer 出「N 次请求 · tok/s」「总 tok」双 pill 并复用 `DshUsagePanel`（本草稿区=草稿账本，今日/累计=共享明细） | ■ |
| U44 | 思考档位滑动 bug | 旧实现手搓 `detectDragGestures`：`preview` 索引在拖动中被 `onPreview` 与本地态双重驱动，`onCommit` 又用闭包里的旧 `steps` 解释索引；旋钮 `offset` 用「中心坐标当左边缘」，两端各越界 14dp；RTL 下几何与绘制不一致。修复：改用原生 Compose `Slider` 承载手势/斜率/取消/RTL（画布只负责视觉），新增 `effortSliderCenter`/`effortSliderIndex` 纯函数（旋钮完全落在轨道内、窄轨道安全钳制），RTL 只镜像画布 | ■ |
| U45 | 思考档位动画 bug | 辐射/焰口/顶档呼吸由 `rememberInfiniteTransition` 的 10 分钟时钟驱动，`publish` 与 `shown` 在拖动中互相覆盖造成跳变；旋钮半径随 `dragging` 突变。修复：位置动画改 `animateFloatAsState`（拖动中 0ms、松手 140ms 收敛），时钟仅在 enabled 且 progress>0 时启动，拖动只做视觉提亮不改变几何 | ■ |
| U46 | 思考档位改成英文 | 档位标签此前随语言本地化（中/日/韩译名）。修复：`chat_reasoning_effort_*` 四语统一为 Auto/Off/Minimal/Low/Medium/High/XHigh/Max，补 minimal/xhigh 两档描述 | ■ |

### 回归守护（新增）
- `CreationUsageTest`：完成请求计数（含未上报 usage 的轮次仍计数）、缓存三桶互斥、思考子集不重复计、旧草稿默认值、往返序列化、草稿隔离
- `CreationEngineUsageTest`：工具回路两轮=两条记录、格式失败轮仍计费、抽取修复+压缩=三条记录、未上报 usage 样本、草稿档位落线、Auto 不发字段、自定义端点身份
- `CreationViewModelUsageTest`：checkpoint/final 快照不得覆盖本轮用量（回归）、重开草稿读回聚合、档位随草稿持久化
- `EffortSliderGeometryTest`：旋钮两端完全在轨道内、窄轨道钳制、越界/NaN 索引安全

### 已知边界（本轮未改，需用户决策）
- 共享明细「清除统计」清的是 JSONL+日汇总+本草稿聚合；聊天侧会话账本（metadata `tellev_token_usage`）不清，两端口径不同。
- 端点是否回传 usage 取决于兼容设置 `includeUsage`（默认关）；未开启时只显示请求数与估算标记，不显示 token 数。
- 手动编辑器入口（`CreationEditorScreen`）仍无模型/档位选择器——该屏属另一入口，本轮未动。

## T. 第十七轮：模型服务统一弹层 + 角色卡书架式改造

编译通过 + 全量单测 1517/0 失败（本轮新增 `CharacterGroupsTest` 10 用例）。

### T1 模型服务统一弹层（DeepSeek Harness 单弹层形态）

用户要求把对话模型、生图引擎、语音(TTS) 三类服务的配置/选择合并进**同一个弹层**。上游 Harness 实际是「配置页 + 选择弹层」分离，本轮按用户要求做单一弹层，统一收敛在数据/注册层。

| 项 | 落地 |
|----|------|
| 新文件 | `ui/dsh/DshModelServicesSheet.kt`：root = 三条服务行（对话/生图/语音，当前值 + ›），各段钻入二级页 |
| 对话段 | 复用重构后的 `DshModelListPane`（模型列表）/`DshEffortPane`（档位滑杆）/`DshModelConfigContent`（连接配置），三者从 `DshPopups.kt` 抽出为 internal |
| 生图段 | 引擎单选（ComfyUI/NovelAI/自定义 imgprof 端点）+ Comfy/NovelAI/端点列表钻入页，全部经 `SettingsViewModel` → `ImageGenSettingsController` 直写 |
| 语音段 | `TtsSettingsValues` 取值/校验 + 开关/音色/语速/模型/格式/自定义端点 + 连接测试（`OpenAiSpeechAdapter.checkStatus`，IO 调度 + runCatching 兜底） |
| 入口 | ①设置 tab「模型配置」卡片 ②聊天抽屉新增「模型服务」行（`DshDrawer` + `DshChatScreen.onOpenModelServices`），两入口共用根层渲染 |
| 刷新链路 | 引擎保存写 secretStore → `ChatViewModel.observeProviderChanges` 自动触发 `refreshImageGenAvailability`（无需额外接线） |
| i18n | 新增 10 键（`dsh_services_*` + `chat_drawer_model_services`），四语齐全 |

### T2 角色卡书架式改造（用户反馈批次）

| ID | 反馈 | 落地 |
|----|------|------|
| V1 | 布局改为书架式 | `LazyVerticalGrid` 列宽 148→132dp、间距收紧、底部 104dp 让位 FAB，视觉上每列一本「书」 |
| V2 | 调换搜索键和导入键 | 顶栏 actions 顺序改为「搜索 → 导入」（原为导入在前） |
| V3 | 角色卡改分组键，可自建分组分类角色卡 | 标题位改为分组键（当前分组名 + ⌄），点开 `DshCharacterGroupSheet`：全部/未分组/各分组筛选 + 新建/重命名/删除；卡片长按菜单新增「分组」项进 `CharacterGroupAssignDialog`（多选勾选 + 就地新建） |
| V4 | 导入功能加入扫描/添加/搜索 | 导入键改为下拉菜单：**扫描角色卡**（SAF 目录树递归找 png/webp/json，`scanCharacterTree`）/ **添加角色卡**（原 OpenMultipleDocuments）/ **搜索**（打开搜索栏） |

**新持久化**：`feature/characters/CharacterGroups.kt` — `character-groups.json`（`DurableFileOps` 模式，同 `ChatModelGroups`）：分组定义 + 成员关系一份存，删分组不动卡片（成员回落未分组）。

**过滤语义**：`CharactersFilter.filter` 纯函数抽出（无 Android 依赖）——分组先收窄集合（全部/未分组/指定），再按关键词过滤名称与标签；`CharactersViewModel.UNGROUPED_GROUP_ID = "__ungrouped__"` 虚拟分组不冲突。

### 回归守护（新增）
- `CharacterGroupsTest`：分组读写往返、缺失/损坏文件回落空表、空白 id/label 条目丢弃、新 id 唯一且带前缀、未分组排除任何分组卡、按分组只留成员、分组×关键词交集、空查询返回全集、成员增删幂等且可往返

### 已知边界
- 扫描导入依赖 SAF（Android 11+ 隐私策略禁选 Download 根等敏感目录，需选子目录）；JVM 单测无法覆盖 ContentResolver 遍历，扫描链路只做了模拟器入口验证。
- 分组为「一卡可属多组」；删除分组后成员回落「未分组」，不级联删除卡片（有意为之）。

## U. 第十八轮：UI 体系合并（剔除旧 Material 强调色体系）

编译通过（main + androidTest 双源集）+ 全量单测 1517/0 失败。

用户要求「剔除两套 UI 体系，只保留一套 DeepSeek Harness 化 UI 体系」。此前 dsh 只包聊天子树，设置/角色卡/社区等 tab 吃的是旧 `TellevTheme`（暖橘强调色），两套并存。

### 落地
| 项 | 内容 |
|----|------|
| dsh 成为唯一主题 | `DshTheme` 补齐 5 个缺失的 Material 角色映射（`secondaryContainer`/`onSecondaryContainer`/`tertiary`/`tertiaryContainer`/`onTertiaryContainer`），此前这 5 个角色在非聊天页有 15 处引用会落到 Material 默认色 |
| dsh 排版 | 新增 `DshTypography`（harness 规格锚点：正文 14/24、secondary 13/20、caption 12/16），替换原先透传的 `MaterialTheme.typography`；`DshShapes` 提取为常量 |
| 系统栏取色 | 旧 `SystemBarAppearance` 移入 DshTheme，改用 dsh token（状态栏 `bgBase`、导航栏 `bgLayer1`） |
| ThemeMode 迁址 | `ThemeMode`/`isDarkTheme`/`parseThemeMode` 从 `ui/theme/Theme.kt` 移入 `ui/dsh/DshTheme.kt`（浅色/深色/跟随系统不是重复体系，dsh 有明暗两套 palette） |
| 根层挂载 | `MainActivity` 改挂 `DshTheme`；`DshRoot` 的 `ProvideDshPalette` 包裹与聊天页内层 `DshTheme`、`DshModelServicesSheet` 内层 `DshTheme` 全部删除（嵌套冗余） |
| 强调色特性删除 | 删 `ui/theme/Theme.kt`+`Typography.kt`（6 套调色板）、`TellevGraph.themeAccentFlow`、`AppPreferences.themeAccentName`/`KEY_THEME_ACCENT`/`DEFAULT_THEME_ACCENT`、`SettingsUiState.themeAccent`+`setThemeAccent`、`AppearanceSettingsController` accent 分支、`SettingsAppearanceSection` 的 `AccentOption` 与「主题色」区块 |
| 死代码清理 | 删 `ui/PlaceholderScreen.kt`（无引用）+ i18n 键 `ui_placeholder_message`；accent i18n 键 8 个删除 |
| i18n | 1950 → 1941 键，四语重生成 |
| androidTest 修复 | 6 个测试改挂 `DshTheme`；`TavernMixedRenderingTest` 前景色改从 dsh palette 取；2 个引用已不存在的 `ChatScreen` 的测试改挂 `DshChatScreen`（带空回调）——**androidTest 源集此前编译不过，本轮恢复可编译** |

### 保留的外观设置
主题模式（浅色/深色/跟随系统）、聊天气泡不透明度、对话字号——三项均为 dsh 体系内的真实可调项，非重复体系。

### 已知边界（有意变更）
- 非聊天页正文字号 12sp → 13sp（`bodySmall` 按 harness 规格对齐）。已在模拟器逐 tab 核对观感，未发现变挤。
- 用户已保存的 `theme_accent` 偏好键不再读取（键值留在 prefs 里不清理，避免动用户数据）。

### 待办（用户新反馈，本轮未动）
- 用户反馈「很多重复和不统一的 UI 功能」。已盘点出的**功能重复**：
  - TTS 配置：`DshModelServicesSheet` 语音段 与 设置→外观→`TtsSettingsSection` 两处
  - 生图引擎/地址/令牌：`DshModelServicesSheet` 生图段 与 设置→`settings/imagegen`（`SettingsImageGenSection`）两处
  - 使用统计：设置页 `UsageStatsEntryCard`（进全屏页）与聊天页 `DshUsagePanel` 弹层、制卡页 `DshUsagePanel` 三处口径
  - 扩展入口：抽屉「插件」行 与 设置→资料→`ExtensionsEntryCard` 两处
  - 优化提示词：设置页入口 与 聊天 composer 常驻键 两处
  下一轮需与用户确认保留哪一侧（倾向：弹层侧保留、设置页重复项删除）。

## V. 第十九轮：重复 UI 功能合并 + 统一弹层放大

编译通过（main + androidTest）+ 全量单测 1517/0 失败。

用户反馈「很多重复和不统一的 UI 功能」「重复的直接合并，弹层太小做大」。

### 弹层放大
`DshModelServicesSheet` 容器：宽 `400dp` → **`560dp` 上限 / 屏宽 98%**，高 `560dp` 固定 → **屏高 88%**，圆角 `RADIUS_LG` → `RADIUS_XL`，内边距 10→12。各段内部滚动上限同步放宽（生图段 460dp）。

### 生图段补全（原设置→生图页功能全量并入）
| 引擎 | 新增控件 |
|------|---------|
| ComfyUI | 模型下拉（可编辑 + 按输入过滤，源自 `state.comfyModels`，带「测试连接后可获取」提示）＋ 工作流 JSON 编辑按钮（状态文字随配置切换）＋ 采样参数按钮 |
| NovelAI | 模型只读下拉（`NovelAiImageSettings.MODELS` 固定清单，映射 id↔显示名）＋ 参数按钮 |

新增私有组件 `DshModelDropdownField`（可编辑/只读两态 + 尾部展开键 + 过滤列表）。三个对话框（`ComfyWorkflowDialog`/`ComfyParamsDialog`/`NovelAiImageParamsDialog`）状态提到弹层顶层，随弹层关闭一并消失。

### 删除的重复入口
| 删除项 | 位置 | 理由 |
|--------|------|------|
| 「生图（引擎与模型）」卡片 + 分隔线 | `SettingsScreen` | 弹层生图段已完全覆盖 |
| `imageGenDetailsOnly` 参数及 4 处分支 | `SettingsScreen` | 页面本身删除 |
| `settings/imagegen` 路由 | `DshRoot` | 同上 |
| TTS 设置区块 | `SettingsScreen` 外观 tab | 弹层语音段是其超集（多连接测试、自定义端点开关、音色/格式胶囊） |
| `TtsSettingsSection.kt` | 整文件删除 | 同上 |
| `ImageGenEntryCard`/`imageGenSummary`/`imageGenDetailsItems` | `SettingsImageGenSection.kt` | 入口已删；保留 4 个可复用对话框 |
| `onOpenImageGenSettings` 参数 | `SettingsScreen`/`DshChatScreen` | 无调用点 |

### 文案修正（入口迁移后的遗留指引）
- 删 `dsh_services_img_advanced`（「高级设置见设置→生图」——高级设置现在就在弹层内，提示已过期）。
- 三处对话框正文「修改后请回到设置页点击「保存」」→「修改后点击下方「保存」」（保存键现在就在同一弹层）。

### i18n
1950 → 1934 键（本轮删 `dsh_services_img_advanced`、`tts_section_title`、`tts_section_desc`、`setimg_entry_title`、`setimg_entry_summary`、`setimg_entry_profiles`、`setscreen_title_imagegen` 共 7 键）。

### 保留的入口（非重复，已确认）
- 使用统计：设置页全屏页（图表/趋势）＋ 聊天/制卡弹层（会话口径）——口径不同。
- 优化提示词：设置页入口 ＋ 聊天输入框常驻键（设置 tab 打开时聊天页可能不在栈上）。
- 扩展：抽屉「插件」行 ＋ 设置→资料入口（同一目的地，抽屉为高频路径）。

### 模拟器验收
弹层三段逐个钻入确认：生图段显示引擎单选 + 三行配置入口；ComfyUI 钻入页四控件齐全（地址/模型下拉/工作流/参数）且工作流对话框可从弹层内正常打开；设置页确认生图卡片与 TTS 区块已消失；外观 tab 仅剩主题/气泡/字号/语言。

## W. 第二十轮：用户反馈三问题修复 + 模型服务/配置融合收口

编译门禁双绿（debug + mvuValidation androidTest）+ 全量单测 1534/0/0/2（154 个 XML 全通过）+ APK 渠道检查通过。

### 用户反馈三问题（2026-10-13）

| ID | 反馈 | 根因与修复 | 状态 |
|----|------|-----------|------|
| X1 | 设置里的 TTS 和生图设置都不见了 | 非缺陷，是 round-19「重复入口合并」有意结果：生图/TTS 全量并入聊天页「模型服务」弹层（生图段：ComfyUI/NovelAI/自定义端点；语音段：TTS 全套 + 连接测试）。本轮按用户要求完成「模型服务与模型配置融合」：设置页「管理模型服务配置」改接统一弹层，服务商管理面（自定义端点 CRUD/高级设置）整体嵌入弹层 `chat-providers` 段，删除 `settings/providers` 独立路由。两处入口（设置页 / 聊天页）各留一个，零功能丢失 | ■ |
| X2 | ai 创建界面的底部 token 统计在哪里 | 旧实现仅在「已完成请求数 > 0」时渲染 pill，且左侧只显示请求数——首次生成前/失败轮完全不可见。修复对齐聊天页 dock 口径：有对话轮次或生成中即常驻显示，左 pill = 「N 轮 · M 步 · 速度」，右 pill = 「总 tok」（请求完成后补上） | ■ |
| X3 | 设置界面的数据统计一点击就闪退 | **存量 P0**：`UsageStatsScreen.kt:108` 根级 Column 挂了 `horizontalScroll`，给整棵子树无限宽约束，与 SummaryCards 自身的水平滚动嵌套，在测量阶段抛 `IllegalStateException: Horizontally scrollable component was measured with an infinity maximum width constraints`（logcat 完整堆栈已取证）。修复：删根级 horizontalScroll，把横向滚动下沉到真正溢出的 30 日热力图行（父 Card 宽度有界，合法）。全仓其余 7 处 horizontalScroll 已逐一审计，无同型嵌套 | ■ |

### 用户新需求：ai 创建的可选预设（同日追加）

| 项 | 落地 |
|----|------|
| 需求 | 「ai 创建的可选预设」——为 AI 制卡会话提供预设选择 |
| 语义 | 制卡链路此前只自动跟随聊天当前所选预设（`CreationEngine.resolveChatPreset`），用户无法为本草案单独指定。新增：草案级 `presetId`（空 = 跟随聊天），⋮ 菜单新增「预设：X」行 → 底部选择器（跟随聊天 / 各预设单选 + 勾选态），选择随草案持久化 |
| 落线 | `CreationEngine.resolveSessionSnapshot(presetId)` 按 id 解析（找不到回落跟随聊天，请求不因预设缺失失败）；`converse`/`extractChunk` 两条链路均改为按草案预设解析 |
| 回归守护 | 预设 id 存于草案侧（与 `reasoningEffort` 同位），跨草稿不串台；旧草稿 `presetId=""` 行为不变 |

### 模型服务/配置融合（F0，用户要求「设置界面留一个聊天界面留一个」）

- 弹层对话段新增「模型服务配置」行 → `chat-providers` pane（ProviderDetailsPane：服务商选择/新建、自定义名、删除、地址、API 密钥、模型 + 目录选择器、高级设置、测试连接、保存、连通状态卡）。
- 删除 `settings/providers` 路由与 `providerDetailsOnly` 模式；设置页「管理模型服务配置」按钮改接统一弹层 root。
- 保留：设置页模型服务卡（含快速切换下拉）、聊天页输入栏模型键（→对话段）+ 抽屉「模型服务」行（→root）。
- 兼容：CompatibilityAdvancedDialog 与删除确认弹窗移入弹层承载；i18n 复用 `setscreen_title_provider`，新增 3 键（`dsh_creation_menu_preset` 等），总键数 1921。

### 已知边界（未动，待用户裁决）

- 密钥区块（SettingsSecretSection）仍列出应用内部 secret id（`provider-selected-id` 等），可被误删——是否过滤/移除待定。
- 世界书同名时选择器按名字匹配，无法在 UI 区分（测试库 3 本同名「李婧」观察到）。
- 「本轮动画改动未视频取证」（E 波自述），静态走查无回归。

### 第二十一轮 F1 波：模型服务配置融合收尾验收（2026-10-13）

F1 代码（子智能体完成）：chat-conn 与 chat-providers 合并为单一「模型服务配置」面，ProviderDetailsPane 为基底；删除 `DshModelConfigContent` 全族（DshPopups -356 行）与 ChatVM 三死方法；护栏测试改靶不删（`ModelConfigKeyClearTest` 4 例改指 settings 侧）；净 -5 → 基线 1529/0/0/2；compileDebugKotlin + assembleDebug 双绿。唯一弃项：「默认思考强度」主动编辑 grid（被动记忆链保留）。

**模拟器验收（emulator-5554，APK 75,470,262B md5 c1d7c0df…，versionCode=47 / 1.7.1.5-debug / base splits 渠道一致）：**

| # | 路径 | 结果 |
|---|------|------|
| 1 | 设置页「管理模型服务配置」→ 统一弹层（对话/生图/语音） | ■ 弹层三分段齐全，当前值正确 |
| 2 | 对话段钻入（选择模型/思考强度档位/模型服务配置） | ■ 二级弹层结构完整 |
| 3 | ProviderDetailsPane 主面板（服务商/名称+删除/地址/密钥/模型/思考档案/高级设置/测试连接/保存/恢复默认） | ■ 控件齐全 |
| 4 | profileSummary 钻入：思考档案 sheet（档位矩阵 7 档 + 建议卡 + 默认思考强度 Auto 被动显示） | ■ |
| 5 | 「恢复默认」确认弹窗：文案「清空接口地址、API 密钥、模型名…高级兼容设置与思考档案不受影响。此操作不可撤销」 | ■ 点取消，未真删 |
| 6 | 清键拦截：表单密钥清空后点保存 → 「清除 API 密钥」确认框（取消/删除） | ■ 点取消，密钥未动 |
| 7 | baseUrl 空白=删回默认：UI 清空显示默认 placeholder `https://api.example.com`；持久层 `ProviderConfigPersistence.kt:311` `ifBlank { ProviderDefaults.baseUrl(...) }` | ■ UI+代码双证 |
| 8 | 旧路由/死代码不可达：`settings/providers`、`DshModelConfigContent`、ChatVM 死方法 grep 全 0 | ■ 代码级 |

**附带验证**：编辑仅写内存草稿（`updateApiKey/updateBaseUrl` 只 update stateFlow），未保存即关弹层草稿丢弃、持久层无损（实测：草稿改名/密钥截断后关弹层，重开全部恢复原值）；快速切换配置下拉正常；「当前使用 OpenAI 兼容 / Qwen-3.8-Flash」口径一致。

**验收遗留**：chat-providers 模型列表下拉需真实服务返回 availableModels 才显示，本模拟器 baseUrl 为占位地址无法触发，由 T10 单测覆盖。

### 第二十二轮 F2 波：模型服务配置残留散点收敛（2026-10-14）

F2 代码落地（brain 派发被 harness 拦截 ×3，主智能体按 brain 契约自拟计划执行，范围经现场调查收敛）：

| 项 | 改动 | 结果 |
|----|------|------|
| 密钥区块过滤 | `SecretStore.kt` 新增 `isInternalSecretId`（`provider-*` / `image-*` 前缀 + `custom-provider-configs` + `tellev-memory-settings-v1`），`SettingsViewModel.load` 与 `PersonaSecretSettingsController.addSecret/deleteSecret` 三处加载点统一 `filterNot` | 内部项不再显示/可误删，密钥区块只呈现用户自定义条目（遗留决策点①落地） |
| 删除确认 | `SettingsSecretSection` 删除按钮改走 AlertDialog（新 i18n 键 `setsec_delete_title` / `setsec_delete_confirm`，键数 1913→1915→1913 净持平：+2 新键 −2 孤儿键） | 误删防线补齐 |
| 死代码 | DshPopups.kt 死 import ×28 清理（setValue 保留防属性委托误删）；孤儿键 `dsh_model_follow_memory` / `dsh_model_advanced` 三处 S.kt + 两个 tsv 删除 | -28 import、-2 孤儿键 |

门禁：compileDebugKotlin + assembleDebug 绿；全量单测 **1531/0/0/2**（基线 1529 + 新增 `InternalSecretIdTest` 2 例：框架自管 id 全判内部 / 用户自定义 id 保持可见）。

模拟器验收（emulator-5554）：
- 密钥区块空态「暂无保存的密钥。」（内部项全部过滤）→ 添加自定义 `my-api-key` 正常显示 → 删除弹「删除密钥」确认框（文案含扩展脚本影响与不可撤销）→ 确认后回到空态。全链路 ✓
- mock 补验（F1 遗留）：`tools/mock_model_service.py` 起宿主 18099，开「允许明文 HTTP」开关后 baseUrl 指向 `http://10.0.2.2:18099`，测试连接成功 → ProviderDetailsPane 模型字段变下拉「可输入筛选，已获取 2 个模型」→ 选择器含搜索框 + fixture-model-a/b + 手动输入/应用。availableModels 链路真实渲染 ✓
- F1 快速回归：统一弹层 → 对话段 → ProviderDetailsPane 主面板结构与保存/测试连接链路无回归 ✓

验收过程现场说明（如实记录）：①模拟器配置名称因 adb 无法输入中文由「OpenAI 兼容」改为英文等价 `OpenAI Compatible`（UI 中可随时改回）；②「允许明文 HTTP」开关被开启（mock 验收需要，如不需要可关闭）；③中间一次字段错位输入已被完整修复并经重开验证持久层正确。
