# tellev 1.5.3 beta 测试包

此构建作为独立应用 `app.tellev.beta` 安装，显示名称为 `tellev Beta`，版本名为
`1.5.3-beta.1`。它可以与正式版并存，数据和设置互不共享。

## 本机构建配置

在仓库根目录的 `local.properties` 中配置测试通道。该文件已被 Git 忽略，禁止把真实密钥写入仓库中的其他文件。

```properties
tellevBetaRelayBaseUrl=https://tellev.click/v1
tellevBetaRelayApiKey=测试通道共享密钥
tellevBetaRelayModel=deepseek-v4-pro
```

内置通道使用 OpenAI 兼容的 `/models` 和 `/chat/completions`。构建时要求 HTTPS，并在缺少任一配置时失败。

## 测试版行为

- 首次安装默认选择 `tellevclick（内置测试通道）`，也允许切换到用户自己的服务商。
- 设置页不把内置 Base URL 或密钥载入界面状态，不提供查看或编辑入口。
- 当前内置密钥可选择 `claude-opus-5` 或 `deepseek-v4-pro`，默认使用后者。
- 模型选择保存在 Android Keystore 支持的应用密钥存储中。
- Beta 继续使用独立包名，并自动和手动检查 GitHub 正式 Release。

## 构建

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleBeta
```

签名 APK 输出到 `app/build/outputs/apk/beta/app-beta.apk`。发布文件名使用
`tellev-1.5.3-beta.1.apk`，并同时公布 SHA-256。

内置认证值仍可被有能力分析 APK 或进程的人员提取；界面隐藏用于防止普通用户查看和误操作，不应被视为绝对的密钥保护边界。
