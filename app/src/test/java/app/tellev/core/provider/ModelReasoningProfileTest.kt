package app.tellev.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 知识库推导与档案注入的回归。 */
class ModelReasoningProfileTest {

    // ── knowledge: longest-boundary-hit ──

    @Test
    fun `vision and generation aliases outrank the family stem`() {
        val flash = ReasoningKnowledgeBase.suggest("deepseek-flash")
        assertEquals("deepseek-v4.1-flash", flash.entryId)
        assertEquals("high", flash.defaultEffort)
        val vision = ReasoningKnowledgeBase.suggest("deepseek-v4-flash-vision-exp")
        assertEquals("deepseek-v4.1-flash", vision.entryId)
        val stem = ReasoningKnowledgeBase.suggest("deepseek-v4-pro")
        assertEquals("deepseek-v4", stem.entryId)
    }

    @Test
    fun `r1 has no off level and defaults high`() {
        val r1 = ReasoningKnowledgeBase.suggest("deepseek-reasoner")
        assertEquals("deepseek-r1", r1.entryId)
        assertTrue("off" !in (r1.efforts?.keys ?: emptySet()))
        assertEquals("high", r1.defaultEffort)
    }

    @Test
    fun `unknown model yields low-confidence empty suggestion`() {
        val s = ReasoningKnowledgeBase.suggest("totally-unknown-model-xyz")
        assertNull(s.efforts)
        assertEquals("low", s.confidence)
    }

    @Test
    fun `non-reasoning family is declared as null efforts`() {
        val s = ReasoningKnowledgeBase.suggest("gpt-4o-mini")
        assertNull(s.efforts)
    }

    // ── store: persistence + suggestion discipline ──

    @Test
    fun `store round-trips and degrades on corruption`() {
        val dir = Files.createTempDirectory("profile-test")
        val store = ModelReasoningProfileStore(
            profiles = mapOf("m1" to ModelReasoningProfile(
                efforts = mapOf("off" to "none", "high" to "high"),
                defaultEffort = "high",
                source = "user",
            )),
            unset = setOf("m2"),
        )
        assertTrue(ModelReasoningProfiles.write(dir, store))
        assertEquals(store, ModelReasoningProfiles.read(dir))

        // corrupted file → empty store, never throws
        dir.resolve("model-reasoning-profiles.json").toFile().writeText("{broken")
        assertEquals(ModelReasoningProfileStore(), ModelReasoningProfiles.read(dir))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `suggestion never overwrites a user declaration or an unset entry`() {
        val store = ModelReasoningProfileStore(
            profiles = mapOf("m1" to ModelReasoningProfile(efforts = mapOf("high" to "high"), source = "user")),
            unset = setOf("m2"),
        )
        val suggestion = ModelReasoningProfile(efforts = mapOf("low" to "low"), source = "knowledge")
        val updated = ModelReasoningProfiles.withSuggestion(store, "m1", suggestion)
        assertEquals("high", updated.profiles.getValue("m1").efforts.getValue("high"))
        assertNull(updated.profiles.getValue("m1").efforts["low"])
        val stillUnset = ModelReasoningProfiles.withSuggestion(store, "m2", suggestion)
        assertNull(stillUnset.profiles["m2"])
    }

    // ── injectWithProfile: profile spelling wins; absent profile keeps legacy ──

    @Test
    fun `profile spelling wins over family hardcode`() {
        val profile = ModelReasoningProfile(efforts = mapOf("off" to "none", "high" to "high"))
        val injection = ReasoningSupport.injectWithProfile(
            family = ReasoningFamily.OpenAiCompatible,
            effort = app.tellev.core.model.ReasoningEffort.High,
            modelId = "deepseek-v4",
            profile = profile,
        )
        assertEquals(JsonPrimitive("high"), injection.fields["reasoning_effort"])
    }

    @Test
    fun `null spelling suppresses the fields for that level`() {
        val profile = ModelReasoningProfile(efforts = mapOf("off" to null, "high" to "high"))
        val injection = ReasoningSupport.injectWithProfile(
            family = ReasoningFamily.OpenAiCompatible,
            effort = app.tellev.core.model.ReasoningEffort.Off,
            modelId = "m",
            profile = profile,
        )
        assertTrue(injection.fields.isEmpty())
        assertTrue("reasoning_effort" in injection.suppress)
    }

    @Test
    fun `level missing from profile off suppresses instead of inventing a spelling`() {
        val profile = ModelReasoningProfile(efforts = mapOf("high" to "high"))
        val injection = ReasoningSupport.injectWithProfile(
            family = ReasoningFamily.OpenAiCompatible,
            effort = app.tellev.core.model.ReasoningEffort.Off,
            modelId = "m",
            profile = profile,
        )
        assertTrue(injection.fields.isEmpty())
    }

    @Test
    fun `absent profile falls back to family mapping unchanged`() {
        val legacy = ReasoningSupport.inject(
            ReasoningFamily.OpenAiCompatible,
            app.tellev.core.model.ReasoningEffort.Low,
        )
        val withNull = ReasoningSupport.injectWithProfile(
            family = ReasoningFamily.OpenAiCompatible,
            effort = app.tellev.core.model.ReasoningEffort.Low,
            modelId = "m",
            profile = null,
        )
        assertEquals(legacy.fields, withNull.fields)
        assertEquals(legacy.suppress, withNull.suppress)
    }

    @Test
    fun `auto always keeps the legacy passthrough`() {
        val raw = buildJsonObject { put("reasoning_effort", JsonPrimitive("raw-keep")) }
        val a = ReasoningSupport.inject(ReasoningFamily.OpenAiCompatible, app.tellev.core.model.ReasoningEffort.Auto, raw)
        val b = ReasoningSupport.injectWithProfile(
            ReasoningFamily.OpenAiCompatible,
            app.tellev.core.model.ReasoningEffort.Auto,
            modelId = "m",
            profile = ModelReasoningProfile(efforts = mapOf("high" to "high")),
            raw = raw,
        )
        assertEquals(a.fields, b.fields)
    }
}
