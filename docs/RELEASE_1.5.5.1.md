# tellev v1.5.5.1

本版本修复模型思考内容与正文混合的传输、保存和显示问题，并补齐群星卡使用的 RP Hub 正则字段兼容。

## 更新内容

- OpenAI 兼容接口和 Gemini 分别传递正文与思考；流式、非流式、中断保存和完成保存使用同一数据边界。
- 思考内容按消息 swipe 保存、切换和恢复；历史提示词只使用正文，避免将模型内部思考回传。
- 兼容仅在消息开头完整闭合的 `<think>` / `<reasoning>` 旧格式；嵌套、错配或未闭合标签保留原文，不猜测分割。
- 仅有思考而没有正文的回复会保留思考展开入口，显示“未收到正文”，并在可重新生成时提供重试。
- 消息操作菜单新增生成诊断和原始回复导出；诊断不含请求头、密钥或请求内容，且不会自动上传。
- 支持 RP Hub 的 `name/regex/flags/replacement` 正则字段，存在 Tavern 标准字段时仍以标准字段为准；placement 2 处理正文，placement 6 处理思考。

## 已知限制

自动化验证不代表群星卡、Sakura 或《道渊》的真机验收完成。发布前仍需在真实设备上回归首轮生成、连续重试、思考阶段停止、swipe 切换、重启后读取和两种导出。实现与验收边界详见 [群星卡：思考与正文分离](qunxing-reasoning.md)。

## 版本信息

- `versionName`: `1.5.5.1`
- `versionCode`: `23`
- 最低系统：Android 12 / API 31
- 目标 SDK：36
- SillyTavern 兼容基线：1.18.0

## 发布验证

- `test assembleRelease lintDebug` 成功；Debug、MvuValidation、Release 三个变体各 594 项单元测试，零失败。
- Node/MVU 集成测试：3 项通过。
- APK 清单与签名验证通过；包名 `app.tellev`，版本 `1.5.5.1` / 23，minSdk 31、targetSdk 36，签名证书与 v1.5.5 正式版一致。
- 真机验收：本次发布准备时 ADB 未发现连接设备，未由代理完成。
- 发布文件：`tellev-1.5.5.1.apk`。
- APK SHA-256：`53395D2271CFED76CB64ED6193C5BC1EB020FD09C4EDFDEF9FA9C48321244401`。
