# tellev v1.6.1 分支修正说明

此前 v1.6.1 正式版误包含本地 MNN 生图功能，对应 Release 已撤下。旧标签保留用于历史追溯；修正版本见 [v1.6.1.1](RELEASE_1.6.1.1.md)。

## 正式版 master

- 包名 `app.tellev`，使用正式版原有签名和普通 Release 更新渠道。
- 聊天生图仅提供 ComfyUI、NovelAI；可按次选择已配置的引擎。
- NovelAI 场景总结使用英文 tag，并在格式不合格时重试一次。
- 保留生成图片消息的显示修复。
- 不包含 Local Dream 原生核心、模型转换资产、本地推理、模型管理或相关前台服务。
- 忽略误装整合包遗留的本地引擎选择；保留已有用户数据。

## 独立生图版 mnn-image-gen

- 包名 `app.tellev.mnn`，使用生图版原有独立签名和 `-mnn` 预发布更新渠道。
- 聊天生图提供本地推理、ComfyUI、NovelAI。
- 保留 SD1.5 / MNN OpenCL 推理、模型导入转换、切换、删除和前台保活。
- 本地推理及 NovelAI 场景总结使用英文 tag，格式不合格时重试一次。
- 保留生成图片消息的显示修复。
- 本地核心和转换资产基于 Local Dream，遵循 CC BY-NC 4.0，仅供非商业使用。

## 构建与验证

- 历史 v1.6.1 的版本号为 `versionName=1.6.1`、`versionCode=26`，最低 Android 12 / API 31。
- 各自在对应 worktree 运行 `:app:testReleaseUnitTest :app:assembleRelease`。
- 校验 APK 包名、版本、签名证书及 SHA-256；两套签名必须保持独立。
- 正式 APK 不得包含 `libstable_diffusion_core.so` 或 `assets/ldcvt/`；生图 APK 必须包含核心及全部转换资产，并仅支持 arm64-v8a。
- 历史错误产物的测试数量及 SHA-256 已移除，不能用于确认本次修正后的 APK。
- 构建和自动测试不代表实际设备出图验收。
