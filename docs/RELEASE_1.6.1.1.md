# tellev v1.6.1.1

修正正式版与独立生图版的功能边界。

## 正式版

- `master` / `app.tellev`，沿用正式版签名和普通发行更新渠道。
- 生图仅提供 ComfyUI、NovelAI，移除误包含的本地 MNN 核心、转换资产和模型管理界面。
- 保留 NovelAI 引擎选择、英文 tag 场景总结及聊天图片显示修复。
- 兼容错误版本遗留的引擎设置，不删除用户保存的数据。

## 独立生图版

- `mnn-image-gen` / `app.tellev.mnn`，沿用生图版独立签名与 `-mnn` 预发布更新渠道。
- 保留本地 SD1.5 / MNN 推理、模型导入转换及管理、前台保活。
- 聊天可选择本地推理、ComfyUI、NovelAI；本地与 NovelAI 场景总结使用英文 tag 并校验格式。
- Local Dream 核心及转换资产遵循 CC BY-NC 4.0，仅供非商业使用。

## 版本与验证

- versionName：1.6.1.1；versionCode：27；最低 Android 12 / API 31。
- 正式版不包含本地推理核心或转换资产；生图版包含核心及七个转换目录文件，仅支持 arm64-v8a。
- 各分支分别执行 Release 单元测试和发行构建，并核验 APK 包名、原有签名及 SHA-256。
- SHA-256 以对应 GitHub Release 中的实际 APK 为准；自动测试不代表真机出图验收。
