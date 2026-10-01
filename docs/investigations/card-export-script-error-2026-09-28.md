# 角色卡导出后脚本报错：2026-09-28 排查记录

## 用户提供的事实

- 从角色列表长按角色卡，在菜单中选择“导出 JSON”。
- 随后反馈聊天记录未显示、多个角色卡出现“加载角色卡脚本失败：Script error”。
- 用户已确认角色名字变化来自预设，与本故障无关；本次没有修改人设或预设选择逻辑。
- 基于官方 `master` 的 `75d066a` 排查，版本保持 `1.7.0.3` / `40`。
- 第二轮补齐证据：故障预设 `Kemini_Aether-fr-4.3 (1).json`、故障卡 `室友的青梅是我的炮友.json`（其 `tavern_helper.scripts` 为空数组）。

## 已确认的代码路径

菜单回调设置待导出的角色 ID，通过 `CreateDocument(application/json)` 打开系统保存窗口；返回后调用 `CharactersViewModel.exportCharacterToJson`，只读角色卡，再将 JSON 写到选择器返回的 URI。

这条路径没有调用聊天记录删除、角色卡保存或角色切换。测试同时覆盖实际 ViewModel 方法和底层导出器，验证导出前后角色卡和聊天 JSONL 字节不变，重新打开存储仍能读取历史。

**导出本身只读不写，已排除为根因；它是让首次“加载-失败”竞态落到待决窗口内的触发时机。**

## 根因（第二轮已复现实锤）

角色卡脚本不只来自卡：`CharacterTavernHelperScripts.buildIsolatedScriptSource` 合并**预设**的
`tavern_helper.scripts`（该卡本身无脚本）。此预设带两个启用脚本：

1. `【SoliUmbra】SPreset`（701B）：模块求值期间向**宿主文档**注入跨域远端脚本
   `https://jnai2d9kgnbs6xzx5c.com/regex_bind/inject.js`（现仍在线，约 229KB）。
2. `aether`（303KB 伴生面板，本体无问题）。

inject.js 顶层执行 `for (const prompt of ctx.chatCompletionSettings.prompts)`，而
Tellev 上下文里 `chatCompletionSettings` 是空对象 → `TypeError: Cannot read properties of
undefined (reading 'prompts')`。跨域脚本的错误对 `window.onerror` 不透明，宿主模板的
`EXTENSION_LOAD_GUARDS` 把任何窗口错误 `extensionFailed(String(e.message))` 上报成
字面 `"Script error."`，恰好落在脚本加载待决窗口内 → 整个角色卡脚本扩展被判失败 →
“加载角色卡脚本失败：Script error.”。

因为预设脚本对**每张卡**都加载，所以所有卡都报错；导出往返触发一次重载后，远端脚本
（有 HTTP 缓存）稳定赶在 ready 之前抛错，故障由偶发转为持续。

复现命令（无头 Edge，需先导出模板与下载远端文件）：

```bash
./gradlew :app:testReleaseUnitTest --tests "app.tellev.core.extension.TavernChatMutationTest"   # 导出模板
curl -o app/build/spreset-inject.js  https://jnai2d9kgnbs6xzx5c.com/regex_bind/inject.js
curl -o app/build/spreset-bundled.html https://jnai2d9kgnbs6xzx5c.com/regex_bind/bundled.html
# resource map 见下；两个 URL 分别映射到上述两个文件
node tools/mvu/verify-card-load.mjs <卡.json> --preset=<预设.json> --resource-map=app/build/spreset-resource-map.json
```

修复前该命令输出 `"failure": "Script error."`；修复后 `ready: true`、`failure: null`、零浏览器错误。

## 修复（两轮合计，均未提交）

第一轮（会话中断前）：

1. 角色卡变更回调与会话切换串行化。旧代码中，旧卡的异步读取可以在切换到新卡后完成，把新会话的角色和脚本替换成旧卡。注入读取延迟的测试在旧代码上失败（期望 b，实际 a），修复后通过。
2. 脚本加载在等待就绪期间被取消时，以不可取消的清理段销毁其 WebView、撤销能力令牌、清除待处理请求和注册项。清理验证令牌归属，避免销毁同 ID 的新运行环境。
3. 卸载的撤权和清理与新运行环境的安装在同一主线程段完成；过期运行环境的 ready/failed 回调被忽略。
4. 取消不再显示为普通脚本加载错误。子脚本加载失败先上报带脚本名的原始异常，并保留第一个错误，避免后续泛化的 `Script error` 覆盖原因（`host.js` 在模块顶层 await 拒绝时抢先上报原始堆栈）。

第二轮（本轮，根因修复）：

5. `ExtensionScriptTemplate._getContext()` 规范化 ST 兼容形状：
   `chatCompletionSettings.prompts`（数组）、`chatCompletionSettings.prompt_order`
   （`[{character_id:100001,order:[]}]`，与 `PresetRepository` 既有约定一致）、
   `extensionSettings.regex`（数组）。`buildTavernContext` 同步补 `prompts/prompt_order`
   原始 JSON。这让 inject.js 的整条初始化链（RegexBinding、ChatSquash、工具注册、
   bundled.html 编辑器 iframe）无错跑通。
6. `EXTENSION_LOAD_GUARDS` 对**跨域不透明错误**（message 恰为 `Script error.` 且无
   filename、无 error 对象，含 unhandledrejection 等价形态）只记日志、不再
   `extensionFailed` 毒化待决加载：远端脚本自身的失败不应连累卡自带脚本的加载。
   同源错误仍照旧立即失败加载。

## 验证与边界

- 无头 Edge + 真实卡 + 真实预设 + 本地伺服的真实远端 inject.js/bundled.html：
  修复前 `failure: "Script error."`，修复后 ready、零失败、零浏览器错误。
- `tools/mvu` jsdom 套件 26/26 通过。
- `verify-character-iframe.mjs` 浏览器探针通过，新增回归断言：同源坏脚本仍带脚本名上报原始异常；SPreset 式注入的跨域抛错脚本不得毒化加载。
- `:app:testReleaseUnitTest` 全量通过；`:app:compileMvuValidationAndroidTestKotlin` 通过；`:app:lint` 通过。
- 未提交、推送或发布 Release；真机验收未做。
- “原本那张卡的历史消失”：当前代码里消息装载先于脚本加载、脚本失败仅置 `uiState.error`，不会清空消息；第一轮修复的选择竞态（旧卡读完成后顶掉新会话状态）是旧版上最可疑的解释。若故障机记录仍缺失，需要该机的聊天数据备份或 JSONL 才能判定是被删、被关联到别的卡、还是仅未显示。

## 遗留风险

- SPreset 的注入查重检查的是 iframe 自己的 document 而脚本追加进宿主 document，同一
  宿主文档内二次注入会因 `let SPresetSettings` 重声明抛 SyntaxError（跨域不透明）。修复 6
  已把这种情况降级为日志，不再失败加载。
- 远端域 `jnai2d9kgnbs6xzx5c.com` 是预设作者的分发通道，内容不受 Tellev 控制；CSP 仍
  允许 https 脚本加载，属于既定的酒馆生态兼容面。
