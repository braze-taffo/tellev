# tellev v1.5.1

本版本在 v1.5.0 的基础上继续收紧 SillyTavern 1.18.0 兼容行为，重点完善预设、正则、消息脚本和富文本渲染链路。

## 预设与提示词

- OpenAI 默认预设改为仓库内锁定的 SillyTavern 1.18.0 `Default.json` 基线，并将所有内置默认预设及缺省运行时回退统一设为 1,000,000 上下文、131,072（128 Ki）最大输出。
- 升级时自动修复仍为 4095/4096 上下文与 300 输出的内置默认预设和已选默认工作副本；更高的自定义值及导入/命名预设保持不变。
- 从旧版首次升级进入应用时显示一次性提示，可直接跳到「设置 → 生成预设」检查或更换当前预设；全新安装不会显示迁移提示。
- 导入的预设会立即启用，并显示实际应用、原样保留和暂未支持的字段；服务商、接口、密钥与模型等路由字段不会覆盖当前连接配置。
- 新增 `top_a`、`min_p`、repetition penalty 及 range 的解析、保存和 provider 参数映射。
- 对齐 `names_behavior` 的四种语义，并支持 `squash_system_messages` 和 `assistant_prefill`。
- 修复预设 ID 被误用为模型名的问题；需要显式模型却未配置时返回明确错误。

## 正则与聊天

- 将正则处理拆分为 Normal / Display / Prompt 三阶段，按角色卡、预设的稳定顺序执行。
- Normal 正则覆盖用户发送、编辑、AI 完成、停止生成和 swipe，并按 swipe 保存处理版本，防止显示与提示词阶段重复替换。
- depth 只统计可见且非 system/tool 的消息，更贴近 SillyTavern 行为；无效正则或 flag 只跳过当前规则。
- 流式回复实时应用正则，并使用与已保存消息一致的 Markdown / TavernHelper HTML 渲染链路。

## 扩展与显示

- TavernHelper 消息兼容层新增 `getChatMessages()` 与 `setChatMessage()`，支持读取消息和更新指定 swipe。
- HTML 消息面板根据应用主题与正文颜色自动选择高对比背景；保留扩展自带背景。
- 折叠内容展开后主动重新测量 WebView 高度，减少内容被截断或留下大块空白。

## 版本信息

- `versionName`: `1.5.1`
- `versionCode`: `18`
- 最低系统：Android 12 / API 31
- SillyTavern 兼容基线：1.18.0
