package io.aegis.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire-compatible Kotlin mirror of `aegis_proto::IdentityDocument`.
//
// Field names use snake_case `@SerialName`s that match the FFI's JSON
// output exactly. Don't rename the Kotlin properties to camelCase —
// that requires per-field annotations and risks drift when the wire
// format evolves; keeping snake_case here keeps the diff against
// aegis-proto's Rust struct one-to-one.

@Serializable
data class IdentityDocument(
    val version: Int,
    @SerialName("identity_id") val identityId: String,
    val aliases: List<String>,
    @SerialName("signing_keys") val signingKeys: List<PublicKeyRecord>,
    @SerialName("encryption_keys") val encryptionKeys: List<PublicKeyRecord>,
    @SerialName("supported_suites") val supportedSuites: List<String>,
    @SerialName("relay_endpoints") val relayEndpoints: List<String>,
    val signature: String? = null,
)

@Serializable
data class PublicKeyRecord(
    @SerialName("key_id") val keyId: String,
    val algorithm: String,
    @SerialName("public_key_b64") val publicKeyB64: String,
)
