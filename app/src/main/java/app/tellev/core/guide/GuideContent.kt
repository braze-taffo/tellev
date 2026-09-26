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
 */
internal fun loadGuide(assets: AssetManager, kind: GuideKind, languageTag: String): GuideDocument? {
    val available = runCatching { assets.list(kind.assetDir)?.toSet() }.getOrNull().orEmpty()
    val file = languageFallbackCandidates(languageTag).firstOrNull { "$it.md" in available } ?: return null
    val source = runCatching {
        assets.open("${kind.assetDir}/$file").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return null
    return try {
        GuideFormat.parse(source)
    } catch (error: IllegalArgumentException) {
        Log.e(TAG, "guide asset ${kind.assetDir}/$file is malformed", error)
        null
    }
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
