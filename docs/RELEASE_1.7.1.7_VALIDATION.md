# 1.7.1.7 发行验证

- 官方主线基线：`83fcbf80731e55ae38e41ac8b7e44572b62882bb`；版本 `1.7.1.7`，versionCode `49`，包名 `app.tellev`，minSdk `31`，targetSdk `36`。
- `test assembleRelease lintDebug` 完整通过。Debug、MvuValidation、Release 各运行 1100 个单元测试，1098 通过、2 个外部样本测试跳过；lint 0 errors、125 warnings、4 hints。
- 更新引导资产与解析测试通过。Android 14 模拟器使用实际打包资产，覆盖四语言共 12 页、Classic/DSH、升级首次启动、分页关闭、版本记录及再次启动不重复显示。
- 临时替换英文指引为旧版本标记，`verifyUpdateGuides` 如预期拒绝；恢复原文件后通过。正式构建日志确认 `preReleaseBuild` 自动执行此检查。
- 正式 APK 中四语言更新引导和新手引导均与构建源文件一致。官方包不含 `libstable_diffusion_core.so` 或 `assets/ldcvt/`。
- APK v2 签名验证通过，与 1.7.1.6 证书一致：`aee2a2d53aeba1f89a50aa4ac52d517f4aa73f95335de1cc6672cf10d1d9f93f`。
- APK SHA-256：`36f297f0cd854f27a68d250119684a35a032585eeb703ae84566aff4538b07c2`，文件大小 7,557,603 字节。
- 本次覆盖自动化、模拟器及发行包检查，未进行真实手机功能验收或在线模型服务验收。

每次发行必须同步更新四语言引导的要求已写入 `AGENTS.md` 和 `docs/UPDATE-GUIDE.md`，并由发行构建的版本检查约束。
