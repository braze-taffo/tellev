# 角色卡酒馆助手兼容：第 1～3 阶段

## 基线与问题定位

测试输入是用户提供的 `b0485e9f6444435193df0d397285830c.png`，SHA-256 为
`3103083e99b7c91d9fdc432fdc7724540f96b989d0f022e48e7a09846695f419`。
角色卡数据在 PNG 后部的 `chara`、`ccv3` 文本块中；只读取图片前部元数据会漏掉它。
它名为《玄浑纪》，有 7 段启用的酒馆助手脚本，依次为 mvu、变量结构、世界引擎、气血同步、战斗挂载、状态栏、玄浑播报。
附件原文不加入 Git；`card-inventory.mjs` 只输出脚本清单和所用接口，必要时可导出到忽略的 `app/build` 目录做本地回放。

截图里的 `getItem` 空值错误能在“战斗挂载”脚本中定位到
`localStorage.getItem('玄浑纪战斗.音效')`。原运行容器关闭了 Android WebView 的 DOM Storage，
该调用发生在模块初始化时。原容器还在单个顶层页面导入所有脚本；酒馆助手实际把每段脚本放在独立 iframe，
所以脚本对 `frameElement`、`window.parent.document` 和按脚本隔离的身份判断没有对应环境。

仓库同级的 `js-slash-runner` 是本地上游源码，当前 HEAD 为
`ef0468636011e810efd12ab9286b4d74cc656aa8`、版本 4.8.11，与
`app/src/main/assets/compat/manifest.json` 的固定提交一致。对照了它的
`src/panel/script/Iframe.vue`、`src/panel/script/iframe.ts` 与 `src/function/util.ts`，
使用其 `TH-script--<name>--<id>` iframe 命名与父页面关系作为本轮兼容基线。

## 已实现

1. 开启 DOM Storage，并以扩展 ID 的 SHA-256 派生独立 HTTPS 源，使角色脚本的 `localStorage` 可用且不同扩展互不共享。
2. 为每段脚本创建独立同源 iframe，在子窗口提供脚本 ID、名称、宿主 API、父页面的 jQuery，以及上游用于脚本选择的登记节点；按卡内顺序加载，并等待每段脚本的就绪处理。限制子 iframe 跳转，避免它带着原生桥访问远端页面。
3. 聊天页在卡片脚本加载成功后提供“卡片界面”入口，以全屏对话框挂载同一个运行时 WebView。关闭界面保留脚本状态；切换角色或卸载时移除并销毁旧 WebView。多脚本卡的初始化时限提高到 90 秒。

## 本地验证

```powershell
node tools/mvu/card-inventory.mjs C:\Users\nac\Downloads\b0485e9f6444435193df0d397285830c.png --extract-to app/build/mvu-fixtures/xuanhun.json
node tools/mvu/verify-character-iframe.mjs
node tools/mvu/verify-card-load.mjs app/build/mvu-fixtures/xuanhun.json 战斗挂载
node tools/mvu/verify-card-load.mjs app/build/mvu-fixtures/xuanhun.json --expect-mvu --expect-ui=jy-hud-frame
```

浏览器回放使用实际导出的宿主 HTML 和兼容资产；已验证“战斗挂载”不再因 `localStorage` 抛错，
七段脚本能够逐段载入，MVU 已初始化，并在宿主页面创建了状态栏 iframe。
2026-09-24 在 TB375FC（Android 16）上分别安装正式版和 MNN 版的独立验证包，
连续运行 `CharacterScriptWebViewTest`、`XuanhunCardAndroidTest` 和
`XuanhunChatUiAndroidTest`，两个渠道各 3 项通过。测试覆盖 Android WebView 的 iframe、
父页面、存储持久性与隔离、原卡 7 段脚本加载，以及聊天页全屏入口和状态栏实际绘制。
原卡尚未进入真实战斗流程；真实模型回复后的战斗交互、长期状态持久化仍需单独验证。
