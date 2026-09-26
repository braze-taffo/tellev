package app.tellev.core.guide

import android.content.res.AssetManager
import android.util.Log
import app.tellev.core.storage.StDirectoryLayout
import java.nio.file.Files

/** 指引的两个用途，对应 `app/src/main/assets/guide/<assetDir>/<语言>.md`。 */
internal enum class GuideKind(val assetDir: String) {
    /** 全新安装的新手指引。 */
    Onboarding("guide/onboarding"),

    /** 覆盖升级后的「本次更新了什么」。 */
    Update("guide/update"),
}

/**
 * 读取并解析指引资产。语言按 [languageFallbackCandidates] 逐级回退；资产缺失
 * 或格式不对时返回 null，由调用方展示「指引内容加载失败」——不把异常抛到
 * 启动路径上，也不静默显示半截内容。
 *
 * 只按回退链逐个 `open` 试读，不用 `AssetManager.list()`：目录列举在真机上
 * 曾经拿不到子项（1.7.0.1 装机验收时表现为「指引内容加载失败」），而按名字
 * 直接开文件既没有这个不确定性，也顺带把「哪个语言真的读到了」变成显式路径。
 */
internal fun loadGuide(assets: AssetManager, kind: GuideKind, languageTag: String): GuideDocument? =
    loadGuideFrom(kind, languageTag, reader = { path ->
        runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
    })

/**
 * [loadGuide] 的测试入口：把「按路径取文本」与「报告失败」抽成参数，
 * 读取逻辑就能在 JVM 单测里覆盖（`android.util.Log` 在单测里是 not mocked，
 * 真机诊断与可测性只能二选一，所以走回调）。
 */
internal fun loadGuideFrom(
    kind: GuideKind,
    languageTag: String,
    reader: (String) -> String?,
    onFailure: (String, Throwable?) -> Unit = ::logGuideFailure,
): GuideDocument? {
    for (language in languageFallbackCandidates(languageTag)) {
        val path = "${kind.assetDir}/$language.md"
        val source = reader(path) ?: continue
        return try {
            GuideFormat.parse(source)
        } catch (error: IllegalArgumentException) {
            onFailure("guide asset $path is malformed", error)
            return null
        }
    }
    onFailure("no readable guide asset under ${kind.assetDir} for '$languageTag'", null)
    return null
}

private fun logGuideFailure(message: String, error: Throwable?) {
    if (error == null) Log.w(TAG, message) else Log.e(TAG, message, error)
}

/**
 * 语言回退顺序：精确标签 → 主语言（中文特判到 `zh-CN`）→ 英文 → 中文。
 * 例：`zh-Hans-CN` → [zh-Hans-CN, zh-CN, en]；`ja-JP` → [ja-JP, ja, en, zh-CN]。
 */
internal fun languageFallbackCandidates(languageTag: String): List<String> {
    val normalized = languageTag.trim().replace('_', '-')
    val primary = normalized.substringBefore('-').lowercase()
    val candidates = LinkedHashSet<String>()
    if (normalized.isNotEmpty()) candidates += normalized
    if (primary.isNotEmpty()) candidates += if (primary == "zh") "zh-CN" else primary
    candidates += "en"
    candidates += "zh-CN"
    return candidates.toList()
}

/**
 * 这次安装是否已经有用户自己的数据。
 *
 * 只看 `characters/`、`chats/`、`group chats/`、`worlds/`：[app.tellev.core.storage.FileStDataStore]
 * 的 bootstrap 会预置默认预设与一个人设，所以预设/人设不能用来判断「是不是全新安装」。
 * 目录枚举也比逐个读角色卡摘要便宜。
 */
internal fun hasAnyUserData(layout: StDirectoryLayout): Boolean =
    listOf(layout.characters, layout.chats, layout.groupChats, layout.worlds).any { directory ->
        runCatching {
            Files.newDirectoryStream(directory).use { it.iterator().hasNext() }
        }.getOrDefault(false)
    }

private const val TAG = "GuideContent"
