# tellev v1.6.1

完善本地生图与聊天内生图流程，正式版和独立生图版同步更新。

## 更新内容

- 正式版接入 Local Dream MNN OpenCL 本地生图，支持导入、转换、切换和删除 SD1.5 模型。
- 聊天内每次生图都可选择已配置的本地推理、ComfyUI 或 NovelAI 引擎。
- 本地推理与 NovelAI 的场景总结会输出经校验的英文图像 tag，格式不合格时自动重试一次。
- 修复生成图片消息可能被前端富文本分支遮住的问题。
- 本地引擎保持前台服务与模型管理，生成进度会同步显示在聊天界面。

## 版本与分发

- `versionName`: `1.6.1`
- `versionCode`: `26`
- 最低系统：Android 12 / API 31；目标 SDK：36。
- 正式版：`app.tellev`，标签 `v1.6.1`，附件 `tellev-1.6.1.apk`。
- 生图版：`app.tellev.mnn`，标签 `v1.6.1-mnn`，附件 `tellev-1.6.1-mnn.apk`，保持预发布。
- 两个渠道使用各自原有签名与独立包名，可分别覆盖升级并同时安装。
- Local Dream 本地生图核心遵循 CC BY-NC 4.0，仅供非商业使用。

## 验证范围

- 正式版 Debug、MVU Validation 与 Release 单元测试各 669 项通过；生图版各 671 项通过。
- 两版 `lintDebug` 均为 0 错误，JavaScript 兼容测试各 5 项通过。
- 两个 Release APK 均通过 v2 签名验证，包名、版本号、最低 SDK 与目标 SDK 均已复核。
- 正式版 APK SHA-256：`66546482E41903CF0E9828C0CD9E12682D6F416FC63B1F415D07678EF8E637A5`。
- 生图版 APK SHA-256：`BA77DF33FF8BCC319A0DE9CB1F4502DB976181F38B8DAE8DC32D537328BBA9E1`。
