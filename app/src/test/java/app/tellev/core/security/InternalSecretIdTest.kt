package app.tellev.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥区块内部项过滤契约：框架自管的 secret（provider/image 配置链路写入）必须
 * 全部被判为内部项——漏判一个就会在设置页露出可误删的内部状态；用户自定义
 * id（扩展脚本 SecretApiHandler 写入）必须保持可见。
 */
class InternalSecretIdTest {

    @Test
    fun `framework-managed ids are internal`() {
        val frameworkIds = listOf(
            "provider-selected-id",
            "custom-provider-configs",
            "image-engine-selected",
            "image-provider-profiles",
            "provider-comfyui-settings",
            "provider-novelai-image-settings",
            "tellev-memory-settings-v1",
            "provider-openai-compatible-baseurl",
            "provider-openai-compatible-apikey",
            "provider-openai-compatible-model",
            "provider-openai-compatible-advanced",
        )
        frameworkIds.forEach { id ->
            assertTrue("expected internal: $id", isInternalSecretId(id))
        }
    }

    @Test
    fun `user-defined extension ids stay visible`() {
        val userIds = listOf(
            "my-api-key",
            "siliconflow",
            "user_token",
            "tellev-custom-note",
            "imaginary-friend", // 含 image 子串但不是 image- 前缀
        )
        userIds.forEach { id ->
            assertFalse("expected user-defined: $id", isInternalSecretId(id))
        }
    }
}
