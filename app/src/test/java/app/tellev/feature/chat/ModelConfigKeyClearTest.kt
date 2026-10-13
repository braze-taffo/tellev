package app.tellev.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 凭据清除语义护栏（F1 融合后：单写者 = 设置侧 ProviderSettingsController）：
 * API 密钥字段为空白不得直接等同于「删除已存密钥」。
 *
 * 表单预填的就是已存密钥，而 SecretStore 读到损坏或不可解密的条目时按「值或 null」
 * 契约回落 null，字段会显示成空白——此时保存会把密钥静默删掉。删除必须由用户在
 * 保存前显式确认（clearApiKey）才会发生。
 */
class ModelConfigKeyClearTest {

    @Test fun `saving with a blank key does not delete the stored credential`() {
        val ctrl = sourceOf("feature/settings/controller/ProviderSettingsController.kt")
        assertTrue(
            "saveProviderConfig must accept an explicit clearApiKey intent",
            ctrl.contains("clearApiKey: Boolean"),
        )
        assertFalse(
            "apiKey 为空白时不得无条件 deleteSecret；必须由 clearApiKey 显式驱动",
            // 只盯 apiKey 这一条：model 的空白语义是「回落到服务商默认值」，本就该删。
            Regex("""isNotBlank\(\)\)[^\n]*else\s+secretStore\.deleteSecret\("provider-[^"]*apikey"""")
                .containsMatchIn(ctrl),
        )
        assertTrue(
            "删除必须挂在 clearApiKey 分支上",
            Regex("""else\s+if\s*\(clearApiKey\)[\s\S]{0,200}?deleteSecret\("provider-[^"]*apikey"""")
                .containsMatchIn(ctrl),
        )
    }

    @Test fun `the model config sheet confirms before clearing a stored key`() {
        val pane = sourceOf("feature/settings/SettingsProviderSection.kt")
        assertTrue(
            "面板保存必须挡住「已存密钥被清空」的路径，才能区分「用户清空」与「没读到」",
            pane.contains("state.apiKey.isBlank() && state.loadedApiKey.isNotBlank()"),
        )
        assertTrue(
            "确认后才以 clearApiKey = true 保存",
            pane.contains("saveProviderConfig(clearApiKey = true)"),
        )
        assertTrue(
            "controller 必须在载入/回读时维护已存密钥快照（比较基准）",
            sourceOf("feature/settings/controller/ProviderSettingsController.kt")
                .contains("loadedApiKey = fields.apiKey"),
        )
    }

    /**
     * 「恢复默认」契约护栏（F1 融合后：单写者 = 设置侧 controller）。
     *
     * 重置必须真的删掉本连接保存的地址/密钥/模型，让读取回落到 ProviderDefaults；
     * 接口地址过去只写不删，用户改错地址后没有任何办法回到默认值。
     */
    @Test fun `model config reset deletes the saved provider fields`() {
        val ctrl = sourceOf("feature/settings/controller/ProviderSettingsController.kt")
        assertTrue(
            "resetProviderConfig 必须存在",
            ctrl.contains("fun resetProviderConfig("),
        )
        // 密钥 id 里的 $ 是 Kotlin 模板字符：用 ${'$'} 写字面量 $，
        // 再交给 Regex.escape 转义，避免它被当成正则锚点。
        val d = "${'$'}"
        val delBaseUrl = Regex.escape("deleteSecret(\"provider-" + d + "providerId-baseurl\")")
        val delApiKey = Regex.escape("deleteSecret(\"provider-" + d + "providerId-apikey\")")
        val delModel = Regex.escape("deleteSecret(\"provider-" + d + "providerId-model\")")
        val resetHead = Regex.escape("fun resetProviderConfig() {")
        assertTrue(
            "重置必须删除接口地址，否则改错的 URL 永远无法恢复默认",
            Regex(resetHead + """[\s\S]{0,1400}?""" + delBaseUrl).containsMatchIn(ctrl),
        )
        assertTrue(
            "重置必须删除 API 密钥",
            Regex(resetHead + """[\s\S]{0,1400}?""" + delApiKey).containsMatchIn(ctrl),
        )
        assertTrue(
            "重置必须删除模型名",
            Regex(resetHead + """[\s\S]{0,1400}?""" + delModel).containsMatchIn(ctrl),
        )
        assertTrue(
            "custom 配置也要能重置（清空 baseUrl/apiKey/model）",
            Regex(resetHead + """[\s\S]{0,1400}?baseUrl = "",""").containsMatchIn(ctrl),
        )
    }

    @Test fun `blank base url on save restores the provider default`() {
        val ctrl = sourceOf("feature/settings/controller/ProviderSettingsController.kt")
        // 保存路径里地址留空必须走删除分支，与 model 的空白语义一致。
        val d = "${'$'}"
        val putBaseUrl = Regex.escape("putSecret(\"provider-" + d + "providerId-baseurl\", state.baseUrl)")
        val delBaseUrl = Regex.escape("deleteSecret(\"provider-" + d + "providerId-baseurl\")")
        assertTrue(
            "接口地址留空 = 恢复默认：需要 deleteSecret 分支",
            Regex("""if \(state\.baseUrl\.isNotBlank\(\)\) \{[\s\S]{0,200}?""" + putBaseUrl +
                """[\s\S]{0,160}?\} else \{[\s\S]{0,200}?""" + delBaseUrl).containsMatchIn(ctrl),
        )
    }

    private fun sourceOf(name: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "app/src/main/java/app/tellev/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: error("cannot locate $name from ${System.getProperty("user.dir")}")
        }
    }
}
