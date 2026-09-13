# tellev 1.6.4 MNN 生图版

## 更新内容

- 自动更新统一读取 GitHub 发行列表，按版本标签后缀和 APK 文件名筛选当前渠道，防止跨渠道更新。
- 设置 → 关于新增启动时自动检查更新开关，默认开启；关闭后仍可手动检查。
- 回收写前日志容量，将视觉图片独立落盘，减少聊天 JSONL 内联数据。
- 增加会话与图片级联清理，减少列表和启动路径重复读取。
- 修复迁移与删除失败处理、删除和生图并发归属，以及会话缓存一致性。

## 发行渠道与自动更新

- app.tellev.mnn，独立 MNN 签名，GitHub 预发布；支持 Local Dream MNN、ComfyUI、NovelAI。
- 发行标签：v1.6.4-mnn；安装包：tellev-1.6.4-mnn.apk。
- 正式版仅匹配不带 -mnn 的版本标签和 APK；MNN 版仅匹配带 -mnn 的版本标签和 APK。

## 版本

- versionName：1.6.4；versionCode：30；最低 Android 12 / API 31。

## 验证

- test assembleRelease 成功；Debug、MvuValidation、Release 各 711 项测试，零失败、零错误、零跳过。
- 已核验包名、版本、签名及 APK 内容；签名与各自 1.6.3 渠道一致。
- 本次未进行真机功能验收。

## APK SHA-256

DFA39ACA24E852CC8745B4BB22C87ACC39D7590133AA71D2B5C2BC9E8466E03F
