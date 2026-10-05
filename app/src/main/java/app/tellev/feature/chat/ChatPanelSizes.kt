package app.tellev.feature.chat

/** Numeric measurements only; never retains a page, context or message body. */
internal data class PanelSizeKey(
    val owner: String,
    val contentVersion: String,
    val widthPx: Int,
    val density: Float,
    val fontScale: Float,
    val theme: String,
)

internal class ChatPanelSizes(private val isBusy: () -> Boolean = { false }) {
    private val heights = LinkedHashMap<PanelSizeKey, Int>(64, .75f, true)
    private val pending = LinkedHashMap<Any, () -> Unit>()
    fun get(key: PanelSizeKey): Int? = heights[key]
    fun record(key: PanelSizeKey, height: Int) {
        if (height <= 0) return
        heights[key] = height
        while (heights.size > 400) heights.remove(heights.keys.first())
    }
    fun deliver(binding: Any, apply: () -> Unit) {
        if (isBusy()) pending[binding] = apply else { pending.remove(binding); apply() }
    }
    fun remove(binding: Any) { pending.remove(binding) }
    fun flush(): Boolean {
        if (isBusy() || pending.isEmpty()) return false
        val batch = pending.values.toList()
        pending.clear()
        batch.forEach { it() }
        return true
    }
    fun clear() { heights.clear(); pending.clear() }
}

internal class ChatScrollDeltas {
    private var distance = 0f
    fun add(delta: Float) { if (delta.isFinite()) distance += delta }
    fun drain(): Float = distance.also { distance = 0f }
}
