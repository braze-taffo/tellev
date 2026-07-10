package app.tellev.feature.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.TavernHelperSettings

// ── EJS Template Settings Panel ───────────────────────────────────────

@Composable
fun EjsTemplateSettingsPanel(
    settings: EjsTemplateSettings,
    onUpdate: (EjsTemplateSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("EJS 提示词模板设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "控制 EJS 模板（<%= ... %>）在提示词生成和消息渲染中的行为。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SettingsSectionHeader("基础")
        SettingsToggleRow("启用 EJS 模板处理", "主开关，关闭后跳过所有模板处理", settings.enabled) {
            onUpdate(settings.copy(enabled = it))
        }
        SettingsToggleRow("调试模式", "输出调试日志", settings.debugEnabled) {
            onUpdate(settings.copy(debugEnabled = it))
        }

        SettingsSectionHeader("生成阶段")
        SettingsToggleRow("生成前处理", "在发送给 LLM 之前处理提示词中的 EJS", settings.generateEnabled) {
            onUpdate(settings.copy(generateEnabled = it))
        }
        SettingsToggleRow("世界书 [GENERATE:BEFORE]", "处理世界书条目中的 GENERATE 指令", settings.generateLoaderEnabled) {
            onUpdate(settings.copy(generateLoaderEnabled = it))
        }
        SettingsToggleRow("@INJECT 注入加载器", "处理 @INJECT 提示词注入指令", settings.injectLoaderEnabled) {
            onUpdate(settings.copy(injectLoaderEnabled = it))
        }
        SettingsToggleRow("预加载世界书", "在生成前预加载世界书条目", settings.preloadWorldinfoEnabled) {
            onUpdate(settings.copy(preloadWorldinfoEnabled = it))
        }

        SettingsSectionHeader("渲染阶段")
        SettingsToggleRow("渲染后处理", "在收到 LLM 响应后处理 EJS 模板", settings.renderEnabled) {
            onUpdate(settings.copy(renderEnabled = it))
        }
        SettingsToggleRow("渲染加载器", "渲染时执行加载器逻辑", settings.renderLoaderEnabled) {
            onUpdate(settings.copy(renderLoaderEnabled = it))
        }
        SettingsToggleRow("代码块处理", "处理代码块中的 EJS", settings.codeBlocksEnabled) {
            onUpdate(settings.copy(codeBlocksEnabled = it))
        }
        SettingsToggleRow("永久评估", "对每条消息永久评估变量", settings.rawMessageEvaluationEnabled) {
            onUpdate(settings.copy(rawMessageEvaluationEnabled = it))
        }
        SettingsToggleRow("消息过滤", "过滤消息内容", settings.filterMessageEnabled) {
            onUpdate(settings.copy(filterMessageEnabled = it))
        }

        SettingsSectionHeader("高级")
        SettingsToggleRow("沙盒模式", "在沙盒中执行 EJS 代码", settings.sandbox) {
            onUpdate(settings.copy(sandbox = it))
        }
        SettingsToggleRow("自动保存变量", "自动保存 EJS 变量更改", settings.autosaveEnabled) {
            onUpdate(settings.copy(autosaveEnabled = it))
        }
        SettingsToggleRow("禁用上下文", "禁用上下文变量注入", settings.withContextDisabled) {
            onUpdate(settings.copy(withContextDisabled = it))
        }
        SettingsToggleRow("反转逻辑", "反转世界书激活逻辑", settings.invertEnabled) {
            onUpdate(settings.copy(invertEnabled = it))
        }
        SettingsToggleRow("编译 Worker", "使用 Web Worker 编译模板", settings.compileWorkers) {
            onUpdate(settings.copy(compileWorkers = it))
        }
        SettingsToggleRow("代码编辑器", "启用代码编辑器 UI", settings.codeEditor) {
            onUpdate(settings.copy(codeEditor = it))
        }
        Text(
            "深度限制: ${if (settings.depthLimit < 0) "无限制" else settings.depthLimit}",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Text("重置默认")
            }
            Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text("完成")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── TavernHelper Settings Panel ───────────────────────────────────────

@Composable
fun TavernHelperSettingsPanel(
    settings: TavernHelperSettings,
    onUpdate: (TavernHelperSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("酒馆助手设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "控制酒馆助手兼容层的行为，包括音频、优化和渲染选项。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SettingsSectionHeader("音频")
        SettingsToggleRow("启用音频", "启用 BGM 和环境音播放", settings.audio.enabled) {
            onUpdate(settings.copy(audio = settings.audio.copy(enabled = it)))
        }
        SettingsToggleRow("BGM 播放", "背景音乐播放", settings.audio.bgm.enabled) {
            onUpdate(settings.copy(audio = settings.audio.copy(bgm = settings.audio.bgm.copy(enabled = it))))
        }
        SettingsToggleRow("BGM 静音", "静音背景音乐", settings.audio.bgm.muted) {
            onUpdate(settings.copy(audio = settings.audio.copy(bgm = settings.audio.bgm.copy(muted = it))))
        }
        SettingsToggleRow("环境音播放", "环境音效播放", settings.audio.ambient.enabled) {
            onUpdate(settings.copy(audio = settings.audio.copy(ambient = settings.audio.ambient.copy(enabled = it))))
        }
        SettingsToggleRow("环境音静音", "静音环境音", settings.audio.ambient.muted) {
            onUpdate(settings.copy(audio = settings.audio.copy(ambient = settings.audio.ambient.copy(muted = it))))
        }
        Text("BGM 音量: ${settings.audio.bgm.volume}  环境音量: ${settings.audio.ambient.volume}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        SettingsSectionHeader("优化")
        SettingsToggleRow("禁用不兼容选项", "自动禁用与 tellev 不兼容的选项", settings.optimize.disableIncompatibleOption) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(disableIncompatibleOption = it)))
        }
        SettingsToggleRow("更好的消息加载", "优化消息加载性能", settings.optimize.betterMessageToLoad) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterMessageToLoad = it)))
        }
        SettingsToggleRow("更好的角色更新", "优化角色更新流程", settings.optimize.betterCharacterUpdate) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterCharacterUpdate = it)))
        }
        SettingsToggleRow("更好的角色导出", "优化角色导出流程", settings.optimize.betterCharacterExport) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterCharacterExport = it)))
        }
        SettingsToggleRow("更好的角色删除", "优化角色删除流程", settings.optimize.betterCharacterDeletion) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterCharacterDeletion = it)))
        }
        SettingsToggleRow("推荐世界书设置", "强制应用推荐的世界书全局设置", settings.optimize.forceRecommendedWorldbookGlobalSettings) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(forceRecommendedWorldbookGlobalSettings = it)))
        }
        SettingsToggleRow("最大化预设上下文长度", "自动最大化预设的上下文长度", settings.optimize.maximizePresetContextLength) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(maximizePresetContextLength = it)))
        }

        HorizontalDivider()
        SettingsSectionHeader("渲染")
        SettingsToggleRow("启用渲染", "启用消息渲染增强", settings.render.enabled) {
            onUpdate(settings.copy(render = settings.render.copy(enabled = it)))
        }
        SettingsToggleRow("允许流式渲染", "在流式生成时实时渲染", settings.render.allowStreaming) {
            onUpdate(settings.copy(render = settings.render.copy(allowStreaming = it)))
        }
        SettingsToggleRow("使用 Blob URL", "使用 Blob URL 加载资源", settings.render.useBlobUrl) {
            onUpdate(settings.copy(render = settings.render.copy(useBlobUrl = it)))
        }
        SettingsToggleRow("优化 highlight.js", "优化代码高亮性能", settings.render.optimizeHljs) {
            onUpdate(settings.copy(render = settings.render.copy(optimizeHljs = it)))
        }
        SettingsToggleRow("深度忽略隐藏", "渲染深度计算时忽略隐藏消息", settings.render.depthIgnoreHidden) {
            onUpdate(settings.copy(render = settings.render.copy(depthIgnoreHidden = it)))
        }
        Text("代码块折叠: ${settings.render.collapseCodeBlock}  渲染深度: ${settings.render.depth}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        SettingsSectionHeader("宏")
        SettingsToggleRow("启用宏增强", "启用扩展宏功能", settings.macro.enabled) {
            onUpdate(settings.copy(macro = settings.macro.copy(enabled = it)))
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Text("重置默认")
            }
            Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text("完成")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Shared helpers ────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
