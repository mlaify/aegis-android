package io.aegis.android.core

import uniffi.aegis_ffi.FfiException
import uniffi.aegis_ffi.generateIdentity as ffiGenerateIdentity
import uniffi.aegis_ffi.signIdentityDocument as ffiSignIdentityDocument
import uniffi.aegis_ffi.version as ffiVersion

/**
 * Top-level facade over the AegisFFI bridge.
 *
 * Mirrors aegis-apple's `Aegis` Swift enum — same surface, same
 * semantics, JSON crosses the boundary in both directions, typed
 * Kotlin records (`IdentityDocument`, `AegisIdentity`) wrap what
 * the app code actually consumes.
 */
object Aegis {
    /** FFI crate version. Useful for confirming the bridge is wired up. */
    fun version(): String = ffiVersion()

    /**
     * Generate a fresh hybrid PQ identity (X25519 + ML-KEM-768 +
     * Ed25519 + ML-DSA-65). The returned identity is *unsigned* —
     * call [signIdentityDocument] before publishing it to a relay.
     *
     * @throws AegisError on FFI failure (most commonly
     *   `AegisError.InvalidInput` for an empty `identityId`).
     */
    @Throws(AegisError::class)
    fun generateIdentity(identityId: String): AegisIdentity {
        val bundle = try {
            ffiGenerateIdentity(identityId)
        } catch (e: FfiException) {
            throw AegisError.from(e)
        }
        return AegisIdentity.from(
            documentJson = bundle.documentJson,
            secretsJson = bundle.secretsJson,
        )
    }

    /**
     * Attach the hybrid Ed25519 + ML-DSA-65 signature to the supplied
     * identity. Returns a new [AegisIdentity] whose
     * `document.signature` is populated; the caller's input is not
     * mutated (Kotlin data class copy semantics).
     */
    @Throws(AegisError::class)
    fun signIdentityDocument(identity: AegisIdentity): AegisIdentity {
        val signedJson = try {
            ffiSignIdentityDocument(identity.documentJson, identity.secretsJson)
        } catch (e: FfiException) {
            throw AegisError.from(e)
        }
        return AegisIdentity.from(
            documentJson = signedJson,
            secretsJson = identity.secretsJson,
        )
    }
}
