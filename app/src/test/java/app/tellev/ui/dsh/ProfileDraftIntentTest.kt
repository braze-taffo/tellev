package app.tellev.ui.dsh

import app.tellev.core.model.ReasoningEffort
import app.tellev.core.provider.ModelReasoningProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bre effort.ts 的契约复刻测试：draftFrom / buildIntent / sameEfforts 三条语义。
 * 用例直接对应上游 vitest 的同名场景。
 */
class ProfileDraftIntentTest {

    @Test
    fun `unset declaration drafts all off`() {
        val draft = profileDraftFrom(null)
        assertTrue(draft.values.all { !it.on })
        assertNull(buildProfileIntent(draft))
    }

    @Test
    fun `false declaration arms only off`() {
        val draft = DISABLED_REASONING_DRAFT
        assertEquals(1, draft.values.count { it.on })
        assertTrue(draft[ReasoningEffort.Off]!!.on)
        val (efforts, disabled) = buildProfileIntent(draft)!!
        assertEquals(DISABLED, disabled)
        assertTrue(efforts.isEmpty())
    }

    @Test
    fun `dict declaration arms declared levels`() {
        val profile = ModelReasoningProfile(
            efforts = mapOf(
                "off" to "none",
                "low" to "low",
                "high" to null,
            ),
        )
        val draft = profileDraftFrom(profile)
        assertTrue(draft[ReasoningEffort.Off]!!.on)
        assertTrue(draft[ReasoningEffort.Low]!!.on)
        assertTrue(draft[ReasoningEffort.High]!!.on)
        // null 拼写（= 不发字段）在草稿里表现为空串。
        assertEquals("", draft[ReasoningEffort.High]!!.wire)
        assertEquals("none", draft[ReasoningEffort.Off]!!.wire)
        assertTrue(!draft[ReasoningEffort.Medium]!!.on)

        // 上游 buildIntent：非 off 档位拼写留空时默认用自己的档位名，
        // 所以档案里的 null（= 不发字段）经过草稿往返会变成 "high"。
        // null 与 "" 的区分只有 off 档保留（off 空拼写 → null）。
        val (efforts, _) = buildProfileIntent(draft)!!
        assertEquals(mapOf("off" to "none", "low" to "low", "high" to "high"), efforts)
    }

    @Test
    fun `armed level without spelling defaults to its own name`() {
        val draft = mapOf<ReasoningEffort, DraftLevel>(
            ReasoningEffort.Off to DraftLevel(),
            ReasoningEffort.Minimal to DraftLevel(),
            ReasoningEffort.Low to DraftLevel(),
            ReasoningEffort.Medium to DraftLevel(),
            ReasoningEffort.High to DraftLevel(on = true, wire = "  "),
            ReasoningEffort.XHigh to DraftLevel(),
            ReasoningEffort.Max to DraftLevel(),
        )
        val (efforts, _) = buildProfileIntent(draft)!!
        assertEquals("high", efforts["high"])
    }

    @Test
    fun `off alone writes false not a dict`() {
        val draft = mapOf<ReasoningEffort, DraftLevel>(
            ReasoningEffort.Off to DraftLevel(on = true, wire = ""),
            ReasoningEffort.Minimal to DraftLevel(),
            ReasoningEffort.Low to DraftLevel(),
            ReasoningEffort.Medium to DraftLevel(),
            ReasoningEffort.High to DraftLevel(),
            ReasoningEffort.XHigh to DraftLevel(),
            ReasoningEffort.Max to DraftLevel(),
        )
        val (efforts, disabled) = buildProfileIntent(draft)!!
        assertEquals(DISABLED, disabled)
        assertTrue(efforts.isEmpty())
    }

    @Test
    fun `off alone always writes false even with a spelling`() {
        // 上游：没有任何思考档位时 return off ? false : undefined，
        // off 的拼写随 out 一起被丢弃。off 的拼写只有在同时勾了思考档位时才保留。
        val draft = mapOf<ReasoningEffort, DraftLevel>(
            ReasoningEffort.Off to DraftLevel(on = true, wire = "none"),
            ReasoningEffort.Minimal to DraftLevel(),
            ReasoningEffort.Low to DraftLevel(),
            ReasoningEffort.Medium to DraftLevel(),
            ReasoningEffort.High to DraftLevel(),
            ReasoningEffort.XHigh to DraftLevel(),
            ReasoningEffort.Max to DraftLevel(),
        )
        val (efforts, disabled) = buildProfileIntent(draft)!!
        assertEquals(DISABLED, disabled)
        assertTrue(efforts.isEmpty())
    }

    @Test
    fun `off spelling survives when a thinking level is also armed`() {
        val draft = mapOf<ReasoningEffort, DraftLevel>(
            ReasoningEffort.Off to DraftLevel(on = true, wire = "none"),
            ReasoningEffort.Minimal to DraftLevel(),
            ReasoningEffort.Low to DraftLevel(),
            ReasoningEffort.Medium to DraftLevel(),
            ReasoningEffort.High to DraftLevel(on = true),
            ReasoningEffort.XHigh to DraftLevel(),
            ReasoningEffort.Max to DraftLevel(),
        )
        val (efforts, disabled) = buildProfileIntent(draft)!!
        assertNull(disabled)
        assertEquals("none", efforts["off"])
        assertEquals("high", efforts["high"])
    }

    @Test
    fun `round trip through stored profile is stable`() {
        val stored = ModelReasoningProfile(
            efforts = mapOf("off" to "none", "low" to "low", "high" to "high"),
            defaultEffort = "low",
            contextWindow = 200_000,
            maxTokens = 30_000,
        )
        val draft = profileDraftFrom(stored)
        assertTrue(draftMatchesStored(draft, stored))

        val (efforts, _) = buildProfileIntent(draft)!!
        val rebuilt = ModelReasoningProfile(
            efforts = efforts,
            defaultEffort = "low",
            contextWindow = 200_000,
            maxTokens = 30_000,
        )
        assertTrue(draftMatchesStored(profileDraftFrom(rebuilt), rebuilt))
    }

    @Test
    fun `level ladder order matches upstream THINKING_LEVELS`() {
        assertEquals(
            listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"),
            DSH_THINKING_LEVELS.map { it.name.lowercase() },
        )
    }
}
