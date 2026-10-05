# Tellev 功能路线图（2026 Q4）

状态总览：30 项功能需求分四个批次实施。批次 1 已完成；批次 2-4 待启动。
每批交付：全量单测 + lint + debug/mini APK（D 盘）+ 功能文档章节。

## 批次 1 · 聊天/记忆可观测性 + 脚本权限与诊断 ✅ 已完成

详见 `docs/FEATURES_2026_10_PROMPT_REASONING_CREATION.md` 第 7 节。

| 需求 | 落点 |
|---|---|
| 当前上下文查看器 | 聊天「更多」菜单 → 上下文查看器（最新一轮构建结果，内存态） |
| 世界书命中条目查看 | `WorldInfoScanner.ScanDiagnostics`（命中关键词/递归层级/token）+ 落选原因六类 |
| 长期记忆来源查看 | `MemoryService.contextDetail`（评分 + 来源消息 id）+ 查看器记忆分区 + 跳转楼层 |
| 记忆纠正和回滚 | `MemoryRecord.history`（上限 5）+ `rollbackCorrection` + 管理对话框「历史版本」 |
| Prompt 诊断导出 | 查看器内「导出 JSON」（SAF，含完整 prompt + 命中/落选 + 记忆溯源） |
| 脚本权限清单 | 扩展调试面板「权限清单」（声明 vs 授予，可吊销/重授） |
| 当前脚本访问了哪些 API | JS shim 统一包装 tellevNative + `ExtensionStatsCollector`（每扩展每 API 计数） |
| 脚本失败堆栈 | `ExtensionDiagnostics.recentErrors`（消息+堆栈+次数去重环，50 条）+ 调试面板错误分区 |
| 一键禁用单个脚本 | `CharacterTavernHelperScripts.withScriptEnabledAt`（按 raw 树路径无损写回）+ 聊天「脚本管理」 |
| 脚本执行耗时统计 | 桥方法 JS 侧计时 + `apiCall` 处理器 Kotlin 侧计时（次数/平均/最大/错误） |

附带成果：i18n 管线修复——194 个历史手写 key 回填 TSV、ui_atmosphere.xml 迁移，
`tools/i18n_consolidate.py` 恢复一键生成（本批起全部文案走 TSV 流程）。

## 批次 2 · Provider 管理增强（待启动）

现状：连接测试已有（checkStatus + listModels，设置页按钮）；`TellevError.retryable`
已定义但无消费者；无任何统计/健康/fallback 基建。

| 需求 | 方案摘要 |
|---|---|
| 连接测试（增强） | 测试结果显示延迟（checkStatus 计时）；失败时给出分类提示（DNS/鉴权/模型不存在） |
| Provider 健康状态 | 每 Provider 请求结果环形记录（内存 + JSON 持久化 `provider-health.json`），设置页状态卡（近 20 次成功/失败、最近错误） |
| 自动 fallback | 生成协调器消费 `retryable`：可重试错误原地重试一次 → 切换会话级「备用 Provider」（设置页配置链）；fallback 触发在 UI 显式标注 |
| 单 Provider 统计 | 请求数/失败数/延迟分布/token 用量（复用 usage 诊断），按 Provider 聚合持久化 |
| 流式响应诊断 | `providerDiagnostics`（已存在，opt-in）改为错误自动捕获 + 设置页开关全量捕获；调试面板新增流式诊断查看器（帧数/解析失败/响应样本） |

关键文件：`core/provider/ProviderAdapter.kt`、`feature/chat/ChatGenerationCoordinator.kt`
（GenerateChunk.Failed 处理点）、`feature/settings/SettingsProviderSection.kt`、`ProviderSettingsController.kt`。

## 批次 3 · 数据可靠性（待启动）

现状：全量 zip 备份/导入已有（BackupCoordinator）；日志原子写 + 损坏隔离已有
（chats/_corrupt、memory/*.corrupt-*）；backups/ 目录闲置；无版本历史/增量导出。

| 需求 | 方案摘要 |
|---|---|
| 角色卡版本历史 | saveCharacter/importCharacter 时快照到 `versions/characters/<id>/<ts>.json`（保留 N 份），角色详情页「历史」查看/恢复 |
| 世界书自动备份 | saveWorldBook 防抖写 `backups/worlds/<id>.json`（单份覆盖）+ 每日滚动一份，数量上限 |
| JSON schema 检查 | 卡片/世界书/预设严格校验器（字段类型/必填/超长），导入时报告 + 设置页「数据体检」入口 |
| 损坏数据恢复 | 扫描 `_corrupt`/`*.corrupt-*`/静默回退的设置文件，恢复 UI（列出、导出、删除、重导入） |
| 全量导出和增量导出 | BackupCoordinator 扩展：选择性导出（仅角色/世界/会话）+ 增量（manifest 含 SHA-256 清单，仅导出自上次变更项）+ 会话单文件 JSONL 导出 |

## 批次 4 · 本地模型体验 + 扩展开发工具（待启动）

现状：Ollama/ComfyUI/SD 适配器齐全但只有通用表单；CleartextGuard 全局默认拒绝非回环
http（有开关但无风险提示）；无 LAN 探测；无脚本控制台/mock/测试卡。

| 需求 | 方案摘要 |
|---|---|
| 本地配置向导 | Ollama/ComfyUI/SD 三向导：默认地址 → 连接测试 → 拉模型列表 → 保存（复用 ImageGenSettingsController.testComfyConnection 等） |
| 局域网 Provider 探测 | 扫描本机网段常见端口（11434/8188/7860/5000/5001/8080），命中即列卡片一键填入；需先开启明文开关，扫描前弹风险确认 |
| 本地 HTTP 风险提示 | 添加 http:// 非回环 Provider 时显示风险横幅（明文传输/局域网暴露），指引到明文开关 |
| 连接延迟和能力探测 | 测试结果附延迟；模型列表展示适配器声明能力（真实 per-model 探测仅对 Ollama /api/tags 扩展字段做，其余标注"未探测"） |
| 内置脚本控制台 | 调试模式白名单：对已装载运行时 WebView 暴露 evaluate 通道 + 输入 UI（风险自担开关） |
| API mock | VirtualApiRouter 路由级 mock：调试面板为单个路由设置固定响应/延迟 |
| 扩展加载日志 | 环形缓冲持久化到 `diagnostics/extensions.log`（滚动），调试面板可导出 |
| WebView 崩溃诊断 | onRenderProcessGone 已捕获 → 用户可见横幅 + 一键重载 + 崩溃计数进调试面板 |
| 兼容性测试卡片 | 内置最小夹具卡（覆盖 MVU/TavernHelper/正则/变量 API 子集）+「运行测试」按钮：装载→断言 API 可达→输出报告 |

## 依赖与约束备忘

- i18n：全部走 TSV + `tools/i18n_consolidate.py`（已修复）；新模块 TSV 归属见前缀映射（tools/i18n_backfill.py）。
- 存储写入一律 JournaledFileWriter / DurableFileOps；新目录先加 StDirectoryLayout。
- 生成主链路改动（批次 2 fallback）必须由 GenerationRuntimeResolverTest + 新增协调器测试守护。
- CleartextGuard 默认拒绝非回环 http 是安全边界，批次 4 的探测/向导不得绕过它，只能引导用户显式开启。
