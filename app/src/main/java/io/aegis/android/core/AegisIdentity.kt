package io.aegis.android.core

import kotlinx.serialization.json.Json

/**
 * A locally-held identity: typed document + opaque secrets (kept as
 * the raw JSON the FFI emitted, so we can round-trip secrets back into
 * the FFI without ever decoding them in Kotlin).
 *
 * Mirrors aegis-apple's `AegisIdentity`. Secrets stay opaque so we
 * never need to handle the raw key bytes in app code; persistence
 * (Android Keystore-backed encryption) operates on the JSON blob as a
 * whole.
 */
data class AegisIdentity(
    val document: IdentityDocument,
    /** Canonical JSON of [document] as the FFI emitted it. */
    val documentJson: String,
    /** Opaque JSON-encoded `HybridPqPrivateKeyMaterial`. */
    val secretsJson: String,
) {
    val identityId: String get() = document.identityId

    companion object {
        internal fun from(documentJson: String, secretsJson: String): AegisIdentity {
            val document = try {
                JsonCodec.decodeFromString<IdentityDocument>(documentJson)
            } catch (t: Throwable) {
                throw AegisError.Serialization(
                    "failed to decode IdentityDocument: ${t.message ?: t::class.simpleName}",
                )
            }
            return AegisIdentity(document, documentJson, secretsJson)
        }
    }
}

/**
 * Shared kotlinx.serialization Json configured to ignore unknown fields
 * (in case the FFI grows new ones before the Kotlin layer is updated)
 * and to encode camelCase / snake_case via per-field `@SerialName`s.
 */
internal val JsonCodec: Json = Json {
    ignoreUnknownKeys = true
}
