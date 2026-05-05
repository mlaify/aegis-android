package io.aegis.android.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// End-to-end smoke tests for the AegisFFI bridge consumed via the
// Aegis facade. Mirrors the Swift suite in
// aegis-apple/shared/Tests/AegisCoreTests/AegisCoreTests.swift.
//
// These run as JVM unit tests; they require build-jni.sh to have been
// run at least once so libaegis_ffi exists for the host architecture.
// JNA picks the library up via the `jna.library.path` system property
// set in app/build.gradle.kts's testOptions.

class AegisCoreTest {

    @Test
    fun versionIsNonEmpty() {
        assertFalse(Aegis.version().isEmpty())
    }

    @Test
    fun generateIdentityRejectsEmptyId() {
        try {
            Aegis.generateIdentity("")
            fail("expected AegisError.InvalidInput")
        } catch (e: AegisError.InvalidInput) {
            // expected
        } catch (e: Throwable) {
            fail("unexpected error type: ${e::class.simpleName} — ${e.message}")
        }
    }

    @Test
    fun generateIdentityEmitsExpectedKeysAndSuites() {
        val identity = Aegis.generateIdentity("amp:did:key:zKotlinTest")

        assertEquals("amp:did:key:zKotlinTest", identity.identityId)
        assertNull("fresh identity should be unsigned", identity.document.signature)

        val signingAlgs = identity.document.signingKeys.map { it.algorithm }.toSet()
        assertEquals(setOf("AMP-ED25519-V1", "AMP-MLDSA65-V1"), signingAlgs)

        val encryptionAlgs = identity.document.encryptionKeys.map { it.algorithm }.toSet()
        assertEquals(setOf("AMP-X25519-V1", "AMP-MLKEM768-V1"), encryptionAlgs)

        assertTrue(
            identity.document.supportedSuites.any { it.contains("HYBRID-X25519-MLKEM768") },
        )
    }

    @Test
    fun signIdentityDocumentAttachesHybridSignature() {
        val unsigned = Aegis.generateIdentity("amp:did:key:zKotlinTest")
        assertNull(unsigned.document.signature)

        val signed = Aegis.signIdentityDocument(unsigned)
        val signature = signed.document.signature
        assertNotNull("signed document should have a signature", signature)
        assertTrue(signature!!.startsWith("ed25519:"))
        assertTrue(signature.contains("|dilithium3:"))

        // Unsigned identity must be unchanged (Kotlin data-class copy semantics).
        assertNull(unsigned.document.signature)

        // The raw documentJson should also reflect the signature on the
        // signed side (we round-trip via FFI rather than re-serialize, so
        // signature bytes are byte-identical to what the FFI emitted).
        val parsed = Json.parseToJsonElement(signed.documentJson).jsonObject
        assertEquals(signature, parsed["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun twoIdentitiesAreDistinct() {
        val a = Aegis.generateIdentity("amp:did:key:zA")
        val b = Aegis.generateIdentity("amp:did:key:zB")

        val aEd = a.document.signingKeys.firstOrNull { it.algorithm == "AMP-ED25519-V1" }?.publicKeyB64
        val bEd = b.document.signingKeys.firstOrNull { it.algorithm == "AMP-ED25519-V1" }?.publicKeyB64
        assertNotNull(aEd)
        assertNotNull(bEd)
        assertNotEquals("fresh identities should produce distinct keys", aEd, bEd)
    }
}
