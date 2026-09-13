# tellev 1.6.4 正式版

## 更新内容

- 自动更新统一读取 GitHub 发行列表，按版本标签后缀和 APK 文件名筛选当前渠道，防止跨渠道更新。
- 设置 → 关于新增启动时自动检查更新开关，默认开启；关闭后仍可手动检查。

## 发行渠道与自动更新

- app.tellev，正式签名，GitHub 普通发行；支持 ComfyUI、NovelAI 远程生图。
- 发行标签：v1.6.4；安装包：tellev-1.6.4.apk。
- 正式版仅匹配不带 -mnn 的版本标签和 APK；MNN 版仅匹配带 -mnn 的版本标签和 APK。

## 版本

- versionName：1.6.4；versionCode：30；最低 Android 12 / API 31。

## 验证

- test assembleRelease 成功；Debug、MvuValidation、Release 各 681 项测试，零失败、零错误、零跳过。
- 已核验包名、版本、签名及 APK 内容；签名与各自 1.6.3 渠道一致。
- 本次未进行真机功能验收。

## APK SHA-256

6C0F8E361BF60F7913090414D0906F1F78AAB985DC3803FA64E3818291D57787
