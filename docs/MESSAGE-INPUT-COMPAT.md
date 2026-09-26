# 消息前端输入框兼容：`#send_textarea`

## 问题定位

预设与角色卡的“可点击选项”界面（如梦鲸思客 `[🦋美化]思客大调查`）靠**直接写 SillyTavern
聊天输入框**回填文本：`document.querySelector('#send_textarea').value = …`，再派发 `input`
事件；ST 的输入框就是页面里的真实元素，所以点选项即入草稿框，用户再按发送。

Tellev 每条消息渲染在**独立 WebView** 里，那个文档里没有 `#send_textarea`：

- 前端脚本本身照常执行、按钮照常画出来（截图里卡片、序号、自定义回答都在），
- 但 `findInput()` 找不到输入框，点击静默失败，只在控制台留一条
  `未找到 SillyTavern 输入框`。

这不是某张卡的写法问题，而是 ST 消息前端的一条通用契约在 Tellev 缺失。

## 已实现

1. **消息级兼容脚本迁到资产**：原先内联在 `TavernMessageCompat.kt` 的
   `tavernMessageCompatScript()` 挪到 `app/src/main/assets/compat/message-host.js`，
   由 `wrapTavernHtml` 以 `<script src>` 注入（顺序：globals.js → message-host.js →
   chat.js → message.js），并在 `CompatAssets` 注册别名。搬家的目的是可用 Node/jsdom
   真跑一遍（见下），而不是只做字符串断言。
2. **虚拟 `#send_textarea`**：消息文档里装一个真实 `<textarea id="send_textarea">`，
   但 `value` 是应用输入框的视图：
   - 读 → `TellevMessage.getInput()`（同步桥；草稿在 Compose 里，因此桥上做一次主线程
     跳转，JS 线程等待，超时 500ms 时退回本页影子值），
   - 写 → `TellevMessage.request('setInput', …)` → `ChatTavernAdapter` 的 `setInput`
     操作 → 聊天页 `onSetInput`（App 输入框仍是主线程 Compose 状态，所以写必须走这条
     异步通道；主线程 Looper 的 FIFO 保证“先写后读”顺序，多次点选才能累加）。
   - 元素用 `position:fixed` 藏在视口外，不参与消息高度测量；`focus()`/`blur()` 被屏蔽，
     否则会给这条消息的 WebView（而不是聊天输入框）弹软键盘。
3. **jQuery 垫片补全**：原 `$` 只支持 `text`/`html`/`css`，且传元素参数时匹配不到任何节点。
   现在支持选择器/元素/NodeList/数组、`val`/`trigger`/`on`/`off`/`each`/`length`/
   `attr`/`prop`/`addClass`/`find`/`closest` 等常用子集，`$(el).val(x).trigger('input')`
   这类写法可用（卡自带 jQuery 时以卡为准）。
4. **`getContext().executeSlashCommands`**：ST 的上下文对象同时挂着命令执行器，前端常用
   `getContext().executeSlashCommands('/setinput …')`；Tellev 的快照现在也带上该方法，
   转发到既有的 `triggerSlash` 通道。

## 明确不支持

- `#send_but`（自动点击发送）：不模拟。选项入草稿后由用户按发送，避免前端静默替用户发消息。
- 扩展沙箱（角色卡“卡片界面” WebView）里的 `/setinput`：`SlashCommandBuiltins` 中仍是
  `stubOk`，会经 `onUnimplementedCommand` 报为未实现。走通它需要把扩展宿主接到聊天页草稿，
  属独立改动。

## 验证

```powershell
# 逻辑级（Node + jsdom，跑的是生产资产与梦鲸思客的真实规则 HTML）
node tools/message-host-eval/test.mjs

# 设备级（真机或模拟器；覆盖资产是否真送达面板 + 同步读回）
./gradlew :app:connectedMvuValidationAndroidTest --tests "app.tellev.TavernPanelInputShimTest"
```

jsdom 用例快照了预设 `[🦋美化]思客大调查` 规则（`tools/message-host-eval/fixtures/`）；
同级 `../梦鲸思客V4-0915.json` 存在时还会断言快照与线上预设一致，预设改动会直接报错。
摘掉垫片重跑可复现原始故障（正是截图里“点选项没反应”）。
