package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.ChatSession
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningSupportTest {

    private fun raw(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ── resolve chain ─────────────────────────────────────────────────────

    @Test
    fun resolvePrefersRequestOverSessionOverPresetOverAuto() {
        assertEquals(ReasoningEffort.Low, ReasoningSupport.resolve(ReasoningEffort.Low, ReasoningEffort.High, ReasoningEffort.Medium))
        assertEquals(ReasoningEffort.Off, ReasoningSupport.resolve(null, ReasoningEffort.Off, ReasoningEffort.High))
        assertEquals(ReasoningEffort.High, ReasoningSupport.resolve(null, null, ReasoningEffort.High))
        assertEquals(ReasoningEffort.Auto, ReasoningSupport.resolve(null, null, null))
    }

    @Test
    fun fromStoredAcceptsNamesCaseInsensitiveAndRejectsGarbage() {
        assertEquals(ReasoningEffort.Off, ReasoningEffort.fromStored("off"))
        assertEquals(ReasoningEffort.Max, ReasoningEffort.fromStored(" MAX "))
        assertNull(ReasoningEffort.fromStored("banana"))
        assertNull(ReasoningEffort.fromStored(null))
        assertNull(ReasoningEffort.fromStored(""))
    }

    // ── family mapping ────────────────────────────────────────────────────

    @Test
    fun familyForMapsKnownProvidersAndGatesGenericReasoning() {
        assertEquals(ReasoningFamily.DeepSeek, ReasoningSupport.familyFor(ProviderCatalog.DEEPSEEK))
        assertEquals(ReasoningFamily.OpenRouter, ReasoningSupport.familyFor(ProviderCatalog.OPENROUTER))
        assertEquals(ReasoningFamily.Gemini, ReasoningSupport.familyFor(ProviderCatalog.GEMINI))
        assertEquals(ReasoningFamily.Anthropic, ReasoningFamily.Anthropic)
        // Generic openai-compatible only speaks reasoning when opted in.
        assertEquals(ReasoningFamily.None, ReasoningSupport.familyFor(ProviderCatalog.OPENAI_COMPATIBLE))
        assertEquals(
            ReasoningFamily.OpenAiCompatible,
            ReasoningSupport.familyFor(ProviderCatalog.OPENAI_COMPATIBLE, buildJsonObject { put("supportsReasoning", true) }),
        )
        assertEquals(ReasoningFamily.None, ReasoningSupport.familyFor(ProviderCatalog.OLLAMA))
    }

    @Test
    fun capabilitiesReflectPerFamilyLimits() {
        assertTrue(!ReasoningSupport.capabilities(ReasoningFamily.None).supportsReasoning)
        assertTrue(!ReasoningSupport.capabilities(ReasoningFamily.OpenAiCompatible).independentBudget)
        assertTrue(ReasoningSupport.capabilities(ReasoningFamily.Gemini).independentBudget)
        assertTrue(ReasoningSupport.capabilities(ReasoningFamily.Anthropic).independentBudget)
        assertTrue(ReasoningEffort.Max !in ReasoningSupport.capabilities(ReasoningFamily.OpenAiCompatible).supportedLevels)
        assertTrue(ReasoningEffort.Max in ReasoningSupport.capabilities(ReasoningFamily.DeepSeek).supportedLevels)
    }

    // ── OpenAI-compatible ────────────────────────────────────────────────

    @Test
    fun openAiCompatibleAutoPassesRawFieldsVerbatim() {
        val raw = raw("""{"thinking":{"type":"enabled"},"reasoning_effort":"high"}""")
        val injection = ReasoningSupport.inject(ReasoningFamily.OpenAiCompatible, ReasoningEffort.Auto, raw)
        assertEquals(raw["thinking"], injection.fields["thinking"])
        assertEquals(raw["reasoning_effort"], injection.fields["reasoning_effort"])
        assertTrue(injection.warnings.isEmpty())
    }

    @Test
    fun openAiCompatibleExplicitLevelsAndMaxClamp() {
        val low = ReasoningSupport.inject(ReasoningFamily.OpenAiCompatible, ReasoningEffort.Low)
        assertEquals("low", low.fields["reasoning_effort"]!!.jsonPrimitive.content)

        val max = ReasoningSupport.inject(ReasoningFamily.OpenAiCompatible, ReasoningEffort.Max)
        assertEquals("high", max.fields["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(max.warnings.isNotEmpty())
    }

    @Test
    fun openAiCompatibleOffSuppressesLegacyFields() {
        val injection = ReasoningSupport.inject(ReasoningFamily.OpenAiCompatible, ReasoningEffort.Off)
        assertTrue(injection.fields.isEmpty())
        assertEquals(setOf("thinking", "reasoning_effort"), injection.suppress)
    }

    // ── DeepSeek ──────────────────────────────────────────────────────────

    @Test
    fun deepSeekAutoPassesRawVerbatimAndExplicitUsesThinkingToggle() {
        val raw = raw("""{"thinking":{"type":"enabled"},"reasoning_effort":"medium"}""")
        val auto = ReasoningSupport.inject(ReasoningFamily.DeepSeek, ReasoningEffort.Auto, raw)
        assertEquals(raw["thinking"], auto.fields["thinking"])

        val off = ReasoningSupport.inject(ReasoningFamily.DeepSeek, ReasoningEffort.Off, raw)
        assertEquals("disabled", off.fields["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("reasoning_effort" in off.suppress)

        val on = ReasoningSupport.inject(ReasoningFamily.DeepSeek, ReasoningEffort.Medium, raw)
        assertEquals("enabled", on.fields["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("reasoning_effort" in on.suppress)
    }

    // ── OpenRouter ────────────────────────────────────────────────────────

    @Test
    fun openRouterMapsEffortAndClampsMax() {
        val raw = raw("""{"reasoning":{"effort":"high"}}""")
        val auto = ReasoningSupport.inject(ReasoningFamily.OpenRouter, ReasoningEffort.Auto, raw)
        assertEquals(raw["reasoning"], auto.fields["reasoning"])

        val low = ReasoningSupport.inject(ReasoningFamily.OpenRouter, ReasoningEffort.Low)
        assertEquals("low", low.fields["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val max = ReasoningSupport.inject(ReasoningFamily.OpenRouter, ReasoningEffort.Max)
        assertEquals("high", max.fields["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(max.warnings.isNotEmpty())
    }

    // ── Gemini ────────────────────────────────────────────────────────────

    @Test
    fun geminiMapsBudgetsAndOffToZero() {
        val raw = raw("""{"thinkingConfig":{"thinkingBudget":512,"includeThoughts":true}}""")
        val auto = ReasoningSupport.inject(ReasoningFamily.Gemini, ReasoningEffort.Auto, raw)
        assertEquals(raw["thinkingConfig"], auto.fields["thinkingConfig"])

        val off = ReasoningSupport.inject(ReasoningFamily.Gemini, ReasoningEffort.Off)
        assertEquals(0, off.fields["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())

        assertEquals(4096, ReasoningSupport.inject(ReasoningFamily.Gemini, ReasoningEffort.Medium)
            .fields["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
        assertEquals(24576, ReasoningSupport.inject(ReasoningFamily.Gemini, ReasoningEffort.Max)
            .fields["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    // ── Anthropic ─────────────────────────────────────────────────────────

    @Test
    fun anthropicAutoAndOffDoNotEnableThinking() {
        val auto = ReasoningSupport.inject(ReasoningFamily.Anthropic, ReasoningEffort.Auto)
        assertTrue(auto.fields.isEmpty())
        val off = ReasoningSupport.inject(ReasoningFamily.Anthropic, ReasoningEffort.Off)
        assertTrue(off.fields.isEmpty())
        assertTrue("thinking" in off.suppress)
    }

    @Test
    fun anthropicExplicitEffortUsesBudgetAndForcesTemperatureOne() {
        val low = ReasoningSupport.inject(ReasoningFamily.Anthropic, ReasoningEffort.Low, maxTokens = 8192)
        val thinking = low.fields["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(2048, thinking["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, low.fields["temperature"]!!.jsonPrimitive.content.toInt())
        assertTrue("top_p" in low.suppress)
    }

    @Test
    fun anthropicBudgetClampsToOutputLimitAndGivesUpWhenTooSmall() {
        val clamped = ReasoningSupport.inject(ReasoningFamily.Anthropic, ReasoningEffort.High, maxTokens = 5_000)
        assertEquals(4_999, clamped.fields["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(clamped.warnings.any { it.contains("4999") })

        val impossible = ReasoningSupport.inject(ReasoningFamily.Anthropic, ReasoningEffort.Low, maxTokens = 1_024)
        assertTrue(impossible.fields.isEmpty())
        assertTrue(impossible.warnings.isNotEmpty())
    }

    // ── effortFor (request metadata + preset field) ──────────────────────

    @Test
    fun effortForReadsRequestOverrideThenPresetField() {
        fun request(metadata: JsonObject = buildJsonObject { }, presetEffort: ReasoningEffort? = null) = GenerateRequest(
            prompt = PromptBuildResult(
                messages = listOf(PromptMessage(MessageRole.User, content = "x")),
                stop = emptyList(),
                maxTokens = null,
                providerType = "openai-compatible",
                diagnostics = PromptDiagnostics(emptyList()),
            ),
            preset = GenerationPreset(id = "p", name = "p", providerType = "openai-compatible", reasoningEffort = presetEffort),
            metadata = metadata,
        )

        assertEquals(ReasoningEffort.Auto, ReasoningSupport.effortFor(request()))
        assertEquals(ReasoningEffort.High, ReasoningSupport.effortFor(request(presetEffort = ReasoningEffort.High)))
        val withOverride = buildJsonObject { put(ReasoningSupport.REQUEST_OVERRIDE_KEY, "off") }
        assertEquals(ReasoningEffort.Off, ReasoningSupport.effortFor(request(withOverride, ReasoningEffort.High)))
        // A garbage override string falls back to the preset field, not Off.
        val garbage = buildJsonObject { put(ReasoningSupport.REQUEST_OVERRIDE_KEY, "banana") }
        assertEquals(ReasoningEffort.High, ReasoningSupport.effortFor(request(garbage, ReasoningEffort.High)))
    }

    // ── session-level override ────────────────────────────────────────────

    private fun sessionRequest(metadata: JsonObject, presetEffort: ReasoningEffort? = null) = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(PromptMessage(MessageRole.User, content = "x")),
            stop = emptyList(),
            maxTokens = null,
            providerType = "openai-compatible",
            diagnostics = PromptDiagnostics(emptyList()),
        ),
        preset = GenerationPreset(id = "p", name = "p", providerType = "openai-compatible", reasoningEffort = presetEffort),
        metadata = metadata,
    )

    private fun session(metadata: JsonObject) = ChatSession(
        id = "s",
        title = "t",
        characterId = null,
        groupId = null,
        messages = emptyList(),
        metadata = metadata,
    )

    @Test
    fun effortForHonorsSessionOverrideBetweenRequestAndPreset() {
        val sessionLow = buildJsonObject { put(ReasoningSupport.SESSION_OVERRIDE_KEY, "Low") }
        assertEquals(ReasoningEffort.Low, ReasoningSupport.effortFor(sessionRequest(sessionLow, ReasoningEffort.High)))
        // Request-level still beats session-level.
        val both = buildJsonObject {
            put(ReasoningSupport.REQUEST_OVERRIDE_KEY, "Off")
            put(ReasoningSupport.SESSION_OVERRIDE_KEY, "Low")
        }
        assertEquals(ReasoningEffort.Off, ReasoningSupport.effortFor(sessionRequest(both, ReasoningEffort.High)))
        // Garbage session value falls through to the preset field.
        val garbage = buildJsonObject { put(ReasoningSupport.SESSION_OVERRIDE_KEY, "banana") }
        assertEquals(ReasoningEffort.High, ReasoningSupport.effortFor(sessionRequest(garbage, ReasoningEffort.High)))
    }

    @Test
    fun sessionOverrideFromReadsSessionSettingKey() {
        assertEquals(
            ReasoningEffort.High,
            ReasoningSupport.sessionOverrideFrom(buildJsonObject { put(ReasoningSupport.SESSION_SETTING_KEY, "High") }),
        )
        assertNull(ReasoningSupport.sessionOverrideFrom(buildJsonObject { put(ReasoningSupport.SESSION_SETTING_KEY, "banana") }))
        assertNull(ReasoningSupport.sessionOverrideFrom(buildJsonObject { }))
        assertNull(ReasoningSupport.sessionOverrideFrom(null))
    }

    @Test
    fun withSessionOverrideAddsClearsAndPreservesOtherKeys() {
        val base = buildJsonObject { put("background", "bg.png") }
        val withHigh = ReasoningSupport.withSessionOverride(base, ReasoningEffort.High)
        assertEquals(ReasoningEffort.High, ReasoningSupport.sessionOverrideFrom(withHigh))
        assertEquals("bg.png", withHigh["background"]!!.jsonPrimitive.content)

        // Null and Auto both clear the override; other keys survive.
        val cleared = ReasoningSupport.withSessionOverride(withHigh, null)
        assertNull(ReasoningSupport.sessionOverrideFrom(cleared))
        assertEquals("bg.png", cleared["background"]!!.jsonPrimitive.content)
        val auto = ReasoningSupport.withSessionOverride(withHigh, ReasoningEffort.Auto)
        assertNull(ReasoningSupport.sessionOverrideFrom(auto))
        assertEquals("bg.png", auto["background"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionOverrideMetadataRoundTripsIntoEffortFor() {
        val sessionWithHigh = session(buildJsonObject { put(ReasoningSupport.SESSION_SETTING_KEY, "High") })
        val metadata = ReasoningSupport.sessionOverrideMetadata(sessionWithHigh)
        // Session override beats the preset's own field.
        assertEquals(ReasoningEffort.High, ReasoningSupport.effortFor(sessionRequest(metadata, ReasoningEffort.Off)))
        // A session without the setting contributes no metadata, so the preset field applies.
        val plain = ReasoningSupport.sessionOverrideMetadata(session(buildJsonObject { }))
        assertTrue(plain.isEmpty())
        assertEquals(ReasoningEffort.Off, ReasoningSupport.effortFor(sessionRequest(plain, ReasoningEffort.Off)))
    }
}
