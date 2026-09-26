package app.tellev.core.update

/**
 * 版本号比较：去掉前导 `v`，只取开头的数字点分段，缺段按 0 补齐，因此
 * `1.4` == `1.4.0`、`1.4.10` > `1.4.9`、`1.5.5` < `1.5.5.1`。
 *
 * 放在顶层是为了让更新检查与「指引是否已经看过当前版本」共用同一口径；
 * [UpdateChecker.compareVersions] 是同一实现的实例入口。
 */
internal fun compareSemverVersions(a: String, b: String): Int {
    val pa = parseSemver(a)
    val pb = parseSemver(b)
    val len = maxOf(pa.size, pb.size)
    for (i in 0 until len) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

private fun parseSemver(version: String): List<Int> {
    val cleaned = version.trim().removePrefix("v").removePrefix("V")
    val match = Regex("""^(\d+(\.\d+){0,3})""").find(cleaned)
    val core = match?.groupValues?.get(1) ?: cleaned
    return core.split('.').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
}
