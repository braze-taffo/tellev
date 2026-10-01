# Android 正式通道兼容性修复

本次在 master 上修复 2026-10-01 审查确认的 12 类问题，并保留此前混合 HTML、主题颜色与可变生成事件的未提交修复。接口依据使用项目 manifest 锁定的 SillyTavern / Tavern Helper / MVU 版本。

| 审查项 | 修复与验证 |
| --- | --- |
| F01–F02 预设接口 | 原生 Storage 权限保护的同步读取；raw ST 与 Helper Preset 的转换；等待异步 updater；检查失败状态；保存未知 raw 字段；映射标准设置与提示词位置，同步旧 native 字段。锁定 MVU 的实际 extra-model 消费者、JS 回放和 Android WebView 测试验证。 |
| F03–F05 生成事件 | 正常聊天、脚本生成共同执行 prompt-ready、generate-after-data、settings-ready，随后才发请求。after-data 提供 prompt 与 dryRun，也回收请求参数修改。OpenAI 格式提供实际请求体，事件修改仅作用于当次请求。 |
| F04 多段内容 | 保留文本、图片及工具消息的结构字段，图片附件也进入生成前投影。无法映射到其他服务商的结构内容明确报错。实际 OpenAI HTTP 拦截测试验证消息、模型、采样、长度、stream、stop 与 JSON 格式设置。 |
| F06 原始生成 | generateRaw 与 generate 普通回复均返回字符串。区分原始提示词模式与预设模式；支持 ordered_prompts、overrides、max_chat_history、injects、preset_name、custom_api，图片转换和 JSON schema。配置仅用于当前请求。DefaultPromptEngine 与真实协调器测试验证顺序、覆盖和事件路径。 |
| F07–F08 变量 | 角色变量写回角色卡 raw，消息前端共用同一数据。预设变量保存至 working copy 和对应命名预设，切换后各自恢复。保留未知字段，旧键值对格式规范化时保留已有内容。真实文件写入/重读验证。 |
| F09 世界书绑定 | 保存角色主世界书与附加书；聊天书保存到 chat_metadata.world_info；创建聊天书幂等；绑定书进入当次扫描即使不在全局启用列表。不存在目标和非当前写入明确失败。 |
| F10–F11 HTML 边界 | 使用保留源码偏移的 token 扫描，跳过注释、脚本/样式 raw text、带引号属性和普通代码围栏；吸附连续头尾块，保留 head/body 与 doctype。Kotlin 用例和 Android 明暗主题渲染测试验证。 |
| F12 取消 | 在错误转换之前重抛 CancellationException。取消不再返回 chat_context_incomplete。 |

## 验证范围

测试使用临时数据目录、内存 JS 原生边界、HTTP 拦截器和独立的 app.tellev.mvuvalidation 模拟器包。没有调用付费模型或操作用户手机，也没有修改工作区提供的真实角色卡、预设文件。完整检查数量和 APK 校验保存在工作区 _buildlogs 与 dist 的本次验证记录。

最终验收：1019 项 JVM 单元测试、42 项 JavaScript 测试、6 项 Android WebView 测试通过；lint、release 构建和 diff 检查通过。新增用例覆盖旧聊天排队写入的拒绝、已物化角色内嵌书不重复激活，以及服务商不能执行的显式工具参数。已有创作取消测试的无限等待改为有超时的等待和状态断言，避免异常时整套验证一直挂住；创作生产代码未改动。

修复完成时的本地交付目录为 `dist/compat-fix-20261001`，该 APK 为官方通道 `app.tellev`，沿用版本 `1.7.1-beta.1` / code 41。签名与此前官方 APK 一致；核对 v2 签名、manifest、兼容资源哈希和 APK 无 MNN 内容。`verification.json` 记录当时的构建证据与全部修改源文件哈希，`changed-source.zip` 包含修改源文件，`tracked-changes.patch` 包含已跟踪文件的差异。

用户随后授权提交、推送并更新发行，以上修复纳入 `v1.7.1` / code 42 正式版。版本、包名和覆盖安装说明见 [正式发布说明](../RELEASE_1.7.1.md)。

这些检查覆盖本次确认的问题，不代表全部 Tavern Helper API 或真实服务商已验收。其他服务商不能处理的多段/工具内容会报错；不支持 proxy_preset，可用明确的 apiurl/key。原来的共享 compatVariables 保留在扩展设置中，不会把无法确定归属的旧 preset 变量自动复制到别的预设。附加角色世界书采用保留在角色 raw extensions 中的本应用字段。
