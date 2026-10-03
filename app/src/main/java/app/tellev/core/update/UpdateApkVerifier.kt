package app.tellev.core.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Pre-install integrity gate for downloaded update APKs.
 *
 * The GitHub-reported `digest` and the APK itself travel the same mirror
 * chain, so a compromised mirror can substitute both and the checksum check
 * passes. The APK's signing certificate breaks that chain: it is produced by
 * the developer's private key, which a mirror attacker does not hold, and a
 * re-signed APK fails the comparison. This is the same evidence the system
 * installer uses for upgrade consistency, moved *before* the installer so a
 * mismatched package is deleted instead of shown to the user.
 */
object UpdateApkVerifier {

    sealed interface Verdict {
        /** Signed by the same certificate(s) as the running build. */
        data object Valid : Verdict

        /** The APK must not be handed to the installer. [reason] is a stable code for logs. */
        data class Invalid(val reason: Reason) : Verdict
    }

    enum class Reason {
        NOT_AN_APK,
        PACKAGE_MISMATCH,
        NO_SIGNING_INFO,
        SIGNATURE_MISMATCH,
    }

    fun verify(context: Context, apk: File): Verdict {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return Verdict.Invalid(Reason.NOT_AN_APK)
        if (archive.packageName != context.packageName) {
            return Verdict.Invalid(Reason.PACKAGE_MISMATCH)
        }
        val archiveSigners = archive.signingInfo?.apkContentsSigners
            ?: return Verdict.Invalid(Reason.NO_SIGNING_INFO)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val installedSigners = installed.signingInfo?.apkContentsSigners
            ?: return Verdict.Invalid(Reason.NO_SIGNING_INFO)
        return if (certificateSetsMatch(
                installedSigners.map { it.toByteArray() },
                archiveSigners.map { it.toByteArray() },
            )
        ) {
            Verdict.Valid
        } else {
            Verdict.Invalid(Reason.SIGNATURE_MISMATCH)
        }
    }

    /**
     * Pure core of [verify] so the set-comparison rule stays unit-testable
     * without the Android PackageManager. Signer sets match when they contain
     * exactly the same certificates (order-independent, duplicate-insensitive).
     */
    fun certificateSetsMatch(installed: List<ByteArray>, archive: List<ByteArray>): Boolean {
        if (installed.isEmpty() || archive.isEmpty()) return false
        return installed.map { sha256Fingerprint(it) }.toSet() ==
            archive.map { sha256Fingerprint(it) }.toSet()
    }

    /** Lowercase hex SHA-256 of a DER-encoded certificate. */
    fun sha256Fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { "%02x".format(it) }
}
