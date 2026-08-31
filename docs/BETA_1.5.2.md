# tellev 1.5.2 beta 测试包

此构建作为独立应用 `app.tellev.beta` 安装，显示名称为 `tellev Beta`，版本名为
`1.5.2-beta.1`。它可以与正式版并存，数据和设置互不共享。

## 本机构建配置

在仓库根目录的 `local.properties` 中加入以下三项。该文件已被 Git 忽略，禁止把真实密钥写入仓库中的其他文件。

```properties
tellevBetaRelayBaseUrl=http://公网IP:端口/可选路径前缀
tellevBetaRelayApiKey=测试通道共享密钥
tellevBetaRelayModel=服务端实际接受的模型ID
```

Base URL 必须以 `http://` 开头。适配器会在其后请求 OpenAI 兼容的
`/v1/models` 和 `/v1/chat/completions`；如果 Base URL 已以 `/v1` 结尾，不会重复添加 `/v1`。

## 构建

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleBeta
```

签名 APK 输出到 `app/build/outputs/apk/beta/app-beta.apk`。缺少任一 beta 配置时，
`assembleBeta` 会在打包前失败；普通 Debug 和 Release 构建不读取这些测试参数。

发布前将 APK 重命名为 `tellev-1.5.2-beta.1.apk`，同时计算并公布 SHA-256。

## GitHub 分发

1. 在 `braze-taffo/tellev` 创建标签和 Release：`v1.5.2-beta.1`。
2. Release 标题使用 `tellev 1.5.2 Beta 1（公开测试）`。
3. 必须标记为 pre-release，不能设为 Latest。
4. 上传 `tellev-1.5.2-beta.1.apk`，并在正文列出 SHA-256、Android 12+、HTTP 明文风险和反馈方式。

正式版和 beta 都只把非预发布、非草稿 Release 视为应用更新。正式版发布后，beta
会提示安装或更新 `app.tellev`；两个应用的数据独立，beta 不会自动卸载。

## 测试版行为

- 首次安装默认选择“DeepSeek V4 Pro 测试通道”，也允许切换到用户自己的服务商。
- 设置页不显示或编辑测试通道的 Base URL、共享密钥与真实模型 ID。
- beta 包允许明文 HTTP；正式版仍保持远程 HTTPS-only。
- 首次启动明确告知明文传输风险，测试者同意后才继续使用。
- beta 包继续自动和手动检查 GitHub 正式 Release。

共享密钥和地址仍可通过拆包或流量分析取得；隐藏配置只用于降低普通用户误操作，不能作为安全边界。
