package app.tellev.core.guide

import app.tellev.core.update.compareSemverVersions

/** 启动时要展示的指引。 */
internal enum class StartupGuide {
    /** 全新安装：从配置模型服务到开始聊天。 */
    Onboarding,

    /** 覆盖升级：本版本更新了哪些功能、入口在哪、怎么用。 */
    UpdateGuide,
}

/**
 * 决定启动时该弹哪个指引（或都不弹）。
 *
 * - 时间戳相等 = 全新安装（沿用 [app.tellev.core.storage.shouldShowOnceAfterUpdate]
 *   的口径）；只有「全新安装 + 确实还没有任何用户数据 + 没看过新手引导」才走新手引导。
 * - 重装后从备份还原了数据的用户时间戳同样相等，但已有数据，所以给的是更新指引。
 * - 其余情况比版本：上次提示过的版本比当前版本旧（或从未提示过）就给更新指引。
 *
 * [hasUserData] 是惰性的：只有走得到「全新安装且没看过引导」那条分支时才会求值，
 * 免得每次冷启动都去枚举磁盘目录。
 */
internal fun decideStartupGuide(
    currentVersion: String,
    lastGuideVersion: String,
    onboardingShown: Boolean,
    firstInstallTime: Long,
    lastUpdateTime: Long,
    hasUserData: () -> Boolean,
): StartupGuide? {
    val isFreshInstall = lastUpdateTime <= firstInstallTime
    if (!onboardingShown && isFreshInstall && !hasUserData()) return StartupGuide.Onboarding
    return if (isGuideOlderThan(lastGuideVersion, currentVersion)) StartupGuide.UpdateGuide else null
}

/** 上次提示过的版本比当前版本旧；空串按 0 处理，即从未提示过。 */
internal fun isGuideOlderThan(lastGuideVersion: String, currentVersion: String): Boolean =
    compareSemverVersions(lastGuideVersion.trim().ifBlank { "0" }, currentVersion) < 0
