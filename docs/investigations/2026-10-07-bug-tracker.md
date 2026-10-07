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
