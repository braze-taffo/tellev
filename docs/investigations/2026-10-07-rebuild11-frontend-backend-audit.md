# rebuild11 APK 审计:前端功能缺失与后端 Bug

日期:2026-10-07。对象:`out/tellev-v1.7.1.2-dsh-rebuild11-release.apk`(versionCode 44,2026-10-07 01:34 构建)。
基线:源码树 `fix/v1.7.1.2-audit` @ `acde811`(ground-up UI 重写)+ 41 个未提交修改文件。
已确认:APK 与当前工作树完全同步(无源码文件新于 APK),mapping 含 12920 处 dsh 引用。
验证:`.\gradlew test` 1265 个测试全绿(4 个构建类型各 1265/0/0)——单元测试不覆盖 UI 接线,以下发现多数是测试盲区。

对比方法:被删除文件读 `git show acde811^:<path>`(旧 UI:ChatScreen 1237 行、ChatBubble 750 行、ChatInputBar 778 行、ChatSessionDrawer 406 行、ChatQuickSettingsPanel、ModelPickerPopup、TellevRoot 831 行等);新 UI 通读 `ui/dsh/` 7 个文件。所有发现经 grep 调用点复核。

---

## 一、前端功能缺失(旧 UI 有、rebuild11 无)

### HIGH — 核心链路断裂

| # | 功能 | 旧版出处 | 新版证据 | 状态 |
|---|------|---------|---------|------|
| 1 | **消息内图片/附件渲染** | ChatBubble.kt:311-415(附件解析+图片分支+ChatBubbleImage 全屏查看 705-750) | DshChatScreen.kt:992-1111 用户/AI 两分支均无 attachments 处理;TavernRenderParser 仅 Text/Reasoning/Frontend 三种 segment;`ChatBubbleImage`(ChatMessageRendererPieces.kt:333)全仓库零调用 | 图片附件发出后气泡里看不到;生图消息同样不可见 |
| 2 | **用户消息编辑/删除/swipe** | ChatBubble.kt:154-233、322-375(所有角色生效);ChatScreen.kt:649-653 | DshChatScreen.kt:992-1032 `isUser` 分支仅时间+复制键;onEdit/onDelete/drag 手势(DshChatScreen.kt:511-513、1078-1086)只挂非 user 分支 | 用户消息不可编辑/删除/切换 |
| 3 | **角色卡脚本授权弹窗(死锁)** | ChatScreen.kt:773-798(pendingScriptConsent AlertDialog→approve/denyCharacterScripts) | ChatViewModel.kt:2049-2078 的 approve/deny 全仓库零 UI 调用;横幅「启用」(DshChatScreen.kt:484)调 requestScriptConsentPrompt 后无任何 Composable 渲染弹窗 | 点「启用」横幅消失、脚本永远无法启用,授权流程死锁 |
| 4 | **生图结果画廊(查看/删除)** | ChatScreen.kt:704-756(generatedImages 画廊+删除确认+prompt 展开) | `generatedImages`/`deleteGeneratedImage` 在 ui/dsh 零引用;生图完成后图片只进 GeneratedImageStore(ChatImageGenerationCoordinator.kt:308-316)不进消息流 | 生图完成后用户无处查看结果 |
| 5 | **生图进度行+取消** | ChatInputBar.kt:289-309(isGeneratingImage+onStopImage) | `isGeneratingImage`/`stopImageGeneration`(ChatViewModel.kt:1778 存在)ui/dsh 零引用 | 生图期间无任何指示、无法中断 |
| 6 | **附件预览 chips 与移除** | ChatInputBar.kt:310-326+572-639(72dp 缩略图+移除);ChatScreen.kt:834-849(孤儿清理) | DshComposer 无 attachments 参数;pendingAttachments(DshChatScreen.kt:166)加入后不可见不可移除,发送时静默带出 | 用户无法核对/取消要发送的附件 |
| 7 | **Persona 运行时切换** | ChatQuickSettingsPanel.kt:169-175(列表 radio→selectPersona);HEAD 版 dsh 也保留过 | `selectPersona`(ChatViewModel.kt:1824)整个 ui/ 零调用;新版弹层只能编辑当前(upsertPersona) | 多 persona 用户无法在聊天内切换身份;README.md:93「运行时切换」宣称断链 |
| 8 | **宽屏 NavigationRail** | TellevRoot.kt:266-315(≥600dp 用 NavigationRail) | DshRoot.kt:253-277 仅 NavigationBar;grep NavigationRail/BoxWithConstraints=0 | 平板/折叠屏丢左侧导航;README「平板与大屏」宣称落空 |

### MEDIUM — 次要功能/反馈缺失

| # | 功能 | 旧版出处 | 新版证据 |
|---|------|---------|---------|
| 9 | 生图失败弹窗与诊断查看 | ChatScreen.kt:799-806、917-936 | imageGenError/imageGenDiagnostic ui/dsh 零引用;DshChatScreen.kt:757 显式 `onShowDiagnostic = null`;失败完全静默 |
| 10 | 生成中(无流式文本时)loading 占位 | ChatScreen.kt:689-700 | DshChatScreen.kt:529 仅流式文本非空时渲染 streaming item;首 token 前无任何反馈 |
| 11 | 模型名手动输入兜底 | ModelPickerPopup.kt:180-218(「手动输入」) | DshModelEffortMenu(DshPopups.kt:101-355)只有目录/分组/当前/最近;目录不含目标模型的自定义端点无法填入 |
| 12 | TTS 页面生命周期绑定 | ChatBubble.kt:122-127(bindPage,页面 ON_STOP 停播) | `TtsRuntime.bindPage` 全仓库零调用(仅旧 ChatBubble 调过);离开聊天页/切会话后朗读继续播完 |
| 13 | WebView HTML 卡片边界滚动传导 | ChatScreen.kt:252-262、635-637(scrollBy 传导+fling+断跟随) | DshChatScreen.kt:510、567 `onBoundaryDrag = { }`、1105 `onScrollStart = { }` 三个空 lambda;长卡片内滚到边界后拖动/惯性不传导,跟随最新也不断开 |
| 14 | frontend(HTML 卡片)swipe 控件 | ChatBubble.kt:339-347(hasFrontend 同样渲染 HtmlSwipeControls) | DshChatScreen.kt:1112 `if (message.swipes.size > 1 && !hasFrontend)`;frontend 消息多 variant 无法切换 |
| 15 | 聊天内生图设置/使用统计/TTS 配置入口 | ChatSettingsSheet.kt:79-125 | DshChatScreen.kt:146-147 声明 onOpenImageGenSettings/onOpenUsageStats 但函数体零调用(死参数);功能仍可从设置 tab 到达,入口藏深 |
| 16 | 触控目标 ≥48dp(无障碍) | MessageActionRow.kt:53-79(IconButton 36dp+M3 兜底) | ActionKey 裸 Box 28dp(DshChatScreen.kt:1218-1231);抽屉删除/图钉 24dp、搜索/导出 28dp,无 minimumInteractiveComponentSize |
| 17 | 输入草稿 rememberSaveable | ChatScreen.kt:211(inputText)、225(editTextField) | DshChatScreen.kt:165 `var inputText by remember`(非 rememberSaveable);旋转/进程死亡丢草稿(旧版保) |

### LOW — 边缘体验

| # | 功能 | 旧版出处 | 新版证据 |
|---|------|---------|---------|
| 18 | 导出生成诊断/原始响应 | ChatBubble.kt:191-208 | generationDiagnostics 在 ui/dsh 零引用 |
| 19 | 「只返回推理无正文」提示+重试 | ChatBubble.kt:302-307 | 零引用 |
| 20 | 顶栏世界书直入按钮 | ChatScreen.kt:457-463 | 变为 ⋮→sheet→「管理」三级(DshChatScreen.kt:893) |
| 21 | 顶栏会话标题 | ChatScreen.kt:408-431 | 顶栏只显示角色名(DshChatScreen.kt:373-382) |
| 22 | 记忆模式状态条 | ChatScreen.kt:530-543 | 只剩 ⋮ 菜单入口,无常驻状态显示 |
| 23 | 抽屉「只看置顶」过滤 | ChatSessionDrawer.kt:197-204(旧 120-123) | DshDrawer 只有搜索+置顶排序,无 pinnedOnly |
| 24 | 发送后收起键盘 | ChatScreen.kt:859(keyboardController?.hide()) | ui/dsh 无 SoftwareKeyboardController 引用 |
| 25 | 打开会话初始定位到底部 | ChatScreen.kt:203-207(initialFirstVisibleItemIndex=lastIndex) | 默认从 0 再 animateScrollToItem;长会话有先顶后底跳变 |
| 26 | TTS 未配置时隐藏播放键 | ChatBubble.kt:130-134(ttsEnabled gate) | 总显示播放键,点击才报错 |
| 27 | 抽屉分组折叠(>4 条展开) | ChatSessionDrawer.kt:264-275 | DshDrawer 分组默认全收起+当前组自动展开,语义变化不大,列出备查 |

### 核实后排除(非缺失)

- 反馈/分享/上下文/分支/删除消息:收进动作行或「更多」菜单,功能均在(DshChatScreen.kt:1131-1183、1246-1278)。
- 语音输入、停止生成、会话重命名/删除/置顶/搜索/分支树、记忆/指标/上下文查看器、脚本管理、背景设置、模型菜单+effort 滑杆、预设调节、世界书绑定/条目开关:保留或增强。
- 退出确认、启动弹窗栈(更新检查/新手引导/QQ 群通知/更新弹窗):工作树版已完整补回(DshRoot.kt:205-239、287-296、494-674)。注意 HEAD 提交版没有——**工作树未提交修改必须尽快提交,HEAD 单独构建不可发布**(暗色主题、点卡进聊天选对 ViewModel 等都在未提交部分修复)。
- MainActivity 角色卡导入 intent、四语言、主题/外观设置、备份、扩展页:未受 UI 重写影响。

---

## 二、后端 Bug(含新增功能缺陷)

### HIGH

**B1. TTS「测试连接」按钮:非法 URL 直接崩溃 + 主线程网络**
`TtsSettingsSection.kt:209` 在 `scope.launch` 内直接调 `OpenAiSpeechAdapter().checkStatus(config)`:
- `endpoint()` 是裸字符串拼接(OpenAiSpeechAdapter.kt:155-156),`Request.Builder().url()` 在 runCatching **之外**(39-44 行)构造——用户在 baseUrl 字段输入一半(如 `h`)点测试,`IllegalArgumentException` 从协程逃逸→**应用崩溃**。开关打开时占位符就是 `"https://"`(TtsSettingsSection.kt:153),编辑中途随时可点(enabled=!testing)。
- `checkStatus` 用 `client.newCall(request).execute()` 同步执行、无 Dispatchers 切换→**主线程网络**,点击后 UI 冻结最长 10s+。
- 默认 `OkHttpClient()`(OpenAiSpeechAdapter.kt:30)绕过全局 15s/5min 超时与 CleartextGuard 拦截器(TellevGraph.kt:137-150)——远程明文 URL 测试通过、真播放被拦,行为矛盾。
修复:runCatching 包整个构造、withContext(IO)、复用 providerClient。

**B2. 输入草稿状态丢失(见缺失 #17)** — `remember` 非 `rememberSaveable`,旧版是 Saveable;后端无涉及,列此备查。

### MEDIUM

**B3. TTS 设置文本字段:每字符写盘 + trim 吃掉输入空格**
`TtsSettingsSection.kt:157-176` OutlinedTextField 的 onValueChange 每字符 `save()`(SharedPreferences apply);外层 `ttsValues = it.validated()`(SettingsScreen.kt:383)→ `initial` 变化 → `remember(initial)` 重建内部 state。`validated()` 的 `trim()` 会把拼音分词所需的尾随空格立即剪掉——**中文输入法下自定义音色/模型字段无法输入含空格值**。修复:本地暂存,失焦/去抖统一保存。

**B4. 损坏 TTS 缓存条目永久毒化播放**
`TtsCache.get()` 只查 isFile+length>0;命中头部截断文件时 MediaPlayer.prepare 失败→`machine.failed()` 清空队列(TtsPlayer.kt:73-75),**坏文件永远留在缓存**(key 不变下次仍命中),同文本永久播放失败;`TtsCache.clear()` 生产代码零调用,UI 无清缓存入口。修复:prepare 失败删该文件并重合成一次。

**B5. 静音/振动模式下 TTS 照播**
全工程无 ringerMode 检查;USAGE_MEDIA 不受铃声静音影响,会议场景静音手机点朗读仍大声出声并抢焦点。修复:播放前查 ringerMode 或提供开关。

**B6. 模型分组只能增不能删**
ChatModelGroups.kt 只有 read/write;ChatViewModel 只有 createModelGroup/assignModelGroup(ChatViewModel.kt:1074-1102);DshModelGroupDialog(DshPopups.kt:393-483)无删除 UI。用户建错分组无法删除,也无法给分组改名(model-groups.json 越积越多)。

**B7. 抽屉会话树只支持两层,fork-of-fork 的孙会话不可见**
treeRows(DshDrawer.kt:372-389)只渲染 root→children 一层;孙会话(父本身是子会话)`parentId != null` 且父在列表,但既不在 roots 也不在 childrenOf(只查 parentId==root.id)……实际核对:childrenOf(parentId) 查任意 parentId,但 treeRows 对 child 不递归展开,孙会话完全不显示,也不在 orphans(父存在)。fork 一个 fork 后,孙会话从抽屉消失(数据还在,聊天中可搜索到)。修复:childrenOf 递归或平铺多层。

**B8. 流式滚动偏移:横幅占位导致差一项**
`LaunchedEffect(state.messages.size, state.isGenerating)` 滚到 `messages.size`(DshChatScreen.kt:307-309),但列表在「角色脚本已禁用」横幅存在时多一个 item(横幅是 item 0,streaming 又是额外 item)——`animateScrollToItem(state.messages.size)` 指向倒数第 2 条附近而非真正的流式气泡,首屏可能定位偏上。低危但真实存在。

**B9. ttsProviderConfigured 提示条件与实际播放约束不一致**
`SettingsScreen.kt:90-91`:`(selectedProviderId == OPENAI_COMPATIBLE || startsWith("custom:")) && state.apiKey.isNotBlank()`;而 `speechConfig`(TtsSpeechService.kt:201-212)只要求 adapterIdFor==OPENAI_COMPATIBLE 且不看 apiKey。配了无鉴权本地端点时设置页红字报错,点朗读其实能成功;反之亦然。提示不可信。

### LOW

**B10. TTS 缓存键 baseUrl 不归一** — 末尾斜杠/大小写不同产生多余缓存 miss(TtsCache.kt:21-24 已含 baseUrl,方向正确,仅缺归一化)。

**B11. 自定义 TTS 端点不支持自定义鉴权头** — speechConfig 生成的 ProviderConfig 无 options,只发 `Authorization: Bearer`;X-API-Key 网关不可用(聊天服务商分支支持,不对等)。

**B12. tts_request_failed 不带 HTTP 码** — ProviderError.diagnostic 存了但 userMessage() 不带出,调试困难。

**B13. 长按语音与文本长按选择冲突** — DshComposer.kt:122-124 注释自认:有文本时长按为选择,语音输入只能键盘收起后或清空文本再长按,入口不稳定。

**B14. 附件键 contentDescription 语义偏窄** — DshComposer.kt:158 用 `chat_attach_image`,实际是附件菜单(含视频/音频/文档/生图)。无障碍朗读误导。

### 已核实无问题(后端)

- **WorldInfoScanner 重写**(逐轮预算闸门+递归步数口径):与上游 world-info.js 语义对齐,有新测试钉住(WorldInfoScannerTest)。
- **WorldBookCodec uid 寻址修复**(对象键=uid 而非列表 index):正确修复了稀疏 UID 错位,注释清晰。
- **PresetCodec reasoning_effort 解析**、**PresetRepository 清除别名键**:往返保真,takeIf{!=Auto} 语义与 ReasoningSupport 一致。
- **GenerationMetrics openAiCached 扣除**、**aggregateHybrid 日汇总口径**:计费重复问题已修;sessionId 归因在两处 record 均已传入;modelUsageFromDaily 空表回退正确。
- **OpenAiCompatibleAdapter effectiveFamily**:显式档位落线上的语义与 ReasoningSupport.uiLevels 呼应,Auto 保持直通。
- **VirtualApiRouter worldinfo create/save/delete 实现**(替代 501 stub)+**缺文件返回 200 {entries:{}}**:与官方契约一致。
- **ChatRepository 会话归属=目录名**、ChatSessionSummary parentId/characterId:序列化向后兼容(默认 null)。
- **forkSession**(take(messageCount)、parentId 写 metadata、storageRevision=0):正确。
- **exportSessionArchive**:完整 ST JSONL(头行+消息行)可重导入;但注意 `CreateDocument("application/json")` MIME 与 `.jsonl` 扩展名不匹配(DshChatScreen.kt:268-270),部分文件管理器按 MIME 存成 .json,重导入不受影响,列为瑕疵。
- **AndroidManifest `<queries>` 声明**:SpeechRecognizer 包可见性必需(minSdk 31),正确。
- **i18n**:a16_dsh_rebuild.tsv(61 键)、legacy_strings.tsv(49 键)与 S.kt/四语言 strings.xml 全对齐;ui/dsh 无硬编码中文。

---

## 三、结论与优先级

**rebuild11 APK 与源码工作树同步,后端核心(提示词引擎/世界书/预设/指标/provider)状态良好且本轮修改质量高;问题集中在 UI 重写时的接线遗漏。**

修复优先级:
1. **#3 脚本授权死锁**(用户被完全卡住,横幅点「启用」无反应)
2. **#1+#4+#5+#6 图片链路**(能发附件、能生图,但结果全程不可见、附件不可核对)
3. **#2 用户消息编辑/删除**(高频操作)
4. **B1 TTS 测试按钮崩溃+ANR**(新增功能自带崩溃路径)
5. **#7 persona 切换、B7 孙会话不可见**
6. 其余 medium/low 按批次处理。

另:HEAD(acde811)不可单独构建发布——暗色主题、点卡进聊天等关键修复都在未提交的工作树修改里,应尽快提交。

---

## 四、第二轮补充(2026-10-07 续)

标注「实测」的结论用临时单元测试复现过(测试文件与日志已删除,仓库无残留);标注「读码」的只经代码核对;标注「未验证」的来自子审计,未在设备上跑。

### HIGH

**N1. 扩展权限闸门可被 `//` 绕过,任意扩展可读出全部密钥(实测)**
`requiredExtensionPermissionForPath`(ExtensionHostPolicy.kt:7-14)用 `URI.path` 判断前缀,不折叠重复斜杠;`VirtualApiRouter.dispatch` 却用 `split("/").filter{isNotEmpty}`(VirtualApiRouter.kt:~80)。于是 `/api//secrets/k1` 的所需权限为 null,闸门放行,路由照常返回 `{"id":"k1","value":"<明文>"}`。同理 `/api//providers`、`/api//chats`、`/api//characters` 绕过 ProviderRequest/Storage 权限。未声明 Secrets 权限的扩展即可读写 API Key。
修复:闸门与路由共用同一个 normalizePath(折叠 `//`、处理 `/./`、`/../`、百分号编码),闸门未命中时默认拒绝而不是默认放行。补回归测试。

**N2. 备份导出把记忆的 SHA-256 哈希脱敏成 `[REDACTED]`,恢复后长期记忆被清空(实测)**
`BackupCoordinator.exportBackup` 默认 `includeSecrets=false`,对每个 .json 调 `SensitiveFieldScanner.sanitize`;其 `^[a-zA-Z0-9]{40,}$` 规则(SensitiveFieldScanner.kt:~35)会命中 64 位十六进制哈希。`MemoryDocument.processed` 恢复后变成 `{m1=[REDACTED]}`,首次构建提示词时 `MemoryService.contextDetail` → `reconcile` 判定「历史消息已变更」→ `invalidated` 丢弃所有非手动记录并立即落盘(MemoryService.kt:199-200、295-313),用户需手动重建(付费调用)。同一规则还会吃掉 `script-consent.json` 的 fingerprint(恢复后角色脚本授权全部重新询问)。
修复:按字段名白名单排除 processed/fingerprint 等;或改成「键名命中才脱敏」,不要按值形状猜;加备份往返测试。

**N3. 重新生成/左右滑动/编辑最新一条回复,会清空整个长期记忆(实测:records 2→0,needsRebuild=true)**
`reconcile` 对每条已处理消息比较内容哈希,任何一条内容变化都整体 `invalidated`。重新生成会替换最新回复的 content,下一次 `contextDetail` 就清空记忆。只要开启记忆,日常「重 roll」就会反复触发付费重建。
修复:只使消息自身与其后派生的记录失效(记录带 sourceIds),不要整库作废;或对 swipe 切换用 swipe 内容哈希区分。

**N4. 点击其它角色分组下的会话,聊天界面不切换角色(读码)**
`DshDrawer` 的 `onOpenSession` 只回传 session.id;`switchSession`(ChatViewModel.kt:863-903)不更新 `selectedCharacter`(该字段只在 319/542/599/790/1425 赋值),还会用当前角色对目标会话执行问候语改写并落盘。结果是显示与发送都用错角色卡,问候语被写成错误角色的内容。
修复:回传 ChatSessionSummary,先按 characterId 选角色再切会话。

**N5. 新 UI 完全不展示生图进度/结果/错误,附件也无法核对(grep 核实)**
ui/dsh 对 isGeneratingImage、imageGenStatus、imageGenError、generatedImages、stopImageGeneration、clearImageError、isGeneratedImage、message.attachments 均零引用。生图点了没有任何反馈,结果不显示;待发附件无缩略图、不能移除;已发图片/文件不在气泡里渲染。(与第一节图片链路条目同源,此处补充核实证据。)

**N6. 文本附件内容被当成 EJS/宏模板执行,且只在发送当轮生效(实测+读码)**
- 附件文本拼在 userInput 末尾(ChatGenerationCoordinator.kt:214-224,348),随后与正文一起走宏展开和 EJS 渲染。实测:附件里的 `<%= 1+1 %>` 被执行成 `2`;含 `<% @items.each ... %>` 的 .erb/.jsp 文件内容被吞并产生警告。上传 html/模板类文件会被篡改,这是提示词注入面。
- `textContent` 只在这一处读取:历史消息不再带附件文本,重新生成(attachments=emptyList,userInput=inputMessage.content)与后续轮次都读不到文档内容。
修复:附件文本放进被转义的专用区块(跳过 EJS/宏),并作为消息的一部分持久化到后续轮次。

### MEDIUM(读码核实)

**N7. 语音识别中点发送,识别结果把已发送内容重新灌回输入框**
`send()`(DshChatScreen.kt:277-285)只清 inputText,不停止识别器也不清 `voiceBaseText`;随后的 partial/final 回调执行 `inputText = voiceBaseText + 识别文字`,而 voiceBaseText 正是刚发出的草稿。「取消」调用的 `voice.stop()` 实际是 `stopListening()`,仍会回调 onResults,文字照样写入。修复:send/取消时 `recognizer.cancel()` 并把 voiceBaseText 置 null,回调在其为 null 时丢弃。

**N8. 输入框无最大高度,粘贴长文后发送键被挤出屏幕**
DshComposer.kt:124-141 只有 `heightIn(min=36.dp)`、无 maxLines。粘贴几十行文本后控制行(附件/模型/发送)被压到约 0 高度。修复:`maxLines=8` 或 `heightIn(max=…)`+内部滚动。

**N9. 选其它供应商分组里的模型,只写进当前供应商**
`selectModel(model)`(ChatViewModel.kt:1642-1675)只收模型 id,写入 `selectedProvider` 的 model 密钥,不切供应商;而模型目录会列出其它供应商/自定义端点的模型。选中后请求把别家模型 id 发给当前端点。修复:回调带 group/provider id,先切供应商再写模型。

**N10. 预设调节保存会静默清掉 maxCompletionTokens**
编辑器初值只读 `preset.maxTokens`(DshPopups.kt:1725),保存时 `maxCompletionTokens = maxTokens`(ChatViewModel.kt:~1930);预设只设了 maxCompletionTokens 时,「最大回复」显示为空,只改温度也会把它写成 null。另外非法输入(`1.2.3`、`-`、TopK 填 `2.5`)被静默当成「清空」并保存,无范围校验(DshPopups.kt:1782-1786)。

**N11. 消息行 index 型状态在删除/切会话后串台**
滑动手势 `pointerInput(message.id)` 内捕获的 `onSwipe` 带旧 index,删掉靠前消息后行因 key=id 保留组合,手势仍作用到旧下标(DshChatScreen.kt:1079-1087);`editingIndex`、`messageMenuIndex` 存下标,切会话或列表变动后新会话同下标行直接进入编辑态并预填旧文本,确认会覆盖该条(用户消息则截断其后全部并重发)。修复:存消息 id,以 session id 为 key,切会话清空。

**N12. 世界书条目编辑器跨书串台;未绑定时改写第一本书**
条目 id 是书内 uid("0"、"1"…),展开状态与暂存字段的 key 不含 book.id(DshPopups.kt:1436、1571-1578),切书后新书条目 0 复用旧书暂存内容,保存即写进新书;`selectedBook` 未绑定时回落 `books.first()`,开关/保存会改动全局第一本书(:1338、:1387)。关键词按逗号切分,正则键 `/a{1,3}/i` 每次保存都被切碎(:1572、:1635)。

**N13. 创作页(编辑路由)重组即重置进行中的会话**
DshRoot.kt:404/419/434 在 `LaunchedEffect` 里调 `startFromCharacter/startFromWorldBook/startWorldBookFromCharacter`,MainActivity 无 configChanges,旋转/深色切换/语言切换都会重新执行并覆盖落盘的草稿。修复:在点击处初始化,或用 `rememberSaveable(cardId)` 只触发一次。(344/369/385 同模式,未核实是否丢未保存编辑。)

**N14. 导航细节**
双击角色卡会 `navigate("chat")` 两次(无 launchSingleTop),要按两次返回(DshRoot.kt:315-321);「用 AI 创建」入口进入顶层路由,返回键被退出确认拦截回不到世界书列表(:356);`presetFocusRequest` 自增后永不清零,每次重进设置 tab 都被拉回预设区(:215、469、559)。

**N15. 抽屉**
搜索收起不清 query,过滤继续生效却无输入框,会话像消失了(DshDrawer.kt:179、203-262);deleteSession 不刷新 sessionGroups,被删会话行残留可点,点击会报错(ChatViewModel.kt:1313-1419);query/展开状态用 remember,旋转即复位。

### LOW

- 自动滚动:拖动关闭 followLatest 后回到底部附近不会恢复(`atBottom` 值未变,Flow 不再发射)(DshChatScreen.kt:296-307);发送/重新生成/继续后不回到底部;脚本禁用横幅占一个 item,「跳转到来源消息」差一条(:737-740);滚动目标 `messages.size` 与横幅/streaming item 个数不严格对应(见 B8)。
- 流式气泡与落库消息在写盘窗口内可能同时显示(未验证)。
- 隐藏消息(isHidden)与 System/Tool 消息在 dsh 无任何标识,统计也计入;消息删除无确认无撤销。
- 生成诊断导出、「正文为空→重试」提示在旧 UI 有、dsh 无(grep ui/dsh 零引用)。
- 硬编码字符串:`"%.0f tok/s"`、`" · "`(DshChatScreen.kt:1473-1484)、默认用户名 `"User"`、QQ 群号 `"754350480"` 与资源重复(DshRoot.kt:610)、抽屉日期固定 `yyyy/M/d`(DshDrawer.kt:541)。
- 无障碍与触控:上下文环、输入框、行内展开按钮无 contentDescription;附件/模型/环 28dp、抽屉删除/置顶 24dp、行高 32dp,均小于 48dp;删除与置顶两图标紧邻易误触。
- 草稿与已选附件用 `remember`(旧版 inputText 是 rememberSaveable),旋转或进设置再返回即丢失。
- 长按语音入口:KDoc 称 Initial 拦截且仅空文本触发,实现却是无条件 `pointerInput(Unit){detectTapGestures(onLongPress)}`,无可见麦克风按钮,读屏用户无入口。
- API 级:Android 15+ 强制 edge-to-edge 下 `adjustResize` 不再缩窗,全工程仅一处 `imePadding`,键盘可能遮挡输入框(未真机验证)。

### 本轮排除

- 返回键先关抽屉(ModalNavigationDrawer 自带处理)、GuideOverlay 自带 BackHandler、UpdateViewModel 防重入、`regenerateResponse/continueGeneration` 全程按 messageId 解析、`WebViewJsExtensionHost` 已处理 onRenderProcessGone(`ChatWebViewPanel/WebViewTemplateEvaluator` 等其余 WebView 未处理,见下一条)。

**N16. 其余 WebView 未实现 onRenderProcessGone(读码)**
全工程仅 WebViewJsExtensionHost.kt:321 处理渲染进程崩溃;WebViewTemplateEvaluator.kt:101、ChatWebViewPanel.kt:371、CommunityScreen.kt:284、CreationScreen.kt:1282 均未处理,平台默认行为是杀掉整个应用。WebViewTemplateEvaluator 崩溃后 `view` 仍非 null,后续 EJS 求值永久挂起到 30s 超时。

### 流程与安全备注

- 第二轮用临时单元测试复现了 N1/N2/N3 与 EJS 附件注入,测试文件与日志均已删除,git status 无 probe 残留。
- 排查过程中 `local.properties`(已 gitignore、从未提交)里的签名库口令曾在一次工具输出里明文出现,**这份会话记录如需分享请先脱敏,建议更换该签名口令**(文档不记录口令内容)。

---

## 五、UI 专项(第三轮,2026-10-07 续)

全部为读码结论,未在真机验证。标注「子审计」的条目来自后台审计员,主线抽查过关键论据。

### 主题与视觉

**U1. Bre 思考滑杆整块硬编码亮色系,暗色模式下发光/文字不可见(子审计+读码核实)**
DshPopups.kt:555-1025 的滑杆绘制用固定亮色渐变(`Color(0xFFFFFFFF)`→`0xFF438FDF` 等)与白色发光,仅 :996 一处按 `isDark` 分支。暗色底上滑杆是亮蓝大块、发光过曝,档位刻度线 `0x8C000000`(黑 56%)在暗色上几乎不可见。分隔线 `Color(0x29797E91)`、档位字 `Color(0xFF4D70FF)` 也是写死的(139、159 行),应走 Dsh token。

**U2. 上下文分段条颜色映射写死亮色值(读码)**
DshContextPopover 的分段条和图例用 `Dsh.segSystem/segWorld/segMessages`(亮暗同值,源自官方 ContextMeter 字面值),亮色值 `0xFF4D93F8` 在暗色 bgBase 上对比度尚可,但亮色模式下 `segSystem=0xFFADB2B8` 对纯白底只有 ~1.9:1,低可视。属设计取舍,列低优先。

### 弹层与编辑器(实测保存路径)

**U3. 世界书 sheet 的三个写入路径缺陷(读码+子审计核实)**
- 未绑定时 `selectedBook = books.firstOrNull()`(DshPopups.kt:1341),界面显示第一本书的名字,圆点开关和条目保存会改写**全局第一本书**(onToggle/onSave 回调 1439-1446 行)。用户以为在「浏览」,实际在改数据。
- 条目编辑器暂存字段 `remember(entry.id)`(1571-1578 行),而 entry.id 是 ST 的书内 uid("0"、"1"…),不含 book.id:切书后新书同 uid 条目直接继承旧书展开态与暂存内容,点保存写进新书(与 N12 同源,此处补充核实)。
- 关键词保存按 `,`/`，` 切分(1635 行),含逗号的正则键(如 `/a{1,3}/i` 或 `foo, bar` 字面键)每次保存都被拆碎。ST 用的是数组字段,不是逗号切分。

**U4. 数字/小数输入无键盘类型、无范围校验(读码)**
DshPopups.kt 全文件 0 处 `keyboardOptions`(世界书 order/depth/probability、预设 5 个参数、模型菜单搜索等全是默认字母键盘);`DshNumberField` 只过滤数字但 `DshDecimalField` 允许 `1.2.3`、`--3`,保存时 `toDoubleOrNull()` 返回 null 被当「清空」静默落库(1800-1807 行)。对照:设置页的 Comfy 参数对话框就正确使用了 `KeyboardType.Number/Decimal`(SettingsImageGenSection.kt:651-697)。同一份代码两种标准。

**U5. 设置页遗留的 `!!` 展开有竞态崩溃窗口(读码)**
SettingsProviderSection.kt:417 `state.providerStatus!!`、SettingsImageGenSection.kt:305/441 同模式。外层 `if (state.xxx != null)` 与读取之间若状态复位(切供应商/重测触发 update 把字段置 null),组合重组在两次帧之间读到 null 即 NPE 崩溃。概率低但路径真实。修复:`val status = state.xxx ?: return@item`。

### 状态与交互

**U6. 角色卡编辑器字段分组丢失旋转前的值(读码,不一致的保存策略)**
CharactersScreen.kt:585-599:主要字段用 `rememberSaveable(character?.id)`,但 `alternateGreetings`(590)、`tags`(597)、`newTag`(598)、`linkedWorldName` 在编辑中切换(601)用普通 `remember`。旋转屏幕后前者保留、后者清零——同一个表单里一半字段还在一半没了,且 alternateGreetings 是 List<String> 本可用 Bundle 保存。设置页 Comfy 参数对话框(636-642 行)同样全是普通 `remember`,旋转即丢草稿。

**U7. 世界书条目编辑器默认值悄悄覆盖原值(读码)**
WorldBookEntryEditScreen.kt:101-115:`priority ?: 0`、`insertionOrder ?: 100`、`depth ?: 4`、`probability ?: 100`——字段清空保存时不报错,直接把这些值写回。ST 语义里 order=100 是常见默认但 priority=0 与 depth=4 并非所有条目的原值;用户清掉一个看不懂的字段保存,数据被静默改写。另外 :36 的 `options.first().second`(WorldComponents.kt:36)在选项列表为空时直接 `NoSuchElementException` 崩溃——现有调用点都传非空列表,属潜在地雷。

**U8. 角色卡 ViewModel 的用户可见提示全部硬编码中文(读码)**
CharactersViewModel.kt 共 14 处(89、127-128、169、194、202、218、226、240、246、254、270、278、344 行):「角色已保存。」「保存角色失败:…」等直接写进 error/info,四语言用户看到中文。同文件其余路径已走 `UiStrings.get(S...)`(318、329 行),说明是改造遗漏不是设计。ProviderSettingsController.kt:157「自定义配置 N」、CreationEngine.kt:682 同类。核心层还有一批硬编码中文异常文案会直通 UI(StDataStore.kt:55-114、ChatSessionRuntime.kt:123、ChatTavernAdapter.kt:143-693、ChatImageGenerationCoordinator.kt:337-386)。

**U9. 扩展页只读信息用 AssistChip 呈现,点击无反应(读码)**
ExtensionsScreen.kt:386-443:权限标签、世界书导入状态、正则数量等 7 处 `AssistChip(onClick = {})`。AssistChip 视觉上是可点组件,用户点了没任何反馈;应改 `Text`+背景或禁用样式。

**U10. 无障碍:WorldBookDetailScreen 的 keys 用 AssistChip 但无 contentDescription;社区页 WebView 无任何回退无障碍(读码,低)**
另外沿用第二节已记录的 dsh 触控区域问题:世界书条目启用圆点 18dp(DshPopups.kt:1487-1493)、模型行内长按分配无任何视觉提示(DshPopups.kt:246-252 附近)。

### 本轮排除

- DshTheme 亮暗双色板逐值对照官方 design-platform.css 字面值,框架与 Material3 映射正确;themeMode 三态(Light/Dark/System)接线正确(MainActivity.kt:49-52 → DshRoot.kt:126-127)。
- MemoryChatDialogs:editing/historyFor/confirmRebuild 均以 session.id 为 key,正确;模式选择弹窗 onDismissRequest={} 是刻意的强制选择,不算缺陷。
- 用量统计页除法都有 `max(1L, ...)` 保护;Canvas 单点路径 `days.size==1` 分支正确;热力图 30 天 `LocalDate.now()` 取键与 DailyUsageSummary.date 同为 ISO 格式。
- Comfy 工作流对话框保存前 `ComfyWorkflowTemplate.parse` 校验,失败置 isError 不落库,处理正确。
- 上下文查看器「跳转到来源消息」按消息 id 反查 index(ContextViewerDialog.kt:210),id 稳定;差一项偏移问题已记录在 LOW(横幅 item)。
- 设置页预设编辑对话框的 raw JSON 校验/移动 prompt 条目的边界检查(:281)正确。
