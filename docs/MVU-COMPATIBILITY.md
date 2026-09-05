# tellev MVU 兼容实施与验收记录

## v1.5.5 发布准备补充（2026-09-05）

用户已确认自行完成真机复验；未逐项提供设备、系统版本和用例范围，因此这项确认不扩展为所有下述接口差异均已解决。v1.5.5（22）的构建、单元测试、Node 集成测试、lint 与 APK 签名检查已通过，详见 [发布说明](RELEASE_1.5.5.md)。下文为此前阶段性实施与验收记录，其中旧 APK 版本、校验值及“未完成真机验收”等描述保留为历史状态。

状态：**本次补全计划尚未完成；当前是存储、竞态防护与独立对照工具的阶段性交付。** 不能据此宣称所有 MVU 或全部已暴露接口兼容。

仅修改 tellev。固定版本和已知导入映射保持原样；没有改角色卡业务字段、甲戌预设、裸露剧情模块标题处理、设备正式包模型配置或签名，也没有发布 GitHub Release。

## 固定基线与工具

- 修改前源码快照：忽略目录 `build/mvu-baseline/source.zip`，对应 HEAD `af4a1d0`，含既有工作区修改和校验清单。
- 上游版本与资源校验值：[manifest.json](../app/src/main/assets/compat/manifest.json)。MVU 源提交为 `61010dab47bc3a08a1b626320bf7fc8c9573eca4`，所取分发产物内嵌构建号为 `7fe9ae7`；二者不是同一概念。
- 依赖完整性见 `tools/mvu/package-lock.json`、`tools/mvu/vendor/SHA256.json`；许可证见 `tools/mvu/THIRD-PARTY-NOTICES.txt` 和 vendor 原文。Tavern Resource 使用 AFPL，不能统称 MIT。
- `prepare-oracle.py` 将固定酒馆、酒馆助手和提示词模板完整源码/分发目录提取到 `build/mvu-oracle`，独立上游运行时不载入 tellev 桥接代码。
- `replay-service.mjs` 提供仅回环地址的确定性 OpenAI 普通/SSE 回放，并记录实际请求文本；不转发模型服务，不记录认证请求头。
- 上游普通卡及《道渊》＋甲戌已经分别走通真实导入、世界书、扩展与普通/流式生成，各捕获两次最终 HTTP 请求。这只是上游回放链路可用，尚不是最终提示词一致性验收。
- 冻结接口面共 414 个入口（含别名），155 个疑似占位候选；369 个找到源码候选位置。[接口矩阵](MVU-API-MATRIX.md)及 `tools/mvu/contracts/api-contracts.json` 逐项保持待验收，静态扫描不等于完整签名/异常契约核对。

## 本次实现与现有证据

| 能力 | 已做的具体变更 | 证据及边界 |
| --- | --- | --- |
| 写入协调 | RuntimeToken、MutationRequest、CommitReceipt；内存即时接纳、所属对象串行落盘、修订号校验、重复请求识别、代次失效、flushWrites | RuntimeWriteCoordinatorTest；尚未把所有资源编辑入口统一到同一个协调器 |
| 聊天变更 | 用稳定消息 ID 和三方字段差异合并代替旧整份快照覆盖；无关变量/元数据保留；同字段冲突明确失败 | ChatSessionMutationTest；支持不同 swipe 槽位合并，业务数组仍整体冲突检查 |
| 文件耐久性 | JSON/JSONL 写入日志、校验、fsync、同文件系统原子替换、提交记录、首次原文件与上次版本保留；扩展设置也接入 | JournaledFileWriterTest、JournaledStorageTest、JournalCrashTest；二进制资源、全部删除/导入导出路径仍未统一 |
| 写入边界 | 原生编辑、删除、swipe 和中断同步提交入队；下一次生成/聊天切换等待写入；全局变量及扩展设置有保存屏障 | ChatWriteLifecycleTest、VariableStoreTest；完整生成事件顺序仍待上游对照 |
| 保存故障 | 保留内存脏状态，保存失败阻止依赖写入/切换，界面收到错误；损坏设置文件明确报告且保留文件 | 保存失败、全局变量并发、设置损坏测试；尚无完整用户可操作的冲突恢复界面 |
| 旧数据保真 | 消息稳定 ID 写入 `_tellev_message_id`；变量数组保留 null 和未知槽位；swipe_info、模板标记和未知字段保留；未修改的旧数字键变量对象保留原形 | FileStDataStoreTest；作用域归属迁移尚未实现，不猜测旧 compatVariables 的归属 |
| 消息批量 API | 按固定上游归并重复楼层、普通 data/message 分支忽略 swipe_id、swipe 数组填充/截断、extra 与 swipe_info 映射、隐藏与 narrator 区分 | 独立上游 10 个样例各重复 3 次；TavernChatMutationTest 比较完整消息对象（仅排除 app 私有稳定 ID），并重新读取落盘数据；刷新/事件契约尚未验收 |
| 前端失效防护 | 消息请求携带原会话代次；排队前捕获来源；失效调用拒绝；会话代次变化重建 WebView，释放时移除桥接并销毁 | TavernFrontendLifecycleTest：真实 Compose 聊天、HTML 按钮写变量、切换后旧请求拒绝、返回原会话数据保留；尚未实现父页面共享 JS 环境 |

独立上游变量基础对照 `replay-baseline.mjs` 重复三次稳定，但当前仍以失败退出：`invalid`、`replaceLatest`、`messages`、`swipes`、`hidden` 五组观察存在差异。根因包括越界读取错误行为、replaceVariables 对 options 的修改以及赋值对象引用语义；后面三组继承同一引用差异。没有通过归一化隐藏这些差异。

## 当前自动化与设备记录

- JVM：579 项、0 失败；Node：3 项通过；Android：6 项通过、2 个显式参数辅助测试跳过。lintDebug 通过。详细以 `app/build/test-results/testDebugUnitTest` 和 `app/build/reports/lint-results-debug.html` 的本次结果为准，汇总见 `build/mvu-oracle/results/verification.json`。
- Node：实际 MVU/Zod 集成、真实 EJS、回放服务测试通过。这些 MVU 测试运行在 tellev 适配环境内，不能冒充完整独立酒馆对照。
- Android 16：Lenovo TB375FC，设备 HA25GHH4。独立 `app.tellev.mvuvalidation` 覆盖安装成功。文件系统恢复、真实 MVU/Zod、EJS、大 HTML 正则和原卡开场回归通过；输入辅助测试未传入参数时跳过，不计为真实模型对话。
- 真实杀进程：PAYLOAD_SYNCED、PREPARED、REPLACED、COMMITTED 四处终止验证进程，另起进程恢复；全部通过，未重复提交。完整记录为 `build/mvu-oracle/results/android-storage-process-death.json`。这不是突然断电或物理磁盘满验收。
- 实际消息前端：TavernFrontendLifecycleTest 通过，验证按钮写入、旧会话请求拒绝、WebView 替换与变量恢复。
- 正式 `app.tellev` 未覆盖安装本次修改，现有用户会话与最新模型配置保留。阶段性发行版已构建并验证签名，尚未安装；三轮真实模型验收未完成。
- Android 12/API 31 真机或模拟器尚未运行；lint 已消除新增 API 34 专用调用，但 lint 不能替代最低版本运行测试。

## 阶段性 APK（不代表兼容计划验收通过）

- `app/build/outputs/apk/release/app-release.apk`：app.tellev，1.5.4 / 21，minSdk 31、targetSdk 36。
- SHA-256：`a1c80ba34f6f936eb61c55eee2fe9c89606ea63dc4be12b00c38596908a081c4`。
- apksigner 验证通过；证书 SHA-256：`aee2a2d53aeba1f89a50aa4ac52d517f4aa73f95335de1cc6672cf10d1d9f93f`。
- 仅本地构建，未覆盖正式包、未发布。验证包已经覆盖安装并用于上述自动化。

## 尚未达到验收条件的范围

1. `TavernSessionRuntime` 父 WebView＋脚本/消息 iframe 尚未落地。现有跨 WebView 复制事件对象、模块注入同名变量、脚本 data/文件夹/按钮/Schema/待处理调用的完整生命周期仍需重构。
2. character、preset、script、extension 的正式归属与唯一归属旧设置迁移没有完成；global/chat/message 的后台、前端、模板全部同步/异步契约仍需统一。所有资源保存入口尚未纳入同一个协调器。
3. 当前 MVU 收包完成判断仍含正文长度、stat_data 和 15 秒等待，原生初始化旧分支仍存在；真实事件结束/写入屏障替代、慢监听器/异常/嵌套事件/中断/重算语义未全部完成。
4. 414 个已公开入口的完整行为、异常、同步返回与 `_bind` 契约尚未全部实现和验收，155 个静态占位候选待逐一处理；不会将改成抛错计为实现完成。
5. EJS 的完整环境、SPreset、世界书嵌套、动态注入、宏、正则、最终 HTTP 提示词顺序未完成独立差异验收。正则仍有 Android 原生处理路径，可终止 JS RegExp 工作线程尚未实现。
6. 独立回放未覆盖全量 MVU 命令/Schema/路径错误矩阵；最终提示词尚未固定时间、随机数与 ID 后各运行三次比较。
7. 全部生命周期、API31、100/500/1000 楼性能、长期重载内存、Sakura、完整导出再导入、存储各对象物理失败矩阵、三轮真模型对话＋重生成/swipe/重启尚未验收。
8. 日志提交记录尚未做容量回收；多存储实例的跨进程锁及所有存储对象的统一事务仍待补齐。保留日志不能代替完整数据迁移和恢复说明/工具。

这些是当前源码可见或缺少证据的缺口，不是已通过项目。本计划完成前仍应继续处理，不得用目标卡的成功样例代替通用范围验收。

## 恢复与保留规则

聊天/普通 JSON 的日志在对应数据根的 `.tellev-writes`；扩展设置有自己的日志根。`.pending` 描述目标、原值校验、负载和操作收据；`.payload` 保存要写入的字节；`.state` 与 `.commits` 记录修订和去重；`.original` 首次原文件不覆盖，`.previous` 为最近前值。

bootstrap 在暴露文件之前恢复已记录的状态操作，不重放监听器、脚本或模型请求。校验不匹配、外部改写或不可原子替换会报告错误并停在冲突处，不自动选择覆盖哪一份。备份/故障取证应保留原数据与对应日志一起处理；目前尚无已验收的一键冲突恢复或旧 scope 自动迁移工具。

Windows JVM 测试证明进程失败恢复，Windows Java 文件系统不支持目录 fsync；Android 测试另外验证了目录同步与原子替换。进程终止结果不扩展为突然断电保证。

## 复现

详见 [tools/mvu/README.md](../tools/mvu/README.md)。正式包安装与发布不属于回放脚本动作。模型密钥不写入回放输入、测试源码或报告。

## 此前设备验收（本次改动前的历史记录）

以下仅保留前一版本证据，不为本次修改背书。

## Device dialogue acceptance, 2026-09-05

The original provider request reached the configured service but was rejected for insufficient quota. The user then changed the model configuration on-device. The existing configuration was retained.

Using greeting 3/3 (test-card opening), the user-specified Tushan Susu opening was entered verbatim and sent. The model completed a Chinese opening response and an MVU update. The real rendered panel showed: Earth-grade spiritual root; cultivation 0/100; lost cultivation and inability to channel spiritual power. Clicking the techniques tab displayed Wanshui Jue and its description; returning to overview succeeded. This verifies one real opening and a status-panel interaction, not the full lifecycle matrix. The model's narrative introduced Qingqiu in one line despite the supplied Tushan origin; prompt adherence should not be conflated with bridge correctness.

Android integration: four tests passed in 4.895 seconds (MVU, EJS, large HTML regex, actual three greetings with preset). Exact Chinese UI entry was verified separately through Accessibility ACTION_SET_TEXT; this helper is skipped unless an explicit input argument is provided.
