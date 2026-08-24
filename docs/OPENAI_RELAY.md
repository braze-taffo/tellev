# 本地 OpenAI 调试中转

这个中转用于捕获并比较 Tellev 与 SillyTavern 实际发送给同一模型端点的请求。它透明转发 OpenAI 兼容的 HTTP/SSE 请求，保留完整请求正文和响应，同时不会把 `Authorization`、`X-API-Key` 等密钥头写入日志。

> 捕获文件包含完整角色卡提示词、聊天内容与模型回复，属于敏感数据。目录 `captures/` 已被 Git 忽略，不要把捕获文件公开上传。

## 1. 启动中转

在 Tellev 仓库根目录运行：

```powershell
$env:OPENAI_RELAY_UPSTREAM = "https://api.deepseek.com"
node tools/openai-relay.mjs
```

默认只监听电脑本机的 `127.0.0.1:8787`，不会暴露到局域网或公网。API Key 不需要传给脚本；客户端原有的认证头会被透明转发。

## 2. 让 Android 手机通过局域网访问

先用 `Get-NetIPConfiguration` 找到电脑当前局域网地址，再让中转监听所有网卡：

```powershell
node tools/openai-relay.mjs --host 0.0.0.0
```

例如电脑地址为 `192.168.101.139`，Tellev 的自定义 OpenAI 配置填写：

- 接口地址：`http://192.168.101.139:8787/tellev`
- API 密钥：原 DeepSeek API Key
- 模型：例如 `deepseek-chat`
- 模型列表路径：`/v1/models`
- 聊天补全路径：`/v1/chat/completions`

只有 Tellev 的 Debug 包允许局域网明文 HTTP；Release 包仍拒绝远程 HTTP。Windows 防火墙可能需要给 TCP 8787 添加入站规则。请仅在可信局域网临时使用：捕获文件虽会隐藏密钥头，但普通 HTTP 流量仍可能被同网设备监听。

手机还没有最新 Debug 包时，可直接在手机浏览器打开下面的地址下载安装：

```text
http://192.168.101.139:8787/__relay/tellev-debug.apk
```

### 可选：USB 反向端口

保持 USB 调试连接：

```powershell
adb reverse tcp:8787 tcp:8787
```

这时 Tellev 的接口地址填写：

- 接口地址：`http://127.0.0.1:8787/tellev`
- API 密钥：原 DeepSeek API Key
- 模型：例如 `deepseek-chat`
- 模型列表路径：`/v1/models`
- 聊天补全路径：`/v1/chat/completions`

Release 包只为 `localhost/127.0.0.1` 允许明文 HTTP。

## 3. 配置 SillyTavern

将自定义 OpenAI 兼容 Base URL 指向：

```text
http://192.168.101.139:8787/sillytavern/v1
```

如果 SillyTavern 与中转运行在同一台电脑，也可继续使用 `http://127.0.0.1:8787/sillytavern/v1`。在 SillyTavern 内填写同一个 DeepSeek API Key。

路径中的 `tellev` / `sillytavern` 只用于给捕获日志分组，转发给上游时会自动移除。

## 4. 比较最新一轮请求

两边各完成一次生成后运行：

```powershell
node tools/compare-openai-captures.mjs
```

报告写入：

```text
captures/openai-relay/latest-comparison.md
```

原始请求与响应位于 `captures/openai-relay/`。请求文件包含逐条 `messages`，可以直接确认角色描述、首条消息、世界书、预设插槽和当前用户输入究竟有没有进入模型上下文。
