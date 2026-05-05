package io.aegis.android.core

import uniffi.aegis_ffi.FfiException

// Single-typed error for AegisCore. Folds the FFI's `FfiException`
// variants into a sealed Kotlin hierarchy plus a few Kotlin-side cases
// (serialization failures, etc.) so app code only needs to catch one
// type. Mirrors aegis-apple's `AegisError`.

sealed class AegisError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : AegisError("invalid input: $message")
    object InvalidKeyMaterial : AegisError("invalid key material") {
        private fun readResolve(): Any = InvalidKeyMaterial
    }
    object EncryptionFailed : AegisError("encryption failed") {
        private fun readResolve(): Any = EncryptionFailed
    }
    object DecryptionFailed : AegisError("decryption failed") {
        private fun readResolve(): Any = DecryptionFailed
    }
    object SignatureVerificationFailed : AegisError("signature verification failed") {
        private fun readResolve(): Any = SignatureVerificationFailed
    }
    class Serialization(message: String) : AegisError("serialization: $message")
    class Identity(message: String) : AegisError("identity: $message")
    class Internal(message: String) : AegisError("internal: $message")

    companion object {
        // Translate the auto-generated FfiException's variant hierarchy
        // into our sealed type. Centralized so callers downstream of the
        // FFI never have to import the generated `uniffi.aegis_ffi`
        // package types.
        fun from(ffi: FfiException): AegisError = when (ffi) {
            is FfiException.InvalidInput -> InvalidInput(ffi.v1)
            is FfiException.InvalidKeyMaterial -> InvalidKeyMaterial
            is FfiException.Encryption -> EncryptionFailed
            is FfiException.Decryption -> DecryptionFailed
            is FfiException.SignatureVerificationFailed -> SignatureVerificationFailed
            is FfiException.Serialization -> Serialization(ffi.v1)
            is FfiException.Identity -> Identity(ffi.v1)
            is FfiException.Internal -> Internal(ffi.v1)
        }
    }
}
