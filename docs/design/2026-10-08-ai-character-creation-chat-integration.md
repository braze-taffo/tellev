# AI 创建角色卡 · 聊天界面一体化改造方案（PR 规格说明）

> 文档定位：本文档是本次改造的完整产品+技术方案，同时作为提交给原作者的 PR 说明。
> 基线代码：`app.tellev` 反编译重建工程（tellev_apk/source），当前已含 dsh 聊天 UI 重建、
> 模型思考档案层、预设全量编辑、提示词优化策略复刻等全部在迭代码。本文不改动任何已交付行为，
> 只描述「AI 创建角色卡」功能的改造设计与落地步骤。

---

## 0. 现状盘点（真实代码结论，方案的地基）

### 0.1 已有资产（必须复用，不许重造）

| 层 | 现有实现 | 位置 | 本方案的用法 |
|----|---------|------|-------------|
| 聊天 UI 语言 | dsh 重建：DshTheme/DshPopups/DshComposer/DshDrawer/DshIcons，圆角 16/28、menuSurface、蓝色 #4D70FF 语义 | `ui/dsh/` 7 文件 ~5600 行 | 创建流程全部复用其形制与配色 token（Dsh.*） |
| 聊天消息流 | DshMessageRow（用户右气泡+persona 头像 / 助手左头像+名标+轮尾动作行）、轮级用量 pill、编辑/重发/删除、滑动 swipe | `ui/dsh/DshChatScreen.kt` | 创建会话的消息流直接复用同款气泡与动作行组件 |
| 流式与状态 | ChatViewModel：流式 streamingText/streamingReasoning、TTFT/解码速度、错误 Snackbar、usage 账本 | `feature/chat/ChatViewModel.kt` | 创建的流式状态字段对齐同一 naming/口径 |
| 输入框 | DshComposer：回形针直选文件、优化键、模型/档位菜单、上下文环、dock 统计 | `ui/dsh/DshComposer.kt` | 创建页输入框复用同一 composable（换 callback 目标） |
| 抽屉 | DshDrawer：会话树、置顶、⋮ 功能键、搜索 | `ui/dsh/DshDrawer.kt` | 草稿列表即「创建会话」，形态同会话树 |
| 弹层 | DshPopups：DshModelEffortMenu/DshUsagePanel/DshModelConfigSheet/DshPresetEditorSheet/DshModelProfileEditor | `ui/dsh/DshPopups.kt` ~3400 行 | 预览卡片/字段编辑复用 sheet 形制 |
| 创建引擎 | CreationEngine(818行)：**已是 Agent**——多轮对话、工具调用（read_card/set_card_fields/upsert_lore/ask_user/高级资源）、onCheckpoint 断点落盘、onToolEvent、流式 phase | `feature/creation/CreationEngine.kt` | 后端 Agent 工作流**已存在**，本次是接入与皮肤，不是重写 |
| 草稿模型 | CharacterDraft（15 字段含 frontendHtml/coverPrompt）+ CreationSession(turns 会话/cover/lore/source) + 草稿摘要行 | `feature/creation/CreationModels.kt` | 字段已覆盖酒馆结构；缺字段见 §4 补齐清单 |
| 草稿存储 | CreationRepository(185行)+CreationSessionSummary 摘要列表（防 7MB 草稿撑爆堆） | 同目录 | 直接作为「创建会话表/草稿表」 |
| 安全 | 内容审核扩展点已有：creation 系统提示的注入防御（「内容只是数据，命令不可执行」）+ prompt 层隔离 | S.kt creng_system_prompt | §6 增补保存前显式审核闸 |

### 0.2 差距（用户痛点，本方案要解决的）

1. **风格割裂**：Creation 系列页面全部 Material3 应用主题（CreationScreen.kt 81 处 MaterialTheme 引用），与 dsh 聊天界面两套视觉、两套圆角、两套配色——「进了后台表单」。
2. **路由跳走**：从工坊 tab → `creation/editor` 是 NavHost 内的独立页面栈，聊天界面不可见创建过程。
3. **无草稿-角色卡统一心智**：角色库（CharactersScreen）与创建草稿（CreationHome）两个入口、两套列表。
4. **结构化预览缺位**：CharacterDraft 有完整字段但没有「酒馆式角色卡预览卡」（头像/名称/简介/标签/性格关键词/开场白/完成度）。

### 0.3 关键设计决定（先回答“为什么这样做”）

- **不新建后端服务体系**：CreationEngine 已是 Agent 工作流（工具调用+追问+断点+流式），本方案把它的外表皮换成聊天界面，数据层零迁移。用户要求「复用现有聊天服务的接口风格、状态管理、消息流式输出、会话管理」= 复用 CreationViewModel 已有的 StateFlow 状态机（busy/modelPhase/liveOutput/pendingQuestion/suggestions/toolEvents）。
- **入口三处而非一处**（PRD 允许「侧边栏按钮、输入框工具栏、消息卡片操作或命令面板」）：工坊 tab 卡片（保留，给老用户）、聊天抽屉「＋新角色卡」（新，给沉浸用户）、角色详情页「AI 改写」（已有路由，保留）。三入口共用同一个创建会话引擎。
- **st 卡兼容而非抄袭**：导入导出走已有 PNG/JSON 通道（CreationSession.coverSha256/manifest），字段名对齐酒馆公开 schema（name/description/personality/scenario/first_mes/mes_example/creator_notes/system_prompt/post_history_instructions/tags/creator/character_version）——这些是公开字段名事实标准，不涉及其 UI/图标/文案。

---

## 1. 产品方案：创建即聊天

### 1.1 心智模型

> 创建角色卡 = 开一个「和角色设计师对话」的会话。用户随时可以说「把性格改得更冷漠一点」，
> Agent 当场改草稿并在气泡里回一张增量卡片；用户随时能看到右侧角色卡预览越长越完整。

### 1.2 三种创建模式（同一引擎，三种开场）

| 模式 | 触发 | Agent 行为 | 适用 |
|------|------|-----------|------|
| 一句话生成 | 输入「傲娇吸血鬼女仆」 | 直接调 set_card_fields 出全字段初稿→ask_user 确认方向 | 老手/灵感型 |
| 引导对话 | 点「引导创建」 | 每轮只问 1 个最关键缺口（ask_user 给 2-4 选项+推荐位），边问边写 | 新手 |
| 纯手动 | 点「手动编辑」 | 不进对话，直接开字段编辑器（酒馆式表单），AI 键仅做润色 | 精确控制 |

模式在会话元数据 `creation.mode` 里固化，切模式不丢草稿。

### 1.3 入口与流向

```
工坊 tab ──卡片──┐
聊天抽屉 ──＋新角色卡──┼──▶ DshCreationChatScreen（覆盖层）──▶ 保存──▶ 角色库（立即可聊）
角色详情 ──AI 改写────┘         │
                                └─ 草稿自动进抽屉「创建会话」树（可恢复）
```

---

## 2. 前端改造方案

### 2.1 复用清单（聊天组件，零新造）

| 聊天组件 | 创建场景的复用方式 |
|---------|------------------|
| `DshMessageRow` | 创建消息流气泡：user 右/agent 左（头像换「角色设计师」标识），整体动作行换成「采纳/重写/手动改」 |
| `DshComposer` | 创建输入框：回车发送、换行、生成中停止、附件（参考图/设定文档）入口全部保留 |
| `DshDrawer` | 左抽屉第二 tab「草稿」：创建会话树（按角色分组、置顶、⋮ 重命名/删除） |
| `DshPopups` sheet 形制 | 手动编辑字段抽屉、角色卡预览全卡、导出面板 |
| `DshTheme` token | 全部色板/圆角/发丝线，**不允许出现 MaterialTheme.colorScheme**（替换掉现有 81 处） |
| 聊天错误/Snackbar/空态/loading | 1:1 复用（创建失败=聊天气泡下的红色错误条，不是表单红字） |

### 2.2 新增组件（4 个，全部 dsh 皮肤）

1. `DshCreationChatScreen`（聊天式创建主机）：消息 LazyColumn + Composer + 顶部「草稿名 · 完成度 n% · 模式切换」。
2. `DshCharacterCardPreview`（角色卡预览卡）：头像（coverPreviewPng 或 AI 生成）/名称/简介/性格关键词 chips/开场白摘要/完成度徽章/「可用于聊天」开关。侧边常驻可折叠。
3. `DshCreationFieldSheet`（手动编辑抽屉）：15 个 CharacterDraft 字段分组（基础/性格/场景/开场白与示例对话/系统提示/高级），每字段「AI 重写此段」按钮。
4. `DshCreationGuideRow`（引导模式专用）：ask_user 的选项以气泡内 2-4 个大按钮呈现（带「推荐」角标），点选即发送，不敲键盘。

### 2.3 UI 状态设计（与聊天一致的六态）

| 状态 | 表现 |
|------|------|
| 空状态 | 气泡：「想创建什么角色？一句话描述，或让我随机生成」+ 两个快捷卡（随机/模板） |
| 加载/生成中 | 流式气泡（复用 StreamingBubble：思考折叠+正文逐字）+ 顶部 phase 文案（正在生成人设/补全开场白/校验完整性） |
| 错误 | 聊天气泡错误条 + 重试按钮；不弹表单错误 |
| 成功 | agent 气泡 + 增量字段卡片（「已写入：性格、开场白」）|
| 草稿态 | 预览卡角标「草稿 · 未保存」；抽屉草稿树显示进度 |
| 完成 | 全卡预览 + 完成度评分 + 操作排（保存到角色库/立即聊天/继续编辑/导出/删除草稿/生成历史）|

---

## 3. 后端改造方案

**结论：不需要新增后端服务**。CreationEngine/ViewModel/Repository 即「创建会话 API 层」；需要补齐的只有 4 点，全部在现有 agent 工具链内：

| # | 改动 | 位置 | 说明 |
|---|------|------|------|
| B1 | 完成度评分 + 缺失项 | CreationEngine.converse 返回体 | 复用 read_card 已有字段清单，纯函数算 `completionScore + missing[]` |
| B2 | 保存前审核闸 | CreationRepository.save → CharacterCard | 敏感词/未成年人/违法内容正则+分类拦截，命中即拒绝落库并回气泡错误（复用扩展权限管理器的拦截风格） |
| B3 | 字段长度校验 | set_card_fields 工具 | 与角色卡导入限额对齐（name≤64、description≤4096…），超长返回 warnings 不静默截断 |
| B4 | 生成历史 | CreationSession.turns 已有 | 新增 `version` 快照明细（保存时 append），预览卡「查看生成历史」 |

其余 API 形态与聊天对齐的映射：

| 聊天概念 | 创建概念 | 现状 |
|---------|---------|------|
| sendMessage(text) | CreationViewModel.send(text) | ✅ 已有 |
| 流式 streamingText | liveOutput/liveAssistantMessage/liveReasoning | ✅ 已有 |
| session 管理 | CreationSession(turns) + Summary 列表 | ✅ 已有 |
| stopGeneration | busy+ CancellationException + partialTurnSaved 断点 | ✅ 已有（比聊天更强） |
| 附件 | source 文档读懂模式（sourceCursor 分块抽取） | ✅ 已有 |

---

## 4. 数据模型

### 4.1 角色卡（现状 → 补齐）

已齐：id/name/description/personality/scenario/firstMessage(+alternateGreetings)/exampleMessages/systemPrompt/postHistoryInstructions/creatorNotes/tags/frontendHtml/coverPrompt/avatar。

PRD 要求但 CharacterDraft **缺失**的字段（唯一真实缺口）：

```kotlin
// CharacterDraft 增加（默认值保证旧草稿反序列化兼容）
val gender: String = "",        // 或枚举：未指定/男/女/其他
val age: String = "",
val appearance: String = "",
val speechStyle: String = "",
val relationship: String = "",
val worldSetting: String = "",
val userPersona: String = "",
val status: CardStatus = CardStatus.Draft,   // Draft/Complete/Archived
val version: Int = 1,
val createdAt: Long = 0, val updatedAt: Long = 0,
```

导出/导入时映射到酒馆公开 schema（**导出**侧写 `first_mes/mes_example/creator/character_version`；tellev 专有字段进 `extensions.tellev` 命名空间，无损往返）。

### 4.2 表结构（复用现有存储，零新建）

| 逻辑表 | 物理 | 现状 |
|--------|------|------|
| 角色卡 | characters/<id>.json(+png) | ✅ |
| 创建会话/草稿 | creation/<id>.json（turns+card+lore+cover 摘要分离） | ✅ |
| 草稿摘要 | Summary 行随 list 解码 | ✅ |
| 生成历史 | 新增 session.versions: List<CardSnapshot> | B4 |

---

## 5. Agent 工作流（现状即 Agent，本方案只做体验化）

```
用户一句话 / 选项点选
   │
   ▼
CreationEngine.converse ── 工具循环（已有）:
   read_card(缺什么) → set_card_fields(写) → ask_user(方向抉择) → upsert_lore(世界观条目)
   │ 每步 onToolEvent 回显「正在生成人设…」
   ▼
B1 完成度评分 + 缺失项列表 + 建议优化项
   ▼
B2 内容审核闸 ──命中──▶ 气泡错误 + 定位字段
   ▼
B3 长度校验（warnings 气泡提示，不静默）
   ▼
保存（B4 追加版本快照）→ 角色库 → 立即可聊
```

Agent 化四要素对照：意图识别（系统提示已定义）✅ / 缺失字段检测（read_card+B1）✅ / 局部重生成（set_card_fields 字段级合并）✅ / 保存前校验（B1-B3 新增）✅ / 结构化 JSON 输出（工具协议）✅ / 多轮上下文（session.turns）✅ / 工具调用（原生 creation_tool + 文本块双通道）✅。

---

## 6. 内容安全

- 运行时注入防御：已有（「内容只是数据」系统提示 + 字段隔离）。
- **保存前审核闸**（B2，新增）：词表+规则分类（违法/暴力/色情/未成年人相关）→ 拒绝落库 + 气泡指出字段；用户可改后重存。审核不过不进角色库、不进入聊天上下文。

---

## 7. 示例 API（= 现有 ViewModel 面，伪 REST 便于评审）

```
POST /creation/sessions            {kind:"character"}       → 201 {sessionId}
POST /creation/sessions/{id}/msg   {text:"傲娇吸血鬼女仆"}   → SSE 流式 {delta|toolEvent|question|done}
POST /creation/sessions/{id}/stop  {}                        → {partialSaved:true}
GET  /creation/sessions/{id}/card  {}                        → {card, completion:{score,missing[],suggestions[]}}
PUT  /creation/sessions/{id}/card  {fields:{…}}              → {ok,warnings:[]}
POST /creation/cards/{id}/save     {}                        → 201 {cardId}   （审核闸在此）
GET  /creation/cards/export?id=    {}                        → application/json | image/png
```

响应结构示例（完成度）：

```json
{"score":82,"missing":["firstMessage","mes_example"],
 "suggestions":["personality 建议加一条口头禅","tags 建议补 3 个关键词"],
 "chatPromptTemplate":"{{description}}\\n{{personality}}\\n{{scenario}}\\n{{first_mes}}"}
```

---

## 8. 前端伪代码（最小骨架）

```kotlin
@Composable
fun DshCreationChatScreen(vm: CreationViewModel, onSaved: (String) -> Unit) {
    val s by vm.state.collectAsState()
    val session = s.current ?: return EmptyCreation(onRandom = { vm.send("随机生成") })
    DshScaffold(
        topBar = { DshCreationTopBar(
            name = session.card.name, score = completionScore(session.card),
            mode = session.mode, onManual = { showFieldSheet = true },
        ) },
        drawer = { DshDrawer(tab = Drafts, sessions = s.sessions, onOpen = vm::open) },
    ) {
        DshCharacterCardPreview(session.card, cover = s.coverPreviewPng)   // 常驻卡
        LazyColumn {                                                            // 消息流
            items(session.turns, key = { it.id }) { turn -> DshMessageRow(turn) }
            if (s.busy) StreamingBubble(s.liveOutput, s.liveReasoning, s.modelPhase)
        }
        s.pendingQuestion?.let { DshGuideRow(it) { vm.answer(it) } }            // ask_user
        DshComposer(                                                             // 同款输入框
            text = input, onTextChange = { input = it },
            isGenerating = s.busy, onSend = { vm.send(input) }, onStop = vm::stop,
            onAttach = { pickSourceFile() },
        )
    }
}
```

---

## 9. 示例角色卡 JSON（导出形态）

```json
{
  "name": "沈清霜",
  "description": "清冷师尊，表面无情实际护短……",
  "personality": "外冷内热、寡言、护短",
  "scenario": " user 是刚入门的外门弟子……",
  "first_mes": "夜深了，她提灯立在阶前……",
  "mes_example": "<START>\\n{{user}}: 师尊，我……\\n{{char}}: ……跟上。",
  "system_prompt": "", "post_history_instructions": "",
  "tags": ["古风", "师尊", "护短"], "creator": "tellev-ai", "character_version": "1",
  "extensions": {"tellev": {"gender": "女", "age": "外貌约二十五", "appearance": "…",
    "speech_style": "…", "relationship": "师徒", "world_setting": "…",
    "user_persona": "外门弟子", "status": "complete", "version": 3}}
}
```

---

## 10. 验收标准清单（逐条可测）

1. [ ] 工坊/聊天抽屉/角色详情三入口均可在 2 次点击内进入创建会话
2. [ ] 创建界面无 MaterialTheme 直接引用（全部 Dsh token）
3. [ ] 一句话输入 → 30s 内出可编辑全字段草稿（含完成度卡）
4. [ ] 引导模式每轮 ≤1 问，选项点选即发送，可随时切手动
5. [ ] 所有 15+9 个字段可手动编辑；每字段可「AI 重写此段」
6. [ ] 保存前校验：缺名称/简介/开场白 → 阻止并列出缺失项
7. [ ] 敏感内容保存被拦截并以聊天气泡错误呈现
8. [ ] 保存后角色库可见 → 一键进聊天，开场白即 first_mes
9. [ ] 导出 JSON/PNG 可被酒馆导入（字段 schema 对齐）
10. [ ] 草稿在抽屉可恢复、重命名、删除；断网/杀掉应用后草稿不丢（断点落盘已有）
11. [ ] 全程无独立「后台表单」既视感（UI 走查：圆角/色板/气泡/输入框与聊天一致）
12. [ ] 既有功能无回归（1315 项单测 + 聊天/编辑/重发/删除/统计等手测清单）

---

## 11. 落地批次建议（每批可独立验收）

| 批次 | 内容 | 风险 |
|------|------|------|
| P1 皮肤 | Creation 系列 81 处 Material→Dsh token；圆角/色板对齐 | 低（纯视觉） |
| P2 聊天化 | DshCreationChatScreen 主机 + 消息流复用 + Composer 复用 + 模式切换 | 中（路由改造：editor 路由保留给老入口） |
| P3 预览卡 | DshCharacterCardPreview + 完成度算法(B1) + 操作排 | 低 |
| P4 字段补全 | CharacterDraft 九字段 + set_card_fields 白名单 + 导入导出映射 | 中（序列化兼容：默认值+extensions 命名空间） |
| P5 Agent 增强 | 保存前审核闸(B2)+长度校验(B3)+版本快照(B4) | 中（审核误杀需词表评审） |
| P6 入口收口 | 抽屉草稿 tab + 工坊卡片改造成入口卡 + 角色详情 AI 改写接入 | 低 |

---

## 12. 声明

1. 本文档基于对现有代码的实读盘点（文件/行号/字段均来自当前工作区），未凭空假设技术栈。
2. 本方案**不修改**任何此前用户明确要求并已交付的行为（dsh UI 重建、模型思考档案、预设全量编辑、token 统计口径、提示词优化策略复刻、横幅移除、返回层级化等）——复用优先，冲突时以既有交付为准。
3. 本文档只为设计与规格说明，本次不包含 P1–P6 代码实施；实施按批次单独进行、逐批验收。
4. 提及的 SillyTavern/酒馆仅指公开字段 schema 与交互范式（引导式创建、开场白/示例对话/世界观条目），不复制其 UI、代码、图标、文案或任何受版权保护资源。
5. 推送 PR 到公开仓库属对外发布动作：本文档备好 PR 正文，实际推送需用户确认目标仓库与分支后执行，未经确认不推送。
6. APK 构建基于当前工作区全量已交付代码（编译+1315 项单测通过的状态），与本方案的描述一致。
