package app.tellev.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApkVerifierTest {
    private fun bytes(seed: Int) = ByteArray(32) { (it * seed).toByte() }

    @Test
    fun `same certificate set matches regardless of order or duplication`() {
        val a = listOf(bytes(1), bytes(2))
        val b = listOf(bytes(2), bytes(1))
        assertTrue(UpdateApkVerifier.certificateSetsMatch(a, b))
        // Signing-info serialization can hand back duplicates; the comparison
        // is set-based, so an extra copy of the same certificate still matches.
        assertTrue(UpdateApkVerifier.certificateSetsMatch(a, listOf(bytes(1), bytes(2), bytes(2))))
    }

    @Test
    fun `a re-signed package does not match the installed certificate`() {
        assertFalse(UpdateApkVerifier.certificateSetsMatch(listOf(bytes(1)), listOf(bytes(9))))
        // A second signature is a different identity, not a superset match.
        assertFalse(
            UpdateApkVerifier.certificateSetsMatch(
                listOf(bytes(1)),
                listOf(bytes(1), bytes(2)),
            ),
        )
    }

    @Test
    fun `empty signature lists never match`() {
        assertFalse(UpdateApkVerifier.certificateSetsMatch(emptyList(), listOf(bytes(1))))
        assertFalse(UpdateApkVerifier.certificateSetsMatch(listOf(bytes(1)), emptyList()))
    }

    @Test
    fun `fingerprints are lowercase hex sha-256`() {
        val fp = UpdateApkVerifier.sha256Fingerprint(ByteArray(0))
        assertTrue(fp.matches(Regex("[0-9a-f]{64}")))
        // Well-known SHA-256 of the empty input.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fp)
    }
}
