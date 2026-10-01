# 角色卡与酒馆处理链修复

日期：2026-09-30。工作区：官方 `master`，基线 `b14876d9ce2d937b19cfcbd699f3f6e3377c1f74`。保留本轮开始前的 11 个修改文件和原有 investigations 文件；安装包包含当前工作区的这些已有改动。

## 结果

「乡村神医在都市·执笔人」真实开场白已作为回归样本验证：8 项 setvar/getvar 在保存消息前展开，保存后的消息没有这些原始命令，章号为第 2295 章；聊天变量持久化为 8 项。原 PNG 未修改，SHA-256 仍为 `A19D8C4993D7D531BED6890B0FF16DE02A2B372EA0620EE3117249AE01F4A59F`。

| 环节 | 修复 |
| --- | --- |
| 开场白 | 普通正则后展开宏并保存；只处理选中的备用开场白，按 swipe 记录版本和内容哈希；旧会话升级保留已推进的本地变量，导入首楼及脚本修改首楼也可处理。 |
| 提示词历史 | 第 0 楼的处理结果在模板和模型投影中共享；保留酒馆生成入口对第 0 楼的特殊展开，其余历史正文不全文重放宏。 |
| 用户输入与编辑 | 普通正则→宏→消息与本地变量一起保存；用户编辑交给发送路径处理一次。普通 AI 正文不额外全文展开，普通正则生成的替换片段仍展开。 |
| 正则宏与顺序 | 接入显式会话宏上下文，查找/trim/替换可读变量；捕获→trim→宏；规则顺序改为全局→预设→角色卡。 |
| 世界书 | 解析条目 scan_depth，按条目深度扫描，包含待发送楼层，接入递归和组评分；保留导入导出及显式 null 继承语义。 |
| 旧式正则 API | 归一化 scope，验证 enable_state 并筛选；不支持的 global/all/preset 明确拒绝，避免静默写进当前角色卡。角色卡作用域和原有字符串别名保留。 |
| 显示格式化 API | 校验楼层及 last/last_user/last_char，按角色和深度运行显示正则，转换 Markdown、格式化引号、清理 HTML、代码高亮，返回同步 HTML 字符串。 |
| 正则边界 | 缺失捕获组为空，保留锁定上游的字面 `$&` 行为；Unicode `\u{...}` 在 u 模式下转为 JVM/ICU 支持的写法；宏值中的美元符号按字面处理。 |

显示后台任务使用所属会话的变量快照，缓存键包含宏上下文。显示正则中的写变量宏只影响本次隔离计算，未把后台显示副作用自动持久化。世界书 sticky/cooldown/delay、全部酒馆设置和所有助手 API 不在本次完整验收范围内。

## 上游对照与审计校正

- SillyTavern：`51ad27fb86d39a3daca3adaa970375c9670c12df`；主要对照 `public/script.js` 的 getFirstMessage、sendMessageAsUser、messageFormatting、生成历史入口；`public/scripts/extensions/regex/engine.js` 的 SCRIPT_TYPES、runRegexScript/filterString；`utils.js` 的 regexFromString；`world-info.js` 的 WorldInfoBuffer.get。
- 酒馆助手：`ef0468636011e810efd12ab9286b4d74cc656aa8`；对照 `src/function/tavern_regex.ts`、`displayed_message.ts` 和 `util/is_frontend.ts` 的调用形状。
- Markdown 转换选项取自酒馆 reloadMarkdownProcessor；DOMPurify 3.4.2 和 highlight.js 11.11.1 使用该锁定仓库 package-lock 的版本，并纳入离线资源哈希与许可文件。

此前隔离 messageFormatting 的复现不足以证明“原始开场白重置变量”为 Tellev 独有行为。补充执行上游 getCharacterCardFields 后，确认这个版本也会在字段读取时展开原始开场白、重放赋值。该归因已在原审计中校正。Tellev 本次按需展开卡开场白字段，避免无条件重置会话；显式 greeting 宏仍取卡的主开场白。

另确认生成入口会对原始第 0 楼执行 substituteParams，即使它是用户楼层。本补丁和回归测试保留了这个特例，没有把它误归为所有用户历史都应再次展开。

## 验证

- Gradle `testDebugUnitTest`：977 项，0 failure / 0 error。新增 14 个直接兼容回归测试和 1 个实际 ViewModel→发送协调器→JSONL 保存回归测试。
- 生产 JavaScript 桥接与现有 MVU 回归：33/33。
- 本地锁定酒馆源码的隔离执行断言：21/21；不等同于完整浏览器/模型调用验收。
- `lintDebug`、签名 Release 构建、`git diff --check` 通过。
- APK：`app.tellev`，版本 `1.7.0.3`，versionCode 40，v2 签名通过；ZIP 中没有本地 MNN 核心或 ldcvt 资产，manifest 未见 Local Dream/MNN 项；7 项兼容资产哈希匹配。

安装包：`C:/Users/nac/Documents/ttolk/_buildlogs/card-macros-20260930/tellev-1.7.0.3-compat-fix.apk`。

SHA-256：`BA7091FF6E2FA647266AD9550A28180328F577350D4995A37D5B3E5CF21656A6`。

证据日志位于 `C:/Users/nac/Documents/ttolk/_buildlogs/card-macros-20260930/`：validation-fix.log、repack-fix.log、bridge-all-fix.log、upstream-results.jsonl、apk-signature.txt、apk-manifest.txt。未操作手机，未进行真实 Android UI/ICU/provider 验收，未提交、推送或发布发行版。

原卡中 `(?m)^◆$` 等正则写法及以后直接输出变量命令的使用方式仍应按酒馆规则评估；本补丁不改原卡，也不把每条普通 AI 回复改为自动全文执行宏。
