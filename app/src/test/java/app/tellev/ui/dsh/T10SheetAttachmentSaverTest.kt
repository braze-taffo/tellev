package app.tellev.ui.dsh

import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T10（弹层与输入修复员）：dsh 待发附件的保存/还原路径。
 * 附件本体已由 ChatCoreHelpers 落盘，缩略条与真实附件列表只随
 * rememberSaveable 走 Bundle，这里锁住「进出 Bundle 不丢字段、坏数据不炸」。
 */
class T10SheetAttachmentSaverTest {

    @Test
    fun `pending strip entries survive a configuration change round trip`() {
        val items = listOf(
            DshPendingAttachment(
                id = "att-1",
                name = "photo.png",
                isImage = true,
                relativePath = "user/images/att-1.jpg",
            ),
            DshPendingAttachment(
                id = "att-2",
                name = "带 空格 & 标点.txt",
                isImage = false,
                relativePath = "user/files/att-2.txt",
            ),
            // 还原后仍可能有缺相对路径的历史条目。
            DshPendingAttachment(id = "att-3", name = "legacy", isImage = false),
        )
        val restored = decodeDshPendingAttachments(encodeDshPendingAttachments(items))
        assertEquals(items, restored)
        assertEquals("user/images/att-1.jpg", restored[0].relativePath)
        assertNull(restored[2].relativePath)
    }

    @Test
    fun `empty list round trips and undecodable payloads degrade to empty`() {
        assertEquals(
            emptyList<DshPendingAttachment>(),
            decodeDshPendingAttachments(encodeDshPendingAttachments(emptyList())),
        )
        assertEquals(emptyList<DshPendingAttachment>(), decodeDshPendingAttachments(""))
        assertEquals(emptyList<DshPendingAttachment>(), decodeDshPendingAttachments("{not json"))
        assertEquals(
            emptyList<app.tellev.core.model.Attachment>(),
            decodeChatAttachments(""),
        )
    }

    @Test
    fun `real attachments keep mime type and metadata across a round trip`() {
        val attachments = listOf(
            Attachment(
                id = "att-1",
                name = "a.png",
                mimeType = "image/jpeg",
                relativePath = "user/images/att-1.jpg",
                source = AttachmentSource.Chat,
                metadata = buildJsonObject { put("detail", JsonPrimitive("auto")) },
            ),
            Attachment(
                id = "att-2",
                name = "book.md",
                mimeType = "text/markdown",
                relativePath = "user/files/att-2.md",
                metadata = buildJsonObject {
                    put("file", JsonPrimitive("user/files/att-2.md"))
                    put("textContent", JsonPrimitive("第一章"))
                },
            ),
        )
        val restored = decodeChatAttachments(encodeChatAttachments(attachments))
        assertEquals(attachments, restored)
        assertEquals("text/markdown", restored[1].mimeType)
        assertEquals("user/files/att-2.md", restored[1].relativePath)
        val textContent = restored[1].metadata["textContent"] as? JsonPrimitive
        assertEquals("第一章", textContent?.contentOrNull)
    }

    @Test
    fun `strip entry keeps the relative path and drops the file body`() {
        val attachment = Attachment("att-9", "pic.png", "image/png", "user/images/att-9.png")
        val strip = attachment.toDshPendingAttachment()
        assertEquals("att-9", strip.id)
        assertEquals("pic.png", strip.name)
        assertTrue(strip.isImage)
        assertEquals("user/images/att-9.png", strip.relativePath)
    }

    @Test
    fun `non image mime types do not render as thumbnails`() {
        val file = Attachment("att-8", "log.txt", "text/plain", "user/files/att-8.txt")
        assertFalse(file.toDshPendingAttachment().isImage)
    }

    @Test
    fun `strip entry declares no File field so it stays bundle saveable`() {
        assertTrue(
            DshPendingAttachment::class.java.declaredFields
                .none { File::class.java.isAssignableFrom(it.type) },
        )
    }
}
