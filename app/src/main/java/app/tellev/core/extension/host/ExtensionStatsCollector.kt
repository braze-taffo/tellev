package app.tellev.core.extension.host

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** Aggregated call statistics for one bridge API of one extension. */
data class ExtensionApiStat(
    val name: String,
    val count: Int,
    val totalMillis: Double,
    val maxMillis: Double,
    val errorCount: Int,
    val lastCalledAtMillis: Long,
)

/** One recent bridge call, for the debug panel's activity trail. */
data class ExtensionRecentCall(
    val name: String,
    val millis: Double,
    val failed: Boolean,
    val atMillis: Long,
)

/**
 * Session-scoped per-extension API call counters and durations for the
 * extension debug panel. In-memory only — cleared when the host is destroyed.
 *
 * Two recording paths feed it:
 * - the JS shim wraps `tellevNative` and reports synchronous bridge hops via
 *   the `traceCall` bridge method (emit/log are skip-listed there);
 * - the host itself records asynchronous handler time for `apiCall`
 *   (method + path), which the JS side cannot observe.
 */
class ExtensionStatsCollector {

    private class MutableStat {
        var count = 0
        var totalMillis = 0.0
        var maxMillis = 0.0
        var errorCount = 0
        var lastCalledAtMillis = 0L
    }

    private val byExtension = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableStat>>()
    private val recentByExtension = ConcurrentHashMap<String, ArrayDeque<ExtensionRecentCall>>()

    fun record(extensionId: String, name: String, millis: Double, failed: Boolean) {
        // computeIfAbsent 保证并发下只有一个容器被创建，否则两个线程会各自 new 一个
        // Deque/Map，其中一个成为孤儿，写入静默丢失。
        val stats = byExtension.computeIfAbsent(extensionId) { ConcurrentHashMap() }
        // 名称条目同样要原子创建：旧的"读不到就新建"在并发下会丢掉先落库的实例，
        // 计数器随之少算。容量上限与创建放在同一把锁里判断。
        val stat = stats[name] ?: synchronized(stats) {
            stats[name] ?: if (stats.size >= MAX_TRACKED_NAMES) null else MutableStat().also { stats[name] = it }
        } ?: return
        synchronized(stat) {
            stat.count++
            stat.totalMillis += millis
            if (millis > stat.maxMillis) stat.maxMillis = millis
            if (failed) stat.errorCount++
            stat.lastCalledAtMillis = System.currentTimeMillis()
        }
        val recent = recentByExtension.computeIfAbsent(extensionId) { ArrayDeque() }
        val call = ExtensionRecentCall(name, millis, failed, System.currentTimeMillis())
        synchronized(recent) {
            recent.addLast(call)
            while (recent.size > MAX_RECENT_CALLS) recent.removeFirst()
        }
    }

    fun stats(extensionId: String): List<ExtensionApiStat> {
        val stats = byExtension[extensionId] ?: return emptyList()
        return stats.map { (name, stat) ->
            synchronized(stat) {
                ExtensionApiStat(name, stat.count, stat.totalMillis, stat.maxMillis, stat.errorCount, stat.lastCalledAtMillis)
            }
        }.sortedByDescending { it.count }
    }

    fun recentCalls(extensionId: String): List<ExtensionRecentCall> {
        val recent = recentByExtension[extensionId] ?: return emptyList()
        return synchronized(recent) { recent.toList() }.asReversed()
    }

    fun clear(extensionId: String?) {
        if (extensionId == null) {
            byExtension.clear()
            recentByExtension.clear()
        } else {
            byExtension.remove(extensionId)
            recentByExtension.remove(extensionId)
        }
    }

    companion object {
        private const val MAX_TRACKED_NAMES = 200
        private const val MAX_RECENT_CALLS = 50
    }
}
