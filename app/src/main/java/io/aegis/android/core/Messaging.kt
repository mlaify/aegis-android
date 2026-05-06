// High-level messaging helpers — typed wrappers around the FFI's
// `open_hybrid_pq` / `seal_hybrid_pq` operations. These keep all the
// JSON ↔ kotlinx.serialization conversion in one place so the Compose
// layer can pass and receive Kotlin records, never raw JSON strings.
//
// Mirrors aegis-apple's `Messaging.swift`. Secrets stay opaque on the
// Kotlin side — we round-trip the JSON the FFI emitted at vault
// creation time rather than decoding into Kotlin types.

package io.aegis.android.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import uniffi.aegis_ffi.FfiException
import uniffi.aegis_ffi.OpenResult
import uniffi.aegis_ffi.SigStatus
import uniffi.aegis_ffi.openHybridPq as ffiOpenHybridPq

/**
 * Fully-decoded result of opening an envelope: typed payload + the
 * FFI's [SigStatus] (verified / failed / unsigned / unavailable).
 *
 * `payloadJson` is the raw JSON the FFI emitted; we keep it around so
 * future flows (e.g. reply-quoting) can use the original bytes
 * without round-tripping through Kotlin types.
 */
data class OpenedEnvelope(
    val payload: PrivatePayload,
    val sigStatus: SigStatus,
    val payloadJson: String,
)

private val messagingJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Open an envelope addressed to the currently-unlocked identity.
 *
 * Requires the vault to be unlocked — uses [Aegis.loadIdentity] as the
 * unlock gate, throwing [VaultError.Locked] otherwise.
 *
 * @param envelope the envelope to open. We re-encode it to JSON for
 *   the FFI; canonical-bytes preservation isn't required for the OPEN
 *   side (the FFI verifies signatures during deserialization).
 * @param prekeyKyber768SecretB64 if [envelope]`.usedPrekeyIds` is
 *   non-empty, supply the matching one-time prekey secret here. Pass
 *   null for envelopes that don't use a prekey.
 * @param senderDocumentJson optional sender's published IdentityDocument
 *   JSON, used to verify the outer signature. Pass null to skip
 *   verification (sigStatus will be [SigStatus.UNAVAILABLE]).
 */
@Throws(VaultError::class, AegisError::class)
fun Aegis.openEnvelope(
    envelope: Envelope,
    prekeyKyber768SecretB64: String? = null,
    senderDocumentJson: String? = null,
): OpenedEnvelope {
    // `loadIdentity()` succeeds only when the session is unlocked,
    // and returns the in-memory secrets blob. Same gate as iOS.
    val identity = loadIdentity()
    val envelopeJson = messagingJson.encodeToString(Envelope.serializer(), envelope)

    val result: OpenResult = try {
        ffiOpenHybridPq(
            envelopeJson = envelopeJson,
            recipientSecretsJson = identity.secretsJson,
            prekeyKyber768SecretB64 = prekeyKyber768SecretB64,
            senderDocJson = senderDocumentJson,
        )
    } catch (e: FfiException) {
        throw AegisError.from(e)
    }

    val payload = try {
        messagingJson.decodeFromString(PrivatePayload.serializer(), result.payloadJson)
    } catch (e: SerializationException) {
        throw AegisError.Serialization(
            "failed to decode PrivatePayload: ${e.message}"
        )
    }

    return OpenedEnvelope(
        payload = payload,
        sigStatus = result.sigStatus,
        payloadJson = result.payloadJson,
    )
}
