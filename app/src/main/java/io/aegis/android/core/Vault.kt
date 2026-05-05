package io.aegis.android.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire-compatible Kotlin mirror of the v2 vault format that
// `mlaify/aegis-web/src/lib/storage.ts` writes to IndexedDB and that
// `TFFHRTP/aegis-apple` writes to Keychain. Same JSON schema, so a
// vault created on one platform deserializes cleanly on the others.
//
// Schema (from vault_unlock_architecture.md):
//   - A random `masterKey` AES-GCM-encrypts the identity secrets blob.
//   - Each entry in `unlock_methods` holds its own AES-GCM-wrapped copy
//     of `masterKey`. Adding/removing a method only re-wraps masterKey;
//     the encrypted secrets blob is untouched.
//
// Methods supported on Android today:
//   - passphrase (mandatory): PBKDF2-SHA256(passphrase, salt, iters)
//     → 256-bit AES-GCM KEK.
//   - passkey (planned, not in this PR): WebAuthn PRF via
//     androidx.credentials. Reserved in the discriminator now so vaults
//     written by aegis-web (or any future cross-platform export/import)
//     that already include passkey methods deserialize without loss.

// ---------------------------------------------------------------------------
// Persisted-identity wrapper
// ---------------------------------------------------------------------------

/** What we actually persist (Keystore-wrapped + EncryptedSharedPreferences):
 *  the public IdentityDocument JSON plus the encrypted vault that holds
 *  the matching secrets. Single record so the two stay in sync atomically.
 *
 *  The document JSON is non-secret — it's published to the relay
 *  verbatim — so we don't need to encrypt it. Storing it alongside the
 *  vault means unlocking yields a complete `AegisIdentity` immediately,
 *  no caller-side caching required. */
@Serializable
data class PersistedIdentity(
    @SerialName("document_json") val documentJson: String,
    val vault: Vault,
)

// ---------------------------------------------------------------------------
// Vault
// ---------------------------------------------------------------------------

/** The encrypted-secrets half of a `PersistedIdentity`. Wire-compatible
 *  with the v2 vault format aegis-web writes to IndexedDB and aegis-apple
 *  writes to Keychain. */
@Serializable
data class Vault(
    /** Always 2 in this schema. Reading rejects any other value. */
    val version: Int = 2,

    /** AES-GCM IV used to encrypt the identity secrets with `masterKey`.
     *  12 bytes, base64-encoded. */
    @SerialName("master_iv_b64") val masterIvB64: String,

    /** AES-GCM(masterKey, JSON of `HybridPqPrivateKeyMaterial`). */
    @SerialName("ciphertext_b64") val ciphertextB64: String,

    /** Enrolled unlock methods. Order is not significant. Always
     *  contains at least one passphrase method. */
    @SerialName("unlock_methods") val unlockMethods: List<UnlockMethod>,
)

// ---------------------------------------------------------------------------
// Unlock methods (tagged-union via `type`)
// ---------------------------------------------------------------------------

/** One enrolled unlock method. The `type` discriminator selects the
 *  concrete shape; we model it with a sealed class + custom serializer
 *  matching the web/iOS schema's tagged-union encoding. */
@Serializable(with = UnlockMethodSerializer::class)
sealed interface UnlockMethod {
    val id: String
    val type: String
    val label: String?
    val enrolledAt: String
}

@Serializable
data class PassphraseMethod(
    /** Always "passphrase". Encoded for parity with the web schema's
     *  tagged union so the JSON has a `"type": "passphrase"` field. */
    override val type: String = "passphrase",
    override val id: String,
    @SerialName("enrolled_at") override val enrolledAt: String,
    override val label: String? = null,
    /** PBKDF2-SHA256 salt (16 bytes, base64-encoded). */
    @SerialName("salt_b64") val saltB64: String,
    val iterations: Int,
    /** AES-GCM IV used to wrap masterKey under the passphrase-derived KEK. */
    @SerialName("iv_b64") val ivB64: String,
    @SerialName("wrapped_master_key_b64") val wrappedMasterKeyB64: String,
) : UnlockMethod

/** Deserialize-only this PR (forward-compat for vaults written by
 *  aegis-web or aegis-apple that already enrolled passkeys). The
 *  Android Passkey enrollment ceremony lands in a follow-up that needs
 *  the digital-asset-links file set up on a real domain. */
@Serializable
data class PasskeyMethod(
    override val type: String = "passkey",
    override val id: String,
    @SerialName("enrolled_at") override val enrolledAt: String,
    override val label: String? = null,
    @SerialName("credential_id_b64") val credentialIdB64: String,
    @SerialName("prf_salt_b64") val prfSaltB64: String,
    @SerialName("iv_b64") val ivB64: String,
    @SerialName("wrapped_master_key_b64") val wrappedMasterKeyB64: String,
) : UnlockMethod

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

sealed class VaultError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedVersion(val version: Int) : VaultError("unsupported vault version: $version")
    class UnknownMethodType(val type: String) : VaultError("unknown unlock-method type: $type")
    object NoPassphraseMethod : VaultError("vault has no passphrase unlock method") {
        private fun readResolve(): Any = NoPassphraseMethod
    }
    class NoSuchMethod(val methodId: String) : VaultError("no unlock method with id $methodId")
    object WouldRemoveLastPassphrase :
        VaultError("refusing to remove last passphrase method — would lock the vault") {
        private fun readResolve(): Any = WouldRemoveLastPassphrase
    }
    object WrongPassphrase : VaultError("wrong passphrase") {
        private fun readResolve(): Any = WrongPassphrase
    }
    class CryptoFailure(message: String, cause: Throwable? = null) : VaultError(message, cause)
    class StorageFailure(message: String, cause: Throwable? = null) : VaultError(message, cause)
    class SerializationFailure(message: String, cause: Throwable? = null) : VaultError(message, cause)
    object NotConfigured :
        VaultError("Aegis.configureWithKeystore(context) must be called before vault operations") {
        private fun readResolve(): Any = NotConfigured
    }
    object NoVault : VaultError("no vault on this device") {
        private fun readResolve(): Any = NoVault
    }
    object Locked : VaultError("vault is locked") {
        private fun readResolve(): Any = Locked
    }
}
