// Wire-compatible Kotlin mirrors of the relay HTTP request / response
// payloads defined in mlaify/aegis-sdk (typescript/src/index.ts) and
// implemented relay-side by mlaify/aegis-relay's RFC-0004 endpoints.
//
// Convention (matches IdentityDocument.kt): keep Kotlin properties in
// camelCase and use @SerialName for the snake_case JSON keys. The
// mapping is exact in both directions, including `_b64` suffixes —
// kotlinx.serialization respects @SerialName verbatim, so unlike the
// Swift side we don't have to worry about strategy-based mangling.

package io.aegis.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─── Envelope ───────────────────────────────────────────────────────────────

@Serializable
data class Envelope(
    val version: Int,
    @SerialName("envelope_id")            val envelopeId: String,
    @SerialName("recipient_id")           val recipientId: String,
    @SerialName("sender_hint")            val senderHint: String? = null,
    @SerialName("created_at")             val createdAt: String,
    @SerialName("expires_at")             val expiresAt: String? = null,
    @SerialName("content_type")           val contentType: String,
    @SerialName("suite_id")               val suiteId: String,
    @SerialName("used_prekey_ids")        val usedPrekeyIds: List<String>,
    val payload: EncryptedBlob,
    /** Ed25519 outer signature (base64). Required for HybridPq suite. */
    @SerialName("outer_signature_b64")    val outerSignatureB64: String? = null,
    /** ML-DSA-65 (Dilithium3) outer signature (base64). Required for HybridPq. */
    @SerialName("outer_pq_signature_b64") val outerPqSignatureB64: String? = null,
)

@Serializable
data class EncryptedBlob(
    @SerialName("nonce_b64")                  val nonceB64: String,
    @SerialName("ciphertext_b64")             val ciphertextB64: String,
    /** Sender's ephemeral X25519 public key (base64). Present for HybridPq. */
    @SerialName("eph_x25519_public_key_b64")  val ephX25519PublicKeyB64: String? = null,
    /** ML-KEM-768 encapsulation ciphertext (base64). Present for HybridPq. */
    @SerialName("mlkem_ciphertext_b64")       val mlkemCiphertextB64: String? = null,
)

// ─── PrivatePayload (decoded out of OpenResult.payloadJson) ────────────────

@Serializable
data class PrivatePayload(
    @SerialName("private_headers") val privateHeaders: PrivateHeaders,
    val body: MessageBody,
    val attachments: List<AttachmentManifest> = emptyList(),
    /** Free-form `extensions` field; we round-trip it as raw JSON
     *  rather than modelling an open shape. */
    val extensions: JsonElement? = null,
)

@Serializable
data class PrivateHeaders(
    val subject: String? = null,
    @SerialName("thread_id")   val threadId: String? = null,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
)

@Serializable
data class MessageBody(
    val mime: String,
    val content: String,
)

@Serializable
data class AttachmentManifest(
    @SerialName("attachment_id")          val attachmentId: String,
    val filename: String,
    val mime: String,
    val size: Long,
    @SerialName("blob_ref")               val blobRef: String,
    @SerialName("content_key_wrap_b64")   val contentKeyWrapB64: String,
)

// ─── Relay endpoint payloads ───────────────────────────────────────────────

@Serializable
data class FetchEnvelopeResponse(
    val envelopes: List<Envelope>,
)

@Serializable
data class StoreEnvelopeRequest(
    val envelope: Envelope,
)

@Serializable
data class StoreEnvelopeResponse(
    val accepted: Boolean,
    @SerialName("relay_id") val relayId: String,
)

@Serializable
data class PublishPrekeysResponse(
    @SerialName("identity_id") val identityId: String,
    val inserted: Int,
    val skipped: Int,
)

@Serializable
data class ClaimedPrekeyResponse(
    @SerialName("identity_id")    val identityId: String,
    @SerialName("key_id")         val keyId: String,
    val algorithm: String,
    @SerialName("public_key_b64") val publicKeyB64: String,
)

@Serializable
data class RelayErrorBody(
    val error: RelayErrorDetail,
)

@Serializable
data class RelayErrorDetail(
    val code: String,
    val message: String,
)

// ─── Local (never-transmitted) prekey persistence types ────────────────────

@Serializable
data class OneTimePrekeySecret(
    @SerialName("key_id")                  val keyId: String,
    val algorithm: String,
    @SerialName("kyber768_secret_key_b64") val kyber768SecretKeyB64: String,
)

@Serializable
data class PrekeyBundlePrivateMaterial(
    @SerialName("identity_id")              val identityId: String,
    @SerialName("one_time_prekey_secrets")  val oneTimePrekeySecrets: List<OneTimePrekeySecret>,
)
