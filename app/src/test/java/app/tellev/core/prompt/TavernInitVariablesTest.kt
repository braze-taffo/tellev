package app.tellev.core.prompt

import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernInitVariablesTest {
    @Test
    fun `disabled initvar entry initializes structured stat data`() {
        val variables = TavernInitVariables.extractMessageVariables(
            listOf(
                WorldBook(
                    id = "book",
                    name = "book",
                    entries = listOf(
                        WorldBookEntry(
                            id = "init",
                            keys = emptyList(),
                            content = """
                                世界:
                                  当前时间: 未知
                                  动向: {}
                                主角:
                                  生命: 100
                                  器灵台词: []
                            """.trimIndent(),
                            enabled = false,
                            comment = "[initvar] 初始",
                        ),
                    ),
                ),
            ),
        )

        val statData = variables!!["stat_data"]!!.jsonObject
        assertEquals("未知", statData["世界"]!!.jsonObject["当前时间"]!!.jsonPrimitive.content)
        assertEquals(100, statData["主角"]!!.jsonObject["生命"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `ordinary disabled entry is not treated as init variables`() {
        val variables = TavernInitVariables.extractMessageVariables(
            listOf(
                WorldBook(
                    id = "book",
                    name = "book",
                    entries = listOf(
                        WorldBookEntry(
                            id = "entry",
                            keys = emptyList(),
                            content = "hp: 100",
                            enabled = false,
                            comment = "ordinary",
                        ),
                    ),
                ),
            ),
        )
        assertNull(variables)
    }
}
