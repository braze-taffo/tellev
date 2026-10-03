package app.tellev.core.extension

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists

class CharacterScriptConsentStoreTest {

    @Test
    fun `consent roundtrip persists approved and denied decisions`() = runBlocking {
        val root = Files.createTempDirectory("script-consent")
        val store = CharacterScriptConsentStore(root)
        assertNull(store.read("char_a"))

        store.write("char_a", approved = true, fingerprint = CharacterScriptConsentStore.fingerprint("src-a"))
        store.write("char_b", approved = false, fingerprint = "fp-b")

        val a = store.read("char_a")!!
        assertTrue(a.approved)
        assertEquals(CharacterScriptConsentStore.fingerprint("src-a"), a.fingerprint)
        assertFalse(store.read("char_b")!!.approved)

        // 新实例从磁盘读回同一份记录（进程重启语义），且无 tmp 残留。
        val reopened = CharacterScriptConsentStore(root)
        assertEquals(a, reopened.read("char_a"))
        assertFalse(root.resolve("script-consent.json.tmp").exists())
    }

    @Test
    fun `overwriting a decision keeps a single entry per character`() = runBlocking {
        val root = Files.createTempDirectory("script-consent-2")
        val store = CharacterScriptConsentStore(root)
        store.write("char", approved = false, fingerprint = "old")
        store.write("char", approved = true, fingerprint = "new")

        val consent = store.read("char")!!
        assertTrue(consent.approved)
        assertEquals("new", consent.fingerprint)
    }

    @Test
    fun `fingerprint changes when the script source changes`() {
        assertTrue(CharacterScriptConsentStore.fingerprint("a") != CharacterScriptConsentStore.fingerprint("b"))
        assertEquals(
            CharacterScriptConsentStore.fingerprint("same"),
            CharacterScriptConsentStore.fingerprint("same"),
        )
    }
}
