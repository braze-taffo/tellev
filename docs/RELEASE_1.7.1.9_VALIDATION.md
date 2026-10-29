# 1.7.1.9 发行验证

- 官方渠道 `master`，基线 `0344ffafa585387719dd71019a7c96fe7e9b86dd`；版本 `1.7.1.9` / 51，包名 `app.tellev`，minSdk 31，targetSdk 36。
- 纳入独立正则 JSON 导入、全局和指定角色范围、同名覆盖、开关、确认删除、全局处理链路及缓存刷新。
- 四语言更新指引各 3 页，内容为导入、覆盖与检查、启停与删除；四语言新手引导同步增加入口和基本用法。
- `gradlew.bat test assembleRelease lintDebug --console=plain` 成功；Debug、MvuValidation、Release 各 1,118 项测试，0 失败、0 错误、2 跳过。
- lint：0 错误、127 warnings、4 hints；`verifyUpdateGuides` 作为 `preReleaseBuild` 必过项执行成功。
- 独立 Android 模拟器上的 `UpdateGuideAndroidTest` 通过：四语言共 12 页，覆盖 Classic/DSH、升级提示、分页、关闭、版本记录和再次启动不重复展示。
- 正式签名 APK 已在独立模拟器上从 1.7.1.8 覆盖升级至 1.7.1.9，验证三页指引、关闭和进程重启后不重复展示。
- 包名、版本、启动 Activity、APK v2 签名通过，证书 SHA-256 与上一正式版一致：`aee2a2d53aeba1f89a50aa4ac52d517f4aa73f95335de1cc6672cf10d1d9f93f`。
- 正式包没有 `libstable_diffusion_core.so` 或 `assets/ldcvt/`。更新和新手引导共 8 份资源与源文件逐字一致。
- APK SHA-256：`b48df6f867f171e41db93cfc2d60870fb0f850f0ab2e89ec827766559c5f9a7c`。
- 正则功能的独立模拟器验证与新增 7 项单元测试见 `REGEX_IMPORT_HANDOFF.md`。本发行版本未进行真实手机或在线模型服务验收。
- 完整文案生成存在 44 个既有源表缺失键；本次新增 24 个键已单独核对四语言资源、资源映射和回退映射与源表一致。

构建、签名、引导测试及发布后回读证据目录：`dist/release-1.7.1.9/`。发行附件使用 `tellev-1.7.1.9.apk`，发布后核对公开状态、标签提交、附件大小、API digest 和下载回读 SHA-256。
