package app.tellev.core.prompt

/**
 * JVM-side mirror of ST-Prompt-Template's inject.ts prompt-injection registry
 * (a module-level map keyed by list name, entries deduped by uid, sticky
 * counters decayed once per generation).
 *
 * Production renders on the WebView, whose template.js keeps the authoritative
 * registry in JS; this Kotlin registry backs the fallback evaluator path
 * ([DefaultPromptTemplateProcessor] without a [PromptTemplateJsBridge]) and the
 * JVM unit tests. The two registries never mix: one build either renders
 * through the bridge or through the Kotlin evaluator, never both.
 */
internal object PromptInjectedRegistry {
    private class Injected(val prompt: String, val order: Int, var sticky: Int, val uid: String)

    private val lists = LinkedHashMap<String, LinkedHashMap<String, Injected>>()

    @Synchronized
    fun inject(key: String, prompt: String, order: Int = 100, sticky: Int = 0, uid: String = "") {
        val resolvedUid = uid.ifEmpty { hashUid("$key#$prompt") }
        lists.getOrPut(key) { LinkedHashMap() }[resolvedUid] = Injected(prompt, order, sticky, resolvedUid)
    }

    @Synchronized
    fun get(key: String, postprocess: List<Pair<Any?, String>> = emptyList()): String {
        val inner = lists[key] ?: return ""
        var combined = inner.values.sortedBy { it.order }.joinToString("\n") { it.prompt }
        for ((search, replace) in postprocess) {
            combined = when (search) {
                is Regex -> search.replaceFirst(combined, replace)
                else -> replaceFirstLiteral(combined, search.toString(), replace)
            }
        }
        return combined
    }

    @Synchronized
    fun has(key: String): Boolean = lists.containsKey(key)

    /** ST deactivatePromptInjection (inject.ts:102): sticky >= 0 survives, else dropped. */
    @Synchronized
    fun deactivate(count: Int = 1) {
        for (key in lists.keys.toList()) {
            val inner = lists[key] ?: continue
            val expired = mutableListOf<String>()
            inner.forEach { (uid, injected) ->
                val next = injected.sticky - count
                if (next >= 0) injected.sticky = next else expired += uid
            }
            if (expired.size == inner.size) {
                lists.remove(key)
            } else {
                expired.forEach(inner::remove)
                if (inner.isEmpty()) lists.remove(key)
            }
        }
    }

    @Synchronized
    fun clear() = lists.clear()

    private fun replaceFirstLiteral(text: String, search: String, replace: String): String {
        if (search.isEmpty()) return text
        val index = text.indexOf(search)
        if (index < 0) return text
        return text.substring(0, index) + replace + text.substring(index + search.length)
    }

    // Same FNV-1a 32-bit as template.js hashUid — uids only need to be stable
    // within this registry; they never cross to the WebView registry.
    // Kotlin Int arithmetic overflow-wraps like Java, matching Math.imul.
    private fun hashUid(text: String): String {
        var hash: Int = 0x811c9dc5.toInt()
        for (element in text) {
            hash = (hash.toInt() xor element.code) * 0x01000193
        }
        return String.format("%08x", hash)
    }
}

/**
 * Kotlin-side resolution of `{{outletPromptsInjected:key}}` placeholders for the
 * fallback (no WebView) path; mirrors template.js applyOutletPrompts /
 * ST-Prompt-Template inject.ts:85.
 */
internal object PromptTemplateOutlet {
    private const val MARKER = "{{outletPromptsInjected:"
    private val pattern = Regex("""\{\{outletPromptsInjected:(.+?)\}\}""")

    fun apply(content: String, recursion: Int = 41): String {
        var result = content
        repeat(recursion) {
            if (!result.contains(MARKER)) return result
            result = pattern.replace(result) { match -> PromptInjectedRegistry.get(match.groupValues[1]) }
        }
        return result
    }
}
