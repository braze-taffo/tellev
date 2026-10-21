# 1.7.1.8 发行验证

- 基线：正式渠道 master，`a849d7ac5a2a6f0600fd4446cb76b09047fe773b`；发行版本 `1.7.1.8` / `50`，包名 `app.tellev`，minSdk `31`，targetSdk `36`。
- 本次包含现有上下文投影、世界书读取和桥接峰值优化，以及 AI 创作提示词与快捷指令调整。没有注入外部诊断 Activity 或用户卡片素材。
- 四语言更新引导各 3 页，覆盖复杂卡片、世界书读取和 AI 创作；入口名称已对照应用资源。首次使用流程不变。
- JavaScript 回归 64 项：63 通过，1 项依赖外部卡片素材的测试跳过，0 失败。
- 完整 Gradle `test assembleRelease lintDebug assembleMvuValidation assembleMvuValidationAndroidTest` 通过。Debug、MvuValidation、Release 各 1111 项单测，0 失败、0 错误、2 跳过。lint 为 0 错误、125 warnings、4 hints。
- 引导入口文案核对后重新构建正式包和独立验证包，并运行全部 guide 单元测试；`verifyUpdateGuides` 和 `preReleaseBuild` 均通过。
- APK manifest 版本与包名正确，v2 签名通过，证书 SHA-256 为 `aee2a2d53aeba1f89a50aa4ac52d517f4aa73f95335de1cc6672cf10d1d9f93f`，与沿用的正式版证书一致。
- APK 不含 Local Dream MNN 核心、转换资产或外部诊断素材；四语言引导与源文件逐字一致。
- APK SHA-256：`924f3e0ab73fd3c9ba2b65e603b16c076a67cfac2d0f93987f5ae1034de57e30`。
- Android 14 / API 34 独立模拟器：`UpdateGuideAndroidTest` 通过，四语言共 12 页，覆盖 Classic/DSH、升级首次展示、分页、关闭、版本记录与再次启动不重复显示。日志 `android-guide.log`，截图由测试直接保存。
- 本发行版本未进行真实手机功能验收或在线模型服务验收。

本次证据目录：`dist/release-1.7.1.8/`。之前性能诊断证据及手机安装记录见 `DAOYUAN_PERFORMANCE_HANDOFF.md`；先前安装的是 `1.7.1.7`，不能当作本发行版本的手机功能验收。
