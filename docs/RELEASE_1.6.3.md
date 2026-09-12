# tellev v1.6.3

核心模块拆分与聊天行为回归维护。

## 更新内容

- 拆分聊天、提示词、扩展接口和数据存储模块，便于后续维护。
- 拆分设置与世界书界面，保留现有功能与数据格式。
- 补充会话切换后的生图隔离、变量写入失败提示及用户消息编辑的回归测试。

## 发行渠道

- MNN 生图版保留 Local Dream MNN、ComfyUI 和 NovelAI，使用 app.tellev.mnn、独立签名及预发布渠道。

## 版本与验证

- versionName：1.6.3；versionCode：29；最低 Android 12 / API 31。
- test assembleRelease 成功；Debug、MvuValidation、Release 各 691 项测试，零失败。
- 已核验包名、签名、APK 内容及 SHA-256。
- 本次未进行真机功能验收；安装包 SHA-256 见 GitHub Release。
- Local Dream 核心及转换资产采用 CC BY-NC 4.0，仅供非商业使用。

## APK SHA-256

51D2D4294DD29208AF0AEF46DBC5C5747859E8ECACEFC4AF63740D7984DDFDDE
