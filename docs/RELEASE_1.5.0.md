# tellev v1.5.0

本版本集中修复原生单角色聊天的可靠性、SillyTavern 提示词兼容、聊天数据保真，以及模型服务配置体验。

## 生成与提示词兼容

- 每次发送、重试、重新生成和扩展生成前重新解析 Provider、对应类别预设、Persona、世界书与禁用状态，避免沿用过期运行时数据或混用其他 Provider 的预设。
- 修复旧版默认预设遗留的 4096 上下文和 300 输出 token 限制；迁移仅处理 tellev 生成的旧默认值，不修改导入预设。
- 按模型补齐最大上下文和回复 token，恢复大型角色卡的世界书、变量规则和长回复能力。
- 支持 MVU 角色卡的 `[initvar]` 世界书约定，将安全解析后的初始状态写入消息级 `stat_data`；`getAllVariables()` 同时可见最新消息变量。
- 遵循预设中的空 `new_chat_prompt` / `new_group_chat_prompt`，并过滤模板展开后为空的消息。
- 将 SillyTavern 的 `seed = -1` 解释为随机种子并省略该字段，避免 OpenAI 兼容端点和 Gemini 拒绝请求。
- OpenAI 兼容、DeepSeek 与 Gemini 的请求参数和提示词结构进一步对齐 SillyTavern。

## 数据可靠性

- `ChatMessage` 与 `ChatSession` 保存完整原始 JSON 对象；JSONL 读写以原始对象为底稿覆盖 tellev 管理字段，保留 reasoning、tool_calls、force_avatar、扩展和未知字段。
- 导入聊天保留原始 `user_name`、`character_name`；新会话写入当前 Persona 名和角色名。
- 增加角色、世界书、Persona、聊天、密钥和 Provider 配置变更流；界面可即时刷新，生成前快照仍以磁盘最新数据为准。

## Provider 与扩展

- 聊天 Provider 选择仅展示支持 Chat 或 Text 生成的适配器；旧的不兼容选择自动回退，但不删除原配置。
- `TavernHelper.generate()` 与主聊天共用生成运行时；无聊天上下文、上下文不完整和上游失败返回稳定的结构化错误。
- 延长角色卡模块加载等待时间，减少手机网络下载 Vue、Zod、YAML、MVU 等依赖时的误超时。

## 界面与路由

- 模型服务配置移入二级页面，主设置页提供当前配置摘要和快速切换。
- 移除“支持作者”二维码及相关资源。
- 角色、世界书与条目详情页以路由 ID 重新加载真实数据；无效 ID 显示错误并阻止误保存。
- 角色导入完成后不再错误提示必须重启；完整备份恢复仍保留重启要求。

## 验证

- Debug / Release 全量单元测试通过。
- Debug / Release APK 构建通过，Release 启用 R8 与资源压缩。
- 使用相同角色卡、输入、DeepSeek 模型和双鱼座预设进行 SillyTavern 对照抓包，确认角色卡、世界书、变量规则和预设主体均进入请求。
