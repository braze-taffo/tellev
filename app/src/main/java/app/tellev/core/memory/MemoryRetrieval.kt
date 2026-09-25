package app.tellev.core.memory

import kotlin.math.ln
import kotlin.math.sqrt

/** Local retrieval; embedding calls are an optional additional signal, never required. */
object MemoryRetrieval {
    fun terms(text: String): List<String> {
        val lower = text.lowercase()
        val words = Regex("[a-z0-9_]{2,}").findAll(lower).map { it.value }.toList()
        val cjkRuns = Regex("[\u3400-\u9fff]+").findAll(lower).map { it.value }
        val cjk = cjkRuns.flatMap { run ->
            if (run.length == 1) sequenceOf(run)
            else (0 until run.length - 1).asSequence().map { run.substring(it, it + 2) }
        }.toList()
        return words + cjk
    }

    fun search(records: List<MemoryRecord>, query: String, vector: List<Float>? = null, limit: Int = 6): List<MemoryRecord> {
        val active = records.filter { it.active && it.text.isNotBlank() }
        if (active.isEmpty()) return emptyList()
        val queryTerms = terms(query).toSet()
        val docs = active.map { terms(it.text + " " + it.subject + " " + it.tags.joinToString(" ")) }
        val averageLength = docs.map { it.size }.average().coerceAtLeast(1.0)
        val documentFrequency = queryTerms.associateWith { term -> docs.count { term in it } }
        val now = System.currentTimeMillis()
        val scored = active.mapIndexed { index, record ->
            val tokens = docs[index]
            val frequencies = tokens.groupingBy { it }.eachCount()
            val bm25 = queryTerms.sumOf { term ->
                val count = frequencies[term]?.toDouble() ?: 0.0
                if (count == 0.0) 0.0 else {
                    val idf = ln(1.0 + (active.size - documentFrequency.getValue(term) + 0.5) /
                        (documentFrequency.getValue(term) + 0.5))
                    idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * tokens.size / averageLength))
                }
            }
            val vectorScore = if (vector != null && record.vector.isNotEmpty()) cosine(vector, record.vector) else 0.0
            val daysOld = ((now - record.createdAtMillis).coerceAtLeast(0L) / 86_400_000.0)
            val recency = 0.25 / (1.0 + daysOld / 90.0)
            val score = bm25 + vectorScore.coerceAtLeast(0.0) * 1.5 +
                record.importance.coerceIn(1, 5) * 0.08 + recency
            Triple(record, score, bm25 > 0.0 || vectorScore > 0.35)
        }.sortedByDescending { it.second }
        val base = scored.filter { it.third }.map { it.first to it.second }
        val seeds = base.take(limit.coerceAtLeast(1))
        val seedIds = seeds.map { it.first.id }.toSet()
        val expandedIds = seeds.flatMap { (record, _) -> record.links.map { it.targetId } }.toSet()
        val neighbors = expandedIds + active.filter { record -> record.links.any { it.targetId in seedIds } }.map { it.id }
        return (seeds + scored.filter { it.first.id in neighbors }.take(2).map { it.first to it.second })
            .distinctBy { it.first.id }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun cosine(a: List<Float>, b: List<Float>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val dot = a.indices.sumOf { a[it].toDouble() * b[it] }
        val lengthA = sqrt(a.sumOf { it.toDouble() * it })
        val lengthB = sqrt(b.sumOf { it.toDouble() * it })
        return if (lengthA == 0.0 || lengthB == 0.0) 0.0 else dot / (lengthA * lengthB)
    }
}
